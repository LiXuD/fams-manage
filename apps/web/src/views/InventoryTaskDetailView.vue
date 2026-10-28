<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import InventoryReadinessPanel from "@/views/InventoryReadinessPanel.vue";
import InventorySealPanel from "@/views/InventorySealPanel.vue";
import InventoryLatePanel from "@/views/InventoryLatePanel.vue";
import InventorySynchronizationPanel from "@/views/InventorySynchronizationPanel.vue";
import InventoryFindingPanel from "@/views/InventoryFindingPanel.vue";
import InventoryReviewPanel from "@/views/InventoryReviewPanel.vue";
import { LoanApiError, loanMessage, loanRequest, type LoanMutation } from "@/loan/api";
import { useSessionStore } from "@/auth/sessionStore";

type Range = { usingOrganizationId: string | null; owningOrganizationId: string | null; categoryId: string | null;
  warehouseId: string | null; locationId: string | null; lifecycle: string | null; exception: string | null; responsibleEmployeeId: string | null };
type Task = { id: string; name: string; task_status: string; version: number; assignment_version: number;
  range_json?: Range; starts_at: string; due_at: string; overdue?: boolean; end_reason?: string };
type Item = { id: string; asset_id: string; assignment_version: number; executor_account_id: string | null; result_count: number;
  snapshot_json: { assetCode: string; name: string; locationName: string; custodyType: string; responsibleEmployeeName?: string; departmentResponsibleEmployeeName?: string } };
type Detail = { task: Task; items: Item[]; total: number; canManage: boolean };
type Choice = { id: string; name: string; status?: string };
const props = defineProps<{ id: string }>();
const router = useRouter();
const session = useSessionStore();
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const detail = ref<Detail | null>(null); const itemPage = ref(1);
const busy = ref(false); const error = ref(""); const pending = ref<LoanMutation | null>(null);
const locked = computed(() => busy.value || !!pending.value);
const executors = ref<Choice[]>([]);
const selectedItems = ref<string[]>([]); const executor = ref(""); const reason = ref(""); const recheck = ref(false);
const preview = ref<{ count: number; digest: string } | null>(null);
const labels: Record<string, string> = { DRAFT: "草稿", READY: "待开始", IN_PROGRESS: "进行中", WAITING_REVIEW: "待复核", COMPLETED: "已完成", CANCELLED: "已取消", TERMINATED: "已终止" };
const manageable = computed(() => !!detail.value && ["READY", "IN_PROGRESS", "WAITING_REVIEW"].includes(detail.value.task.task_status));
async function open() {
  detail.value = await loanRequest<Detail>(`/inventory-tasks/${encodeURIComponent(props.id)}?page=${itemPage.value}&pageSize=20`);
  selectedItems.value = []; preview.value = null;
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await open(); }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function send(path: string, body: unknown, version: number) {
  if (busy.value || pending.value && pending.value.path !== path) return;
  pending.value ??= { path, key: crypto.randomUUID(), version, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const result = await loanRequest<Task>(pending.value.path, pending.value);
    pending.value = null;
    if (result.task_status === "DELETED") await router.push("/inventory-tasks");
    else if (result.id !== props.id) { itemPage.value = 1; await router.push(`/inventory-tasks/${encodeURIComponent(result.id)}`); }
    else await open();
  } catch (e) {
    error.value = loanMessage(e);
    if (e instanceof LoanApiError && e.status >= 400 && e.status < 500) pending.value = null;
  } finally { busy.value = false; }
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
async function pages(delta: number) {
  if (locked.value) return;
  itemPage.value += delta; busy.value = true;
  try { await open(); } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
watch(() => props.id, () => { itemPage.value = 1; void refresh(); });
onMounted(async () => {
  busy.value = true;
  try {
    await open();
    if (admin.value) {
      let p = 1; let rows: Choice[];
      do { rows = await loanRequest<Choice[]>(`/inventory-tasks/executors?page=${p++}&pageSize=100`); executors.value.push(...rows); } while (rows.length === 100);
    }
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>
<template>
  <AppChrome>
    <template #actions>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked"
          @click="refresh">刷新</button>
        <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/inventory-tasks">返回任务列表</router-link>
      </div>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section v-if="detail" class="fams-panel fams-stack" aria-label="盘点任务详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">{{ detail.task.name }}</h2>
          <p class="fams-panel-subtitle">开始 {{ new Date(detail.task.starts_at).toLocaleString() }} · 截止
            {{ new Date(detail.task.due_at).toLocaleString() }}{{ detail.task.overdue ? ' · 已逾期，仍可执行' : '' }}</p>
        </div>
        <div class="fams-panel-actions">
          <span class="fams-status" :data-tone="detail.task.overdue ? 'warning' : 'info'">{{ labels[detail.task.task_status] }}</span>
        </div>
      </div>
      <p v-if="detail.task.end_reason" class="fams-panel-subtitle">结束原因：{{ detail.task.end_reason }}</p>
      <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy"
        @click="send(pending.path, pending.body, pending.version)">重试原操作</button>

      <fieldset v-if="admin" class="fams-card fams-stack" :disabled="locked">
        <legend class="fams-panel-title">任务操作</legend>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" type="button" @click="copyRange">复制范围创建新草稿</button>
          <template v-if="detail.task.task_status === 'DRAFT'">
            <router-link class="fams-button" data-kind="secondary" :to="`/inventory-tasks/${detail.task.id}/edit`">编辑草稿</router-link>
            <button class="fams-button" data-kind="secondary" type="button" @click="previewPublishedRange">预览应盘数量</button>
            <button class="fams-button" data-kind="primary" type="button" :disabled="!preview?.count" @click="publish">发布并冻结集合</button>
          </template>
          <button v-if="detail.task.task_status === 'READY'" class="fams-button" data-kind="primary" type="button"
            @click="action('start')">手动开始</button>
        </div>
        <p v-if="preview">预计 {{ preview.count }} 项（待入库和未发布资产已排除）</p>
        <label class="fams-field"><span class="fams-field-label">操作原因</span>
          <textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
        <div class="fams-panel-actions">
          <button v-if="detail.task.task_status === 'DRAFT'" class="fams-button" data-kind="secondary" type="button"
            :disabled="!reason.trim()" @click="action('delete')">删除草稿</button>
          <template v-if="manageable">
            <button class="fams-button" data-kind="secondary" type="button" :disabled="!reason.trim()"
              @click="action('cancel')">取消无结果任务</button>
            <button class="fams-button" data-kind="secondary" type="button" :disabled="!reason.trim()"
              @click="action('terminate')">终止并保留结果</button>
          </template>
        </div>
        <div v-if="manageable" class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">主执行人</span>
            <select class="fams-control" v-model="executor"><option value="">请选择</option><option v-for="e in executors" :key="e.id" :value="e.id">{{ e.name }}</option></select></label>
          <label class="fams-selection"><input v-model="recheck" type="checkbox" />明确发起复盘（保留既有结果）</label>
        </div>
        <div v-if="manageable" class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button"
            :disabled="!selectedItems.length || !executor || !reason.trim() || detail.task.task_status === 'WAITING_REVIEW' && !recheck"
            @click="assign">分配所选应盘项</button>
        </div>
      </fieldset>

      <InventoryReviewPanel v-if="admin && detail.task.task_status !== 'DRAFT'" :key="detail.task.id" :task-id="detail.task.id" @changed="refresh" />
      <InventoryReadinessPanel v-if="admin && detail.task.task_status !== 'DRAFT'" :key="`readiness:${detail.task.id}`" :task-id="detail.task.id" @changed="refresh" />
      <InventorySealPanel v-if="admin && detail.task.task_status !== 'DRAFT'" :key="`seal:${detail.task.id}`" :task-id="detail.task.id" @changed="refresh" />
      <InventoryLatePanel v-if="admin && ['COMPLETED', 'CANCELLED', 'TERMINATED'].includes(detail.task.task_status)" :key="`late:${detail.task.id}`" :task-id="detail.task.id" />
      <InventorySynchronizationPanel v-if="admin && detail.task.task_status !== 'DRAFT'" :key="`sync:${detail.task.id}`" :task-id="detail.task.id" />
      <InventoryFindingPanel v-if="admin && detail.task.task_status !== 'DRAFT'" :key="`finding:${detail.task.id}`" :task-id="detail.task.id" @changed="refresh" />

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th v-if="admin" scope="col">选择</th><th scope="col" data-column-kind="identity">资产</th>
            <th scope="col">账面位置</th><th scope="col">账面责任</th><th scope="col">分配版本</th></tr></thead>
          <tbody>
            <tr v-for="i in detail.items" :key="i.id">
              <td v-if="admin"><label class="fams-selection"><input v-model="selectedItems" type="checkbox" :value="i.id"
                :disabled="locked || !manageable"
                :aria-label="`选择 ${i.snapshot_json.assetCode}`" /></label></td>
              <th scope="row" data-column-kind="identity">{{ i.snapshot_json.assetCode }} · {{ i.snapshot_json.name }}</th>
              <td>{{ i.snapshot_json.locationName }}</td>
              <td>{{ i.snapshot_json.responsibleEmployeeName ?? i.snapshot_json.departmentResponsibleEmployeeName ?? '仓库保管' }}</td>
              <td class="u-number">{{ i.assignment_version }}</td>
            </tr>
            <tr v-if="!detail.items.length" class="fams-table-empty"><td :colspan="admin ? 5 : 4"><strong>本页没有应盘项</strong>翻页查看其余应盘集合。</td></tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ detail.total }} 项应盘</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button"
            :disabled="locked || itemPage === 1" @click="pages(-1)">上一页</button>
          <span class="fams-pagination-page">第 {{ itemPage }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button"
            :disabled="locked || itemPage * 20 >= detail.total" @click="pages(1)">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
