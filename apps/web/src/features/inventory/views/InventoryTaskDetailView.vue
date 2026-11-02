<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import InventoryReadinessPanel from "@/features/inventory/panels/InventoryReadinessPanel.vue";
import InventorySealPanel from "@/features/inventory/panels/InventorySealPanel.vue";
import InventoryLatePanel from "@/features/inventory/panels/InventoryLatePanel.vue";
import InventorySynchronizationPanel from "@/features/inventory/panels/InventorySynchronizationPanel.vue";
import InventoryFindingPanel from "@/features/inventory/panels/InventoryFindingPanel.vue";
import InventoryReviewPanel from "@/features/inventory/panels/InventoryReviewPanel.vue";
import { BusinessApiError, businessMessage, businessRequest, type BusinessMutation } from "@/api/business";
import { inventoryTaskStatusLabels as labels } from "@/features/inventory/api";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness, type LivenessToken } from "@/composables/useRequestLiveness";

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
const detail = ref<Detail | null>(null); const itemPage = shallowRef(1);
const busy = shallowRef(false); const error = shallowRef(""); const pending = ref<BusinessMutation | null>(null);
const locked = computed(() => busy.value || !!pending.value);
const executors = ref<Choice[]>([]);
const selectedItems = ref<string[]>([]); const executor = shallowRef(""); const reason = shallowRef(""); const recheck = shallowRef(false);
const preview = ref<{ count: number; digest: string } | null>(null);
const identity = computed(() => JSON.stringify([props.id, session.user?.id, session.user?.roles, session.csrfToken]));
const liveness = useRequestLiveness(() => [identity.value]);
const manageable = computed(() => !!detail.value && ["READY", "IN_PROGRESS", "WAITING_REVIEW"].includes(detail.value.task.task_status));
const startsAtText = computed(() => (detail.value ? new Date(detail.value.task.starts_at).toLocaleString() : ""));
const dueAtText = computed(() => (detail.value ? new Date(detail.value.task.due_at).toLocaleString() : ""));
const overdueText = computed(() => (detail.value?.task.overdue ? " · 已逾期，仍可执行" : ""));
const statusTone = computed(() => (detail.value?.task.overdue ? "warning" : "info"));
const isDraft = computed(() => detail.value?.task.task_status === "DRAFT");
const isReady = computed(() => detail.value?.task.task_status === "READY");
const reasonMissing = computed(() => !reason.value.trim());
const assignDisabled = computed(() => !selectedItems.value.length || !executor.value || reasonMissing.value
  || detail.value?.task.task_status === "WAITING_REVIEW" && !recheck.value);
const panelsVisible = computed(() => !!admin.value && detail.value?.task.task_status !== "DRAFT");
const latePanelVisible = computed(() => !!admin.value && !!detail.value
  && ["COMPLETED", "CANCELLED", "TERMINATED"].includes(detail.value.task.task_status));
const emptyColSpan = computed(() => (admin.value ? 5 : 4));
const prevDisabled = computed(() => locked.value || itemPage.value === 1);
const nextDisabled = computed(() => locked.value || itemPage.value * 20 >= (detail.value?.total ?? 0));
type Confirm = "publish" | "copy" | "start" | "cancel" | "terminate" | "delete" | "assign";
const confirmKind = ref<Confirm | null>(null);
const confirmLabels: Record<Confirm, string> = { publish: "确认发布并冻结集合", copy: "确认复制范围创建新草稿",
  start: "确认开始盘点", cancel: "确认取消无结果任务", terminate: "确认终止并保留证据", delete: "确认删除草稿",
  assign: "确认分配所选资产" };
const confirmHints: Record<Exclude<Confirm, "publish">, string> = { copy: "复制此范围创建新草稿？原任务和快照保留。", start: "确认开始盘点？",
  cancel: "确认取消无结果任务？", terminate: "确认终止并保留证据？", delete: "确认删除草稿？",
  assign: "确认分配所选资产？原执行人的历史授权和证据保留。" };
const confirmHint = computed(() => confirmKind.value === "publish"
  ? `发布并冻结 ${preview.value?.count ?? 0} 项应盘集合？后续资产流转不会改写快照。`
  : confirmHints[confirmKind.value ?? "copy"]);
async function open(turn: LivenessToken) {
  const result = await businessRequest<Detail>(`/inventory-tasks/${encodeURIComponent(props.id)}?page=${itemPage.value}&pageSize=20`);
  if (!liveness.isCurrent(turn)) return;
  detail.value = result;
  selectedItems.value = []; preview.value = null;
}
async function refresh() {
  if (busy.value) return; const turn = liveness.begin(); busy.value = true; error.value = "";
  try { await open(turn); }
  catch (e) { if (liveness.isCurrent(turn)) error.value = businessMessage(e); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function send(path: string, body: unknown, version: number) {
  if (busy.value || pending.value && pending.value.path !== path) return;
  pending.value ??= { path, key: crypto.randomUUID(), version, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await businessRequest<Task>(pending.value.path, pending.value);
    if (!liveness.isCurrent(turn)) return;
    pending.value = null;
    if (result.task_status === "DELETED") await router.push("/inventory-tasks");
    else if (result.id !== props.id) { itemPage.value = 1; await router.push(`/inventory-tasks/${encodeURIComponent(result.id)}`); }
    else await open(turn);
  } catch (e) {
    if (!liveness.isCurrent(turn)) return;
    error.value = businessMessage(e);
    if (e instanceof BusinessApiError && e.status >= 400 && e.status < 500) pending.value = null;
  } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function previewPublishedRange() {
  const task = detail.value?.task; if (!task?.range_json || locked.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = "";
  try { const result = await businessRequest<{ count: number; digest: string }>("/inventory-tasks/preview", { path: "/inventory-tasks/preview", key: crypto.randomUUID(), version: 0, body: task.range_json });
    if (liveness.isCurrent(turn)) preview.value = result; }
  catch (e) { if (liveness.isCurrent(turn)) error.value = businessMessage(e); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
function openConfirm(kind: Confirm): void {
  const task = detail.value?.task;
  if (!task || locked.value) return;
  if (kind === "publish" && !preview.value?.count) return;
  if (kind === "copy" && !task.range_json) return;
  if (kind === "assign" && (!selectedItems.value.length || !executor.value)) return;
  if (kind !== "start" && kind !== "publish" && kind !== "copy" && !reason.value.trim()) return;
  confirmKind.value = kind;
}

function runConfirm(): void {
  const kind = confirmKind.value; const task = detail.value?.task;
  if (!kind || !task || busy.value) return;
  confirmKind.value = null;
  if (kind === "publish") {
    void send(`/inventory-tasks/${task.id}/publication`,
      { expectedCount: preview.value!.count, previewDigest: preview.value!.digest }, task.version);
    return;
  }
  if (kind === "copy") {
    const startsAt = new Date(Date.now() + 3600000).toISOString(); const dueAt = new Date(Date.now() + 90000000).toISOString();
    void send(`/inventory-tasks/${task.id}/copy`,
      { name: `${task.name.slice(0, 120)} 副本`, range: task.range_json, startsAt, dueAt }, task.version);
    return;
  }
  if (kind === "assign") {
    void send(`/inventory-tasks/${task.id}/assignments`, { itemIds: selectedItems.value, executorAccountId: executor.value,
      recheck: recheck.value, reason: reason.value.trim() }, task.version);
    return;
  }
  void send(`/inventory-tasks/${task.id}/${kind}`, kind === "start" ? undefined : { reason: reason.value.trim() }, task.version);
}
async function pages(delta: number) {
  if (locked.value) return;
  const turn = liveness.begin();
  itemPage.value += delta; busy.value = true;
  try { await open(turn); } catch (e) { if (liveness.isCurrent(turn)) error.value = businessMessage(e); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(() => props.id, () => { itemPage.value = 1; void refresh(); });
onMounted(async () => {
  const turn = liveness.begin();
  busy.value = true;
  try {
    await open(turn);
    if (admin.value) {
      let p = 1; let rows: Choice[];
      do { rows = await businessRequest<Choice[]>(`/inventory-tasks/executors?page=${p++}&pageSize=100`);
        if (liveness.isCurrent(turn)) executors.value.push(...rows); } while (rows.length === 100);
    }
  } catch (e) { if (liveness.isCurrent(turn)) error.value = businessMessage(e); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
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
          <p class="fams-panel-subtitle">开始 {{ startsAtText }} · 截止
            {{ dueAtText }}{{ overdueText }}</p>
        </div>
        <div class="fams-panel-actions">
          <span class="fams-status" :data-tone="statusTone">{{ labels[detail.task.task_status] }}</span>
        </div>
      </div>
      <p v-if="detail.task.end_reason" class="fams-panel-subtitle">结束原因：{{ detail.task.end_reason }}</p>
      <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy"
        @click="send(pending.path, pending.body, pending.version)">重试原操作</button>

      <fieldset v-if="admin" class="fams-card fams-stack" :disabled="locked">
        <legend class="fams-panel-title">任务操作</legend>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" type="button" @click="openConfirm('copy')">复制范围创建新草稿</button>
          <template v-if="isDraft">
            <router-link class="fams-button" data-kind="secondary" :to="`/inventory-tasks/${detail.task.id}/edit`">编辑草稿</router-link>
            <button class="fams-button" data-kind="secondary" type="button" @click="previewPublishedRange">预览应盘数量</button>
            <button class="fams-button" data-kind="primary" type="button" :disabled="!preview?.count" @click="openConfirm('publish')">发布并冻结集合</button>
          </template>
          <button v-if="isReady" class="fams-button" data-kind="primary" type="button"
            @click="openConfirm('start')">手动开始</button>
        </div>
        <p v-if="preview">预计 {{ preview.count }} 项（待入库和未发布资产已排除）</p>
        <label class="fams-field"><span class="fams-field-label">操作原因</span>
          <textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
        <div class="fams-panel-actions">
          <button v-if="isDraft" class="fams-button" data-kind="secondary" type="button"
            :disabled="reasonMissing" @click="openConfirm('delete')">删除草稿</button>
          <template v-if="manageable">
            <button class="fams-button" data-kind="secondary" type="button" :disabled="reasonMissing"
              @click="openConfirm('cancel')">取消无结果任务</button>
            <button class="fams-button" data-kind="secondary" type="button" :disabled="reasonMissing"
              @click="openConfirm('terminate')">终止并保留结果</button>
          </template>
        </div>
        <div v-if="manageable" class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">主执行人</span>
            <select class="fams-control" v-model="executor"><option value="">请选择</option><option v-for="e in executors" :key="e.id" :value="e.id">{{ e.name }}</option></select></label>
          <label class="fams-selection"><input v-model="recheck" type="checkbox" />明确发起复盘（保留既有结果）</label>
        </div>
        <div v-if="manageable" class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button"
            :disabled="assignDisabled"
            @click="openConfirm('assign')">分配所选应盘项</button>
        </div>
        <div v-if="confirmKind" class="fams-card fams-stack" role="group" :aria-label="confirmLabels[confirmKind]">
          <p>{{ confirmHint }}</p>
          <div class="fams-panel-actions">
            <button class="fams-button" data-kind="primary" type="button" :disabled="busy"
              @click="runConfirm">{{ confirmLabels[confirmKind] }}</button>
            <button class="fams-button" data-kind="secondary" type="button" :disabled="busy"
              @click="confirmKind = null">返回修改</button>
          </div>
        </div>
      </fieldset>

      <InventoryReviewPanel v-if="panelsVisible" :key="detail.task.id" :task-id="detail.task.id" @changed="refresh" />
      <InventoryReadinessPanel v-if="panelsVisible" :key="`readiness:${detail.task.id}`" :task-id="detail.task.id" @changed="refresh" />
      <InventorySealPanel v-if="panelsVisible" :key="`seal:${detail.task.id}`" :task-id="detail.task.id" @changed="refresh" />
      <InventoryLatePanel v-if="latePanelVisible" :key="`late:${detail.task.id}`" :task-id="detail.task.id" />
      <InventorySynchronizationPanel v-if="panelsVisible" :key="`sync:${detail.task.id}`" :task-id="detail.task.id" />
      <InventoryFindingPanel v-if="panelsVisible" :key="`finding:${detail.task.id}`" :task-id="detail.task.id" @changed="refresh" />

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
            <tr v-if="!detail.items.length" class="fams-table-empty"><td :colspan="emptyColSpan"><strong>本页没有应盘项</strong>翻页查看其余应盘集合。</td></tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ detail.total }} 项应盘</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button"
            :disabled="prevDisabled" @click="pages(-1)">上一页</button>
          <span class="fams-pagination-page">第 {{ itemPage }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button"
            :disabled="nextDisabled" @click="pages(1)">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
