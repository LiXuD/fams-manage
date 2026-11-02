<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import InventoryEvidenceDecisionPanel from "@/features/inventory/panels/InventoryEvidenceDecisionPanel.vue";
import InventorySelectionPanel from "@/features/inventory/panels/InventorySelectionPanel.vue";
import InventoryDifferencePanel from "@/features/inventory/panels/InventoryDifferencePanel.vue";
import InventoryPhotoEvidence from "@/features/inventory/panels/InventoryPhotoEvidence.vue";
import { useSessionStore } from "@/auth/sessionStore";
import type { BusinessMutation } from "@/api/business";
import { ReviewApiError, reviewProblems, reviewRequest, type BatchResult, type Board, type Observation, type ReviewItem } from "@/features/inventory/api";
const props = defineProps<{ taskId: string }>();
const emit = defineEmits<{ changed: [] }>();
const session = useSessionStore();
const board = ref<Board | null>(null); const page = shallowRef(1); const selected = ref<string[]>([]);
const reason = ref("已核对本轮现场正常观察"); const busy = shallowRef(false); const error = shallowRef("");
const pending = ref<BusinessMutation | null>(null); const result = ref<BatchResult | null>(null);
const observations = ref<{ itemId: string; rows: Observation[]; total: number; page: number } | null>(null);
const stage = ref<BusinessMutation | null>(null); const confirmOpen = shallowRef(false); const confirmCount = shallowRef(0);
const confirmLabel = "确认采信所选正常观察";
const confirmHint = computed(() => `采信所选 ${confirmCount.value} 项正常观察？存在差异或冲突的项目不会自动采信。`);
const identity = computed(() => JSON.stringify([session.user?.id, session.csrfToken, session.user?.roles]));
const locked = computed(() => busy.value || !!pending.value);
let generation = 0, live = true;
const current = (epoch: number, owner: string, task: string) => live && epoch === generation && owner === identity.value && props.taskId === task;
const clear = () => { generation++; board.value = null; selected.value = []; observations.value = null; result.value = null; pending.value = null; busy.value = false; stage.value = null; confirmOpen.value = false; };
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError) {
    if ([401, 403, 404].includes(cause.status)) { clear(); error.value = "登录、权限或当前记录已失效，已隐藏原资料，请重新登录或核对授权。"; return; }
    if (cause.fields.processedBy) {
      error.value = `该项已由 ${cause.fields.processedBy} 于 ${new Date(cause.fields.processedAt).toLocaleString()} 处理，结果：${cause.fields.result === "ACCEPTED" ? "已采信" : "请查看最新结论"}。请刷新后核对。`; return;
    }
    if (cause.status === 409) { error.value = "任务、资料或处理版本已变化，请刷新核对逐项结果。"; return; }
    if (cause.status === 400) { error.value = "请核对选择和采信原因。"; return; }
  }
  error.value = pending.value ? "操作结果尚未确认，请保留原操作并重试。" : "读取暂未完成，请刷新；已收到的采信结果不因此改变。";
}
async function load() {
  if (!/^[1-9][0-9]*$/.test(props.taskId)) return;
  const epoch = ++generation, owner = identity.value, task = props.taskId;
  busy.value = true; error.value = "";
  try {
    const value = await reviewRequest<Board>(`/inventory-reviews/tasks/${task}?page=${page.value}&pageSize=20`);
    if (current(epoch, owner, task)) { board.value = value; selected.value = []; observations.value = null; }
  } catch (cause) { if (current(epoch, owner, task)) failure(cause); }
  finally { if (current(epoch, owner, task)) busy.value = false; }
}
function openAccept() {
  if (busy.value || pending.value || !board.value) return;
  const items = board.value.items.filter(row => selected.value.includes(row.item.id) && row.canAcceptNormal);
  if (!items.length || !reason.value.trim()) return;
  confirmCount.value = items.length;
  stage.value = { path: `/inventory-reviews/tasks/${props.taskId}/normal-acceptance`, key: crypto.randomUUID(),
    version: board.value.task.version, body: { reason: reason.value.trim(), items: items.map(row => ({ itemId: row.item.id,
      recheckGeneration: row.item.recheck_generation, reviewVersion: row.review.version, assetVersion: row.current!.version })) } };
  confirmOpen.value = true;
}
function runAccept() {
  if (busy.value || !stage.value) return;
  pending.value = stage.value; stage.value = null; confirmOpen.value = false;
  void accept();
}
function closeConfirm() { stage.value = null; confirmOpen.value = false; }
async function accept() {
  if (busy.value || !board.value || !pending.value) return;
  const epoch = ++generation, owner = identity.value, task = props.taskId;
  const original = pending.value; busy.value = true; error.value = "";
  try {
    const reply = await reviewRequest<BatchResult>(original.path, original);
    if (!current(epoch, owner, task)) return;
    result.value = reply; pending.value = null; emit("changed"); await load();
  } catch (cause) {
    if (!current(epoch, owner, task)) return;
    if (cause instanceof ReviewApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    failure(cause);
  } finally { if (current(epoch, owner, task)) busy.value = false; }
}
async function history(row: ReviewItem, delta = 0) {
  if (locked.value) return;
  const next = observations.value?.itemId === row.item.id ? observations.value.page + delta : 1;
  if (next < 1) return;
  const epoch = ++generation, owner = identity.value, task = props.taskId; busy.value = true;
  try {
    const data = await reviewRequest<{ items: Observation[]; total: number }>(`/inventory-reviews/tasks/${task}/items/${row.item.id}/observations?page=${next}&pageSize=20`);
    if (current(epoch, owner, task)) observations.value = { itemId: row.item.id, rows: data.items, total: data.total, page: next };
  } catch (cause) { if (current(epoch, owner, task)) failure(cause); }
  finally { if (current(epoch, owner, task)) busy.value = false; }
}
async function paginate(delta: number) {
  if (locked.value || !board.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= board.value.total) return;
  page.value += delta; await load();
}
const modes: Record<string, string> = { STORAGE: "仓库保管", PERSONAL: "个人使用", SHARED: "部门公用" };
const conditions: Record<string, string> = { GOOD: "正常", DAMAGED: "损坏", UNKNOWN: "不确定" };
const outcomes: Record<string, string> = { ACCEPTED: "本轮观察候选", DIFFERENCE_REVIEW: "差异待复核", DUPLICATE: "重复，未重复计数", CONFLICT_REVIEW: "冲突待复核", OLD_ASSIGNMENT_REVIEW: "旧分配证据", TIME_REVIEW: "时间待核实", LATE_REVIEW: "迟到补充线索", EXPIRED: "过期拒绝", FINDING_REVIEW: "任务外或手工线索" };
interface BoardRow extends ReviewItem {
  selectLabel: string; bookLocationText: string; bookCustodyText: string; responsibleText: string;
  candidateOutcomeText: string; roundText: string; candidateLocationText: string; candidateCustodyText: string; candidateConditionText: string;
  currentLocationText: string; currentCustodyText: string; reviewedAtText: string; reviewActionText: string; blockedText: string;
}
interface ObservationRow extends Observation {
  outcomeText: string; receivedAtText: string; observedAtText: string; photoCount: number; locationText: string; responsibilityText: string;
}
interface ResultRow { itemId: string; statusText: string; processedText: string }
const boardRows = computed<BoardRow[]>(() => (board.value?.items ?? []).map(row => {
  const candidate = row.candidate?.facts_json.actual;
  return { ...row,
    selectLabel: `选择 ${row.item.snapshot_json.assetCode}`,
    bookLocationText: row.item.snapshot_json.locationName || '未指定位置',
    bookCustodyText: modes[row.item.snapshot_json.custodyType ?? ''] || '待核实',
    responsibleText: row.item.snapshot_json.responsibleEmployeeName ?? row.item.snapshot_json.departmentResponsibleEmployeeName ?? '仓库保管',
    candidateOutcomeText: row.candidate ? outcomes[row.candidate.outcome] ?? '待核实' : '',
    roundText: `第 ${row.item.recheck_generation + 1} 轮`,
    candidateLocationText: row.candidate ? (candidate?.locationId === row.item.snapshot_json.locationId
      ? row.item.snapshot_json.locationName ?? '' : candidate?.locationHint || candidate?.locationId || '未知') : '',
    candidateCustodyText: row.candidate ? modes[candidate?.custodyType ?? ''] || '未知使用模式' : '',
    candidateConditionText: row.candidate ? conditions[candidate?.condition ?? ''] || '状况未知' : '',
    currentLocationText: row.current ? row.current.locationName || '位置待核实' : '',
    currentCustodyText: row.current ? modes[row.current.custodyType ?? ''] || '待核实' : '',
    reviewedAtText: new Date(row.review.reviewed_at!).toLocaleString(),
    reviewActionText: row.review.review_status === 'SELECTED' ? '已选择待采信证据' : row.review.review_status === 'ACCEPTED' ? '已采信' : '已记录未观察',
    blockedText: row.blockedReason ? reviewProblems[row.blockedReason] ?? '须独立复核' : '' };
}));
const observationRows = computed<ObservationRow[]>(() => (observations.value?.rows ?? []).map(row => ({ ...row,
  outcomeText: outcomes[row.outcome] ?? '待核实',
  receivedAtText: new Date(row.received_at).toLocaleString(),
  observedAtText: row.facts_json.rawObservedAt ? new Date(row.facts_json.rawObservedAt).toLocaleString() : '未保留或待核实',
  photoCount: row.facts_json.photoIds?.length ?? 0,
  locationText: row.facts_json.actual?.locationHint || row.facts_json.actual?.locationId || '未知',
  responsibilityText: row.facts_json.actual?.responsibilityHint || row.facts_json.actual?.responsibleEmployeeId || '待核实或仓库保管' })));
const resultRows = computed<ResultRow[]>(() => (result.value?.items ?? []).map(item => ({ itemId: item.itemId,
  statusText: item.status === 'ACCEPTED' ? '已采信' : reviewProblems[item.code ?? ''] ?? '请核对当前结果',
  processedText: item.current?.reviewer_username ? `；${item.current.reviewer_username} 于 ${new Date(item.current.reviewed_at!).toLocaleString()} 已处理` : '' })));
const matchedRows = computed<ReviewItem[]>(() => { const itemId = observations.value?.itemId;
  return itemId ? (board.value?.items ?? []).filter(row => row.item.id === itemId) : []; });
const observationIds = computed<string[]>(() => observations.value?.rows.map(row => row.id) ?? []);
const acceptanceOpen = computed(() => !!board.value && ['IN_PROGRESS', 'WAITING_REVIEW'].includes(board.value.task.task_status));
const nextObservationDisabled = computed(() => { const seen = observations.value;
  return locked.value || !seen || seen.page * 20 >= seen.total; });
const prevObservationDisabled = computed(() => { const seen = observations.value;
  return locked.value || !seen || seen.page === 1; });
const nextBoardDisabled = computed(() => locked.value || !board.value || page.value * 20 >= board.value.total);
const prevBoardDisabled = computed(() => locked.value || page.value === 1);
const acceptDisabled = computed(() => !selected.value.length || !reason.value.trim());
watch(() => props.taskId, () => { generation++; clear(); page.value = 1; void load(); }, { immediate: true });
watch(identity, () => { generation++; clear(); error.value = "身份或权限已变化，请重新进入复核任务。"; }, { flush: "sync" });
onBeforeUnmount(() => { live = false; generation++; clear(); });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="盘点证据复核">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">证据复核</h3>
        <p class="fams-panel-subtitle">账面、现场观察与当前资产分别保留。采信不直接改变资产责任，封存前仍须完成差异及同步检查。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">刷新复核信息</button>
        <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="accept">重试原采信操作</button>
      </div>
    </div>

    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="board" class="fams-panel-subtitle">已采信 {{ board.reviewedCount }} 项 / 应盘 {{ board.total }} 项</p>
    <p v-if="result" class="fams-notice" data-state="success" role="status"><strong>本次已采信 {{ result.accepted }} 项，冲突或待核实 {{ result.conflicts }} 项。</strong></p>
    <ul v-if="result" class="fams-timeline">
      <li v-for="row in resultRows" :key="row.itemId">应盘项 {{ row.itemId }}：{{ row.statusText }}<small v-if="row.processedText">{{ row.processedText }}</small></li>
    </ul>

    <div v-if="board" class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col">选择</th><th scope="col" data-column-kind="identity">账面快照</th>
          <th scope="col">原始现场观察</th><th scope="col">当前资产与复核</th></tr></thead>
        <tbody>
          <tr v-for="row in boardRows" :key="row.item.id">
            <td><label class="fams-selection"><input v-model="selected" :value="row.item.id" type="checkbox"
              :disabled="locked || !row.canAcceptNormal" :aria-label="row.selectLabel" /></label></td>
            <th scope="row" data-column-kind="identity"><strong>{{ row.item.snapshot_json.assetCode }} · {{ row.item.snapshot_json.name }}</strong><p>{{ row.bookLocationText }}</p><p>{{ row.bookCustodyText }} · {{ row.responsibleText }}</p></th>
            <td>
              <template v-if="row.candidate">
                <p>{{ row.candidateOutcomeText }} · {{ row.roundText }}</p>
                <p>位置：{{ row.candidateLocationText }}</p>
                <p>{{ row.candidateCustodyText }} · {{ row.candidateConditionText }}</p>
                <p v-if="row.candidate.facts_json.actual?.unknownReason">未知原因：{{ row.candidate.facts_json.actual.unknownReason }}</p>
              </template>
              <p v-else>尚无本轮观察候选</p>
              <button class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="locked" @click="history(row)">查看全部原始证据</button>
            </td>
            <td>
              <template v-if="row.current"><strong>{{ row.current.name }}</strong><p>{{ row.currentLocationText }} · {{ row.currentCustodyText }}</p></template>
              <p v-else>当前资产不可用</p>
              <p v-if="row.review.reviewer_username">{{ row.review.reviewer_username }} 于 {{ row.reviewedAtText }} {{ row.reviewActionText }}；原因：{{ row.review.reason }}</p>
              <p v-if="row.blockedReason">{{ row.blockedText }}</p>
              <p v-if="row.movementEvents.length">期间业务事件 {{ row.movementEvents.length }} 条，不能仅凭当前位置相同判正常。</p>
            </td>
          </tr>
          <tr v-if="!board.items.length" class="fams-table-empty"><td colspan="4"><strong>本页没有应盘项</strong>翻页或刷新后继续复核。</td></tr>
        </tbody>
      </table>
    </div>

    <div class="fams-pagination">
      <div class="fams-pagination-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevBoardDisabled" @click="paginate(-1)">上页证据</button>
        <span class="fams-pagination-page">第 {{ page }} 页</span>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextBoardDisabled" @click="paginate(1)">下页证据</button>
      </div>
    </div>

    <fieldset v-if="acceptanceOpen" class="fams-card fams-stack" :disabled="locked">
      <label class="fams-field"><span class="fams-field-label">正常采信原因</span>
        <textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
      <button class="fams-button" data-kind="primary" type="button" :disabled="acceptDisabled" @click="openAccept">逐项采信所选正常观察</button>
      <div v-if="confirmOpen" class="fams-card fams-stack" role="group" :aria-label="confirmLabel">
        <p>{{ confirmHint }}</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="runAccept">{{ confirmLabel }}</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="closeConfirm">返回修改</button>
        </div>
      </div>
    </fieldset>

    <template v-if="observations && board"><InventorySelectionPanel :key="observations.itemId" :task-id="taskId" :item-id="observations.itemId" :item-page="board.page" :observations="observations.rows" @invalid="clear" @changed="load" /></template>
    <template v-if="observations && board"><InventoryEvidenceDecisionPanel :key="observations.itemId" :task-id="taskId" :item-id="observations.itemId" :item-page="board.page" :observations="observations.rows" @invalid="clear" @changed="load" /></template>
    <template v-if="observations && board"><InventoryDifferencePanel v-for="row in matchedRows" :key="row.item.id" :task-id="taskId" :item="row" :item-page="board.page" :observation-ids="observationIds" @invalid="clear" /></template>

    <section v-if="observations" class="fams-card fams-stack">
      <h3 class="fams-panel-title">应盘项 {{ observations.itemId }} 的原始证据</h3>
      <article v-for="observation in observationRows" :key="observation.id" class="fams-card">
        <p>证据 {{ observation.id }} · {{ observation.outcomeText }} · 接收于 {{ observation.receivedAtText }}</p>
        <p>观察于 {{ observation.observedAtText }}；照片 {{ observation.photoCount }} 张</p>
        <p>位置 {{ observation.locationText }}；责任线索 {{ observation.responsibilityText }}</p>
        <p>{{ observation.facts_json.actual?.description }}</p>
        <InventoryPhotoEvidence v-if="observation.facts_json.photoIds?.length" :task-id="taskId" :event-id="observation.client_event_id" :photo-ids="observation.facts_json.photoIds" @invalid="clear" />
      </article>
      <template v-for="row in matchedRows" :key="row.item.id">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevObservationDisabled" @click="history(row,-1)">上页原始证据</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextObservationDisabled" @click="history(row,1)">下页原始证据</button>
      </template>
    </section>
  </section>
</template>
