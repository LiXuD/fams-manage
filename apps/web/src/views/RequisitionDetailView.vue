<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import RequisitionReassignment from "@/components/RequisitionReassignment.vue";
import AppChrome from "@/components/AppChrome.vue";
import RequisitionDemandForm from "./RequisitionDemandForm.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loadRequisitionCatalogs, requisitionLabels as labels, requisitionMessage, requisitionRequest } from "@/requisition/api";
import type { Requisition, RequisitionAllocation, RequisitionChoice, RequisitionDemand, RequisitionDemandInput,
  RequisitionInventory } from "../../../../packages/api-types/src/requisition";

const props = defineProps<{ id: string }>();
const session = useSessionStore();
const selected = ref<Requisition | null>(null);
const busy = ref(false); const error = ref(""); const notice = ref("");
const catalogs = ref<Record<string, RequisitionChoice[]>>({});
const canShared = ref(false); const selfEmployee = ref(""); const selfOrg = ref("");
const changeVisible = ref(false);
const observedCode = ref(""); const destination = ref(""); const evidence = ref(""); const returned = ref(false);
const reason = ref(""); const shortageReason = ref("STOCK_SHORTAGE"); const shortageQuantity = ref(1);
const inventory = ref<RequisitionInventory | null>(null); const inventoryDemand = ref<RequisitionDemand | null>(null);
const inventoryPage = ref(1); const replacementId = ref("");
let pending: { path: string; method: string; key: string; version: number; body?: unknown } | null = null;
const retryable = ref(false);
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const creator = computed(() => selected.value?.creatorAccountId === session.user?.id);
const owner = computed(() => selected.value?.ownership.find(row => !row.archivedAt)?.processorAccountId);
const canExecute = computed(() => warehouse.value && (!owner.value || owner.value === session.user?.id)
  && ["WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING", "WAITING_HANDOVER"].includes(selected.value?.requestStatus ?? ""));
const reasonLabels: Record<string, string> = { STOCK_SHORTAGE: "库存不足", SPECIFICATION_MISMATCH: "规格不匹配",
  TEMPORARILY_UNAVAILABLE: "暂不可用", OTHER: "其他" };
const handoverLabels: Record<string, string> = { WAITING_DELIVERY: "待交出", DELIVERED_WAITING_RECEIPT: "已交出待接收",
  RECOVERY_PENDING_CANCEL: "待收回取消", RECEIVED: "已接收", REFUSED: "接收人反馈不符", CANCELLED: "已取消",
  DELIVERED: "已交出", RECOVERY_REQUESTED: "已登记收回", RECOVERED: "已收回验收" };

async function open() {
  if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "没有此申请或操作的当前权限。"; return; }
  selected.value = await requisitionRequest<Requisition>("/" + props.id);
  inventory.value = null;
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await open(); pending = null; retryable.value = false; }
  catch (e) { error.value = requisitionMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, body?: unknown, method = "POST") {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "上次操作结果尚未确认，请先重试或刷新。"; return; }
  pending ??= { path, method, key: crypto.randomUUID(), version: selected.value?.version ?? 0,
    body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = ""; notice.value = "";
  try {
    const result = await requisitionRequest<Requisition>(pending.path, pending.method, pending);
    pending = null; retryable.value = false; selected.value = result; changeVisible.value = false; inventory.value = null;
    notice.value = "操作已保存。";
  } catch (e) { error.value = requisitionMessage(e); retryable.value = true; }
  finally { busy.value = false; }
}
function change() { if (retryable.value) return; changeVisible.value = true; }
function submitChange(payload: { demands: RequisitionDemandInput[]; requestType: "REQUISITION" | "BORROW";
  expectedReturnDate: string | null }) {
  void mutate("/" + selected.value!.id + "/changes", payload);
}
async function retry() { if (pending) await mutate(pending.path); }
async function findInventory(demand: RequisitionDemand, nextPage = 1) {
  if (busy.value) return; busy.value = true; error.value = "";
  try {
    inventory.value = await requisitionRequest<RequisitionInventory>(`/${selected.value!.id}/demands/${demand.id}/inventory?page=${nextPage}&pageSize=20`);
    inventoryDemand.value = demand; inventoryPage.value = nextPage;
  } catch (e) { error.value = requisitionMessage(e); } finally { busy.value = false; }
}
async function handover(allocation: RequisitionAllocation, action: "deliver" | "cancel" | "recovery" | "recover") {
  const body = { allocationVersion: allocation.version, lockVersion: allocation.lockVersion,
    assetVersion: allocation.assetSnapshotJson.version ?? 0, observedAssetCode: observedCode.value,
    ...(action === "deliver" ? { destination: destination.value, evidence: evidence.value }
      : { reason: reason.value, inspectionEvidence: evidence.value, physicalReturned: returned.value }) };
  await mutate(`/${selected.value!.id}/allocations/${allocation.id}/${action}`, body);
}
onMounted(async () => {
  busy.value = true;
  try {
    const loaded = await loadRequisitionCatalogs();
    catalogs.value = loaded.catalogs; canShared.value = loaded.canShared;
    selfEmployee.value = loaded.employeeId; selfOrg.value = loaded.organizationId;
    await open();
    if (selected.value && new URLSearchParams(window.location.search).get("saved")) {
      notice.value = "操作已保存。";
      window.history.replaceState(null, "", window.location.pathname);
    }
  } catch (e) { error.value = requisitionMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/requisitions">返回申请列表</router-link>
    </template>

    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <RequisitionDemandForm v-if="selected && changeVisible" mode="change" :catalogs="catalogs" :can-shared="canShared"
      :self-employee-id="selfEmployee" :self-organization-id="selfOrg" :demands="selected.demands"
      :request-type="selected.requestType" :expected-return-date="selected.expectedReturnDate"
      :disabled="busy || retryable" @submit="submitChange" />

    <section v-if="selected && !changeVisible" class="fams-panel fams-stack" aria-labelledby="detail-title">
      <div class="fams-panel-head">
        <div>
          <h2 id="detail-title" class="fams-panel-title">{{ selected.requestNo }} · {{ labels[selected.requestStatus] }}</h2>
          <p v-if="selected.sourceRequestId" class="fams-panel-subtitle">来源申请 {{ selected.sourceRequestId }} / 需求 {{ selected.sourceDemandId }}</p>
          <p v-if="owner" class="fams-panel-subtitle">当前处理库管账号：{{ owner }}</p>
        </div>
      </div>

      <div class="fams-field-row">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="refresh">刷新结果</button>
        <button v-if="retryable" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="retry">重试上次操作</button>
      </div>

      <RequisitionReassignment v-if="admin && owner && ['WAREHOUSE_PROCESSING', 'WAITING_HANDOVER'].includes(selected.requestStatus)"
        :id="selected.id" :version="selected.version" @saved="refresh" />

      <div class="fams-panel-actions">
        <template v-if="creator && ['DRAFT', 'REJECTED'].includes(selected.requestStatus)">
          <router-link class="fams-button" data-kind="secondary" :to="`/requisitions/${selected.id}/edit`">修改草稿</router-link>
          <button v-if="selected.requestStatus === 'DRAFT'" class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable"
            @click="mutate('/' + selected.id + '/submit')">提交管理员确认</button></template>
        <button v-if="creator && !selected.changes?.some(c => c.proposalStatus === 'PENDING') && ['DRAFT', 'REJECTED', 'PENDING_ADMIN_DECISION'].includes(selected.requestStatus)"
          class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable || !reason.trim()"
          @click="mutate('/' + selected.id + '/cancel', { reason })">取消申请</button>
        <template v-if="admin && selected.requestStatus === 'PENDING_ADMIN_DECISION'">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable"
            @click="mutate('/' + selected.id + '/decision', { result: 'APPROVED', comment: reason })">批准当前版本</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable || !reason.trim()"
            @click="mutate('/' + selected.id + '/decision', { result: 'REJECTED', comment: reason })">驳回当前版本</button></template>
        <button v-if="canExecute && !owner" class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable"
          @click="mutate('/' + selected.id + '/claim')">领取整单处理权</button>
        <button v-if="(creator || owner === session.user?.id) && ['WAITING_WAREHOUSE_CLAIM', 'WAREHOUSE_PROCESSING', 'WAITING_HANDOVER'].includes(selected.requestStatus)"
          class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="change">提请范围变更</button>
      </div>

      <label class="fams-field">
        <span class="fams-field-label">决定、取消、更换或核查说明</span>
        <textarea class="fams-control" v-model="reason" maxlength="1000" :disabled="busy || retryable" />
      </label>

      <article v-for="(change, index) in selected.changes" :key="index" class="fams-card fams-stack">
        <h3>范围变更 · {{ change.proposalStatus === 'PENDING' ? '待确认' : change.proposalStatus === 'APPROVED' ? '已批准' : '已驳回' }}</h3>
        <p v-for="(d, i) in change.proposalJson.demands" :key="i">需求 {{ i + 1 }}：{{ catalogs.category?.find(c => c.id === String(d.category.id))?.name }} · {{ d.specification }} · 数量 {{ d.quantity }} · {{ d.purpose }} · {{ d.expectedDate }} · {{ d.custodyType === 'PERSONAL' ? '个人使用' : '部门公用' }} · 使用组织 {{ d.organization.id }} · 责任人员 {{ d.employee.id }} · 固定位置 {{ d.location.id }}</p>
      </article>

      <article v-for="d in selected.demands" :key="d.id" class="fams-card fams-stack">
        <h3>{{ d.categoryNameSnapshot }} · {{ d.specification }}</h3>
        <p>{{ d.purpose }} · 期望 {{ d.expectedDate }} · {{ d.custodyType === 'PERSONAL' ? '个人使用' : '部门公用' }}</p>
        <dl class="fams-facts">
          <div><dt>申请</dt><dd>{{ d.requestedQuantity }}</dd></div>
          <div><dt>待分配</dt><dd>{{ d.pendingAllocationQuantity }}</dd></div>
          <div><dt>待交接</dt><dd>{{ d.pendingHandoverQuantity }}</dd></div>
          <div><dt>已完成</dt><dd>{{ d.completedQuantity }}</dd></div>
          <div><dt>无法满足</dt><dd>{{ d.unfulfillableQuantity }}</dd></div>
          <div><dt>已取消</dt><dd>{{ d.cancelledQuantity }}</dd></div>
        </dl>
        <div v-if="canExecute && d.pendingAllocationQuantity > 0" class="fams-field-row">
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="replacementId = ''; findInventory(d)">选择可用库存</button>
          <label class="fams-field"><span class="fams-field-label">无法满足数量</span>
            <input class="fams-control" v-model.number="shortageQuantity" type="number" min="1" :max="d.pendingAllocationQuantity" /></label>
          <label class="fams-field"><span class="fams-field-label">原因</span>
            <select class="fams-control" v-model="shortageReason"><option v-for="(label, code) in reasonLabels" :key="code" :value="code">{{ label }}</option></select></label>
          <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable || (shortageReason === 'OTHER' && !reason.trim())"
            @click="mutate(`/${selected.id}/demands/${d.id}/unfulfillable`, { quantity: shortageQuantity, reason: shortageReason, description: reason })">记录无法满足</button>
        </div>
        <div v-if="creator && d.unfulfillableQuantity > 0" class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="mutate(`/${selected.id}/demands/${d.id}/followup`)">{{ selected.followups.some(f => f.sourceDemandId === d.id) ? '查看最新后续 / 失败后再申请' : '就此部分重新申请' }}</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable || !reason.trim()" @click="mutate('/' + selected.id + '/dispute', { reason })">通知管理员核查</button>
        </div>
      </article>

      <div v-if="selected.decisions.length" class="fams-panel-divider" />
      <p v-for="decision in selected.decisions" :key="decision.id" class="fams-panel-subtitle">版本 {{ decision.requestVersion }}：{{ decision.decisionResult === 'APPROVED' ? '批准' : '驳回' }} {{ decision.commentText }}</p>
      <p v-for="record in selected.unfulfillable" :key="record.id" class="fams-panel-subtitle">无法满足 {{ record.quantity }} 件：{{ reasonLabels[record.reasonCode] }} {{ record.reasonDescription }}；当时可用 {{ record.inventoryQuerySummaryJson.availableCount }} 件，查询于 {{ record.inventoryQuerySummaryJson.queriedAt }}</p>

      <article v-for="allocation in selected.allocations" :key="allocation.id" class="fams-card fams-stack">
        <h3>{{ allocation.assetSnapshotJson.assetCode }} · {{ allocation.assetSnapshotJson.name }} · {{ handoverLabels[allocation.allocationStatus] ?? allocation.allocationStatus }}</h3>
        <p v-if="allocation.approvedTargetJson">批准变更后的责任人员：{{ catalogs.employee?.find(c => c.id === allocation.approvedTargetJson!.employeeId)?.name ?? allocation.approvedTargetJson.employeeId }} · 固定位置：{{ catalogs.location?.find(c => c.id === allocation.approvedTargetJson!.locationId)?.name ?? allocation.approvedTargetJson.locationId }}</p>
        <div class="fams-panel-actions">
          <router-link v-if="canExecute && ['WAITING_DELIVERY', 'DELIVERED_WAITING_RECEIPT'].includes(allocation.allocationStatus)" class="fams-button" data-kind="link" data-size="sm"
            :to="{ path: '/requisition-target-changes', query: { businessId: selected.id, itemId: allocation.id } }">申请目标变更</router-link>
          <router-link v-if="canExecute && allocation.allocationStatus === 'DELIVERED_WAITING_RECEIPT'" class="fams-button" data-kind="link" data-size="sm"
            :to="{ path: '/proxy-confirmations', query: { businessId: selected.id, itemId: allocation.id } }">申请异常代确认</router-link>
          <button v-if="canExecute && allocation.allocationStatus === 'WAITING_DELIVERY'" class="fams-button" data-kind="secondary" data-size="sm" type="button"
            :disabled="busy || retryable || !reason.trim()" @click="replacementId = allocation.id; findInventory(selected!.demands.find(d => d.id === allocation.demandId)!)">更换未交出资产</button>
          <template v-if="canExecute && allocation.allocationStatus === 'WAITING_DELIVERY'">
            <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable || !observedCode.trim() || !destination.trim() || !evidence.trim()"
              @click="handover(allocation, 'deliver')">记录实物交出</button>
            <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable || !reason.trim()"
              @click="handover(allocation, 'cancel')">取消未交出分配</button>
          </template>
          <button v-if="canExecute && allocation.allocationStatus === 'DELIVERED_WAITING_RECEIPT'" class="fams-button" data-kind="secondary" type="button"
            :disabled="busy || retryable || !reason.trim()" @click="handover(allocation, 'recovery')">登记待收回取消</button>
          <button v-if="canExecute && allocation.allocationStatus === 'RECOVERY_PENDING_CANCEL'" class="fams-button" data-kind="primary" type="button"
            :disabled="busy || retryable || !returned || !observedCode.trim() || !evidence.trim() || !reason.trim()"
            @click="handover(allocation, 'recover')">确认收回验收并取消</button>
        </div>
      </article>

      <fieldset v-if="canExecute" :disabled="busy || retryable" class="fams-card fams-stack">
        <legend class="fams-panel-title">实物交出与收回凭据</legend>
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">核对实物编码</span>
            <input class="fams-control" v-model="observedCode" maxlength="128" /></label>
          <label class="fams-field"><span class="fams-field-label">临时去向</span>
            <input class="fams-control" v-model="destination" maxlength="1000" /></label>
          <label class="fams-field fams-field-wide"><span class="fams-field-label">交付 / 收回验收依据</span>
            <textarea class="fams-control" v-model="evidence" maxlength="1000" /></label>
        </div>
        <label class="fams-selection"><input v-model="returned" type="checkbox" />已实际收回并验收，资产可继续在原仓位保管</label>
      </fieldset>

      <p v-for="h in selected.handovers" :key="h.allocationId" class="fams-panel-subtitle">交出 {{ h.deliveredAt }} · 截止 {{ h.receiptDeadlineAt }} · 实物去向 {{ h.destinationText }}</p>
      <ul v-if="selected.handoverEvents?.length" class="fams-timeline">
        <li v-for="e in selected.handoverEvents" :key="e.id">{{ handoverLabels[e.eventType] ?? e.eventType }} · {{ e.evidenceText }}<small>{{ e.occurredAt }}</small></li>
      </ul>
      <p class="fams-field-hint">交出后仍保留正式在库事实和锁；接收人从小程序“待接收”核对实物，成功后更新正式责任。</p>
    </section>

    <section v-if="inventory && inventoryDemand" class="fams-panel fams-stack" aria-labelledby="inventory-title">
      <div class="fams-panel-head">
        <div>
          <h2 id="inventory-title" class="fams-panel-title">可用库存 · {{ inventory.total }} 件</h2>
          <p class="fams-panel-subtitle">仅列出在库、正常、正式卡且无业务锁的同类资产；提交时再次核对。</p>
        </div>
      </div>
      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">资产编码</th><th scope="col">名称</th><th scope="col">型号</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="asset in inventory.items" :key="asset.id">
              <th scope="row" data-column-kind="identity">{{ asset.assetCode }}</th>
              <td>{{ asset.name }}</td><td class="u-secondary">{{ asset.model }}</td>
              <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="busy || retryable"
                @click="mutate(`/${selected!.id}/demands/${inventoryDemand!.id}/allocations`, { asset: { id: asset.id, version: asset.version }, ...(replacementId ? { predecessorAllocationId: replacementId, replacementReason: reason } : {}) })">{{ replacementId ? '确认更换' : '分配此资产' }}</button></td>
            </tr>
            <tr v-if="!inventory.items.length" class="fams-table-empty"><td colspan="4"><strong>没有可用库存</strong>该需求可记录无法满足并通知管理员核查。</td></tr>
          </tbody>
        </table>
      </div>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ inventory.total }} 件</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || inventoryPage === 1"
            @click="findInventory(inventoryDemand, inventoryPage - 1)">上一页库存</button>
          <span class="fams-pagination-page">第 {{ inventoryPage }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || inventoryPage * 20 >= inventory.total"
            @click="findInventory(inventoryDemand, inventoryPage + 1)">下一页库存</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
