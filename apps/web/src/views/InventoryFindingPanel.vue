<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import type { LoanMutation } from "@/loan/api";
import type { AssetCard } from "@/asset/api";
import type { InboundReceipt } from "@/inbound/api";
import { ReviewApiError, reviewRequest, type Observation } from "@/inventory-review/api";
import InventoryPhotoEvidence from "./InventoryPhotoEvidence.vue";
interface FindingView { taskVersion: number; observation: Observation;
  subject: { version?: number; subject_status?: string; method?: string };
  history: { after_version: number; method: string; subject_status: string; reason: string; evidence: string;
    actor_username: string; occurred_at?: string; assetId?: string | null; inboundReceiptId?: string | null }[]; historyTotal: number;
  timeSubject?: { version: number; subject_status: string }[];
  recollections?: { after_version: number; counterObservationId: string; actor_username: string; occurred_at: string; reason: string; evidence: string }[] }
const props = defineProps<{ taskId: string }>();
const emit = defineEmits<{ changed: [] }>();
const session = useSessionStore();
const owner = computed(() => JSON.stringify([props.taskId, session.user?.id, session.csrfToken, session.user?.roles]));
const rows = ref<Observation[]>([]), total = ref(0), page = ref(1), detail = ref<FindingView | null>(null);
const busy = ref(false), error = ref(""), notice = ref(""), pending = ref<LoanMutation | null>(null);
const method = ref("NON_ENTERPRISE"), reason = ref(""), evidence = ref("");
const assetCode = ref(""), receiptNumber = ref(""), assets = ref<AssetCard[]>([]), receipts = ref<InboundReceipt[]>([]);
const selectedAsset = ref(""), selectedReceipt = ref("");
const confirmation = ref<LoanMutation | null>(null);
const counters = ref<Observation[]>([]), counterTotal = ref(0), counterPage = ref(1), counterId = ref("");
const timeSubject = computed(() => detail.value?.timeSubject?.[0]);
const locked = computed(() => busy.value || !!pending.value || !!confirmation.value);
let generation = 0, live = true;
const current = (epoch: number, identity: string) => live && generation === epoch && owner.value === identity;
function clear() { generation++; rows.value = []; total.value = 0; detail.value = null; assets.value = []; receipts.value = [];
  selectedAsset.value = ""; selectedReceipt.value = ""; pending.value = null; confirmation.value = null; counters.value = []; counterId.value = ""; busy.value = false; notice.value = ""; }
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)) { clear(); error.value = "身份、权限或线索已失效，已隐藏原资料。"; return; }
  if (cause instanceof ReviewApiError && cause.status === 409) {
    error.value = cause.fields.processedBy ? `该差异已由 ${cause.fields.processedBy} 处理，请刷新核对。` : "任务、差异或正式单据已变化，请刷新后核对。"; return;
  }
  error.value = pending.value ? "操作结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。";
}
async function load() {
  if (locked.value) return;
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = ""; detail.value = null;
  try { const result = await reviewRequest<{ items: Observation[]; total: number }>(`/inventory-reviews/tasks/${props.taskId}/findings?page=${page.value}&pageSize=20`);
    if (current(epoch, identity)) { rows.value = result.items; total.value = result.total; }
  } catch (cause) { if (current(epoch, identity)) failure(cause); }
  finally { if (current(epoch, identity)) busy.value = false; }
}
async function open(row: Observation) {
  if (locked.value || !["FINDING_REVIEW", "TIME_REVIEW"].includes(row.outcome)) return;
  const previous = detail.value?.observation.id;
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = ""; detail.value = null;
  if (previous !== row.id) { reason.value = ""; evidence.value = ""; assetCode.value = ""; receiptNumber.value = ""; }
  assets.value = []; receipts.value = []; selectedAsset.value = ""; selectedReceipt.value = ""; counters.value = []; counterId.value = ""; counterPage.value = 1;
  try { const result = await reviewRequest<FindingView>(`/inventory-reviews/tasks/${props.taskId}/findings/${row.id}`);
    if (current(epoch, identity)) { detail.value = result; method.value = result.subject.subject_status === "PROCESSING" ? "REFRESH_INBOUND" : "NON_ENTERPRISE"; }
  } catch (cause) { if (current(epoch, identity)) failure(cause); }
  finally { if (current(epoch, identity)) busy.value = false; }
}
async function search() {
  if (locked.value || !detail.value || method.value === "NON_ENTERPRISE") return;
  if (method.value === "DUPLICATE_ASSET" ? !assetCode.value.trim() : !receiptNumber.value.trim()) return;
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = "";
  assets.value = []; receipts.value = []; selectedAsset.value = ""; selectedReceipt.value = "";
  try {
    if (method.value === "DUPLICATE_ASSET") {
      const result = await reviewRequest<{ items: AssetCard[] }>(`/assets?page=1&pageSize=20&assetCode=${encodeURIComponent(assetCode.value.trim())}`);
      if (current(epoch, identity)) assets.value = result.items.filter(row => row.cardStage === "ACTIVE" && row.assetCode === assetCode.value.trim());
    } else {
      const result = await reviewRequest<{ items: InboundReceipt[] }>(`/inbound-receipts?page=1&pageSize=20&receiptNumber=${encodeURIComponent(receiptNumber.value.trim())}`);
      if (current(epoch, identity)) receipts.value = result.items.filter(row => row.status !== "DRAFT" && row.receiptNumber === receiptNumber.value.trim());
    }
  } catch (cause) { if (current(epoch, identity)) failure(cause); }
  finally { if (current(epoch, identity)) busy.value = false; }
}
async function save(prepare = false, time = false) {
  if (busy.value || !detail.value) return;
  const row = detail.value.observation;
  if (!pending.value) {
    let body: unknown;
    let path = `/inventory-reviews/tasks/${props.taskId}/findings/${row.id}/decisions`;
    if (prepare) { path = `/inventory-reviews/tasks/${props.taskId}/differences/preparation`; body = { observationIds: [row.id] }; }
    else if (time) {
      if (!counterId.value || !timeSubject.value || reason.value.trim().length < 3 || evidence.value.trim().length < 10) {
        error.value = "请选择实际重采线索并填写同一实物的核实依据。"; return;
      }
      path = `/inventory-reviews/tasks/${props.taskId}/findings/${row.id}/time-decisions`;
      body = { subjectVersion: timeSubject.value.version, counterObservationId: counterId.value, reason: reason.value.trim(), evidence: evidence.value.trim() };
    } else {
      if (reason.value.trim().length < 3 || evidence.value.trim().length < 10) { error.value = "请填写处理原因和明确核实依据。"; return; }
      const receipt = receipts.value.find(value => value.id === selectedReceipt.value);
      const asset = assets.value.find(value => value.id === selectedAsset.value);
      const item = receipt?.items.find(value => value.asset.id === selectedAsset.value);
      if (method.value === "DUPLICATE_ASSET" && !asset || ["LINK_INBOUND", "REFRESH_INBOUND"].includes(method.value) && (!receipt || !item)) {
        error.value = "请查找并明确选择对应的正式资产或入库项目。"; return;
      }
      body = { subjectVersion: detail.value.subject.version ?? 0, method: method.value, reason: reason.value.trim(), evidence: evidence.value.trim(),
        ...(method.value === "DUPLICATE_ASSET" && asset ? { assetId: asset.id, ownerVersion: asset.version } : {}),
        ...(receipt && item && method.value !== "NON_ENTERPRISE" && method.value !== "DUPLICATE_ASSET"
          ? { assetId: item.asset.id, inboundReceiptId: receipt.id, ownerVersion: receipt.version } : {}) };
    }
    // Preserve the exact request before entering the confirmation and before any unknown reply.
    const original = { path, key: crypto.randomUUID(), version: detail.value.taskVersion, body };
    confirmation.value = original;
    return;
  }
  const epoch = ++generation, identity = owner.value, original = pending.value;
  busy.value = true; error.value = "";
  try { await reviewRequest(original.path, original);
    if (current(epoch, identity)) { pending.value = null; notice.value = "已保存，请读取当前处理及历史；入库未实际验收不会提前关闭。"; emit("changed"); }
  } catch (cause) {
    if (current(epoch, identity)) { if (cause instanceof ReviewApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); }
  } finally { if (current(epoch, identity)) busy.value = false; }
  if (current(epoch, identity) && !pending.value) await open(row);
}
async function confirmSave() {
  if (busy.value || !confirmation.value) return;
  pending.value = confirmation.value; confirmation.value = null; await save();
}
async function loadCounters(delta = 0) {
  if (locked.value || !detail.value) return;
  const next = counterPage.value + delta;
  if (next < 1 || delta > 0 && (next - 1) * 20 >= counterTotal.value) return;
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = "";
  try {
    const result = await reviewRequest<{ items: Observation[]; total: number }>(`/inventory-reviews/tasks/${props.taskId}/findings/${detail.value.observation.id}/recollection-targets?page=${next}&pageSize=20`);
    if (current(epoch, identity)) { counters.value = result.items; counterTotal.value = result.total; counterPage.value = next; counterId.value = ""; }
  } catch (cause) { if (current(epoch, identity)) failure(cause); }
  finally { if (current(epoch, identity)) busy.value = false; }
}
async function paginate(delta: number) { if (locked.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= total.value) return; page.value += delta; await load(); }
watch(owner, () => { clear(); page.value = 1; error.value = ""; }, { flush: "sync" });
watch(method, () => { assets.value = []; receipts.value = []; selectedAsset.value = ""; selectedReceipt.value = ""; });
onBeforeUnmount(() => { live = false; clear(); });
const states: Record<string, string> = { OPEN: "待处理", PROCESSING: "处理中", CLOSED: "已关闭" };
const ownerStates: Record<string, string> = { PENDING_ADMIN_DECISION: "待管理员审批", WAITING_WAREHOUSE_CLAIM: "待库管领取", WAREHOUSE_PROCESSING: "库管处理中", COMPLETED: "已完成", REJECTED: "已驳回", CANCELLED: "已取消" };
const methodLabels: Record<string, string> = { NON_ENTERPRISE: "非企业资产依据", DUPLICATE_ASSET: "已登记资产重复线索", LINK_INBOUND: "关联正式入库", REFRESH_INBOUND: "核对正式入库进度" };
</script>
<template>
  <section class="fams-card fams-stack" aria-label="盘盈与任务外线索复核">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">盘盈与任务外线索</h3>
        <p class="fams-panel-subtitle">线索不会自动建卡。库管先按正常权限建卡并提交入库，管理员在此关联已提交单据；实际验收后才能关闭盘盈。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" data-size="sm" type="button" :disabled="locked" @click="load">读取盘盈与任务外线索</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
    <div v-if="confirmation" class="fams-card fams-stack" role="group" aria-label="确认当前复核">
      <p>请核对当前选择及依据。重采只解决原时间不确定性，资产归属仍独立处理；原始证据继续保留。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="confirmSave">确认保存当前复核</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="confirmation = null">返回修改</button>
      </div>
    </div>
    <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="save()">重试原盘盈复核操作</button>
    <ul class="fams-timeline">
      <li v-for="row in rows" :key="row.id">
        <p>{{ row.facts_json.actual?.description || '原始现场线索' }} · {{ new Date(row.received_at).toLocaleString() }}</p>
        <button v-if="['FINDING_REVIEW', 'TIME_REVIEW'].includes(row.outcome)" class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="locked" @click="open(row)">核对线索 {{ row.id }}</button>
        <p v-else>时间、过期或迟到线索须沿对应复核处理，原始事实继续保留。</p></li>
    </ul>
    <div class="fams-pagination">
      <span class="fams-pagination-summary">共 {{ total }} 条</span>
      <div class="fams-pagination-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page === 1" @click="paginate(-1)">上一页线索</button>
        <span class="fams-pagination-page">第 {{ page }} 页</span>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page * 20 >= total" @click="paginate(1)">下一页线索</button>
      </div>
    </div>
    <div v-if="detail" class="fams-card fams-stack">
      <h3 class="fams-panel-title">当前线索 {{ detail.observation.id }}</h3>
      <p>{{ detail.observation.facts_json.actual?.description }}</p>
      <p>{{ states[detail.subject.subject_status ?? ''] || '尚未列入复核' }}</p>
      <InventoryPhotoEvidence v-if="detail.observation.facts_json.photoIds?.length" :task-id="taskId" :event-id="detail.observation.client_event_id" :photo-ids="detail.observation.facts_json.photoIds" @invalid="clear" />
      <button v-if="detail.subject.version === undefined" class="fams-button" data-kind="primary" type="button" :disabled="locked" @click="save(true)">列入盘盈独立复核</button>
      <fieldset v-else-if="detail.subject.subject_status !== 'CLOSED'" class="fams-stack" :disabled="locked">
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">盘盈处理方式</span>
            <select class="fams-control" v-model="method"><option value="NON_ENTERPRISE">有明确依据确认非企业资产</option><option value="DUPLICATE_ASSET">核实为已登记资产的重复线索</option><option value="LINK_INBOUND">关联已提交的正式入库</option><option value="REFRESH_INBOUND">核对既有关联入库进度</option></select></label>
          <label v-if="method === 'DUPLICATE_ASSET'" class="fams-field"><span class="fams-field-label">权威资产编码</span>
            <input class="fams-control" v-model="assetCode" maxlength="128" /></label>
          <label v-if="method === 'LINK_INBOUND' || method === 'REFRESH_INBOUND'" class="fams-field"><span class="fams-field-label">正式入库单号</span>
            <input class="fams-control" v-model="receiptNumber" maxlength="128" /></label>
        </div>
        <button v-if="method !== 'NON_ENTERPRISE'" class="fams-button" data-kind="secondary" type="button" @click="search">查找正式依据</button>
        <div v-if="assets.length" class="fams-selection-group">
          <label v-for="asset in assets" :key="asset.id" class="fams-selection"><input v-model="selectedAsset" type="radio" :value="asset.id" />{{ asset.assetCode }} · {{ asset.name }}</label>
        </div>
        <div v-for="receipt in receipts" :key="receipt.id" class="fams-card fams-stack">
          <p>{{ receipt.receiptNumber }} · {{ ownerStates[receipt.status] ?? '状态待核实' }}</p>
          <a class="fams-button" data-kind="link" :href="`/inbound?receiptId=${encodeURIComponent(receipt.id)}`">查看正式入库单</a>
          <div class="fams-selection-group">
            <label v-for="item in receipt.items" :key="item.id" class="fams-selection"><input type="radio" :checked="selectedReceipt === receipt.id && selectedAsset === item.asset.id" @change="selectedReceipt = receipt.id; selectedAsset = item.asset.id" />{{ item.assetSnapshot.assetCode }} · {{ item.assetSnapshot.assetName }} · {{ item.executionStatus === 'ACCEPTED' ? '已实际验收' : '待验收' }}</label>
          </div>
        </div>
        <label class="fams-field"><span class="fams-field-label">盘盈处理原因</span>
          <textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
        <label class="fams-field"><span class="fams-field-label">明确盘盈核实依据</span>
          <textarea class="fams-control" v-model="evidence" maxlength="2000" /></label>
        <button class="fams-button" data-kind="primary" type="button" @click="save()">保存盘盈复核与关联</button>
      </fieldset>
      <section v-if="detail.observation.outcome === 'TIME_REVIEW'" class="fams-card fams-stack" aria-label="独立时间核实">
        <h4 class="fams-panel-title">独立时间核实 · {{ states[timeSubject?.subject_status ?? ''] || '尚未列入复核' }}</h4>
        <p>原时间仍不确定。请实际重新采集照片与现场事实，再明确关联同一实物的新线索。盘盈归属不会被重采自动关闭。</p>
        <button v-if="!timeSubject" class="fams-button" data-kind="primary" type="button" :disabled="locked" @click="save(true)">补齐时间与盘盈双项复核</button>
        <fieldset v-else-if="timeSubject.subject_status !== 'CLOSED'" class="fams-stack" :disabled="locked">
          <button class="fams-button" data-kind="secondary" type="button" @click="loadCounters()">读取有效重采线索</button>
          <div v-if="counters.length" class="fams-selection-group">
            <label v-for="counter in counters" :key="counter.id" class="fams-selection"><input v-model="counterId" type="radio" :value="counter.id" />{{ counter.facts_json.actual?.description || '实际重采现场线索' }} · {{ new Date(counter.received_at).toLocaleString() }}</label>
          </div>
          <div class="fams-pagination">
            <span class="fams-pagination-summary">共 {{ counterTotal }} 条</span>
            <div class="fams-pagination-actions">
              <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="counterPage === 1" @click="loadCounters(-1)">上一页重采线索</button>
              <span class="fams-pagination-page">第 {{ counterPage }} 页</span>
              <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="counterPage * 20 >= counterTotal" @click="loadCounters(1)">下一页重采线索</button>
            </div>
          </div>
          <label class="fams-field"><span class="fams-field-label">同一实物核实原因</span>
            <textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
          <label class="fams-field"><span class="fams-field-label">原线索与重采照片、特征核对依据</span>
            <textarea class="fams-control" v-model="evidence" maxlength="2000" /></label>
          <button class="fams-button" data-kind="primary" type="button" @click="save(false, true)">保存独立重采关联</button>
        </fieldset>
        <ul class="fams-timeline">
          <li v-for="link in detail.recollections" :key="link.after_version">{{ link.actor_username }} · {{ new Date(link.occurred_at).toLocaleString() }} · 已关联重采线索 {{ link.counterObservationId }} · {{ link.reason }} · {{ link.evidence }}；原时间仍不确定</li>
        </ul>
      </section>
      <h4 class="fams-panel-title">最近 100 条处理历史 / 共 {{ detail.historyTotal }} 条</h4>
      <ul class="fams-timeline">
        <li v-for="history in detail.history" :key="history.after_version">{{ history.actor_username }} · {{ history.occurred_at ? new Date(history.occurred_at).toLocaleString() : '时间待核实' }} · {{ states[history.subject_status] }} · {{ methodLabels[history.method] ?? '历史处理' }} · {{ history.reason }} · {{ history.evidence }}<a v-if="history.inboundReceiptId" :href="`/inbound?receiptId=${encodeURIComponent(history.inboundReceiptId)}`">正式入库</a></li>
      </ul>
    </div>
  </section>
</template>
