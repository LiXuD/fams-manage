<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import InventorySynchronizationPanel from "@/views/InventorySynchronizationPanel.vue";
import InventoryFindingPanel from "@/views/InventoryFindingPanel.vue";
import InventoryReviewPanel from "@/views/InventoryReviewPanel.vue";
import { LoanApiError, loanMessage, loanRequest, type LoanMutation } from "@/loan/api";
import { useSessionStore } from "@/auth/sessionStore";

type Range = { usingOrganizationId: string | null; owningOrganizationId: string | null; categoryId: string | null;
  warehouseId: string | null; locationId: string | null; lifecycle: string | null; exception: string | null; responsibleEmployeeId: string | null };
type Task = { id: string; name: string; task_status: string; version: number; assignment_version: number;
  range_json?: Range; starts_at: string; due_at: string; expected_count?: number; overdue?: boolean; end_reason?: string };
type Item = { id: string; asset_id: string; assignment_version: number; executor_account_id: string | null; result_count: number;
  snapshot_json: { assetCode: string; name: string; locationName: string; custodyType: string; responsibleEmployeeName?: string; departmentResponsibleEmployeeName?: string } };
type Detail = { task: Task; items: Item[]; total: number; canManage: boolean };
type Choice = { id: string; name: string; status?: string };
const session = useSessionStore();
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const tasks = ref<Task[]>([]); const detail = ref<Detail | null>(null); const total = ref(0); const page = ref(1); const itemPage = ref(1);
const busy = ref(false); const error = ref(""); const pending = ref<LoanMutation | null>(null);
const locked = computed(() => busy.value || !!pending.value);
const catalogs = ref<Record<string, Choice[]>>({}); const executors = ref<Choice[]>([]);
const editing = ref<string | null>(null); const name = ref(""); const start = ref(""); const due = ref("");
const emptyRange = (): Range => ({ usingOrganizationId: null, owningOrganizationId: null, categoryId: null, warehouseId: null,
  locationId: null, lifecycle: null, exception: null, responsibleEmployeeId: null });
const range = ref<Range>(emptyRange()); const preview = ref<{ count: number; digest: string } | null>(null);
const selectedItems = ref<string[]>([]); const executor = ref(""); const reason = ref(""); const recheck = ref(false);
const lifecycleLabels: Record<string, string> = { IN_STOCK: "在库", IN_USE: "在用", BORROWED: "借出", UNDER_REPAIR: "维修中", FACTORY_REPAIR: "返厂维修", PENDING_SCRAP: "待报废", SCRAPPED: "已报废" };
const labels: Record<string, string> = { DRAFT: "草稿", READY: "待开始", IN_PROGRESS: "进行中", WAITING_REVIEW: "待复核", COMPLETED: "已完成", CANCELLED: "已取消", TERMINATED: "已终止" };
async function list() {
  const result = await loanRequest<{ items: Task[]; total: number }>(`/inventory-tasks?page=${page.value}&pageSize=20`);
  tasks.value = result.items; total.value = result.total;
}
async function open(id: string) {
  detail.value = await loanRequest<Detail>(`/inventory-tasks/${encodeURIComponent(id)}?page=${itemPage.value}&pageSize=20`);
  selectedItems.value = []; preview.value = null;
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await list(); if (detail.value) await open(detail.value.task.id); }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function choose(id: string) {
  if (locked.value) return; busy.value = true; error.value = ""; itemPage.value = 1;
  try { await open(id); } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function send(path: string, body: unknown, version: number) {
  if (busy.value || pending.value && pending.value.path !== path) return;
  pending.value ??= { path, key: crypto.randomUUID(), version, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const result = await loanRequest<Task>(pending.value.path, pending.value);
    pending.value = null; editing.value = null; await list();
    if (result.task_status === "DELETED") detail.value = null;
    else { itemPage.value = 1; await open(result.id); }
  } catch (e) {
    error.value = loanMessage(e);
    if (e instanceof LoanApiError && e.status >= 400 && e.status < 500) pending.value = null;
  } finally { busy.value = false; }
}
function save() {
  const startsAt = new Date(start.value); const dueAt = new Date(due.value);
  if (!name.value.trim() || !Number.isFinite(startsAt.getTime()) || !Number.isFinite(dueAt.getTime()) || dueAt <= startsAt) {
    error.value = "请填写名称和有效的开始、截止时间。"; return;
  }
  const normalized = { ...range.value, responsibleEmployeeId: range.value.responsibleEmployeeId?.trim() || null };
  const body = { name: name.value.trim(), range: normalized, startsAt: startsAt.toISOString(), dueAt: dueAt.toISOString() };
  void send(editing.value ? `/inventory-tasks/${editing.value}/revision` : "/inventory-tasks", body, editing.value ? detail.value!.task.version : 0);
}
function edit() {
  const task = detail.value?.task; if (!task?.range_json || locked.value) return;
  editing.value = task.id; name.value = task.name; range.value = { ...emptyRange(), ...task.range_json };
  const local = (value: string) => { const date = new Date(value); return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16); };
  start.value = local(task.starts_at); due.value = local(task.due_at); preview.value = null;
}
async function previewPublishedRange() {
  const task = detail.value?.task; if (!task?.range_json || locked.value) return;
  busy.value = true; error.value = "";
  try { preview.value = await loanRequest<{ count: number; digest: string }>("/inventory-tasks/preview", { path: "/inventory-tasks/preview", key: crypto.randomUUID(), version: 0, body: task.range_json }); }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
function publish() {
  if (!detail.value || !preview.value?.count || !window.confirm(`发布并冻结 ${preview.value.count} 项应盘集合？后续资产流转不会改写快照。`)) return;
  void send(`/inventory-tasks/${detail.value.task.id}/publication`, { expectedCount: preview.value.count, previewDigest: preview.value.digest }, detail.value.task.version);
}
function copyRange() {
  const task = detail.value?.task; if (!task?.range_json || locked.value || !window.confirm("复制此范围创建新草稿？原任务和快照保留。")) return;
  const startsAt = new Date(Date.now() + 3600000).toISOString(); const dueAt = new Date(Date.now() + 90000000).toISOString();
  void send(`/inventory-tasks/${task.id}/copy`, { name: `${task.name.slice(0, 120)} 副本`, range: task.range_json, startsAt, dueAt }, task.version);
}
function action(value: "start" | "cancel" | "terminate" | "delete") {
  if (!detail.value || value !== "start" && !reason.value.trim() || !window.confirm(`确认${value === "start" ? "开始盘点" : value === "delete" ? "删除草稿" : value === "cancel" ? "取消无结果任务" : "终止并保留证据"}？`)) return;
  void send(`/inventory-tasks/${detail.value.task.id}/${value}`, value === "start" ? undefined : { reason: reason.value.trim() }, detail.value.task.version);
}
function assign() {
  if (!detail.value || !selectedItems.value.length || !executor.value || !reason.value.trim() || !window.confirm("确认分配所选资产？原执行人的历史授权和证据保留。")) return;
  void send(`/inventory-tasks/${detail.value.task.id}/assignments`, { itemIds: selectedItems.value, executorAccountId: executor.value, recheck: recheck.value, reason: reason.value.trim() }, detail.value.task.version);
}
async function pages(delta: number, items = false) {
  if (locked.value) return;
  if (items && detail.value) { itemPage.value += delta; await choosePage(); }
  else { page.value += delta; await refresh(); }
}
async function choosePage() {
  if (!detail.value) return; busy.value = true;
  try { await open(detail.value.task.id); } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
onMounted(async () => {
  busy.value = true;
  try {
    await list();
    if (admin.value) {
      for (const kind of ["organization", "category", "warehouse", "location", "employee"]) {
        const all: Choice[] = []; let p = 1; let rows: Choice[];
        do {
          const path = `/inventory-tasks/catalog/${kind}?page=${p++}&pageSize=200`;
          rows = (await loanRequest<{ items: Choice[] }>(path)).items; all.push(...rows);
        } while (rows.length === 200);
        catalogs.value[kind] = all;
      }
      let p = 1; let rows: Choice[];
      do { rows = await loanRequest<Choice[]>(`/inventory-tasks/executors?page=${p++}&pageSize=100`); executors.value.push(...rows); } while (rows.length === 100);
    }
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>
<template>
  <AppChrome><section class="panel"><h1>盘点任务</h1>
    <p>发布时冻结应盘集合与责任快照。盘点不直接改变资产责任；执行人只查看自己的应盘项。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p><button :disabled="locked" @click="refresh">刷新</button>
    <button v-if="pending" :disabled="busy" @click="send(pending.path, pending.body, pending.version)">重试原操作</button>
    <form v-if="admin" @submit.prevent="save"><h2>{{ editing ? "修改草稿" : "创建盘点草稿" }}</h2><fieldset :disabled="locked">
      <label>任务名称<input v-model="name" required maxlength="128"></label>
      <label>开始时间<input v-model="start" type="datetime-local" required></label><label>截止时间<input v-model="due" type="datetime-local" required></label>
      <label v-for="field in ([['usingOrganizationId','使用部门','organization'],['owningOrganizationId','所属组织','organization'],['categoryId','分类','category'],['warehouseId','仓库','warehouse'],['locationId','位置','location']] as const)" :key="field[0]">{{ field[1] }}<select v-model="range[field[0]]"><option :value="null">全部</option><option v-for="c in catalogs[field[2]]" :key="c.id" :value="c.id">{{ c.name }}{{ c.status === "DISABLED" ? "（已停用）" : "" }}</option></select></label>
      <label>生命周期<select v-model="range.lifecycle"><option :value="null">全部</option><option v-for="(text, value) in lifecycleLabels" :key="value" :value="value">{{ text }}</option></select></label>
      <label>异常状态<select v-model="range.exception"><option :value="null">全部</option><option value="NORMAL">正常</option><option value="SEARCHING">查找中</option><option value="LOST">遗失</option></select></label>
      <label>当前责任人<select v-model="range.responsibleEmployeeId"><option :value="null">全部</option><option v-for="c in catalogs.employee" :key="c.id" :value="c.id">{{ c.name }}{{ c.status === "DISABLED" ? "（已停用）" : "" }}</option></select></label><button type="submit">保存草稿</button>
      <button v-if="editing" type="button" @click="editing = null; name = ''; range = emptyRange()">新建其他任务</button>
    </fieldset></form>
    <h2>{{ admin ? "全部任务" : "我的任务" }}</h2><button v-for="t in tasks" :key="t.id" :disabled="locked" @click="choose(t.id)">{{ t.name }} · {{ labels[t.task_status] }}{{ t.overdue ? ' · 逾期' : '' }}</button>
    <button :disabled="locked || page === 1" @click="pages(-1)">上一页</button>{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}<button :disabled="locked || page * 20 >= total" @click="pages(1)">下一页</button>
    <section v-if="detail" class="panel"><h2>{{ detail.task.name }} · {{ labels[detail.task.task_status] }}</h2>
      <p>开始 {{ new Date(detail.task.starts_at).toLocaleString() }} · 截止 {{ new Date(detail.task.due_at).toLocaleString() }}{{ detail.task.overdue ? ' · 已逾期，仍可执行' : '' }}</p>
      <p v-if="detail.task.end_reason">结束原因：{{ detail.task.end_reason }}</p>
      <fieldset v-if="admin" :disabled="locked"><button @click="copyRange">复制范围创建新草稿</button>
        <template v-if="detail.task.task_status === 'DRAFT'"><button @click="edit">编辑草稿</button><button @click="previewPublishedRange">预览应盘数量</button><p v-if="preview">预计 {{ preview.count }} 项（待入库和未发布资产已排除）</p><button :disabled="!preview?.count" @click="publish">发布并冻结集合</button></template>
        <button v-if="detail.task.task_status === 'READY'" @click="action('start')">手动开始</button>
        <label>操作原因<textarea v-model="reason" maxlength="1000" /></label>
        <button v-if="detail.task.task_status === 'DRAFT'" :disabled="!reason.trim()" @click="action('delete')">删除草稿</button>
        <template v-if="['READY','IN_PROGRESS'].includes(detail.task.task_status)"><button :disabled="!reason.trim()" @click="action('cancel')">取消无结果任务</button><button :disabled="!reason.trim()" @click="action('terminate')">终止并保留结果</button>
          <label>主执行人<select v-model="executor"><option value="">请选择</option><option v-for="e in executors" :key="e.id" :value="e.id">{{ e.name }}</option></select></label><label><input v-model="recheck" type="checkbox">明确发起复盘（保留既有结果）</label><button :disabled="!selectedItems.length || !executor || !reason.trim()" @click="assign">分配所选应盘项</button></template>
      </fieldset>
      <InventoryReviewPanel v-if="admin && detail.task.task_status !== 'DRAFT'" :key="detail.task.id" :task-id="detail.task.id" @changed="refresh" />
      <InventorySynchronizationPanel v-if="admin && detail.task.task_status !== 'DRAFT'" :key="`sync:${detail.task.id}`" :task-id="detail.task.id" />
      <InventoryFindingPanel v-if="admin && detail.task.task_status !== 'DRAFT'" :key="`finding:${detail.task.id}`" :task-id="detail.task.id" @changed="refresh" />
      <table><thead><tr><th v-if="admin">选择</th><th>资产</th><th>账面位置</th><th>账面责任</th><th>分配版本</th></tr></thead><tbody><tr v-for="i in detail.items" :key="i.id"><td v-if="admin"><input v-model="selectedItems" type="checkbox" :value="i.id" :disabled="locked || !['READY','IN_PROGRESS'].includes(detail.task.task_status)"></td><td>{{ i.snapshot_json.assetCode }} · {{ i.snapshot_json.name }}</td><td>{{ i.snapshot_json.locationName }}</td><td>{{ i.snapshot_json.responsibleEmployeeName ?? i.snapshot_json.departmentResponsibleEmployeeName ?? '仓库保管' }}</td><td>{{ i.assignment_version }}</td></tr></tbody></table>
      <button :disabled="locked || itemPage === 1" @click="pages(-1, true)">上页应盘项</button>{{ itemPage }} / {{ Math.max(1, Math.ceil(detail.total / 20)) }}<button :disabled="locked || itemPage * 20 >= detail.total" @click="pages(1, true)">下页应盘项</button>
    </section>
  </section></AppChrome>
</template>
<style scoped>
label { display: block; margin: 12px 0; } input:not([type=checkbox]), select, textarea { display: block; padding: 8px; min-width: 260px; }
button { margin: 8px; padding: 8px 12px; } table { width: 100%; text-align: left; } th, td { padding: 10px; }
</style>
