<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import type { LoanMutation } from "@/loan/api";
import { ReviewApiError, reviewProblems, reviewRequest, type BatchResult, type Board, type Observation, type ReviewItem } from "@/inventory-review/api";
const props = defineProps<{ taskId: string }>();
const emit = defineEmits<{ changed: [] }>();
const session = useSessionStore();
const board = ref<Board | null>(null); const page = ref(1); const selected = ref<string[]>([]);
const reason = ref("已核对本轮现场正常观察"); const busy = ref(false); const error = ref("");
const pending = ref<LoanMutation | null>(null); const result = ref<BatchResult | null>(null);
const observations = ref<{ itemId: string; rows: Observation[]; total: number; page: number } | null>(null);
const identity = computed(() => JSON.stringify([session.user?.id, session.csrfToken, session.user?.roles]));
const locked = computed(() => busy.value || !!pending.value);
let generation = 0, live = true;
const current = (epoch: number, owner: string, task: string) => live && epoch === generation && owner === identity.value && props.taskId === task;
const clear = () => { board.value = null; selected.value = []; observations.value = null; result.value = null; pending.value = null; busy.value = false; };
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
async function accept() {
  if (busy.value || !board.value) return;
  if (!pending.value) {
    const items = board.value.items.filter(row => selected.value.includes(row.item.id) && row.canAcceptNormal);
    if (!items.length || !reason.value.trim() || !window.confirm(`采信所选 ${items.length} 项正常观察？存在差异或冲突的项目不会自动采信。`)) return;
    pending.value = { path: `/inventory-reviews/tasks/${props.taskId}/normal-acceptance`, key: crypto.randomUUID(),
      version: board.value.task.version, body: { reason: reason.value.trim(), items: items.map(row => ({ itemId: row.item.id,
        recheckGeneration: row.item.recheck_generation, reviewVersion: row.review.version, assetVersion: row.current!.version })) } };
  }
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
watch(() => props.taskId, () => { generation++; clear(); page.value = 1; void load(); }, { immediate: true });
watch(identity, () => { generation++; clear(); error.value = "身份或权限已变化，请重新进入复核任务。"; }, { flush: "sync" });
onBeforeUnmount(() => { live = false; generation++; clear(); });
</script>
<template>
  <section class="panel" aria-label="盘点证据复核">
    <h2>证据复核</h2><p>账面、现场观察与当前资产分别保留。采信不直接改变资产责任，封存前仍须完成差异及同步检查。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p><button :disabled="busy" @click="load">刷新复核信息</button>
    <button v-if="pending" :disabled="busy" @click="accept">重试原采信操作</button>
    <p v-if="board">已采信 {{ board.reviewedCount }} 项 / 应盘 {{ board.total }} 项</p>
    <p v-if="result" role="status">本次已采信 {{ result.accepted }} 项，冲突或待核实 {{ result.conflicts }} 项。</p>
    <ul v-if="result"><li v-for="item in result.items" :key="item.itemId">应盘项 {{ item.itemId }}：{{ item.status === 'ACCEPTED' ? '已采信' : reviewProblems[item.code ?? ''] ?? '请核对当前结果' }}<span v-if="item.current?.reviewer_username">；{{ item.current.reviewer_username }} 于 {{ new Date(item.current.reviewed_at!).toLocaleString() }} 已处理</span></li></ul>
    <table v-if="board"><thead><tr><th>选择</th><th>账面快照</th><th>原始现场观察</th><th>当前资产与复核</th></tr></thead><tbody>
      <tr v-for="row in board.items" :key="row.item.id">
        <td><input v-model="selected" :value="row.item.id" type="checkbox" :disabled="locked || !row.canAcceptNormal" :aria-label="`选择 ${row.item.snapshot_json.assetCode}`"></td>
        <td><strong>{{ row.item.snapshot_json.assetCode }} · {{ row.item.snapshot_json.name }}</strong><p>{{ row.item.snapshot_json.locationName || '未指定位置' }}</p><p>{{ modes[row.item.snapshot_json.custodyType ?? ''] || '待核实' }} · {{ row.item.snapshot_json.responsibleEmployeeName ?? row.item.snapshot_json.departmentResponsibleEmployeeName ?? '仓库保管' }}</p></td>
        <td><template v-if="row.candidate"><p>{{ outcomes[row.candidate.outcome] ?? '待核实' }} · 第 {{ row.item.recheck_generation + 1 }} 轮</p><p>位置：{{ row.candidate.facts_json.actual?.locationId === row.item.snapshot_json.locationId ? row.item.snapshot_json.locationName : row.candidate.facts_json.actual?.locationHint || row.candidate.facts_json.actual?.locationId || '未知' }}</p><p>{{ modes[row.candidate.facts_json.actual?.custodyType ?? ''] || '未知使用模式' }} · {{ conditions[row.candidate.facts_json.actual?.condition ?? ''] || '状况未知' }}</p><p v-if="row.candidate.facts_json.actual?.unknownReason">未知原因：{{ row.candidate.facts_json.actual.unknownReason }}</p></template><p v-else>尚无本轮观察候选</p><button :disabled="locked" @click="history(row)">查看全部原始证据</button></td>
        <td><template v-if="row.current"><strong>{{ row.current.name }}</strong><p>{{ row.current.locationName || '位置待核实' }} · {{ modes[row.current.custodyType ?? ''] || '待核实' }}</p></template><p v-else>当前资产不可用</p><p v-if="row.review.reviewer_username">{{ row.review.reviewer_username }} 于 {{ new Date(row.review.reviewed_at!).toLocaleString() }} 已采信；原因：{{ row.review.reason }}</p><p v-if="row.blockedReason">{{ reviewProblems[row.blockedReason] ?? '须独立复核' }}</p><p v-if="row.movementEvents.length">期间业务事件 {{ row.movementEvents.length }} 条，不能仅凭当前位置相同判正常。</p></td>
      </tr>
    </tbody></table>
    <button :disabled="locked || page === 1" @click="paginate(-1)">上页证据</button>第 {{ page }} 页<button :disabled="locked || !board || page * 20 >= board.total" @click="paginate(1)">下页证据</button>
    <fieldset v-if="board && ['IN_PROGRESS','WAITING_REVIEW'].includes(board.task.task_status)" :disabled="locked"><label>正常采信原因<textarea v-model="reason" maxlength="1000" /></label><button :disabled="!selected.length || !reason.trim()" @click="accept">逐项采信所选正常观察</button></fieldset>
    <section v-if="observations" class="panel"><h3>应盘项 {{ observations.itemId }} 的原始证据</h3><article v-for="observation in observations.rows" :key="observation.id"><p>{{ outcomes[observation.outcome] ?? '待核实' }} · 接收于 {{ new Date(observation.received_at).toLocaleString() }}</p><p>观察于 {{ observation.facts_json.rawObservedAt ? new Date(observation.facts_json.rawObservedAt).toLocaleString() : '未保留或待核实' }}；照片 {{ observation.facts_json.photoIds?.length ?? 0 }} 张</p><p>位置 {{ observation.facts_json.actual?.locationHint || observation.facts_json.actual?.locationId || '未知' }}；责任线索 {{ observation.facts_json.actual?.responsibilityHint || observation.facts_json.actual?.responsibleEmployeeId || '待核实或仓库保管' }}</p><p>{{ observation.facts_json.actual?.description }}</p></article><template v-for="row in board?.items.filter(item => item.item.id === observations!.itemId) ?? []" :key="row.item.id"><button :disabled="locked || observations.page === 1" @click="history(row,-1)">上页原始证据</button><button :disabled="locked || observations.page * 20 >= observations.total" @click="history(row,1)">下页原始证据</button></template></section>
  </section>
</template>
<style scoped>
table { width: 100%; border-collapse: collapse; text-align: left; } th, td { padding: 12px; border-bottom: 1px solid #e4e7ed; vertical-align: top; } button { margin: 8px; padding: 8px 12px; } textarea { display: block; width: 100%; min-height: 64px; } article { border-bottom: 1px solid #e4e7ed; padding: 8px; }
</style>
