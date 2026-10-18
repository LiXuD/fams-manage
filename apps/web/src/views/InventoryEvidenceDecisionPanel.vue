<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { reviewRequest, ReviewApiError, type Board, type Observation, type Review } from "@/inventory-review/api";
import type { LoanMutation } from "@/loan/api";
const props = defineProps<{ taskId: string; itemId: string; itemPage: number; observations: Observation[] }>();
const emit = defineEmits<{ invalid: []; changed: [] }>();
interface Selection { taskVersion: number; recheckGeneration: number; contextDigest: string; review: Review & { observation_id?: string } }
interface Subject { dimension: string; version: number; subject_status: string; reviewer_username: string | null; reviewed_at: string | null }
interface Disposition { observation_id: string; counter_observation_id: string; counter_review_version: number; disposition: string; reason: string; evidence: string; actor_username: string; recorded_at: string }
const session = useSessionStore();
const identity = computed(() => JSON.stringify([props.taskId, props.itemId, session.user?.id, session.csrfToken, session.user?.roles]));
const selection = ref<Selection | null>(null), board = ref<Board | null>(null), subjects = ref<Subject[]>([]), dispositions = ref<Disposition[]>([]);
const target = ref(""), method = ref("REJECT_EVIDENCE"), reason = ref(""), evidence = ref(""), page = ref(1), total = ref(0);
const busy = ref(false), error = ref(""), pending = ref<LoanMutation | null>(null);
const locked = computed(() => busy.value || !!pending.value);
const currentItem = computed(() => board.value?.items.find(row => row.item.id === props.itemId));
const chosen = computed(() => target.value === selection.value?.review.observation_id);
const special = (dimension: string) => ["CONFLICT", "OLD_ASSIGNMENT", "TIME"].includes(dimension);
const references = computed(() => chosen.value ? subjects.value.filter(row => special(row.dimension)) : subjects.value);
const available = computed(() => props.observations.filter(row => row.recheck_generation === selection.value?.recheckGeneration &&
  ["ACCEPTED", "DIFFERENCE_REVIEW", "CONFLICT_REVIEW", "OLD_ASSIGNMENT_REVIEW", "TIME_REVIEW"].includes(row.outcome)));
const active = computed(() => board.value && ["IN_PROGRESS", "WAITING_REVIEW"].includes(board.value.task.task_status));
const dimensions: Record<string, string> = { NORMAL: "正常", LOCATION: "位置", USAGE_MODE: "使用方式", RESPONSIBILITY: "责任", CONDITION: "状况", UNKNOWN: "未知事实", PERIOD_MOVEMENT: "期间流转", CONFLICT: "观察冲突", OLD_ASSIGNMENT: "旧分配", TIME: "时间异常", FINDING: "任务外线索", LATE: "迟到线索" };
const statuses: Record<string, string> = { OPEN: "待处理", PROCESSING: "处理中", CLOSED: "已关闭", TRANSFERRED: "已转后续业务" };
let epoch = 0, live = true;
const valid = (token: number, who: string) => live && token === epoch && who === identity.value;
function clear() { selection.value = null; board.value = null; subjects.value = []; dispositions.value = []; target.value = "";
  reason.value = ""; evidence.value = ""; pending.value = null; busy.value = false; }
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError) {
    if ([401,403,404].includes(cause.status)) { clear(); emit("invalid"); error.value = "当前身份、权限或记录已失效，已隐藏原资料。"; return; }
    if (cause.fields.processedBy) { error.value = `${cause.fields.processedBy} 已于 ${new Date(cause.fields.processedAt).toLocaleString()} 处理，请刷新核对结果。`; return; }
    const messages: Record<string, string> = {
      INVENTORY_REVIEW_RECOLLECTION_REQUIRED: "原始首次持久化或离线期限无法可靠还原，请安排补盘；修改观察时间不能延长期限。",
      INVENTORY_REVIEW_PREPARATION_REQUIRED: "还有原始差异未列入，请先在独立差异区域列入当前页证据。",
      INVENTORY_REVIEW_UNDECIDED_EVIDENCE: "仍有未选证据缺少明确的反证或补充处置，请逐条核对。",
      INVENTORY_REVIEW_UNRESOLVED_DIMENSIONS: "仍有未闭环的独立差异，请沿相应事实或正式业务继续处理。",
      INVENTORY_REVIEW_LINKED_EVIDENCE_REQUIRED: "该证据已有正式业务关联，请沿关联业务继续，不得通过反证覆盖。",
      INVENTORY_REVIEW_RECHECK_REQUIRED: "所选证据已作为生效决定的依据；新证据请安排复盘。",
      INVENTORY_REVIEW_CONTEXT_CHANGED: "资产发生新的流转或物理信息变化，请重新核对或安排复盘。",
    };
    if (messages[cause.code]) { error.value = messages[cause.code]!; return; }
    if (cause.status === 409) { error.value = "任务、证据或差异处理版本已变化，请刷新核对。"; return; }
    if (cause.status === 400) { error.value = "请完整填写处理原因与可追溯的反证依据。"; return; }
  }
  error.value = pending.value ? "处理结果尚未确认，请保留并重试原操作。" : "读取未完成，请刷新重试。";
}
async function load() {
  if (pending.value) return;
  const token = ++epoch, who = identity.value; busy.value = true; error.value = "";
  try {
    const base = `/inventory-reviews/tasks/${props.taskId}/items/${props.itemId}`;
    const [value, task, records] = await Promise.all([reviewRequest<Selection>(base + "/selection"),
      reviewRequest<Board>(`/inventory-reviews/tasks/${props.taskId}?page=${props.itemPage}&pageSize=20`),
      reviewRequest<{ items: Disposition[]; total: number }>(base + `/evidence-dispositions?page=${page.value}&pageSize=20`)]);
    if (valid(token, who)) { selection.value = value; board.value = task; dispositions.value = records.items; total.value = records.total; subjects.value = []; target.value = ""; }
  } catch (cause) { if (valid(token, who)) failure(cause); }
  finally { if (valid(token, who)) busy.value = false; }
}
async function readSubjects() {
  if (locked.value || !target.value) return;
  const token = ++epoch, who = identity.value, observation = target.value; busy.value = true; subjects.value = [];
  method.value = chosen.value ? "VERIFY_SELECTED" : "REJECT_EVIDENCE";
  try {
    const data = await reviewRequest<{ items: Subject[] }>(`/inventory-reviews/tasks/${props.taskId}/items/${props.itemId}/evidence-subjects?observationId=${observation}`);
    if (valid(token, who) && target.value === observation) subjects.value = data.items;
  } catch (cause) { if (valid(token, who)) failure(cause); }
  finally { if (valid(token, who)) busy.value = false; }
}
function snapshot() {
  if (!selection.value || !currentItem.value?.current) return null;
  return { recheckGeneration: selection.value.recheckGeneration, reviewVersion: selection.value.review.version,
    assetVersion: currentItem.value.current.version, contextDigest: selection.value.contextDigest,
    reason: reason.value.trim(), evidence: evidence.value.trim() };
}
async function send(acceptance = false) {
  if (busy.value) return;
  if (!pending.value) {
    const data = snapshot();
    if (!active.value || !data || data.reviewVersion < 1 || data.reason.length < 3 || data.evidence.length < 10) return;
    if (acceptance ? selection.value?.review.review_status !== "SELECTED" : !available.value.some(row => row.id === target.value)) return;
    if (chosen.value && !acceptance && !references.value.length) return;
    const message = acceptance ? "最终采信所选证据？服务端将重新核对全部本轮证据、独立差异、当前流转和原始离线期限。" :
      chosen.value ? `共同核实所选证据的 ${references.value.length} 个异常维度？位置、责任和状况等仍须独立闭环。` :
      method.value === "REJECT_EVIDENCE" ? `以明确反证拒绝证据 ${target.value}，并对所列 ${references.value.length} 个维度共同记录决定？已有正式关联不得覆盖。` :
      `将证据 ${target.value} 保留为补充线索？位置、责任、状况和期间流转不会因此关闭。`;
    if (!window.confirm(message)) return;
    pending.value = { path: `/inventory-reviews/tasks/${props.taskId}/items/${props.itemId}/${acceptance ? "acceptance" : "evidence-decisions"}`,
      key: crypto.randomUUID(), version: selection.value!.taskVersion, body: acceptance ? data : { ...data, observationId: target.value,
        method: method.value, subjects: references.value.map(row => ({ dimension: row.dimension, version: row.version })) } };
  }
  const original = pending.value, token = ++epoch, who = identity.value; busy.value = true; error.value = "";
  try { await reviewRequest(original.path, original); if (!valid(token, who)) return; pending.value = null; emit("changed"); await load(); }
  catch (cause) { if (!valid(token, who)) return; if (cause instanceof ReviewApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); }
  finally { if (valid(token, who)) busy.value = false; }
}
async function pages(delta: number) { if (locked.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= total.value) return; page.value += delta; await load(); }
watch(identity, () => { epoch++; clear(); error.value = ""; page.value = 1; if (session.user?.roles.includes("ASSET_ADMIN")) void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; epoch++; clear(); });
</script>
<template>
  <section class="panel" aria-label="证据处置与最终采信"><h3>证据处置与最终采信</h3><p>原始事件全部保留。反证须明确说明事实为何不成立；补充线索不能代替真实差异闭环。</p>
    <p v-if="error" role="alert">{{ error }}</p><button :disabled="locked" @click="load">刷新证据处置</button><button v-if="pending" :disabled="busy" @click="send()">重试原证据处置</button>
    <template v-if="selection"><p v-if="selection.review.observation_id">当前选择：证据 {{ selection.review.observation_id }} · {{ selection.review.review_status === 'ACCEPTED' ? '已采信' : '待采信' }}</p><p v-else>请先明确选择本轮证据。</p>
      <fieldset :disabled="locked || !active || !selection.review.observation_id"><label>待处理原始证据<select v-model="target" @change="readSubjects"><option value="">请选择</option><option v-for="row in available" :key="row.id" :value="row.id">证据 {{ row.id }} · {{ row.id === selection.review.observation_id ? '当前所选' : '未选证据' }} · 位置 {{ row.facts_json.actual?.locationHint || row.facts_json.actual?.locationId || '待核实' }}</option></select></label>
        <p v-for="row in subjects" :key="row.dimension">{{ dimensions[row.dimension] || row.dimension }}：{{ statuses[row.subject_status] }} · 第 {{ row.version }} 版<span v-if="row.reviewer_username"> · {{ row.reviewer_username }}</span></p>
        <label>处理方式<select v-model="method"><option v-if="chosen" value="VERIFY_SELECTED">核实所选证据的冲突、旧分配或时间依据</option><template v-else><option value="REJECT_EVIDENCE">以明确反证拒绝这条未选证据</option><option value="SUPPLEMENTAL">保留为补充线索，物理差异继续处理</option></template></select></label>
        <label>处理原因<textarea v-model="reason" minlength="3" maxlength="1000" /></label><label>明确反证或核实依据<textarea v-model="evidence" minlength="10" maxlength="2000" /></label>
        <button :disabled="!target || chosen && !references.length || reason.trim().length < 3 || evidence.trim().length < 10" @click="send(false)">共同提交证据处置</button>
        <button :disabled="selection.review.review_status !== 'SELECTED' || reason.trim().length < 3 || evidence.trim().length < 10" @click="send(true)">最终采信所选证据</button>
      </fieldset>
      <h4>未选证据处置记录</h4><p v-if="!total">尚无反证或补充处置。</p><article v-for="row in dispositions" :key="row.observation_id"><p>证据 {{ row.observation_id }} · {{ row.disposition === 'REJECTED' ? '有反证拒绝' : '补充线索' }}；反证依据选择 {{ row.counter_observation_id }} 第 {{ row.counter_review_version }} 版</p><p>{{ row.actor_username }} · {{ new Date(row.recorded_at).toLocaleString() }}；{{ row.reason }}；{{ row.evidence }}</p></article>
      <button :disabled="locked || page === 1" @click="pages(-1)">上页处置记录</button>第 {{ page }} 页<button :disabled="locked || page * 20 >= total" @click="pages(1)">下页处置记录</button>
    </template>
  </section>
</template>
<style scoped>
label { display: block; margin: 8px 0; } textarea { display: block; width: 100%; min-height: 64px; } button { margin: 8px; padding: 8px 12px; } article { border-bottom: 1px solid #e4e7ed; }
</style>
