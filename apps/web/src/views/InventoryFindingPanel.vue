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
    actor_username: string; occurred_at?: string; assetId?: string | null; inboundReceiptId?: string | null }[]; historyTotal: number }
const props = defineProps<{ taskId: string }>();
const emit = defineEmits<{ changed: [] }>();
const session = useSessionStore();
const owner = computed(() => JSON.stringify([props.taskId, session.user?.id, session.csrfToken, session.user?.roles]));
const rows = ref<Observation[]>([]), total = ref(0), page = ref(1), detail = ref<FindingView | null>(null);
const busy = ref(false), error = ref(""), notice = ref(""), pending = ref<LoanMutation | null>(null);
const method = ref("NON_ENTERPRISE"), reason = ref(""), evidence = ref("");
const assetCode = ref(""), receiptNumber = ref(""), assets = ref<AssetCard[]>([]), receipts = ref<InboundReceipt[]>([]);
const selectedAsset = ref(""), selectedReceipt = ref("");
const locked = computed(() => busy.value || !!pending.value);
let generation = 0, live = true;
const current = (epoch: number, identity: string) => live && generation === epoch && owner.value === identity;
function clear() { generation++; rows.value = []; total.value = 0; detail.value = null; assets.value = []; receipts.value = [];
  selectedAsset.value = ""; selectedReceipt.value = ""; pending.value = null; busy.value = false; notice.value = ""; }
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
  if (locked.value || row.outcome !== "FINDING_REVIEW") return;
  const previous = detail.value?.observation.id;
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = ""; detail.value = null;
  if (previous !== row.id) { reason.value = ""; evidence.value = ""; assetCode.value = ""; receiptNumber.value = ""; }
  assets.value = []; receipts.value = []; selectedAsset.value = ""; selectedReceipt.value = "";
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
async function save(prepare = false) {
  if (busy.value || !detail.value) return;
  const row = detail.value.observation;
  if (!pending.value) {
    let body: unknown;
    let path = `/inventory-reviews/tasks/${props.taskId}/findings/${row.id}/decisions`;
    if (prepare) { path = `/inventory-reviews/tasks/${props.taskId}/differences/preparation`; body = { observationIds: [row.id] }; }
    else {
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
    const identity = owner.value, epoch = generation;
    if (!window.confirm(prepare ? "将这条盘盈线索列入独立复核？" : "保存这条盘盈差异的核实依据及正式关联？")) return;
    if (!current(epoch, identity)) return;
    pending.value = original;
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
async function paginate(delta: number) { if (locked.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= total.value) return; page.value += delta; await load(); }
watch(owner, () => { clear(); page.value = 1; error.value = ""; }, { flush: "sync" });
watch(method, () => { assets.value = []; receipts.value = []; selectedAsset.value = ""; selectedReceipt.value = ""; });
onBeforeUnmount(() => { live = false; clear(); });
const states: Record<string, string> = { OPEN: "待处理", PROCESSING: "处理中", CLOSED: "已关闭" };
const ownerStates: Record<string, string> = { PENDING_ADMIN_DECISION: "待管理员审批", WAITING_WAREHOUSE_CLAIM: "待库管领取", WAREHOUSE_PROCESSING: "库管处理中", COMPLETED: "已完成", REJECTED: "已驳回", CANCELLED: "已取消" };
const methodLabels: Record<string, string> = { NON_ENTERPRISE: "非企业资产依据", DUPLICATE_ASSET: "已登记资产重复线索", LINK_INBOUND: "关联正式入库", REFRESH_INBOUND: "核对正式入库进度" };
</script>
<template>
  <section class="finding-panel" aria-label="盘盈与任务外线索复核">
    <h2>盘盈与任务外线索</h2><p>线索不会自动建卡。库管先按正常权限建卡并提交入库，管理员在此关联已提交单据；实际验收后才能关闭盘盈。</p>
    <button :disabled="locked" @click="load">读取盘盈与任务外线索</button><p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <button v-if="pending" :disabled="busy" @click="save()">重试原盘盈复核操作</button>
    <ul><li v-for="row in rows" :key="row.id"><p>{{ row.facts_json.actual?.description || '原始现场线索' }} · {{ new Date(row.received_at).toLocaleString() }}</p>
      <button v-if="row.outcome === 'FINDING_REVIEW'" :disabled="locked" @click="open(row)">核对线索 {{ row.id }}</button><p v-else>时间、过期或迟到线索须沿对应复核处理，原始事实继续保留。</p></li></ul>
    <button :disabled="locked || page === 1" @click="paginate(-1)">上一页线索</button>第 {{ page }} 页 / 共 {{ total }} 条<button :disabled="locked || page * 20 >= total" @click="paginate(1)">下一页线索</button>
    <section v-if="detail"><h3>当前线索 {{ detail.observation.id }}</h3><p>{{ detail.observation.facts_json.actual?.description }}</p><p>{{ states[detail.subject.subject_status ?? ''] || '尚未列入复核' }}</p>
      <InventoryPhotoEvidence v-if="detail.observation.facts_json.photoIds?.length" :task-id="taskId" :event-id="detail.observation.client_event_id" :photo-ids="detail.observation.facts_json.photoIds" @invalid="clear" />
      <button v-if="detail.subject.version === undefined" :disabled="locked" @click="save(true)">列入盘盈独立复核</button>
      <fieldset v-else :disabled="locked || detail.subject.subject_status === 'CLOSED'"><label>盘盈处理方式<select v-model="method"><option value="NON_ENTERPRISE">有明确依据确认非企业资产</option><option value="DUPLICATE_ASSET">核实为已登记资产的重复线索</option><option value="LINK_INBOUND">关联已提交的正式入库</option><option value="REFRESH_INBOUND">核对既有关联入库进度</option></select></label>
        <label v-if="method === 'DUPLICATE_ASSET'">权威资产编码<input v-model="assetCode" maxlength="128"></label>
        <label v-if="method === 'LINK_INBOUND' || method === 'REFRESH_INBOUND'">正式入库单号<input v-model="receiptNumber" maxlength="128"></label>
        <button v-if="method !== 'NON_ENTERPRISE'" @click="search">查找正式依据</button>
        <label v-for="asset in assets" :key="asset.id"><input v-model="selectedAsset" type="radio" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</label>
        <div v-for="receipt in receipts" :key="receipt.id"><p>{{ receipt.receiptNumber }} · {{ ownerStates[receipt.status] ?? '状态待核实' }}</p><a :href="`/inbound?receiptId=${encodeURIComponent(receipt.id)}`">查看正式入库单</a>
          <label v-for="item in receipt.items" :key="item.id"><input type="radio" :checked="selectedReceipt === receipt.id && selectedAsset === item.asset.id" @change="selectedReceipt = receipt.id; selectedAsset = item.asset.id">{{ item.assetSnapshot.assetCode }} · {{ item.assetSnapshot.assetName }} · {{ item.executionStatus === 'ACCEPTED' ? '已实际验收' : '待验收' }}</label></div>
        <label>盘盈处理原因<textarea v-model="reason" maxlength="1000" /></label><label>明确盘盈核实依据<textarea v-model="evidence" maxlength="2000" /></label><button @click="save()">保存盘盈复核与关联</button>
      </fieldset>
      <h4>最近 100 条处理历史 / 共 {{ detail.historyTotal }} 条</h4><ul><li v-for="history in detail.history" :key="history.after_version">{{ history.actor_username }} · {{ history.occurred_at ? new Date(history.occurred_at).toLocaleString() : '时间待核实' }} · {{ states[history.subject_status] }} · {{ methodLabels[history.method] ?? '历史处理' }} · {{ history.reason }} · {{ history.evidence }}<a v-if="history.inboundReceiptId" :href="`/inbound?receiptId=${encodeURIComponent(history.inboundReceiptId)}`">正式入库</a></li></ul>
    </section>
  </section>
</template>
<style scoped>
.finding-panel { margin: 24px 0; padding: 20px; border: 1px solid #d8dee8; } label { display: block; margin: 12px 0; } textarea { display: block; width: 100%; } button { margin: 8px; } fieldset { border: 0; padding: 0; }
</style>
