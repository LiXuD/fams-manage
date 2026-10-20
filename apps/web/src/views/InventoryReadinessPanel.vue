<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import type { LoanMutation } from "@/loan/api";
import { ReviewApiError, reviewRequest } from "@/inventory-review/api";
interface Row { item: { id: string; recheck_generation: number; review_status?: string; reason_kind?: string; reason?: string;
  evidence?: string; actor_username?: string; recorded_at?: string; snapshot_json: { assetCode: string; name: string } };
  current: { version: number } | null; canRecord: boolean; physicalMissingOpen: boolean }
interface View { task: { id: string; version: number; task_status: string }; items: Row[]; total: number; missingCount: number; notObservedCount: number }
interface History { id: string; recheck_generation: number; reason_kind: string; reason: string; evidence: string; actor_username: string; recorded_at: string }
const props = defineProps<{ taskId: string }>(); const emit = defineEmits<{ changed: [] }>();
const session = useSessionStore(); const owner = computed(() => JSON.stringify([props.taskId, session.user?.id, session.user?.roles, session.csrfToken]));
const view = ref<View | null>(null), page = ref(1), busy = ref(false), error = ref(""), notice = ref("");
const chosen = ref(""), kind = ref("UNABLE_TO_OBSERVE"), reason = ref(""), evidence = ref(""), waitingReason = ref("");
const history = ref<History[]>([]), historyTotal = ref(0), historyItem = ref(""), historyPage = ref(1);
const pending = ref<LoanMutation | null>(null), confirming = ref(false), summary = ref("");
const locked = computed(() => busy.value || !!pending.value);
const kinds: Record<string, string> = { UNABLE_TO_OBSERVE: "无法实盘（如现场无法进入）", EVIDENCE_UNAVAILABLE: "原证据失效或无法使用，需补盘", ASSET_NOT_FOUND: "实物未找到，继续查找或正式报失" };
let generation = 0, live = true;
const current = (epoch: number, identity: string) => live && epoch === generation && owner.value === identity;
function clear() { generation++; view.value = null; chosen.value = ""; reason.value = ""; evidence.value = ""; waitingReason.value = "";
  history.value = []; historyTotal.value = 0; historyItem.value = ""; pending.value = null; confirming.value = false; summary.value = ""; busy.value = false; notice.value = ""; }
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)) { clear(); error.value = "身份、权限或任务已失效，已隐藏原资料。"; return; }
  if (cause instanceof ReviewApiError && cause.status === 409) {
    pending.value = null; confirming.value = false;
    error.value = cause.fields.processedBy ? `该项已由 ${cause.fields.processedBy} 于 ${cause.fields.processedAt || '已记录时间'} 处理，请刷新核对。`
      : "任务、实盘证据或复核已变化，请刷新核对后继续。"; return;
  }
  if (cause instanceof ReviewApiError && cause.status === 400) { pending.value = null; confirming.value = false; error.value = "请核对原因类型、原因及明确依据。"; return; }
  error.value = pending.value ? "提交结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。";
}
async function load() {
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = ""; view.value = null; chosen.value = "";
  try { const result = await reviewRequest<View>(`/inventory-readiness/tasks/${props.taskId}?page=${page.value}&pageSize=20`);
    if (current(epoch, identity)) view.value = result;
  } catch (cause) { if (current(epoch, identity)) failure(cause); }
  finally { if (current(epoch, identity)) busy.value = false; }
}
function choose(row: Row) { if (locked.value || !row.canRecord) return; chosen.value = row.item.id; reason.value = ""; evidence.value = ""; notice.value = ""; }
function prepareRecord() {
  const row = view.value?.items.find(item => item.item.id === chosen.value);
  if (locked.value || !view.value || !row?.canRecord || !row.current || reason.value.trim().length < 3 || evidence.value.trim().length < 10) return;
  pending.value = { path: `/inventory-readiness/tasks/${props.taskId}/items/${row.item.id}/unobserved`, key: crypto.randomUUID(), version: view.value.task.version,
    body: { recheckGeneration: row.item.recheck_generation, assetVersion: row.current.version, kind: kind.value, reason: reason.value.trim(), evidence: evidence.value.trim() } };
  summary.value = `${row.item.snapshot_json.assetCode} · ${kinds[kind.value]}。${reason.value.trim()}。依据：${evidence.value.trim()}。未盘不计正常；实物未找到仍须真实后续闭环。`;
  confirming.value = true;
}
function prepareWaiting() {
  if (locked.value || !view.value || view.value.task.task_status !== "IN_PROGRESS" || view.value.missingCount || waitingReason.value.trim().length < 3) return;
  pending.value = { path: `/inventory-readiness/tasks/${props.taskId}/waiting-review`, key: crypto.randomUUID(), version: view.value.task.version, body: { reason: waitingReason.value.trim() } };
  summary.value = `进入待复核。${waitingReason.value.trim()}。未盘 ${view.value.notObservedCount} 项不计正常；差异、同步与封存仍须分别核实。`; confirming.value = true;
}
async function send() {
  if (busy.value || !pending.value) return;
  const epoch = generation, identity = owner.value, operation = pending.value; busy.value = true; error.value = ""; confirming.value = false;
  try { await reviewRequest(operation.path, operation);
    if (current(epoch, identity)) { pending.value = null; busy.value = false; notice.value = "本次处理已保存，原始观察保持不变。"; await load(); emit("changed"); }
  } catch (cause) { if (current(epoch, identity)) failure(cause); }
  finally { if (current(epoch, identity)) busy.value = false; }
}
async function readHistory(item: string, next = 1) {
  if (locked.value || !view.value?.items.some(row => row.item.id === item)) return;
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = ""; history.value = [];
  try { const result = await reviewRequest<{ items: History[]; total: number }>(`/inventory-readiness/tasks/${props.taskId}/items/${item}/unobserved-history?page=${next}&pageSize=20`);
    if (current(epoch, identity)) { history.value = result.items; historyTotal.value = result.total; historyItem.value = item; historyPage.value = next; }
  } catch (cause) { if (current(epoch, identity)) failure(cause); }
  finally { if (current(epoch, identity)) busy.value = false; }
}
function change(delta: number) { if (locked.value) return; page.value += delta; void load(); }
watch(owner, () => { clear(); page.value = 1; void load(); }, { immediate: true });
onBeforeUnmount(() => { live = false; clear(); });
</script>
<template>
  <section aria-label="未盘原因与待复核">
    <h3>未盘原因与待复核</h3><p>未盘不计正常。实物未找到须保留查找或正式报失义务，不能用原因文字或同步确认代替闭环。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice">{{ notice }}</p>
    <button :disabled="busy || confirming" @click="load">刷新未盘与就绪情况</button>
    <p v-if="view">尚无结果或明确原因 {{ view.missingCount }} 项；本轮明确未盘 {{ view.notObservedCount }} 项。</p>
    <article v-for="row in view?.items ?? []" :key="row.item.id">
      <p>{{ row.item.snapshot_json.assetCode }} · {{ row.item.snapshot_json.name }} · {{ row.item.review_status === 'NOT_OBSERVED' ? '明确未盘，不计正常' : row.item.review_status === 'ACCEPTED' ? '已采信' : '待核对' }}</p>
      <p v-if="row.item.reason_kind">{{ kinds[row.item.reason_kind] }} · {{ row.item.reason }} · {{ row.item.actor_username }}</p>
      <p v-if="row.physicalMissingOpen">实物未找到：真实查找或报失后续尚待闭环。</p>
      <button :disabled="locked || !row.canRecord" @click="choose(row)">记录 {{ row.item.snapshot_json.assetCode }} 未盘原因</button>
      <button :disabled="locked" @click="readHistory(row.item.id)">查看 {{ row.item.snapshot_json.assetCode }} 历次未盘原因</button>
    </article>
    <fieldset v-if="chosen" :disabled="locked"><legend>明确记录本轮未盘</legend>
      <label>未盘类型<select v-model="kind"><option v-for="(name, value) in kinds" :key="value" :value="value">{{ name }}</option></select></label>
      <label>未盘原因<textarea v-model="reason" maxlength="1000" /></label><label>明确依据<textarea v-model="evidence" maxlength="2000" /></label>
      <button :disabled="reason.trim().length < 3 || evidence.trim().length < 10" @click="prepareRecord">核对未盘记录</button>
    </fieldset>
    <fieldset v-if="view?.task.task_status === 'IN_PROGRESS'" :disabled="locked"><label>进入待复核原因<textarea v-model="waitingReason" maxlength="1000" /></label>
      <button :disabled="!!view.missingCount || waitingReason.trim().length < 3" @click="prepareWaiting">核对进入待复核</button></fieldset>
    <div v-if="confirming" role="group" aria-label="确认本次未盘或待复核处理"><p>{{ summary }}</p>
      <button :disabled="busy" @click="send">确认保存本次处理</button><button :disabled="busy" @click="pending = null; confirming = false; summary = ''">返回核对</button></div>
    <button v-if="pending && !confirming" :disabled="busy" @click="send">重试原未盘或待复核操作</button>
    <button :disabled="locked || page === 1" @click="change(-1)">上页未盘检查</button><span>第 {{ page }} 页</span><button :disabled="locked || !view || page * 20 >= view.total" @click="change(1)">下页未盘检查</button>
    <div v-if="historyItem"><h4>历次未盘原因</h4><p v-for="record in history" :key="record.id">第 {{ record.recheck_generation + 1 }} 轮 · {{ kinds[record.reason_kind] }} · {{ record.reason }} · {{ record.evidence }} · {{ record.actor_username }} · {{ record.recorded_at }}</p>
      <button :disabled="locked || historyPage === 1" @click="readHistory(historyItem, historyPage - 1)">上页未盘历史</button><button :disabled="locked || historyPage * 20 >= historyTotal" @click="readHistory(historyItem, historyPage + 1)">下页未盘历史</button></div>
  </section>
</template>
