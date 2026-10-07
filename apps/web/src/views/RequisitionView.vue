<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { requisitionMessage, requisitionRequest } from "@/requisition/api";
import type { Requisition, RequisitionCatalog, RequisitionChoice, RequisitionDemand, RequisitionDemandInput,
  RequisitionAllocation, RequisitionInventory, RequisitionPage, RequisitionSummary } from "../../../../packages/api-types/src/requisition";

const route = useRoute(); const router = useRouter(); const session = useSessionStore();
const rows = ref<RequisitionSummary[]>([]); const selected = ref<Requisition | null>(null);
const busy = ref(false); const error = ref(""); const notice = ref(""); const page = ref(1); const total = ref(0);
const status = ref(""); const formVisible = ref(false); const editing = ref<"new" | "revise" | "change">("new");
const form = ref<RequisitionDemandInput[]>([]); const catalogs = ref<Record<string, RequisitionChoice[]>>({});
const requestType = ref<"REQUISITION" | "BORROW">("REQUISITION"); const expectedReturnDate = ref("");
const canShared = ref(false); const selfEmployee = ref(""); const selfOrg = ref("");
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
const labels: Record<string, string> = { DRAFT: "草稿", PENDING_ADMIN_DECISION: "待管理员确认", REJECTED: "已驳回",
  WAITING_WAREHOUSE_CLAIM: "待库管领取", WAREHOUSE_PROCESSING: "库管处理中", WAITING_HANDOVER: "待交接",
  PARTIALLY_COMPLETED: "部分完成", COMPLETED: "已完成", UNFULFILLABLE: "无法满足", CANCELLED: "已取消" };
const reasonLabels: Record<string, string> = { STOCK_SHORTAGE: "库存不足", SPECIFICATION_MISMATCH: "规格不匹配",
  TEMPORARILY_UNAVAILABLE: "暂不可用", OTHER: "其他" };
const handoverLabels: Record<string, string> = { WAITING_DELIVERY: "待交出", DELIVERED_WAITING_RECEIPT: "已交出待接收",
  RECOVERY_PENDING_CANCEL: "待收回取消", RECEIVED: "已接收", REFUSED: "接收人反馈不符", CANCELLED: "已取消",
  DELIVERED: "已交出", RECOVERY_REQUESTED: "已登记收回", RECOVERED: "已收回验收" };

async function load() {
  const result = await requisitionRequest<RequisitionPage>(`?page=${page.value}&pageSize=20${status.value ? "&status=" + status.value : ""}`);
  rows.value = result.items; total.value = result.total;
}
async function open(id: string) {
  selected.value = await requisitionRequest<Requisition>("/" + id);
  await router.replace({ query: { id } }); inventory.value = null; formVisible.value = false;
}
async function selectRequest(id: string) {
  if (busy.value || retryable.value) return;
  busy.value = true; error.value = "";
  try { await open(id); }
  catch (e) { error.value = requisitionMessage(e); }
  finally { busy.value = false; }
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await load(); if (selected.value) await open(selected.value.id); pending = null; retryable.value = false; }
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
    pending = null; retryable.value = false; selected.value = result; formVisible.value = false; inventory.value = null;
    await router.replace({ query: { id: result.id } }); await load(); notice.value = "操作已保存。";
  } catch (e) { error.value = requisitionMessage(e); retryable.value = true; }
  finally { busy.value = false; }
}
function newDemand(): RequisitionDemandInput {
  const reference = (kind: string, preferred?: string) => {
    const value = catalogs.value[kind]?.find(item => item.id === preferred) ?? catalogs.value[kind]?.[0];
    return { id: value?.id ?? "", version: value?.version ?? 0 };
  };
  return { category: reference("category"), specification: "", purpose: "", expectedDate: new Date().toISOString().slice(0, 10),
    custodyType: "PERSONAL", organization: reference("organization", selfOrg.value), employee: reference("employee", selfEmployee.value),
    location: reference("location"), quantity: 1, preferredBrand: "", preferredModel: "" };
}
function setReference(d: RequisitionDemandInput, field: "category" | "organization" | "employee" | "location", event: Event) {
  const id = (event.target as HTMLSelectElement).value;
  const choice = catalogs.value[field]?.find(item => item.id === id);
  if (choice) {
    d[field] = { id: choice.id, version: choice.version };
    if (field === "organization") {
      const employee = catalogs.value.employee?.find(item => item.organizationId === choice.id);
      d.employee = { id: employee?.id ?? "", version: employee?.version ?? 0 };
    }
  }
}
function edit(mode: "new" | "revise" | "change") {
  if (retryable.value) return; editing.value = mode; formVisible.value = true;
  requestType.value = mode === "new" ? "REQUISITION" : selected.value?.requestType ?? "REQUISITION";
  expectedReturnDate.value = mode === "new" ? "" : selected.value?.expectedReturnDate ?? "";
  form.value = mode === "new" ? [newDemand()] : selected.value!.demands.map(d => ({
    category: { id: d.categoryId, version: d.categoryVersionSnapshot }, specification: d.specification,
    preferredBrand: d.preferredBrand ?? "", preferredModel: d.preferredModel ?? "", purpose: d.purpose,
    expectedDate: d.expectedDate, custodyType: d.custodyType,
    organization: { id: d.targetOrganizationId, version: d.organizationVersionSnapshot },
    employee: { id: d.targetEmployeeId ?? d.targetDepartmentEmployeeId!, version: d.employeeVersionSnapshot },
    location: { id: d.targetLocationId, version: d.locationVersionSnapshot }, quantity: d.requestedQuantity,
  }));
}
async function save() {
  const path = editing.value === "new" ? "" : "/" + selected.value!.id + (editing.value === "change" ? "/changes" : "");
  await mutate(path, { demands: form.value.map(d => ({ ...d })), requestType: requestType.value,
    expectedReturnDate: requestType.value === "BORROW" ? expectedReturnDate.value : null }, editing.value === "revise" ? "PATCH" : "POST");
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
    for (const kind of ["category", "organization", "employee", "location"]) {
      const items: RequisitionChoice[] = []; let catalogPage = 1; let result: RequisitionCatalog;
      do { result = await requisitionRequest<RequisitionCatalog>(`/catalog/${kind}?page=${catalogPage++}&pageSize=200`);
        items.push(...result.items); } while (result.items.length === 200);
      catalogs.value[kind] = items; canShared.value = result.canApplyShared; selfEmployee.value = result.employeeId; selfOrg.value = result.organizationId;
    }
    await load(); if (typeof route.query.id === "string") await open(route.query.id);
  } catch (e) { error.value = requisitionMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome><section class="panel requisition-page">
    <div class="page-heading"><div><h1>领用与借用申请</h1><p>按数量申请，由一名管理员确认后分配实物。</p></div>
      <button :disabled="busy || retryable" @click="edit('new')">新建申请</button></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <div class="req-actions"><button :disabled="busy" @click="refresh">刷新结果</button>
      <button v-if="retryable" :disabled="busy" @click="retry">重试上次操作</button>
      <label>状态筛选 <select v-model="status" :disabled="busy || retryable" @change="page = 1; refresh()">
        <option value="">全部</option><option v-for="(label, code) in labels" :key="code" :value="code">{{ label }}</option></select></label></div>
    <div class="table-scroll"><table><thead><tr><th>申请编号</th><th>发起人</th><th>状态</th><th>操作</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.id"><td>{{ row.requestNo }}</td><td>{{ row.creatorUsernameSnapshot }}</td>
        <td>{{ labels[row.requestStatus] }}</td><td><button :disabled="busy || retryable" @click="selectRequest(row.id)">查看申请</button></td></tr></tbody></table></div>
    <p v-if="!rows.length && !busy">当前筛选没有申请。</p>
    <div class="req-actions"><button :disabled="busy || page === 1" @click="page--; refresh()">上一页</button><span>{{ page }} 页 · {{ total }} 条</span>
      <button :disabled="busy || page * 20 >= total" @click="page++; refresh()">下一页</button></div>
    <form v-if="formVisible" @submit.prevent="save">
      <h2>{{ editing === 'new' ? '新建数量申请' : editing === 'change' ? '提请批准范围变更' : '修改草稿' }}</h2>
      <label>申请类型<select v-model="requestType" :disabled="busy || retryable || editing === 'change'"
        @change="requestType === 'BORROW' && form.forEach(d => d.custodyType = 'PERSONAL')">
        <option value="REQUISITION">领用</option><option value="BORROW">借用</option></select></label>
      <label v-if="requestType === 'BORROW'">预计归还日<input v-model="expectedReturnDate" type="date" required
        :disabled="busy || retryable || editing === 'change'"></label>
      <fieldset v-for="(d, index) in form" :key="index" :disabled="busy || retryable" class="req-demand-form"><legend>需求 {{ index + 1 }}</legend>
        <label>分类<select :value="d.category.id" @change="setReference(d, 'category', $event)"><option v-for="c in catalogs.category" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label>规格<input v-model="d.specification" required maxlength="1000"></label><label>数量<input v-model.number="d.quantity" type="number" min="1" max="10000" required></label>
        <label>用途<input v-model="d.purpose" required maxlength="1000"></label><label>期望日期<input v-model="d.expectedDate" type="date" required></label>
        <label>偏好品牌<input v-model="d.preferredBrand" maxlength="128"></label><label>偏好型号<input v-model="d.preferredModel" maxlength="128"></label>
        <label>使用方式<select v-model="d.custodyType"><option value="PERSONAL">个人使用</option><option v-if="canShared && requestType !== 'BORROW'" value="SHARED">部门公用</option></select></label>
        <label>使用组织<select :value="d.organization.id" @change="setReference(d, 'organization', $event)"><option v-for="c in catalogs.organization" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label>{{ d.custodyType === 'PERSONAL' ? '实际使用人' : '部门责任人' }}<select :value="d.employee.id" @change="setReference(d, 'employee', $event)">
          <option v-for="c in catalogs.employee?.filter(c => c.organizationId === d.organization.id)" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label>固定位置<select :value="d.location.id" @change="setReference(d, 'location', $event)"><option v-for="c in catalogs.location" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <button v-if="form.length > 1 && editing !== 'change'" type="button" @click="form.splice(index, 1)">移除此需求</button>
      </fieldset>
      <div class="req-actions"><button v-if="editing !== 'change' && form.length < 50" type="button" :disabled="busy || retryable" @click="form.push(newDemand())">增加需求</button>
        <button type="submit" :disabled="busy || retryable">{{ editing === 'change' ? '提请变更确认' : '保存草稿' }}</button></div>
      <p>偏好不代表库存承诺。已履行和待交接数量不会被变更回退。</p>
    </form>
    <section v-if="selected && !formVisible">
      <h2>{{ selected.requestNo }} · {{ labels[selected.requestStatus] }}</h2>
      <section v-for="(change, index) in selected.changes" :key="index" class="req-demand">
        <h3>范围变更 · {{ change.proposalStatus === 'PENDING' ? '待确认' : change.proposalStatus === 'APPROVED' ? '已批准' : '已驳回' }}</h3>
        <p v-for="(d, i) in change.proposalJson.demands" :key="i">需求 {{ i + 1 }}：{{ catalogs.category?.find(c => c.id === String(d.category.id))?.name }} · {{ d.specification }} · 数量 {{ d.quantity }} · {{ d.purpose }} · {{ d.expectedDate }} · {{ d.custodyType === 'PERSONAL' ? '个人使用' : '部门公用' }} · 使用组织 {{ d.organization.id }} · 责任人员 {{ d.employee.id }} · 固定位置 {{ d.location.id }}</p>
      </section>
      <p v-if="selected.sourceRequestId">来源申请 {{ selected.sourceRequestId }} / 需求 {{ selected.sourceDemandId }}</p>
      <p v-if="owner">当前处理库管账号：{{ owner }}</p>
      <div class="req-actions">
        <template v-if="creator && ['DRAFT', 'REJECTED'].includes(selected.requestStatus)"><button :disabled="busy || retryable" @click="edit('revise')">修改草稿</button>
          <button v-if="selected.requestStatus === 'DRAFT'" :disabled="busy || retryable" @click="mutate('/' + selected.id + '/submit')">提交管理员确认</button></template>
        <button v-if="creator && !selected.changes?.some(c => c.proposalStatus === 'PENDING') && ['DRAFT', 'REJECTED', 'PENDING_ADMIN_DECISION'].includes(selected.requestStatus)" :disabled="busy || retryable || !reason.trim()" @click="mutate('/' + selected.id + '/cancel', { reason })">取消申请</button>
        <template v-if="admin && selected.requestStatus === 'PENDING_ADMIN_DECISION'"><button :disabled="busy || retryable" @click="mutate('/' + selected.id + '/decision', { result: 'APPROVED', comment: reason })">批准当前版本</button>
          <button :disabled="busy || retryable || !reason.trim()" @click="mutate('/' + selected.id + '/decision', { result: 'REJECTED', comment: reason })">驳回当前版本</button></template>
        <button v-if="canExecute && !owner" :disabled="busy || retryable" @click="mutate('/' + selected.id + '/claim')">领取整单处理权</button>
        <button v-if="(creator || owner === session.user?.id) && ['WAITING_WAREHOUSE_CLAIM', 'WAREHOUSE_PROCESSING', 'WAITING_HANDOVER'].includes(selected.requestStatus)" :disabled="busy || retryable" @click="edit('change')">提请范围变更</button>
      </div>
      <label>决定、取消、更换或核查说明<textarea v-model="reason" maxlength="1000" :disabled="busy || retryable" /></label>
      <section v-for="d in selected.demands" :key="d.id" class="req-demand">
        <h3>{{ d.categoryNameSnapshot }} · {{ d.specification }}</h3><p>{{ d.purpose }} · 期望 {{ d.expectedDate }} · {{ d.custodyType === 'PERSONAL' ? '个人使用' : '部门公用' }}</p>
        <p>申请 {{ d.requestedQuantity }} / 待分配 {{ d.pendingAllocationQuantity }} / 待交接 {{ d.pendingHandoverQuantity }} / 已完成 {{ d.completedQuantity }} / 无法满足 {{ d.unfulfillableQuantity }} / 已取消 {{ d.cancelledQuantity }}</p>
        <div v-if="canExecute && d.pendingAllocationQuantity > 0" class="req-actions"><button :disabled="busy || retryable" @click="replacementId = ''; findInventory(d)">选择可用库存</button>
          <label>无法满足数量<input v-model.number="shortageQuantity" type="number" min="1" :max="d.pendingAllocationQuantity"></label>
          <label>原因<select v-model="shortageReason"><option v-for="(label, code) in reasonLabels" :key="code" :value="code">{{ label }}</option></select></label>
          <button :disabled="busy || retryable || (shortageReason === 'OTHER' && !reason.trim())" @click="mutate(`/${selected.id}/demands/${d.id}/unfulfillable`, { quantity: shortageQuantity, reason: shortageReason, description: reason })">记录无法满足</button></div>
        <div v-if="creator && d.unfulfillableQuantity > 0" class="req-actions"><button :disabled="busy || retryable" @click="mutate(`/${selected.id}/demands/${d.id}/followup`)">{{ selected.followups.some(f => f.sourceDemandId === d.id) ? '查看最新后续 / 失败后再申请' : '就此部分重新申请' }}</button>
          <button :disabled="busy || retryable || !reason.trim()" @click="mutate('/' + selected.id + '/dispute', { reason })">通知管理员核查</button></div>
      </section>
      <p v-for="decision in selected.decisions" :key="decision.id">版本 {{ decision.requestVersion }}：{{ decision.decisionResult === 'APPROVED' ? '批准' : '驳回' }} {{ decision.commentText }}</p>
      <p v-for="record in selected.unfulfillable" :key="record.id">无法满足 {{ record.quantity }} 件：{{ reasonLabels[record.reasonCode] }} {{ record.reasonDescription }}；当时可用 {{ record.inventoryQuerySummaryJson.availableCount }} 件，查询于 {{ record.inventoryQuerySummaryJson.queriedAt }}</p>
      <p v-for="allocation in selected.allocations" :key="allocation.id">{{ allocation.assetSnapshotJson.assetCode }} · {{ allocation.assetSnapshotJson.name }} · {{ handoverLabels[allocation.allocationStatus] ?? allocation.allocationStatus }}
        <button v-if="canExecute && allocation.allocationStatus === 'WAITING_DELIVERY'" :disabled="busy || retryable || !reason.trim()" @click="replacementId = allocation.id; findInventory(selected!.demands.find(d => d.id === allocation.demandId)!)">更换未交出资产</button></p>
      <fieldset v-if="canExecute" :disabled="busy || retryable"><legend>实物交出与收回凭据</legend>
        <label>核对实物编码<input v-model="observedCode" maxlength="128" /></label>
        <label>临时去向<input v-model="destination" maxlength="1000" /></label>
        <label>交付 / 收回验收依据<textarea v-model="evidence" maxlength="1000" /></label>
        <label><input v-model="returned" type="checkbox" />已实际收回并验收，资产可继续在原仓位保管</label>
      </fieldset>
      <div v-for="a in selected.allocations" :key="'handover-' + a.id" class="req-actions">
        <span>{{ a.assetSnapshotJson.assetCode }}</span>
        <template v-if="canExecute && a.allocationStatus === 'WAITING_DELIVERY'">
          <button :disabled="busy || retryable || !observedCode.trim() || !destination.trim() || !evidence.trim()" @click="handover(a, 'deliver')">记录实物交出</button>
          <button :disabled="busy || retryable || !reason.trim()" @click="handover(a, 'cancel')">取消未交出分配</button>
        </template>
        <button v-if="canExecute && a.allocationStatus === 'DELIVERED_WAITING_RECEIPT'" :disabled="busy || retryable || !reason.trim()" @click="handover(a, 'recovery')">登记待收回取消</button>
        <button v-if="canExecute && a.allocationStatus === 'RECOVERY_PENDING_CANCEL'" :disabled="busy || retryable || !returned || !observedCode.trim() || !evidence.trim() || !reason.trim()" @click="handover(a, 'recover')">确认收回验收并取消</button>
      </div>
      <p v-for="h in selected.handovers" :key="h.allocationId">交出 {{ h.deliveredAt }} · 截止 {{ h.receiptDeadlineAt }} · 实物去向 {{ h.destinationText }}</p>
      <p v-for="e in selected.handoverEvents" :key="e.id">{{ handoverLabels[e.eventType] ?? e.eventType }} · {{ e.evidenceText }} · {{ e.occurredAt }}</p>
      <p>交出后仍保留正式在库事实和锁；接收人从小程序“待接收”核对实物，成功后更新正式责任。</p>
    </section>
    <section v-if="inventory && inventoryDemand"><h3>可用库存 · {{ inventory.total }} 件</h3><p>仅列出在库、正常、正式卡且无业务锁的同类资产；提交时再次核对。</p>
      <div v-for="asset in inventory.items" :key="asset.id" class="req-actions"><span>{{ asset.assetCode }} · {{ asset.name }} · {{ asset.model }}</span>
        <button :disabled="busy || retryable" @click="mutate(`/${selected!.id}/demands/${inventoryDemand!.id}/allocations`, { asset: { id: asset.id, version: asset.version }, ...(replacementId ? { predecessorAllocationId: replacementId, replacementReason: reason } : {}) })">{{ replacementId ? '确认更换' : '分配此资产' }}</button></div>
      <button :disabled="busy || inventoryPage === 1" @click="findInventory(inventoryDemand, inventoryPage - 1)">上一页库存</button>
      <button :disabled="busy || inventoryPage * 20 >= inventory.total" @click="findInventory(inventoryDemand, inventoryPage + 1)">下一页库存</button>
    </section>
  </section></AppChrome>
</template>

<style scoped>
.req-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 16px 0; }
.req-demand-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; padding: 20px; margin: 16px 0; border: 1px solid var(--color-border, #dfe4eb); }
.requisition-page label { display: grid; gap: 6px; }.requisition-page input, .requisition-page select, .requisition-page button, .requisition-page textarea { min-height: 44px; }
.req-demand { border-top: 1px solid var(--color-border, #dfe4eb); padding: 12px 0; }
</style>
