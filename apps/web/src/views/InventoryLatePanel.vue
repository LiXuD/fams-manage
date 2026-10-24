<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { reviewRequest, ReviewApiError } from "@/inventory-review/api";
import InventoryLateDamagePanel from "./InventoryLateDamagePanel.vue";
import type { LoanMutation } from "@/loan/api";
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
const board = ref<Board | null>(null), detail = ref<Detail | null>(null), page = ref(1), busy = ref(false), error = ref("");
const pending = ref<LoanMutation | null>(null), confirming = ref(false), confirmation = ref("");
const reason = ref(""), evidence = ref(""), method = ref("NOTE"), draftName = ref("");
const startsAt = ref(new Date(Date.now() + 3600000).toISOString()), dueAt = ref(new Date(Date.now() + 7 * 86400000).toISOString());
const damageBusy = ref(false);
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
const results: Record<string, string> = { OPEN: "待补充核实", INFORMATION: "补充线索", FOLLOWUP_OPEN: "关联复盘尚待实际完成", REVIEWED: "已记录补充结论" };
watch(owner, () => { clear(); page.value = 1; void load(); }, { immediate: true }); onBeforeUnmount(() => { live = false; clear(); });
</script>
<template>
  <section aria-label="终态补充证据与关联复盘"><h3>终态补充证据与关联复盘</h3>
    <p>补传不改变原报告、完成数或最近盘点日期；后续观察不证明封存时的实物状况。时间异常和任务外线索不能凭补充文字自动采信。</p>
    <p v-if="error" role="alert">{{ error }}</p><button :disabled="busy || confirming || damageBusy" @click="load">刷新补充证据</button>
    <button :disabled="locked || !board" @click="prepareLegacy">准备既有补充证据</button>
    <article v-for="entry in board?.items || []" :key="entry.evidence.observation_id"><p>证据 {{ entry.evidence.observation_id }} · {{ kinds[entry.evidence.comparison_kind] }} · {{ results[entry.review.result] }} · {{ entry.todoOpen ? '管理员待办开放' : '没有开放待办' }}</p>
      <button :disabled="locked" @click="open(entry)">核对原证据与补充历史</button></article>
    <button :disabled="locked || page === 1" @click="paginate(-1)">上页补充证据</button><span>第 {{ page }} 页 · 共 {{ board?.total || 0 }} 条</span><button :disabled="locked || !board || page * 20 >= board.total" @click="paginate(1)">下页补充证据</button>
    <template v-if="detail"><InventoryLateDamagePanel v-if="detail.case.damageFollowup?.observation_id" :key="`${taskId}:${detail.case.evidence.observation_id}`" :task-id="taskId" :observation-id="detail.case.evidence.observation_id" :blocked="busy || !!pending" @busy-change="damageBusy = $event" @invalidated="clear" @changed="load" /><p>原观察 {{ detail.case.evidence.snapshot_json.rawObservedAt }} · 原校准时间 {{ detail.case.evidence.snapshot_json.calibratedObservedAt || '尚未核实' }} · 接收 {{ detail.case.evidence.snapshot_json.receivedAt }}</p>
      <p>原采信观察 {{ detail.case.evidence.snapshot_json.acceptedObservationId || '无' }} · 原冻结报告 {{ detail.case.evidence.report_sha256 || '取消或终止任务未封存' }}</p>
      <p>现场线索：{{ detail.case.evidence.snapshot_json.facts.actual?.locationHint || detail.case.evidence.snapshot_json.facts.actual?.locationId || '位置未明确' }} · {{ detail.case.evidence.snapshot_json.facts.actual?.condition || '状况未明确' }} · {{ detail.case.evidence.snapshot_json.facts.actual?.unknownReason || detail.case.evidence.snapshot_json.facts.actual?.description }}</p>
      <article v-for="event in detail.history.items" :key="event.decision_version"><p>{{ event.actor_username }} · {{ event.decided_at }} · {{ methods[event.method || ''] }} · {{ results[event.result] }}</p><p>{{ event.reason }} · {{ event.evidence }}</p><p v-if="event.linked_task_id">关联复盘任务 {{ event.linked_task_id }}</p></article>
      <button :disabled="locked || detail.history.page === 1" @click="readHistory(-1)">上页补充决定</button><span>补充历史第 {{ detail.history.page }} 页 · 共 {{ detail.history.total }} 条</span><button :disabled="locked || detail.history.page * 20 >= detail.history.total" @click="readHistory(1)">下页补充决定</button>
      <fieldset :disabled="locked"><label>补充处理<select v-model="method"><option v-for="(label, value) in methods" :key="value" :value="value">{{ label }}</option></select></label>
        <label>原因<textarea v-model="reason" maxlength="1000" /></label><label>核实依据<textarea v-model="evidence" maxlength="2000" /></label>
        <template v-if="['LINK_RECHECK', 'REPLACE_RECHECK'].includes(method)"><label>复盘名称<input v-model="draftName" maxlength="128"></label><label>计划开始时间（带时区）<input v-model="startsAt"></label><label>计划截止时间（带时区）<input v-model="dueAt"></label><p>复制原任务范围创建草稿，发布、分配、实盘和封存须另行实际完成。</p></template>
        <button :disabled="reason.trim().length < 3 || evidence.trim().length < 3" @click="prepareDecision">核对补充处理</button></fieldset>
    </template>
    <div v-if="confirming" role="group" aria-label="确认补充处理"><p>{{ confirmation }}</p><button :disabled="busy" @click="send">确认追加补充处理</button><button :disabled="busy" @click="pending = null; confirming = false">返回补充核对</button></div>
    <button v-if="pending && !confirming" :disabled="busy" @click="send">原键重试未确认补充处理</button>
  </section>
</template>
