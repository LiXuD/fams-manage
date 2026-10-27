<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { reviewRequest, ReviewApiError, type Board, type Observation, type Review } from "@/inventory-review/api";
import type { LoanMutation } from "@/loan/api";
const props = defineProps<{ taskId: string; itemId: string; itemPage: number; observations: Observation[] }>();
const emit = defineEmits<{ invalid: []; changed: [] }>();
interface SelectionView { taskVersion: number; recheckGeneration: number; contextDigest: string; review: Review;
  history: { review_version: number; observation_id: string; actor_username: string; recorded_at: string;
    verified_observed_at: string; time_source: string; reason: string; evidence: string }[]; total: number }
const session = useSessionStore();
const identity = computed(() => JSON.stringify([props.taskId, props.itemId, session.user?.id, session.csrfToken, session.user?.roles]));
const data = ref<SelectionView | null>(null), board = ref<Board | null>(null), page = ref(1);
const choice = ref(""), time = ref(""), reason = ref(""), evidence = ref(""), error = ref(""), notice = ref("");
const busy = ref(false), pending = ref<LoanMutation | null>(null);
const locked = computed(() => busy.value || !!pending.value);
const item = computed(() => board.value?.items.find(row => row.item.id === props.itemId));
const available = computed(() => props.observations.filter(row => row.recheck_generation === data.value?.recheckGeneration &&
  ["ACCEPTED", "DIFFERENCE_REVIEW", "CONFLICT_REVIEW", "OLD_ASSIGNMENT_REVIEW", "TIME_REVIEW"].includes(row.outcome)));
const needsTime = computed(() => available.value.find(row => row.id === choice.value)?.outcome === "TIME_REVIEW");
const editable = computed(() => board.value && ["IN_PROGRESS", "WAITING_REVIEW"].includes(board.value.task.task_status) &&
  (!data.value?.review.review_status || data.value.review.review_status === "SELECTED"));
const outcomes: Record<string, string> = { ACCEPTED: "正常观察候选", DIFFERENCE_REVIEW: "差异证据", CONFLICT_REVIEW: "冲突证据", OLD_ASSIGNMENT_REVIEW: "旧分配证据", TIME_REVIEW: "时间待核实" };
const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
let epoch = 0, live = true;
const valid = (token: number, who: string) => live && token === epoch && who === identity.value;
function clear() { data.value = null; board.value = null; choice.value = ""; time.value = ""; reason.value = "";
  evidence.value = ""; pending.value = null; busy.value = false; }
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError) {
    if ([401,403,404].includes(cause.status)) { clear(); emit("invalid"); error.value = "当前身份、权限或记录已失效，已隐藏原资料。"; return; }
    if (cause.fields.processedBy) { error.value = `${cause.fields.processedBy} 已于 ${new Date(cause.fields.processedAt).toLocaleString()} 处理，请刷新核对其选择。`; return; }
    if (cause.status === 409) { error.value = "复核版本或资产流转已变化，请刷新并重新核对。"; return; }
    if (cause.status === 400) { error.value = "请核对证据、时间范围、原因及明确依据。"; return; }
  }
  error.value = pending.value ? "提交结果尚未确认，请重试原选择。" : "读取未完成，请刷新重试。";
}
async function load() {
  if (pending.value) return;
  const token = ++epoch, who = identity.value; busy.value = true; error.value = "";
  try {
    const [selection, task] = await Promise.all([
      reviewRequest<SelectionView>(`/inventory-reviews/tasks/${props.taskId}/items/${props.itemId}/selection?page=${page.value}&pageSize=20`),
      reviewRequest<Board>(`/inventory-reviews/tasks/${props.taskId}?page=${props.itemPage}&pageSize=20`)]);
    if (valid(token, who)) { data.value = selection; board.value = task; }
  } catch (cause) { if (valid(token, who)) failure(cause); }
  finally { if (valid(token, who)) busy.value = false; }
}
async function save() {
  if (busy.value || !data.value || !item.value?.current) return;
  if (!pending.value) {
    if (!editable.value || !available.value.some(row => row.id === choice.value) || reason.value.trim().length < 3 || evidence.value.trim().length < 10) return;
    let verifiedObservedAt: string | undefined;
    if (needsTime.value) {
      const instant = new Date(time.value);
      if (!time.value || !Number.isFinite(instant.getTime())) { error.value = "请填写核实后的现场观察时间。"; return; }
      verifiedObservedAt = instant.toISOString();
    }
    if (!window.confirm("保存这条证据及核实依据作为待采信选择？独立差异仍需处理，不会增加已采信数量。")) return;
    pending.value = { path: `/inventory-reviews/tasks/${props.taskId}/items/${props.itemId}/selection`, key: crypto.randomUUID(),
      version: data.value.taskVersion, body: { observationId: choice.value, recheckGeneration: data.value.recheckGeneration,
        reviewVersion: data.value.review.version ?? 0, assetVersion: item.value.current.version, contextDigest: data.value.contextDigest,
        ...(verifiedObservedAt ? { verifiedObservedAt } : {}), reason: reason.value.trim(), evidence: evidence.value.trim() } };
  }
  const original = pending.value, token = ++epoch, who = identity.value; busy.value = true; error.value = "";
  try {
    await reviewRequest(original.path, original);
    if (!valid(token, who)) return;
    pending.value = null; notice.value = "已保存待采信选择；原始证据及历次选择均保留，差异尚须独立闭环。"; emit("changed"); await load();
  } catch (cause) {
    if (!valid(token, who)) return;
    if (cause instanceof ReviewApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    failure(cause);
  } finally { if (valid(token, who)) busy.value = false; }
}
async function pages(delta: number) { if (locked.value || !data.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= data.value.total) return; page.value += delta; await load(); }
watch(identity, () => { epoch++; clear(); error.value = ""; notice.value = ""; page.value = 1;
  if (session.user?.roles.includes("ASSET_ADMIN")) void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; epoch++; clear(); });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="证据选择与时间核实">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">证据选择与时间核实</h3>
        <p class="fams-panel-subtitle">从当前页本轮原始证据中明确选择。选择保留为待采信结论，不替代差异处理或最终采信。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="load">刷新选择与流转依据</button>
        <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="save">重试原证据选择</button>
      </div>
    </div>

    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>

    <template v-if="data">
      <p v-if="data.review.review_status" class="fams-panel-subtitle">本轮结论：{{ data.review.review_status === 'SELECTED' ? '待采信选择' : data.review.review_status === 'ACCEPTED' ? '已采信' : '未观察' }}，第 {{ data.review.version }} 版</p>
      <fieldset class="fams-card fams-stack" :disabled="locked || !editable">
        <label class="fams-field"><span class="fams-field-label">本轮原始证据</span>
          <select class="fams-control" v-model="choice"><option value="">请选择证据</option><option v-for="row in available" :key="row.id" :value="row.id">证据 {{ row.id }} · {{ outcomes[row.outcome] }} · 位置 {{ row.facts_json.actual?.locationHint || row.facts_json.actual?.locationId || '待核实' }} · 接收于 {{ new Date(row.received_at).toLocaleString() }}</option></select></label>
        <label v-if="needsTime" class="fams-field"><span class="fams-field-label">核实后的现场观察时间（{{ localZone }}）</span>
          <input class="fams-control" v-model="time" type="datetime-local" step="0.001" /></label>
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">选择原因</span>
            <textarea class="fams-control" v-model="reason" minlength="3" maxlength="1000" /></label>
          <label class="fams-field"><span class="fams-field-label">明确核实依据</span>
            <textarea class="fams-control" v-model="evidence" minlength="10" maxlength="2000" /></label>
        </div>
        <button class="fams-button" data-kind="primary" type="button" :disabled="!choice || reason.trim().length < 3 || evidence.trim().length < 10" @click="save">保存待采信选择</button>
      </fieldset>

      <h4 class="fams-panel-title">历次选择</h4>
      <p v-if="!data.total" class="fams-panel-subtitle">尚无显式选择记录。</p>
      <ul class="fams-timeline">
        <li v-for="row in data.history" :key="row.review_version">第 {{ row.review_version }} 版 · 证据 {{ row.observation_id }} · {{ row.actor_username }} · {{ new Date(row.recorded_at).toLocaleString() }}<small>观察时间：{{ new Date(row.verified_observed_at).toLocaleString() }}（{{ row.time_source === 'ADMIN_VERIFIED' ? '管理员核实' : '采集校准' }}）</small><small>{{ row.reason }}；依据：{{ row.evidence }}</small></li>
      </ul>

      <div class="fams-pagination">
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page === 1" @click="pages(-1)">上页选择记录</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page * 20 >= data.total" @click="pages(1)">下页选择记录</button>
        </div>
      </div>
    </template>
  </section>
</template>
