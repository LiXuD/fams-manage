<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import RequisitionReassignment from "@/features/requisition/panels/RequisitionReassignment.vue";
import AppChrome from "@/components/AppChrome.vue";
import RequisitionDemandForm from "./../panels/RequisitionDemandForm.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { allocationStatusLabels, handoverEventLabels, loadRequisitionCatalogs, requisitionLabels as labels, requisitionMessage, requisitionRequest } from "@/features/requisition/api";
import type { Requisition, RequisitionAllocation, RequisitionChoice, RequisitionDemand, RequisitionDemandInput,
  RequisitionInventory } from "@fams/api-types/requisition";

const props = defineProps<{ id: string }>();
const session = useSessionStore();
const liveness = useRequestLiveness(() => [props.id]);
const selected = ref<Requisition | null>(null);
const busy = shallowRef(false); const error = shallowRef(""); const notice = shallowRef("");
const catalogs = ref<Record<string, RequisitionChoice[]>>({});
const canShared = shallowRef(false); const selfEmployee = shallowRef(""); const selfOrg = shallowRef("");
const changeVisible = shallowRef(false);
const observedCode = shallowRef(""); const destination = shallowRef(""); const evidence = shallowRef(""); const returned = shallowRef(false);
const reason = shallowRef(""); const shortageReason = shallowRef("STOCK_SHORTAGE"); const shortageQuantity = shallowRef(1);
const inventory = ref<RequisitionInventory | null>(null); const inventoryDemand = ref<RequisitionDemand | null>(null);
const inventoryPage = shallowRef(1); const replacementId = shallowRef("");
interface RequisitionMutation { path: string; method: string; key: string; version: number; body?: unknown }
/** 确认式幂等提交：在途意图槽与幂等键簿记归底座；busy/retryable、URL、method 与文案留在本视图。 */
const { pending, prepare: draft, confirm: adopt, settle, reset } = useConfirmedSubmit<Omit<RequisitionMutation, "key">>();
const retryable = shallowRef(false);
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const creator = computed(() => selected.value?.creatorAccountId === session.user?.id);
const owner = computed(() => selected.value?.ownership.find(row => !row.archivedAt)?.processorAccountId);
const canExecute = computed(() => warehouse.value && (!owner.value || owner.value === session.user?.id)
  && ["WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING", "WAITING_HANDOVER"].includes(selected.value?.requestStatus ?? ""));
const reasonLabels: Record<string, string> = { STOCK_SHORTAGE: "库存不足", SPECIFICATION_MISMATCH: "规格不匹配",
  TEMPORARILY_UNAVAILABLE: "暂不可用", OTHER: "其他" };

type ProposalChange = NonNullable<Requisition["changes"]>[number];
interface ProposalDemandRow extends RequisitionDemandInput { key: string; categoryName: string; custodyText: string }
interface ProposalChangeRow extends ProposalChange { key: string; statusText: string; demandRows: ProposalDemandRow[] }
interface DemandRow extends RequisitionDemand {
  custodyText: string; showAllocate: boolean; showFollowup: boolean; followupText: string;
}
type ProposalDecision = NonNullable<Requisition["decisions"]>[number];
interface DecisionRow extends ProposalDecision { resultText: string }
interface AllocationRow extends RequisitionAllocation {
  targetEmployeeName: string; targetLocationName: string;
  canTargetChange: boolean; canProxyConfirm: boolean; canReplaceAsset: boolean; canRecoverCancel: boolean;
}

const custodyLabel = (type: RequisitionDemandInput["custodyType"]) => (type === "PERSONAL" ? "个人使用" : "部门公用");
const requestStatus = computed(() => selected.value?.requestStatus ?? "");
const canReassign = computed(() => admin.value && !!owner.value
  && ["WAREHOUSE_PROCESSING", "WAITING_HANDOVER"].includes(requestStatus.value));
const canDraftActions = computed(() => creator.value && ["DRAFT", "REJECTED"].includes(requestStatus.value));
const canSubmitDraft = computed(() => requestStatus.value === "DRAFT");
const canCancel = computed(() => creator.value && !selected.value?.changes?.some(c => c.proposalStatus === "PENDING")
  && ["DRAFT", "REJECTED", "PENDING_ADMIN_DECISION"].includes(requestStatus.value));
const canDecide = computed(() => admin.value && requestStatus.value === "PENDING_ADMIN_DECISION");
const canRequestChange = computed(() => (creator.value || owner.value === session.user?.id)
  && ["WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING", "WAITING_HANDOVER"].includes(requestStatus.value));
const reasonDisabled = computed(() => busy.value || retryable.value || !reason.value.trim());
const unfulfillableDisabled = computed(() => busy.value || retryable.value
  || (shortageReason.value === "OTHER" && !reason.value.trim()));
const deliverDisabled = computed(() => busy.value || retryable.value || !observedCode.value.trim()
  || !destination.value.trim() || !evidence.value.trim());
const recoverDisabled = computed(() => busy.value || retryable.value || !returned.value || !observedCode.value.trim()
  || !evidence.value.trim() || !reason.value.trim());
const assignText = computed(() => replacementId.value ? "确认更换" : "分配此资产");
const prevDisabled = computed(() => busy.value || inventoryPage.value === 1);
const nextDisabled = computed(() => busy.value || (inventory.value ? inventoryPage.value * 20 >= inventory.value.total : true));

const changeRows = computed<ProposalChangeRow[]>(() => (selected.value?.changes ?? []).map((row, index) => ({
  ...row,
  key: `${index}:${row.proposalStatus}:${JSON.stringify(row.proposalJson)}`,
  statusText: row.proposalStatus === "PENDING" ? "待确认" : row.proposalStatus === "APPROVED" ? "已批准" : "已驳回",
  demandRows: row.proposalJson.demands.map((d, i) => ({
    ...d,
    key: [i, d.category.id, d.specification, d.quantity, d.custodyType, d.organization.id, d.employee.id,
      d.location.id, d.expectedDate].join(":"),
    categoryName: catalogs.value.category?.find(c => c.id === String(d.category.id))?.name ?? "",
    custodyText: custodyLabel(d.custodyType),
  })),
})));
const demandRows = computed<DemandRow[]>(() => (selected.value?.demands ?? []).map(d => ({
  ...d,
  custodyText: custodyLabel(d.custodyType),
  showAllocate: !!(canExecute.value && d.pendingAllocationQuantity > 0),
  showFollowup: !!(creator.value && d.unfulfillableQuantity > 0),
  followupText: (selected.value?.followups ?? []).some(f => f.sourceDemandId === d.id)
    ? "查看最新后续 / 失败后再申请" : "就此部分重新申请",
})));
const decisionRows = computed<DecisionRow[]>(() => (selected.value?.decisions ?? []).map(decision => ({
  ...decision,
  resultText: decision.decisionResult === "APPROVED" ? "批准" : "驳回",
})));
const allocationRows = computed<AllocationRow[]>(() => (selected.value?.allocations ?? []).map(allocation => {
  const target = allocation.approvedTargetJson;
  const employee = target ? catalogs.value.employee?.find(c => c.id === target.employeeId) : undefined;
  const location = target ? catalogs.value.location?.find(c => c.id === target.locationId) : undefined;
  const waitingDelivery = allocation.allocationStatus === "WAITING_DELIVERY";
  const deliveredWaiting = allocation.allocationStatus === "DELIVERED_WAITING_RECEIPT";
  return {
    ...allocation,
    targetEmployeeName: target ? (employee ? employee.name : target.employeeId) : "",
    targetLocationName: target ? (location ? location.name : target.locationId) : "",
    canTargetChange: !!(canExecute.value && (waitingDelivery || deliveredWaiting)),
    canProxyConfirm: !!(canExecute.value && deliveredWaiting),
    canReplaceAsset: !!(canExecute.value && waitingDelivery),
    canRecoverCancel: !!(canExecute.value && allocation.allocationStatus === "RECOVERY_PENDING_CANCEL"),
  };
}));

async function open() {
  if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "没有此申请或操作的当前权限。"; return; }
  const turn = liveness.begin();
  const result = await requisitionRequest<Requisition>("/" + props.id);
  if (!liveness.isCurrent(turn)) return;
  selected.value = result;
  inventory.value = null;
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await open(); reset(); retryable.value = false; }
  catch (e) { error.value = requisitionMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, body?: unknown, method = "POST") {
  if (busy.value) return;
  let operation = pending.value;
  if (operation && operation.payload.path !== path) { error.value = "上次操作结果尚未确认，请先重试或刷新。"; return; }
  if (!operation) { draft(() => ({ path, method, version: selected.value?.version ?? 0,
    body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown })); operation = adopt(); }
  if (!operation) return;
  busy.value = true; error.value = ""; notice.value = "";
  try {
    const result = await requisitionRequest<Requisition>(operation.payload.path, operation.payload.method, { ...operation.payload, key: operation.key });
    settle(); retryable.value = false; selected.value = result; changeVisible.value = false; inventory.value = null;
    notice.value = "操作已保存。";
  } catch (e) { error.value = requisitionMessage(e); retryable.value = true; }
  finally { busy.value = false; }
}
function change() { if (retryable.value) return; changeVisible.value = true; }
function submitChange(payload: { demands: RequisitionDemandInput[]; requestType: "REQUISITION" | "BORROW";
  expectedReturnDate: string | null }) {
  void mutate("/" + selected.value!.id + "/changes", payload);
}
async function retry() { if (pending.value) await mutate(pending.value.payload.path); }
async function findInventory(demand: RequisitionDemand, nextPage = 1) {
  if (busy.value) return; busy.value = true; error.value = "";
  const turn = liveness.begin();
  try {
    const result = await requisitionRequest<RequisitionInventory>(`/${selected.value!.id}/demands/${demand.id}/inventory?page=${nextPage}&pageSize=20`);
    if (liveness.isCurrent(turn)) {
      inventory.value = result;
      inventoryDemand.value = demand; inventoryPage.value = nextPage;
    }
  } catch (e) { error.value = requisitionMessage(e); } finally { busy.value = false; }
}
function openInventory(demand: RequisitionDemand): void {
  replacementId.value = "";
  void findInventory(demand);
}
function replaceAsset(allocation: AllocationRow): void {
  replacementId.value = allocation.id;
  void findInventory(selected.value!.demands.find(d => d.id === allocation.demandId)!);
}
function assign(asset: RequisitionInventory["items"][number]): void {
  void mutate(`/${selected.value!.id}/demands/${inventoryDemand.value!.id}/allocations`, {
    asset: { id: asset.id, version: asset.version },
    ...(replacementId.value ? { predecessorAllocationId: replacementId.value, replacementReason: reason.value } : {}),
  });
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
  const turn = liveness.begin();
  try {
    const loaded = await loadRequisitionCatalogs();
    if (liveness.isCurrent(turn)) {
      catalogs.value = loaded.catalogs; canShared.value = loaded.canShared;
      selfEmployee.value = loaded.employeeId; selfOrg.value = loaded.organizationId;
    }
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

      <RequisitionReassignment v-if="canReassign"
        :id="selected.id" :version="selected.version" @saved="refresh" />

      <div class="fams-panel-actions">
        <template v-if="canDraftActions">
          <router-link class="fams-button" data-kind="secondary" :to="`/requisitions/${selected.id}/edit`">修改草稿</router-link>
          <button v-if="canSubmitDraft" class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable"
            @click="mutate('/' + selected.id + '/submit')">提交管理员确认</button></template>
        <button v-if="canCancel"
          class="fams-button" data-kind="danger" type="button" :disabled="reasonDisabled"
          @click="mutate('/' + selected.id + '/cancel', { reason })">取消申请</button>
        <template v-if="canDecide">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable"
            @click="mutate('/' + selected.id + '/decision', { result: 'APPROVED', comment: reason })">批准当前版本</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="reasonDisabled"
            @click="mutate('/' + selected.id + '/decision', { result: 'REJECTED', comment: reason })">驳回当前版本</button></template>
        <button v-if="canExecute && !owner" class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable"
          @click="mutate('/' + selected.id + '/claim')">领取整单处理权</button>
        <button v-if="canRequestChange"
          class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="change">提请范围变更</button>
      </div>

      <label class="fams-field">
        <span class="fams-field-label">决定、取消、更换或核查说明</span>
        <textarea class="fams-control" v-model="reason" maxlength="1000" :disabled="busy || retryable" />
      </label>

      <article v-for="change in changeRows" :key="change.key" class="fams-card fams-stack">
        <h3>范围变更 · {{ change.statusText }}</h3>
        <p v-for="(d, i) in change.demandRows" :key="d.key">需求 {{ i + 1 }}：{{ d.categoryName }} · {{ d.specification }} · 数量 {{ d.quantity }} · {{ d.purpose }} · {{ d.expectedDate }} · {{ d.custodyText }} · 使用组织 {{ d.organization.id }} · 责任人员 {{ d.employee.id }} · 固定位置 {{ d.location.id }}</p>
      </article>

      <article v-for="d in demandRows" :key="d.id" class="fams-card fams-stack">
        <h3>{{ d.categoryNameSnapshot }} · {{ d.specification }}</h3>
        <p>{{ d.purpose }} · 期望 {{ d.expectedDate }} · {{ d.custodyText }}</p>
        <dl class="fams-facts">
          <div><dt>申请</dt><dd>{{ d.requestedQuantity }}</dd></div>
          <div><dt>待分配</dt><dd>{{ d.pendingAllocationQuantity }}</dd></div>
          <div><dt>待交接</dt><dd>{{ d.pendingHandoverQuantity }}</dd></div>
          <div><dt>已完成</dt><dd>{{ d.completedQuantity }}</dd></div>
          <div><dt>无法满足</dt><dd>{{ d.unfulfillableQuantity }}</dd></div>
          <div><dt>已取消</dt><dd>{{ d.cancelledQuantity }}</dd></div>
        </dl>
        <div v-if="d.showAllocate" class="fams-field-row">
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="openInventory(d)">选择可用库存</button>
          <label class="fams-field"><span class="fams-field-label">无法满足数量</span>
            <input class="fams-control" v-model.number="shortageQuantity" type="number" min="1" :max="d.pendingAllocationQuantity" /></label>
          <label class="fams-field"><span class="fams-field-label">原因</span>
            <select class="fams-control" v-model="shortageReason"><option v-for="(label, code) in reasonLabels" :key="code" :value="code">{{ label }}</option></select></label>
          <button class="fams-button" data-kind="danger" type="button" :disabled="unfulfillableDisabled"
            @click="mutate(`/${selected.id}/demands/${d.id}/unfulfillable`, { quantity: shortageQuantity, reason: shortageReason, description: reason })">记录无法满足</button>
        </div>
        <div v-if="d.showFollowup" class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="mutate(`/${selected.id}/demands/${d.id}/followup`)">{{ d.followupText }}</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="reasonDisabled" @click="mutate('/' + selected.id + '/dispute', { reason })">通知管理员核查</button>
        </div>
      </article>

      <div v-if="selected.decisions.length" class="fams-panel-divider" />
      <p v-for="decision in decisionRows" :key="decision.id" class="fams-panel-subtitle">版本 {{ decision.requestVersion }}：{{ decision.resultText }} {{ decision.commentText }}</p>
      <p v-for="record in selected.unfulfillable" :key="record.id" class="fams-panel-subtitle">无法满足 {{ record.quantity }} 件：{{ reasonLabels[record.reasonCode] }} {{ record.reasonDescription }}；当时可用 {{ record.inventoryQuerySummaryJson.availableCount }} 件，查询于 {{ record.inventoryQuerySummaryJson.queriedAt }}</p>

      <article v-for="allocation in allocationRows" :key="allocation.id" class="fams-card fams-stack">
        <h3>{{ allocation.assetSnapshotJson.assetCode }} · {{ allocation.assetSnapshotJson.name }} · {{ allocationStatusLabels[allocation.allocationStatus] }}</h3>
        <p v-if="allocation.approvedTargetJson">批准变更后的责任人员：{{ allocation.targetEmployeeName }} · 固定位置：{{ allocation.targetLocationName }}</p>
        <div class="fams-panel-actions">
          <router-link v-if="allocation.canTargetChange" class="fams-button" data-kind="link" data-size="sm"
            :to="{ path: '/requisition-target-changes', query: { businessId: selected.id, itemId: allocation.id } }">申请目标变更</router-link>
          <router-link v-if="allocation.canProxyConfirm" class="fams-button" data-kind="link" data-size="sm"
            :to="{ path: '/proxy-confirmations', query: { businessId: selected.id, itemId: allocation.id } }">申请异常代确认</router-link>
          <button v-if="allocation.canReplaceAsset" class="fams-button" data-kind="secondary" data-size="sm" type="button"
            :disabled="reasonDisabled" @click="replaceAsset(allocation)">更换未交出资产</button>
          <template v-if="allocation.canReplaceAsset">
            <button class="fams-button" data-kind="primary" type="button" :disabled="deliverDisabled"
              @click="handover(allocation, 'deliver')">记录实物交出</button>
            <button class="fams-button" data-kind="danger" type="button" :disabled="reasonDisabled"
              @click="handover(allocation, 'cancel')">取消未交出分配</button>
          </template>
          <button v-if="allocation.canProxyConfirm" class="fams-button" data-kind="secondary" type="button"
            :disabled="reasonDisabled" @click="handover(allocation, 'recovery')">登记待收回取消</button>
          <button v-if="allocation.canRecoverCancel" class="fams-button" data-kind="primary" type="button"
            :disabled="recoverDisabled"
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
        <li v-for="e in selected.handoverEvents" :key="e.id">{{ handoverEventLabels[e.eventType] }} · {{ e.evidenceText }}<small>{{ e.occurredAt }}</small></li>
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
                @click="assign(asset)">{{ assignText }}</button></td>
            </tr>
            <tr v-if="!inventory.items.length" class="fams-table-empty"><td colspan="4"><strong>没有可用库存</strong>该需求可记录无法满足并通知管理员核查。</td></tr>
          </tbody>
        </table>
      </div>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ inventory.total }} 件</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled"
            @click="findInventory(inventoryDemand, inventoryPage - 1)">上一页库存</button>
          <span class="fams-pagination-page">第 {{ inventoryPage }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled"
            @click="findInventory(inventoryDemand, inventoryPage + 1)">下一页库存</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
