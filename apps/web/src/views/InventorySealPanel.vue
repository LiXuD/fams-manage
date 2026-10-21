<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import type { LoanMutation } from "@/loan/api";
import { ReviewApiError, reviewRequest } from "@/inventory-review/api";
import { sealedRecordLines, synchronizationReasonText, sealBlockerText } from "@/inventory-sealing-display";
interface Row { accountId: string; name: string; current: boolean; reason: string; devices: { deviceId: string; current: boolean; reason: string }[] }
interface Report { report_sha256: string; accepted_count: number; not_observed_count: number; captured_count: number;
  actor_username: string; created_at: string; missing_sync_reason: string | null; snapshot_json: unknown }
interface Preflight { task: { id: string; version: number; task_status: string }; sealed: boolean; report?: Report;
  blockers?: { code: string; itemId?: string; observationId?: string }[]; blockerCount?: number;
  synchronization?: Row[]; synchronizationDigest?: string; missingSynchronizationCount?: number }
interface Page { items: { snapshot_json: unknown; snapshot_sha256: string }[]; total: number }
const props = defineProps<{ taskId: string }>(); const emit = defineEmits<{ changed: [] }>();
const session = useSessionStore(); const owner = computed(() => JSON.stringify([props.taskId, session.user?.id, session.user?.roles, session.csrfToken]));
const view = ref<Preflight | null>(null), busy = ref(false), error = ref(""), notice = ref("");
const reason = ref(""), waiver = ref(""), confirming = ref(false), pending = ref<LoanMutation | null>(null);
const section = ref("items"), page = ref(1), rows = ref<Page | null>(null);
const locked = computed(() => busy.value || !!pending.value);
let epoch = 0, live = true;
const current = (stamp: number, identity: string) => live && stamp === epoch && owner.value === identity;
function clear() { epoch++; view.value = null; rows.value = null; reason.value = ""; waiver.value = ""; pending.value = null;
  confirming.value = false; busy.value = false; notice.value = ""; }
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)) { clear(); error.value = "身份、权限或任务已失效，已隐藏冻结报告和原操作。"; return; }
  if (cause instanceof ReviewApiError && [400, 409].includes(cause.status)) {
    pending.value = null; confirming.value = false;
    error.value = cause.fields.processedBy ? `已由 ${cause.fields.processedBy} 于 ${cause.fields.processedAt || '已记录时间'} 处理，请刷新核对。`
      : "封存条件、任务或同步名单已变化，请刷新后逐项核对。"; return;
  }
  error.value = pending.value ? "封存结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。";
}
async function load() {
  const stamp = ++epoch, identity = owner.value; busy.value = true; error.value = ""; view.value = null; rows.value = null;
  try { const result = await reviewRequest<Preflight>(`/inventory-sealing/tasks/${props.taskId}`);
    if (current(stamp, identity)) view.value = result;
  } catch (cause) { if (current(stamp, identity)) failure(cause); }
  finally { if (current(stamp, identity)) busy.value = false; }
}
function prepare() {
  const state = view.value;
  if (locked.value || !state || state.sealed || state.task.task_status !== "WAITING_REVIEW" || state.blockerCount
    || !state.synchronizationDigest || reason.value.trim().length < 3
    || ((state.missingSynchronizationCount ?? 0) > 0 && waiver.value.trim().length < 10)) return;
  pending.value = { path: `/inventory-sealing/tasks/${props.taskId}/seal`, key: crypto.randomUUID(), version: state.task.version,
    body: { reason: reason.value.trim(), synchronizationDigest: state.synchronizationDigest,
      missingSynchronizationReason: (state.missingSynchronizationCount ?? 0) > 0 ? waiver.value.trim() : null } };
  confirming.value = true;
}
async function send() {
  if (busy.value || !pending.value) return;
  const stamp = epoch, identity = owner.value, original = pending.value; busy.value = true; confirming.value = false; error.value = "";
  try { await reviewRequest(original.path, original);
    if (current(stamp, identity)) { pending.value = null; busy.value = false; notice.value = "已封存，报告与已采信日期已在同一事务保存。"; await load(); emit("changed"); }
  } catch (cause) { if (current(stamp, identity)) failure(cause); }
  finally { if (current(stamp, identity)) busy.value = false; }
}
async function readRows(next = 1) {
  if (locked.value || !view.value?.sealed) return;
  const stamp = ++epoch, identity = owner.value; busy.value = true; rows.value = null; error.value = "";
  try { const result = await reviewRequest<Page>(`/inventory-sealing/tasks/${props.taskId}/report/${section.value}?page=${next}&pageSize=20`);
    if (current(stamp, identity)) { rows.value = result; page.value = next; }
  } catch (cause) { if (current(stamp, identity)) failure(cause); }
  finally { if (current(stamp, identity)) busy.value = false; }
}
async function exportReport() {
  if (locked.value || !view.value?.sealed || !view.value.report) return;
  const stamp = ++epoch, identity = owner.value, header = view.value.report; busy.value = true; error.value = "";
  try { const sections: Record<string, Page['items']> = {};
    for (const name of ["items", "evidence", "synchronization"]) {
      const saved: Page['items'] = []; let next = 1;
      while (true) { const result = await reviewRequest<Page>(`/inventory-sealing/tasks/${props.taskId}/report/${name}?page=${next++}&pageSize=100`);
        if (!current(stamp, identity)) return; saved.push(...result.items);
        if (saved.length >= result.total) break;
        if (!result.items.length) throw new Error("Incomplete frozen report page");
      }
      sections[name] = saved;
    }
    if (!current(stamp, identity)) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify({ header, sections }, null, 2)], { type: "application/json;charset=utf-8" }));
    try { const link = document.createElement("a"); link.href = url; link.download = `inventory-report-${props.taskId}.json`; link.click(); }
    finally { URL.revokeObjectURL(url); }
  } catch (cause) { if (current(stamp, identity)) failure(cause); }
  finally { if (current(stamp, identity)) busy.value = false; }
}
watch(owner, () => { clear(); page.value = 1; void load(); }, { immediate: true });
onBeforeUnmount(() => { live = false; clear(); });
</script>
<template>
  <section aria-label="封存检查与冻结报告">
    <h3>封存检查与冻结报告</h3>
    <p>未盘不计正常；差异与实物缺失须真实闭环。执行人声明不能证明未知离线设备为空，缺少声明时须明确原因并保留待补证名单。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice">{{ notice }}</p>
    <button :disabled="busy || confirming" @click="load">刷新封存检查与报告</button>
    <template v-if="view && !view.sealed">
      <p>封存阻断 {{ view.blockerCount }} 项；缺少同步确认 {{ view.missingSynchronizationCount }} 人。</p>
      <p v-for="(blocker, index) in view.blockers" :key="index">{{ sealBlockerText(blocker.code) }} · {{ blocker.itemId || blocker.observationId || '任务' }}</p>
      <article v-for="row in view.synchronization" :key="row.accountId"><p>{{ row.name }} · {{ row.current ? '当前已声明' : synchronizationReasonText(row.reason) }}</p>
        <p v-for="device in row.devices" :key="device.deviceId">{{ device.deviceId }} · {{ device.current ? '当前已声明' : synchronizationReasonText(device.reason) }}</p></article>
      <fieldset :disabled="locked"><label>封存原因<textarea v-model="reason" maxlength="1000" /></label>
        <label v-if="(view.missingSynchronizationCount ?? 0) > 0">缺少同步确认的封存依据<textarea v-model="waiver" maxlength="2000" /></label>
        <button :disabled="view.task.task_status !== 'WAITING_REVIEW' || !!view.blockerCount || reason.trim().length < 3 || ((view.missingSynchronizationCount ?? 0) > 0 && waiver.trim().length < 10)" @click="prepare">核对本次封存</button></fieldset>
      <div v-if="confirming" role="group" aria-label="确认冻结报告与待补证名单"><p>{{ reason }}。缺少同步确认 {{ view.missingSynchronizationCount }} 人，依据：{{ waiver || '无需豁免' }}。未盘不计正常，封存后仅追加迟到或关联复盘，不改原报告。</p>
        <button :disabled="busy" @click="send">确认封存本任务</button><button :disabled="busy" @click="pending = null; confirming = false">返回封存核对</button></div>
    </template>
    <button v-if="pending && !confirming" :disabled="busy" @click="send">原键重试未确认封存</button>
    <template v-if="view?.sealed && view.report"><p>已冻结 · {{ view.report.actor_username }} · {{ view.report.created_at }} · 校验 {{ view.report.report_sha256 }}</p>
      <p>已采信 {{ view.report.accepted_count }} 项；明确未盘 {{ view.report.not_observed_count }} 项；接收计数 {{ view.report.captured_count }}。</p>
      <p v-if="view.report.missing_sync_reason">待补证依据：{{ view.report.missing_sync_reason }}</p>
      <label>冻结报告部分<select v-model="section" :disabled="locked"><option value="items">应盘项与日期依据</option><option value="evidence">原始证据与独立决定</option><option value="synchronization">同步声明与待补证名单</option></select></label>
      <button :disabled="locked" @click="readRows(1)">读取本部分冻结记录</button><button :disabled="locked" @click="exportReport">导出完整冻结报告 JSON</button>
      <article v-for="(row, index) in rows?.items ?? []" :key="index"><p v-for="(line, lineIndex) in sealedRecordLines(row.snapshot_json)" :key="lineIndex">{{ line }}</p><p>冻结校验 {{ row.snapshot_sha256 }}</p></article>
      <button :disabled="locked || !rows || page === 1" @click="readRows(page - 1)">上页冻结报告</button><span>第 {{ page }} 页</span><button :disabled="locked || !rows || page * 20 >= rows.total" @click="readRows(page + 1)">下页冻结报告</button>
    </template>
  </section>
</template>
