<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import type { BusinessMutation } from "@/api/business";
import { ReviewApiError, reviewRequest } from "@/features/inventory/api";
import { sealedRecordLines, synchronizationReasonText, sealBlockerText } from "@/features/inventory/sealing-display";
interface Row { accountId: string; name: string; current: boolean; reason: string; devices: { deviceId: string; current: boolean; reason: string }[] }
interface Report { report_sha256: string; accepted_count: number; not_observed_count: number; captured_count: number;
  actor_username: string; created_at: string; missing_sync_reason: string | null; snapshot_json: unknown }
interface Preflight { task: { id: string; version: number; task_status: string }; sealed: boolean; report?: Report;
  blockers?: { code: string; itemId?: string; observationId?: string }[]; blockerCount?: number;
  synchronization?: Row[]; synchronizationDigest?: string; missingSynchronizationCount?: number }
interface Page { items: { snapshot_json: unknown; snapshot_sha256: string }[]; total: number }
const props = defineProps<{ taskId: string }>(); const emit = defineEmits<{ changed: [] }>();
const session = useSessionStore(); const owner = computed(() => JSON.stringify([props.taskId, session.user?.id, session.user?.roles, session.csrfToken]));
const view = ref<Preflight | null>(null), busy = shallowRef(false), error = shallowRef(""), notice = shallowRef("");
const reason = shallowRef(""), waiver = shallowRef("");
/** 确认式幂等提交：草稿/在途两槽与幂等键簿记归底座；busy/locked、URL 与文案留在本面板。 */
const { prepared, pending, prepare: draft, confirm: adopt, abandon, fail, cancel, reset } = useConfirmedSubmit<Omit<BusinessMutation, "key">>({ failure: (cause) => cause instanceof ReviewApiError && [400, 409].includes(cause.status) });
const confirming = computed(() => !!prepared.value);
const section = shallowRef("items"), page = shallowRef(1), rows = ref<Page | null>(null);
const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
interface BlockerRow { key: string; text: string; target: string }
interface SynchronizationRow { accountId: string; name: string; statusText: string; devices: { deviceId: string; statusText: string }[] }
interface ReportLine { key: string; text: string }
interface ReportRow { key: string; snapshot_sha256: string; lines: ReportLine[] }
const blockerRows = computed<BlockerRow[]>(() => (view.value?.blockers ?? []).map(blocker => {
  const target = blocker.itemId || blocker.observationId || '任务';
  return { key: `${blocker.code}:${target}`, text: sealBlockerText(blocker.code), target };
}));
const synchronizationRows = computed<SynchronizationRow[]>(() => (view.value?.synchronization ?? []).map(row => ({
  accountId: row.accountId, name: row.name, statusText: row.current ? '当前已声明' : synchronizationReasonText(row.reason),
  devices: row.devices.map(device => ({ deviceId: device.deviceId, statusText: device.current ? '当前已声明' : synchronizationReasonText(device.reason) })),
})));
const reportRows = computed<ReportRow[]>(() => (rows.value?.items ?? []).map(row => ({ key: row.snapshot_sha256, snapshot_sha256: row.snapshot_sha256,
  lines: sealedRecordLines(row.snapshot_json).map((line, index) => ({ key: `${row.snapshot_sha256}:${index}:${line}`, text: line })) })));
const needsWaiver = computed(() => (view.value?.missingSynchronizationCount ?? 0) > 0);
const prevReportDisabled = computed(() => locked.value || !rows.value || page.value === 1);
const nextReportDisabled = computed(() => locked.value || !rows.value || page.value * 20 >= rows.value.total);
const sealDisabled = computed(() => { const state = view.value;
  return !state || state.task.task_status !== 'WAITING_REVIEW' || !!state.blockerCount || reason.value.trim().length < 3
    || ((state.missingSynchronizationCount ?? 0) > 0 && waiver.value.trim().length < 10); });
const liveness = useRequestLiveness(() => [owner.value]);
function clear() { liveness.invalidate(); view.value = null; rows.value = null; reason.value = ""; waiver.value = ""; reset();
  busy.value = false; notice.value = ""; }
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)) { clear(); error.value = "身份、权限或任务已失效，已隐藏冻结报告和原操作。"; return; }
  if (cause instanceof ReviewApiError && [400, 409].includes(cause.status)) {
    fail(cause);
    error.value = cause.fields.processedBy ? `已由 ${cause.fields.processedBy} 于 ${cause.fields.processedAt || '已记录时间'} 处理，请刷新核对。`
      : "封存条件、任务或同步名单已变化，请刷新后逐项核对。"; return;
  }
  error.value = pending.value || prepared.value ? "封存结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。";
}
async function load() {
  const turn = liveness.begin(); busy.value = true; error.value = ""; view.value = null; rows.value = null;
  try { const result = await reviewRequest<Preflight>(`/inventory-sealing/tasks/${props.taskId}`);
    if (liveness.isCurrent(turn)) view.value = result;
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
function prepare() {
  const state = view.value;
  if (locked.value || !state || state.sealed || state.task.task_status !== "WAITING_REVIEW" || state.blockerCount
    || !state.synchronizationDigest || reason.value.trim().length < 3
    || ((state.missingSynchronizationCount ?? 0) > 0 && waiver.value.trim().length < 10)) return;
  draft(() => ({ path: `/inventory-sealing/tasks/${props.taskId}/seal`, version: state.task.version,
    body: { reason: reason.value.trim(), synchronizationDigest: state.synchronizationDigest,
      missingSynchronizationReason: (state.missingSynchronizationCount ?? 0) > 0 ? waiver.value.trim() : null } }));
}
async function send() {
  if (busy.value || !pending.value) return;
  const turn = liveness.begin(), operation = pending.value;
  const original: BusinessMutation = { path: operation.payload.path, key: operation.key, version: operation.payload.version, body: operation.payload.body };
  busy.value = true; error.value = "";
  try { await reviewRequest(original.path, original);
    if (liveness.isCurrent(turn)) { abandon(); busy.value = false; notice.value = "已封存，报告与已采信日期已在同一事务保存。"; await load(); emit("changed"); }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function confirm() { if (busy.value || !adopt()) return; await send(); }
async function readRows(next = 1) {
  if (locked.value || !view.value?.sealed) return;
  const turn = liveness.begin(); busy.value = true; rows.value = null; error.value = "";
  try { const result = await reviewRequest<Page>(`/inventory-sealing/tasks/${props.taskId}/report/${section.value}?page=${next}&pageSize=20`);
    if (liveness.isCurrent(turn)) { rows.value = result; page.value = next; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function exportReport() {
  if (locked.value || !view.value?.sealed || !view.value.report) return;
  const turn = liveness.begin(), header = view.value.report; busy.value = true; error.value = "";
  try { const sections: Record<string, Page['items']> = {};
    for (const name of ["items", "evidence", "synchronization"]) {
      const saved: Page['items'] = []; let next = 1;
      while (true) { const result = await reviewRequest<Page>(`/inventory-sealing/tasks/${props.taskId}/report/${name}?page=${next++}&pageSize=100`);
        if (!liveness.isCurrent(turn)) return; saved.push(...result.items);
        if (saved.length >= result.total) break;
        if (!result.items.length) throw new Error("Incomplete frozen report page");
      }
      sections[name] = saved;
    }
    if (!liveness.isCurrent(turn)) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify({ header, sections }, null, 2)], { type: "application/json;charset=utf-8" }));
    try { const link = document.createElement("a"); link.href = url; link.download = `inventory-report-${props.taskId}.json`; link.click(); }
    finally { URL.revokeObjectURL(url); }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(owner, () => { clear(); page.value = 1; void load(); }, { immediate: true });
onBeforeUnmount(() => { clear(); });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="封存检查与冻结报告">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">封存检查与冻结报告</h3>
        <p class="fams-panel-subtitle">未盘不计正常；差异与实物缺失须真实闭环。执行人声明不能证明未知离线设备为空，缺少声明时须明确原因并保留待补证名单。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || confirming" @click="load">刷新封存检查与报告</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
    <template v-if="view && !view.sealed">
      <p>封存阻断 {{ view.blockerCount }} 项；缺少同步确认 {{ view.missingSynchronizationCount }} 人。</p>
      <ul class="fams-timeline">
        <li v-for="blocker in blockerRows" :key="blocker.key">{{ blocker.text }} · {{ blocker.target }}</li>
      </ul>
      <article v-for="row in synchronizationRows" :key="row.accountId" class="fams-card fams-stack">
        <p>{{ row.name }} · {{ row.statusText }}</p>
        <ul v-if="row.devices.length" class="fams-timeline">
          <li v-for="device in row.devices" :key="device.deviceId">{{ device.deviceId }} · {{ device.statusText }}</li>
        </ul>
      </article>
      <fieldset class="fams-card fams-stack" :disabled="locked">
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">封存原因</span>
            <textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
          <label v-if="needsWaiver" class="fams-field"><span class="fams-field-label">缺少同步确认的封存依据</span>
            <textarea class="fams-control" v-model="waiver" maxlength="2000" /></label>
        </div>
        <button class="fams-button" data-kind="primary" type="button" :disabled="sealDisabled" @click="prepare">核对本次封存</button>
      </fieldset>
      <div v-if="confirming" class="fams-card fams-stack" role="group" aria-label="确认冻结报告与待补证名单">
        <p>{{ reason }}。缺少同步确认 {{ view.missingSynchronizationCount }} 人，依据：{{ waiver || '无需豁免' }}。未盘不计正常，封存后仅追加迟到或关联复盘，不改原报告。</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="confirm">确认封存本任务</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="cancel()">返回封存核对</button>
        </div>
      </div>
    </template>
    <button v-if="pending && !confirming" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="send">原键重试未确认封存</button>
    <template v-if="view?.sealed && view.report">
      <dl class="fams-facts">
        <div><dt>冻结人</dt><dd>{{ view.report.actor_username }}</dd></div>
        <div><dt>冻结时间</dt><dd>{{ view.report.created_at }}</dd></div>
        <div><dt>校验</dt><dd>{{ view.report.report_sha256 }}</dd></div>
      </dl>
      <p>已采信 {{ view.report.accepted_count }} 项；明确未盘 {{ view.report.not_observed_count }} 项；接收计数 {{ view.report.captured_count }}。</p>
      <p v-if="view.report.missing_sync_reason">待补证依据：{{ view.report.missing_sync_reason }}</p>
      <label class="fams-field"><span class="fams-field-label">冻结报告部分</span>
        <select class="fams-control" v-model="section" :disabled="locked"><option value="items">应盘项与日期依据</option><option value="evidence">原始证据与独立决定</option><option value="synchronization">同步声明与待补证名单</option></select></label>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="locked" @click="readRows(1)">读取本部分冻结记录</button>
        <button class="fams-button" data-kind="primary" type="button" :disabled="locked" @click="exportReport">导出完整冻结报告 JSON</button>
      </div>
      <article v-for="row in reportRows" :key="row.key" class="fams-card fams-stack">
        <ul class="fams-timeline">
          <li v-for="line in row.lines" :key="line.key">{{ line.text }}</li>
        </ul>
        <small>冻结校验 {{ row.snapshot_sha256 }}</small>
      </article>
      <div class="fams-pagination">
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevReportDisabled" @click="readRows(page - 1)">上页冻结报告</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextReportDisabled" @click="readRows(page + 1)">下页冻结报告</button>
        </div>
      </div>
    </template>
  </section>
</template>
