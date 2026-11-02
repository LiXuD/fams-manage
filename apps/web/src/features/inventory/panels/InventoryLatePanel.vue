<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { inventoryLateResultLabels as results, reviewRequest, ReviewApiError } from "@/features/inventory/api";
import InventoryLateDamagePanel from "./InventoryLateDamagePanel.vue";
import type { BusinessMutation } from "@/api/business";
interface Evidence { observation_id: string; comparison_kind: string; report_sha256: string | null;
  snapshot_json: { rawObservedAt: string; calibratedObservedAt: string | null; receivedAt: string;
    assetId: string | null; acceptedObservationId: string | null; facts: { actual?: { locationHint?: string; locationId?: string; condition?: string; unknownReason?: string; description?: string } } } }
interface Decision { decision_version: number; result: string; method?: string; reason?: string; evidence?: string;
  linked_task_id?: string | null; actor_username?: string; decided_at?: string }
interface Entry { evidence: Evidence; review: Decision; todoOpen: boolean; damageFollowup?: { observation_id?: string; damage_status?: string } }
interface Board { task: { version: number; name: string; range_json: unknown }; items: Entry[]; total: number }
interface Detail { case: Entry; history: { items: Decision[]; page: number; pageSize: number; total: number } }
const props = defineProps<{ taskId: string }>(); const session = useSessionStore();
const owner = computed(() => JSON.stringify([props.taskId, session.user?.id, session.user?.roles, session.csrfToken]));
const board = ref<Board | null>(null), detail = ref<Detail | null>(null), page = shallowRef(1), busy = shallowRef(false), error = shallowRef("");
const pending = ref<BusinessMutation | null>(null), confirming = shallowRef(false), confirmation = shallowRef("");
const reason = shallowRef(""), evidence = shallowRef(""), method = ref("NOTE"), draftName = shallowRef("");
const startsAt = ref(new Date(Date.now() + 3600000).toISOString()), dueAt = ref(new Date(Date.now() + 7 * 86400000).toISOString());
const damageBusy = shallowRef(false);
const locked = computed(() => busy.value || !!pending.value || damageBusy.value); let generation = 0, live = true;
const current = (epoch: number, identity: string) => live && epoch === generation && identity === owner.value;
function clear() { generation++; damageBusy.value = false; board.value = null; detail.value = null; pending.value = null; confirming.value = false; busy.value = false; reason.value = ""; evidence.value = ""; confirmation.value = ""; }
function fail(cause: unknown) {
  if (cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)) { clear(); error.value = "当前身份、权限或记录失效，已隐藏原资料与操作。"; return; }
  if (cause instanceof ReviewApiError && [400, 409].includes(cause.status)) {
    pending.value = null; confirming.value = false;
    error.value = cause.fields.processedBy ? `已由 ${cause.fields.processedBy} 于 ${cause.fields.processedAt || '已记录时间'} 处理，请刷新核对。`
      : "条件或补充决定版本已变化，请刷新核对；复盘须实际完成对应资产采信，不能凭创建草稿关闭。"; return;
  }
  error.value = pending.value ? "处理结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。";
}
async function load() {
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = ""; board.value = null; detail.value = null;
  try { const result = await reviewRequest<Board>(`/inventory-late/tasks/${props.taskId}?page=${page.value}&pageSize=20`); if (current(epoch, identity)) board.value = result; }
  catch (cause) { if (current(epoch, identity)) fail(cause); } finally { if (current(epoch, identity)) busy.value = false; }
}
async function open(entry: Entry) {
  if (locked.value) return; const epoch = ++generation, identity = owner.value; busy.value = true; detail.value = null; error.value = "";
  try { const result = await reviewRequest<Detail>(`/inventory-late/tasks/${props.taskId}/observations/${entry.evidence.observation_id}`);
    if (current(epoch, identity)) { detail.value = result; reason.value = ""; evidence.value = ""; method.value = "NOTE"; draftName.value = `${board.value?.task.name || '盘点'} 关联复盘`.slice(0, 128); } }
  catch (cause) { if (current(epoch, identity)) fail(cause); } finally { if (current(epoch, identity)) busy.value = false; }
}
async function readHistory(delta: number) {
  if (locked.value || !detail.value) return;
  const next = detail.value.history.page + delta;
  if (next < 1 || (next - 1) * 20 >= detail.value.history.total) return;
  const epoch = ++generation, identity = owner.value, id = detail.value.case.evidence.observation_id; busy.value = true; error.value = "";
  try { const result = await reviewRequest<Detail>(`/inventory-late/tasks/${props.taskId}/observations/${id}?page=${next}&pageSize=20`); if (current(epoch, identity)) detail.value = result; }
  catch (cause) { if (current(epoch, identity)) fail(cause); } finally { if (current(epoch, identity)) busy.value = false; }
}
function prepareLegacy() {
  if (locked.value || !board.value) return;
  pending.value = { path: `/inventory-late/tasks/${props.taskId}/prepare`, key: crypto.randomUUID(), version: board.value.task.version };
  confirmation.value = "把既有合法补充或未核时证据纳入清单，每批最多100条；保留原始分类与回执，不改变原报告和日期。"; confirming.value = true;
}
function prepareDecision() {
  if (locked.value || !board.value || !detail.value || reason.value.trim().length < 3 || evidence.value.trim().length < 3) return;
  const row = detail.value.case;
  const body: Record<string, unknown> = { method: method.value, reason: reason.value.trim(), evidence: evidence.value.trim() };
  if (["LINK_RECHECK", "REPLACE_RECHECK"].includes(method.value)) {
    const start = new Date(startsAt.value), due = new Date(dueAt.value);
    if (!draftName.value.trim() || !Number.isFinite(start.getTime()) || !Number.isFinite(due.getTime()) || due <= start) { error.value = "请核对复盘名称及起止时间。"; return; }
    body.recheck = { name: draftName.value.trim(), range: structuredClone(JSON.parse(JSON.stringify(board.value.task.range_json))), startsAt: start.toISOString(), dueAt: due.toISOString() };
  }
  pending.value = { path: `/inventory-late/tasks/${props.taskId}/observations/${row.evidence.observation_id}/decisions`, key: crypto.randomUUID(), version: row.review.decision_version, body };
  confirmation.value = `${methods[method.value]}：${body.reason}；依据：${body.evidence}。仅追加补充决定，不覆盖原报告、完成数或最近盘点日期。`;
  confirming.value = true;
}
async function send() {
  if (busy.value || !pending.value) return; const epoch = generation, identity = owner.value, original = pending.value;
  busy.value = true; confirming.value = false; error.value = "";
  try { await reviewRequest(original.path, original); if (current(epoch, identity)) { pending.value = null; await load(); } }
  catch (cause) { if (current(epoch, identity)) fail(cause); } finally { if (current(epoch, identity)) busy.value = false; }
}
async function paginate(delta: number) { if (locked.value || !board.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= board.value.total) return; page.value += delta; await load(); }
const kinds: Record<string, string> = { FROZEN_CONFLICT: "与原采信事实冲突", NO_ACCEPTED: "原项没有有效采信", TIME_UNVERIFIED: "原时间仍待核实", FINDING: "任务外实物线索", FACTS_MATCH: "事实一致的补充线索" };
const methods: Record<string, string> = { NOTE: "记录补充结论", LINK_RECHECK: "创建关联复盘草稿", REPLACE_RECHECK: "已结束复盘后另建一轮", CONFIRM_RECHECK: "核对实际完成的关联复盘" };
const entryRows = computed(() => (board.value?.items || []).map(entry => ({ ...entry,
  openTaskText: entry.todoOpen ? "管理员待办开放" : "没有开放待办" })));
const boardTotal = computed(() => board.value?.total || 0);
const refreshDisabled = computed(() => busy.value || confirming.value || damageBusy.value);
const prepareDisabled = computed(() => locked.value || !board.value);
const prevDisabled = computed(() => locked.value || page.value === 1);
const nextDisabled = computed(() => locked.value || !board.value || page.value * 20 >= board.value.total);
const damagePanelKey = computed(() => detail.value ? `${props.taskId}:${detail.value.case.evidence.observation_id}` : "");
const damageBlocked = computed(() => busy.value || !!pending.value);
const historyPrevDisabled = computed(() => locked.value || detail.value?.history.page === 1);
const historyNextDisabled = computed(() => { const history = detail.value?.history; return locked.value || !history || history.page * 20 >= history.total; });
const sceneLocationText = computed(() => { const actual = detail.value?.case.evidence.snapshot_json.facts.actual; return actual?.locationHint || actual?.locationId || "位置未明确"; });
const sceneConditionText = computed(() => detail.value?.case.evidence.snapshot_json.facts.actual?.condition || "状况未明确");
const sceneFactText = computed(() => { const actual = detail.value?.case.evidence.snapshot_json.facts.actual; return actual?.unknownReason || actual?.description; });
const decisionRows = computed(() => (detail.value?.history.items || []).map(event => ({ ...event, methodText: methods[event.method || ""] })));
const recheckDraftVisible = computed(() => ["LINK_RECHECK", "REPLACE_RECHECK"].includes(method.value));
const decisionDisabled = computed(() => reason.value.trim().length < 3 || evidence.value.trim().length < 3);
watch(owner, () => { clear(); page.value = 1; void load(); }, { immediate: true }); onBeforeUnmount(() => { live = false; clear(); });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="终态补充证据与关联复盘">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">终态补充证据与关联复盘</h3>
        <p class="fams-panel-subtitle">补传不改变原报告、完成数或最近盘点日期；后续观察不证明封存时的实物状况。时间异常和任务外线索不能凭补充文字自动采信。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="refreshDisabled" @click="load">刷新补充证据</button>
        <button class="fams-button" data-kind="primary" data-size="sm" type="button" :disabled="prepareDisabled" @click="prepareLegacy">准备既有补充证据</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <article v-for="entry in entryRows" :key="entry.evidence.observation_id" class="fams-card fams-stack">
      <p>证据 {{ entry.evidence.observation_id }} · {{ kinds[entry.evidence.comparison_kind] }} · {{ results[entry.review.result] }} · {{ entry.openTaskText }}</p>
      <button class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="locked" @click="open(entry)">核对原证据与补充历史</button>
    </article>
    <div class="fams-pagination">
      <span class="fams-pagination-summary">共 {{ boardTotal }} 条</span>
      <div class="fams-pagination-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="paginate(-1)">上页补充证据</button>
        <span class="fams-pagination-page">第 {{ page }} 页</span>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="paginate(1)">下页补充证据</button>
      </div>
    </div>
    <template v-if="detail">
      <InventoryLateDamagePanel v-if="detail.case.damageFollowup?.observation_id" :key="damagePanelKey" :task-id="taskId" :observation-id="detail.case.evidence.observation_id" :blocked="damageBlocked" @busy-change="damageBusy = $event" @invalidated="clear" @changed="load" />
      <dl class="fams-facts">
        <div><dt>原观察</dt><dd>{{ detail.case.evidence.snapshot_json.rawObservedAt }}</dd></div>
        <div><dt>原校准时间</dt><dd>{{ detail.case.evidence.snapshot_json.calibratedObservedAt || '尚未核实' }}</dd></div>
        <div><dt>接收</dt><dd>{{ detail.case.evidence.snapshot_json.receivedAt }}</dd></div>
        <div><dt>原采信观察</dt><dd>{{ detail.case.evidence.snapshot_json.acceptedObservationId || '无' }}</dd></div>
        <div><dt>原冻结报告</dt><dd>{{ detail.case.evidence.report_sha256 || '取消或终止任务未封存' }}</dd></div>
      </dl>
      <p>现场线索：{{ sceneLocationText }} · {{ sceneConditionText }} · {{ sceneFactText }}</p>
      <ul class="fams-timeline">
        <li v-for="event in decisionRows" :key="event.decision_version">{{ event.actor_username }} · {{ event.decided_at }} · {{ event.methodText }} · {{ results[event.result] }}
          <small>{{ event.reason }} · {{ event.evidence }}<template v-if="event.linked_task_id"> · 关联复盘任务 {{ event.linked_task_id }}</template></small></li>
      </ul>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ detail.history.total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="historyPrevDisabled" @click="readHistory(-1)">上页补充决定</button>
          <span class="fams-pagination-page">补充历史第 {{ detail.history.page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="historyNextDisabled" @click="readHistory(1)">下页补充决定</button>
        </div>
      </div>
      <fieldset class="fams-card fams-stack" :disabled="locked">
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">补充处理</span>
            <select class="fams-control" v-model="method"><option v-for="(label, value) in methods" :key="value" :value="value">{{ label }}</option></select></label>
          <label class="fams-field"><span class="fams-field-label">原因</span>
            <textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
          <label class="fams-field"><span class="fams-field-label">核实依据</span>
            <textarea class="fams-control" v-model="evidence" maxlength="2000" /></label>
        </div>
        <template v-if="recheckDraftVisible">
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">复盘名称</span>
              <input class="fams-control" v-model="draftName" maxlength="128" /></label>
            <label class="fams-field"><span class="fams-field-label">计划开始时间（带时区）</span>
              <input class="fams-control" v-model="startsAt" /></label>
            <label class="fams-field"><span class="fams-field-label">计划截止时间（带时区）</span>
              <input class="fams-control" v-model="dueAt" /></label>
          </div>
          <p>复制原任务范围创建草稿，发布、分配、实盘和封存须另行实际完成。</p>
        </template>
        <button class="fams-button" data-kind="primary" type="button" :disabled="decisionDisabled" @click="prepareDecision">核对补充处理</button>
      </fieldset>
    </template>
    <div v-if="confirming" class="fams-card fams-stack" role="group" aria-label="确认补充处理"><p>{{ confirmation }}</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="send">确认追加补充处理</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="pending = null; confirming = false">返回补充核对</button>
      </div>
    </div>
    <button v-if="pending && !confirming" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="send">原键重试未确认补充处理</button>
  </section>
</template>
