<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import TransferReassignment from "@/components/TransferReassignment.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loanRequest, loanMessage } from "@/loan/api";
import type { LoanMutation } from "@/loan/api";
import type { Transfer, TransferAsset, TransferChoice, TransferItem, TransferTarget, TransferReceipt } from "../../../../packages/api-types/src/transfer";

const session = useSessionStore();
const route = useRoute();
const rows = ref<Transfer[]>([]), assets = ref<TransferAsset[]>([]), selected = ref<Transfer | null>(null);
const organizations = ref<TransferChoice[]>([]), employees = ref<TransferChoice[]>([]), warehouses = ref<TransferChoice[]>([]), locations = ref<TransferChoice[]>([]);
const receipts = ref<TransferReceipt[]>([]), receipt = ref<TransferReceipt | null>(null);
const draftItems = ref<{ asset: TransferAsset; target: TransferTarget }[]>([]);
const assetId = ref(""), owning = ref(""), using = ref(""), custody = ref("PERSONAL"), employee = ref(""), warehouse = ref(""), location = ref("");
const reason = ref(""), code = ref(""), destination = ref(""), evidence = ref(""), signer = ref(""), receivedAt = ref("");
const personId = ref(""), personAssets = ref<TransferAsset[]>([]), personNotice = ref("");
const personnelChoices = ref<TransferChoice[]>([]);
const peopleNames = ref<Record<string, string>>({}), personTasks = ref("");
const busy = ref(false), error = ref(""), notice = ref(""), retryable = ref(false), page = ref(1), total = ref(0), checked = ref(false);
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const operator = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const currentAsset = computed(() => assets.value.find(a => a.id === assetId.value));
const stock = computed(() => currentAsset.value?.lifecycleStatus === "IN_STOCK");
const bins = computed(() => locations.value.filter(l => stock.value ? l.locationType === "BIN" && l.warehouseId === warehouse.value : l.locationType !== "BIN"));
const labels: Record<string, string> = { DRAFT: "草稿", PENDING_APPROVAL: "待管理员确认", REJECTED: "已驳回", WAITING_WAREHOUSE_CLAIM: "待库管领取",
  PROCESSING: "库管处理中", WAITING_HANDOVER: "待实际交接", COMPLETED: "已完成", PARTIALLY_COMPLETED: "部分完成", CANCELLED: "已取消",
  READY: "未交出", DELIVERED: "待目标人接收", REFUSED: "实物不符待核查", RECOVERY_PENDING: "待实物收回" };
let pending: LoanMutation | null = null, grant: string | null = null;

async function catalog(kind: string, org?: string) {
  const result: TransferChoice[] = []; let p = 1, part: TransferChoice[];
  do { part = (await loanRequest<{ items: TransferChoice[] }>(`/transfers/catalog/${kind}?page=${p++}&pageSize=200${org ? `&organizationId=${org}` : ""}`)).items; result.push(...part); }
  while (part.length === 200);
  return result;
}
async function load() {
  const [list, eligible, waiting] = await Promise.all([
    loanRequest<{ items: Transfer[]; total: number }>(`/transfers?page=${page.value}&pageSize=20`),
    loanRequest<{ items: TransferAsset[]; total: number }>(`/transfers/eligible-assets?page=${page.value}&pageSize=20`),
    loanRequest<{ items: TransferReceipt[] }>("/transfers/receipts/pending")]);
  rows.value = list.items; total.value = Math.max(list.total, eligible.total); assets.value = eligible.items; receipts.value = waiting.items;
  if (selected.value) selected.value = await loanRequest<Transfer>("/transfers/" + selected.value.id);
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await load(); pending = null; grant = null; receipt.value = null; retryable.value = false; }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function open(id: string) {
  if (busy.value || pending) return; busy.value = true; error.value = "";
  try { selected.value = await loanRequest<Transfer>("/transfers/" + id); await loadPeople(selected.value); }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "请先重试上次操作或刷新核对。"; return; }
  pending ??= { path, version, key: crypto.randomUUID(), body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const result = await loanRequest<{ id: string }>(pending.path, pending);
    const action = pending.path; pending = null; retryable.value = false; notice.value = "操作已保存。";
    if (action.includes("/receipts/")) { receipt.value = null; grant = null; }
    else { selected.value = await loanRequest<Transfer>("/transfers/" + result.id); await loadPeople(selected.value); }
    if (action === "/transfers") draftItems.value = [];
    await load();
  } catch (e) { error.value = loanMessage(e); retryable.value = pending !== null; }
  finally { busy.value = false; }
}
function retry() { const operation = pending; if (operation) void mutate(operation.path, operation.version); }
function place(id: unknown) { return locations.value.find(row => row.id === String(id))?.name ?? "原登记位置"; }
function organization(id: unknown) { return organizations.value.find(row => row.id === String(id))?.name ?? "未指定组织"; }
function custodyName(value: unknown) { return value === "PERSONAL" ? "个人" : value === "SHARED" ? "部门公用" : "仓库保管"; }
function personName(id: unknown) { return id ? peopleNames.value[String(id)] ?? "原登记责任人员" : "仓库保管"; }
async function loadPeople(value: Transfer) {
  const ids = new Set(value.items.flatMap(item => [item.targetSnapshot.usingOrganizationId, item.sourceSnapshot.using_organization_id]).filter(Boolean).map(String));
  const choices = await Promise.all([...ids].map(id => catalog("employee", id)));
  peopleNames.value = Object.fromEntries(choices.flat().map(person => [person.id, person.name]));
}
async function loadPersonnelChoices() {
  if (!admin.value || busy.value) return;
  busy.value = true;
  try { personnelChoices.value = await catalog("personnel"); }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function chooseAsset() {
  const asset = currentAsset.value; if (!asset) return;
  owning.value = asset.owningOrganizationId ?? ""; using.value = asset.usingOrganizationId ?? ""; custody.value = asset.custodyType;
  employee.value = asset.responsibleEmployeeId ?? asset.departmentResponsibleEmployeeId ?? "";
  warehouse.value = asset.storageWarehouseId ?? ""; location.value = asset.currentLocationId;
  if (using.value) await chooseOrganization(false);
}
async function chooseOrganization(reset = true) {
  if (reset) employee.value = ""; employees.value = []; if (!using.value) return;
  busy.value = true;
  try { employees.value = await catalog("employee", using.value); }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
function addItem() {
  const asset = currentAsset.value; if (!asset || !location.value || draftItems.value.some(i => i.asset.id === asset.id)) { error.value = "请选择未重复的来源资产和目标位置。"; return; }
  const loc = bins.value.find(l => l.id === location.value), wh = warehouses.value.find(w => w.id === warehouse.value);
  const org = organizations.value.find(o => o.id === using.value), owner = organizations.value.find(o => o.id === owning.value);
  const person = employees.value.find(e => e.id === employee.value);
  if (!loc || stock.value && !wh || !stock.value && (!org || !person || custody.value === "SHARED" && !person.sharedEligible)) { error.value = "请核对有效目标部门、责任人和位置；公用责任人须为目标部门资产员。"; return; }
  const versions: Record<string, number> = { location: loc.version };
  if (owner) versions.owningOrganization = owner.version;
  if (stock.value) versions.warehouse = wh!.version;
  else { versions.usingOrganization = org!.version; versions.employee = person!.version; }
  draftItems.value.push({ asset, target: { owningOrganizationId: owner?.id ?? null, custodyType: stock.value ? "STORAGE" : custody.value,
    usingOrganizationId: stock.value ? null : org!.id, employeeId: stock.value ? null : person!.id,
    warehouseId: stock.value ? wh!.id : null, locationId: loc.id, referenceVersions: versions } });
}
function create() {
  if (!draftItems.value.length || !reason.value.trim()) { error.value = "请添加调拨明细并填写原因。"; return; }
  void mutate("/transfers", 0, { reason: reason.value.trim(), items: draftItems.value.map(i => ({ assetId: i.asset.id, assetVersion: i.asset.version, target: i.target })) });
}
function decide(approved: boolean) { if (selected.value && reason.value.trim()) void mutate(`/transfers/${selected.value.id}/decision`, selected.value.version, { approved, reason: reason.value.trim() }); }
function physical(item: TransferItem, action: string) {
  if (!selected.value || !code.value.trim() || !destination.value.trim() || !evidence.value.trim()) { error.value = "请填写实物编码、实际去向和交接/收回依据。"; return; }
  const body = { itemVersion: item.version, observedCode: code.value.trim(), destination: destination.value.trim(), evidence: evidence.value.trim(),
    actualReceiver: signer.value.trim() || null, receivedAt: receivedAt.value ? new Date(receivedAt.value).toISOString() : null, physicalReturned: action === "recover" ? checked.value : null };
  void mutate(`/transfers/${selected.value.id}/items/${item.id}/${action}`, selected.value.version, body);
}
async function openReceipt(row: TransferReceipt) {
  if (busy.value || pending) return; busy.value = true; error.value = ""; grant = null;
  try { const credential = await loanRequest<{ grant: string; requestVersion: number }>(`/transfers/receipts/${row.id}/grant`, { path: "", version: 0, key: crypto.randomUUID() });
    grant = credential.grant; receipt.value = { ...row, requestVersion: credential.requestVersion }; code.value = ""; checked.value = false;
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
function receive(refuse: boolean) {
  if (!receipt.value || !grant || !checked.value || !code.value.trim() || refuse && !reason.value.trim()) { error.value = "请核对实物、编码及不符原因。"; return; }
  void mutate(`/transfers/receipts/${receipt.value.id}/${refuse ? "refuse" : "receive"}`, receipt.value.requestVersion,
    { grant, observedCode: code.value.trim(), physicalChecked: true, reason: reason.value.trim() });
}
async function personnel() {
  if (!personId.value || busy.value) return; busy.value = true; error.value = "";
  try { const value = await loanRequest<{ employee: { name: string; status: string }; assets: TransferAsset[]; pendingReceipts: unknown[]; processingTransfers: unknown[]; openLoans: unknown[]; openReturns: unknown[]; openRequisitions: unknown[] }>(`/transfers/personnel-handover?employeeId=${personId.value}`);
    personAssets.value = value.assets; personNotice.value = `${value.employee.name} · ${value.employee.status === "ACTIVE" ? "有效" : "已停用"}；未交接前保留原责任。`;
    personTasks.value = `待接收调拨 ${value.pendingReceipts.length}，处理中调拨 ${value.processingTransfers.length}，未关闭借用 ${value.openLoans.length}，待归还 ${value.openReturns.length}，领用/借用待办 ${value.openRequisitions.length}。请通过对应业务完成交接；停用不会自动关闭。`;
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
onMounted(async () => {
  busy.value = true;
  try {
    await load(); [organizations.value, warehouses.value, locations.value] = await Promise.all([catalog("organization"), catalog("warehouse"), catalog("location")]);
    if (route.query.id && /^\d+$/.test(String(route.query.id))) {
      selected.value = await loanRequest<Transfer>(`/transfers/${encodeURIComponent(String(route.query.id))}`);
      await loadPeople(selected.value);
    }
  }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <section class="fams-panel fams-stack" aria-labelledby="create-title">
      <div class="fams-panel-head">
        <div>
          <h2 id="create-title" class="fams-panel-title">新建调拨</h2>
          <p class="fams-panel-subtitle">先添加调拨明细再保存草稿；一名库管记录整单执行，多人负责时按分组分别提交。</p>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新</button>
          <button v-if="retryable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="retry">重试上次操作</button>
        </div>
      </div>

      <label class="fams-field">
        <span class="fams-field-label">申请 / 决定原因</span>
        <textarea class="fams-control" v-model="reason" maxlength="1000" :disabled="busy || retryable" />
      </label>

      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">来源资产</span>
          <select class="fams-control" v-model="assetId" :disabled="busy || retryable" @change="chooseAsset"><option value="">请选择当前有操作权的资产</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">目标所属组织</span>
          <select class="fams-control" v-model="owning" :disabled="busy || retryable"><option value="">未指定</option><option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option></select></label>
        <template v-if="!stock">
          <label class="fams-field"><span class="fams-field-label">目标使用组织</span>
            <select class="fams-control" v-model="using" :disabled="busy || retryable" @change="chooseOrganization()"><option value="">请选择</option><option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option></select></label>
          <label class="fams-field"><span class="fams-field-label">目标责任模式</span>
            <select class="fams-control" v-model="custody" :disabled="busy || retryable"><option value="PERSONAL">个人</option><option value="SHARED">部门公用</option></select></label>
          <label class="fams-field"><span class="fams-field-label">实际目标责任人</span>
            <select class="fams-control" v-model="employee" :disabled="busy || retryable"><option value="">请选择</option><option v-for="person in employees.filter(p => custody !== 'SHARED' || p.sharedEligible)" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
        </template>
        <label v-else class="fams-field"><span class="fams-field-label">目标仓库</span>
          <select class="fams-control" v-model="warehouse" :disabled="busy || retryable" @change="location = ''"><option value="">请选择</option><option v-for="wh in warehouses" :key="wh.id" :value="wh.id">{{ wh.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">目标位置</span>
          <select class="fams-control" v-model="location" :disabled="busy || retryable"><option value="">请选择</option><option v-for="loc in bins" :key="loc.id" :value="loc.id">{{ loc.name }}</option></select></label>
      </div>
      <p v-if="currentAsset" class="fams-panel-subtitle">原责任模式 {{ custodyName(currentAsset.custodyType) }} · 原使用组织 {{ organization(currentAsset.usingOrganizationId) }} · 原位置 {{ place(currentAsset.currentLocationId) }}</p>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="addItem">添加调拨明细</button>

      <article v-for="(item, index) in draftItems" :key="item.asset.id" class="fams-card fams-stack">
        <p>{{ item.asset.assetCode }}：{{ place(item.asset.currentLocationId) }} → {{ place(item.target.locationId) }}；{{ custodyName(item.asset.custodyType) }} → {{ custodyName(item.target.custodyType) }}；{{ organization(item.target.usingOrganizationId) }}</p>
        <button class="fams-button" data-kind="danger" data-size="sm" type="button" :disabled="busy || retryable" @click="draftItems.splice(index, 1)">移除此明细</button>
      </article>
      <p v-if="draftItems.length" class="fams-panel-subtitle">本单 {{ draftItems.length }} 项，由一名库管记录全部执行；需要多名库管分别负责时，建议按来源/目标位置分组分别提交。</p>

      <div class="fams-form-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable || !draftItems.length" @click="create">保存调拨草稿</button>
      </div>
    </section>

    <section class="fams-panel fams-stack" aria-labelledby="request-title">
      <div class="fams-panel-head">
        <h2 id="request-title" class="fams-panel-title">申请与执行</h2>
      </div>
      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">调拨单</th><th scope="col">状态</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.id }}</th>
              <td><span class="fams-status">{{ labels[row.requestStatus] }}</span></td>
              <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button"
                :disabled="busy || retryable" @click="open(row.id)">查看 / 执行</button></td>
            </tr>
            <tr v-if="!rows.length" class="fams-table-empty"><td colspan="3"><strong>当前没有调拨申请</strong>从上方新建调拨开始。</td></tr>
          </tbody>
        </table>
      </div>

      <div v-if="selected" class="fams-card fams-stack">
        <h3>调拨 {{ selected.id }} · {{ labels[selected.requestStatus] }}</h3>
        <p>{{ selected.reason }}</p>
        <dl class="fams-facts">
          <div><dt>总项数</dt><dd>{{ selected.totalQuantity }}</dd></div>
          <div><dt>完成</dt><dd>{{ selected.completedQuantity }}</dd></div>
          <div><dt>取消</dt><dd>{{ selected.cancelledQuantity }}</dd></div>
          <div><dt>待处理</dt><dd>{{ selected.pendingQuantity }}</dd></div>
          <div><dt>来源分组</dt><dd>{{ selected.sourceGroups }}</dd></div>
          <div><dt>目标分组</dt><dd>{{ selected.targetGroups }}</dd></div>
        </dl>
        <p class="fams-panel-subtitle">整单由一名库管记录全部执行；如需多人负责，批准前建议按分组分别提交。</p>
        <TransferReassignment v-if="admin && ['PROCESSING','WAITING_HANDOVER'].includes(selected.requestStatus)" :key="selected.id" :id="selected.id" :version="selected.version" @saved="refresh" />
        <div class="fams-panel-actions">
          <button v-if="selected.creatorAccountId === session.user?.id && selected.requestStatus === 'DRAFT'" class="fams-button" data-kind="primary" type="button"
            :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/submit`, selected.version)">提交调拨</button>
          <button v-if="selected.creatorAccountId === session.user?.id && ['DRAFT', 'PENDING_APPROVAL'].includes(selected.requestStatus)" class="fams-button" data-kind="danger" type="button"
            :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/cancel`, selected.version)">取消申请</button>
          <template v-if="admin && selected.requestStatus === 'PENDING_APPROVAL'">
            <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="decide(true)">批准调拨</button>
            <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="decide(false)">驳回调拨</button>
          </template>
          <button v-if="operator && selected.requestStatus === 'WAITING_WAREHOUSE_CLAIM'" class="fams-button" data-kind="primary" type="button"
            :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/claim`, selected.version)">领取整单调拨</button>
        </div>

        <div v-if="operator && selected.processorAccountId === session.user?.id && ['PROCESSING','WAITING_HANDOVER'].includes(selected.requestStatus)" class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">实物编码 / 当前二维码</span>
            <input class="fams-control" v-model="code" :disabled="busy || retryable" /></label>
          <label class="fams-field"><span class="fams-field-label">实际去向</span>
            <input class="fams-control" v-model="destination" :disabled="busy || retryable" maxlength="500" /></label>
          <label class="fams-field"><span class="fams-field-label">目标现场实际签收人</span>
            <input class="fams-control" v-model="signer" :disabled="busy || retryable" maxlength="200" /></label>
          <label class="fams-field"><span class="fams-field-label">现场收妥时间</span>
            <input class="fams-control" v-model="receivedAt" type="datetime-local" :disabled="busy || retryable" /></label>
          <label class="fams-field fams-field-wide"><span class="fams-field-label">现场交接 / 收回依据</span>
            <textarea class="fams-control" v-model="evidence" :disabled="busy || retryable" maxlength="1000" /></label>
          <label class="fams-selection fams-field-wide"><input v-model="checked" type="checkbox" :disabled="busy || retryable" />已实际收回并核对实物</label>
        </div>

        <article v-for="item in selected.items" :key="item.id" class="fams-card fams-stack">
          <h4>{{ item.sourceSnapshot.asset_code }} · {{ labels[item.itemStatus] }}</h4>
          <p>来源：{{ custodyName(item.sourceSnapshot.custody_type) }} / {{ organization(item.sourceSnapshot.using_organization_id) }} / {{ personName(item.sourceSnapshot.responsible_employee_id ?? item.sourceSnapshot.department_responsible_employee_id) }} / {{ place(item.sourceSnapshot.current_location_id) }}</p>
          <p>目标：{{ custodyName(item.targetSnapshot.custodyType) }} / {{ organization(item.targetSnapshot.usingOrganizationId) }} / {{ personName(item.targetSnapshot.employeeId) }} / {{ place(item.targetSnapshot.locationId) }}</p>
          <p v-if="item.destination" class="u-secondary">实际去向 {{ item.destination }} · {{ item.deliveryEvidence }}</p>
          <div v-if="operator && selected.processorAccountId === session.user?.id" class="fams-panel-actions">
            <template v-if="item.itemStatus === 'READY'">
              <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable"
                @click="physical(item, item.receiptRequired ? 'deliver' : 'verify')">{{ item.receiptRequired ? '记录交出，等待目标人接收' : '记录现场收妥并完成' }}</button>
              <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="physical(item, 'cancel')">未交出取消</button>
            </template>
            <button v-if="['DELIVERED','REFUSED'].includes(item.itemStatus)" class="fams-button" data-kind="secondary" type="button"
              :disabled="busy || retryable" @click="physical(item, 'recovery')">开始实物收回</button>
            <button v-if="item.itemStatus === 'RECOVERY_PENDING'" class="fams-button" data-kind="primary" type="button"
              :disabled="busy || retryable" @click="physical(item, 'recover')">确认实物收回后取消</button>
            <router-link v-if="item.itemStatus === 'DELIVERED' && item.receiptRequired" class="fams-button" data-kind="link" data-size="sm"
              :to="{ path: '/proxy-confirmations', query: { businessType: 'TRANSFER', businessId: selected.id, itemId: item.id } }">申请异常代确认</router-link>
            <router-link v-if="['READY', 'DELIVERED', 'REFUSED'].includes(item.itemStatus)" class="fams-button" data-kind="link" data-size="sm"
              :to="{ path: '/transfer-target-changes', query: { businessId: selected.id, itemId: item.id } }">申请目标变更</router-link>
          </div>
          <p v-if="item.itemStatus === 'COMPLETED'" class="fams-panel-subtitle">正式目标已更新，原责任和交接证据保留。</p>
        </article>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || retryable || page === 1"
            @click="page--; refresh()">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || retryable || page * 20 >= total"
            @click="page++; refresh()">下一页</button>
        </div>
      </div>
    </section>

    <section class="fams-panel fams-stack" aria-labelledby="receipt-title">
      <div class="fams-panel-head">
        <h2 id="receipt-title" class="fams-panel-title">本人待接收</h2>
        <span class="fams-status" data-tone="info">{{ receipts.length }} 项</span>
      </div>
      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">资产</th><th scope="col">名称</th><th scope="col">实际去向</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in receipts" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.assetCode }}</th>
              <td>{{ row.name }}</td><td>{{ row.destination }}</td>
              <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button"
                :disabled="busy || retryable" @click="openReceipt(row)">核对并接收</button></td>
            </tr>
            <tr v-if="!receipts.length" class="fams-table-empty"><td colspan="4"><strong>没有待接收的调拨</strong>库管记录实物交出后会出现在这里。</td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="receipt" class="fams-card fams-stack">
        <h3>{{ receipt.assetCode }} · {{ receipt.name }}</h3>
        <p>请核对实际收到的实物。</p>
        <label class="fams-field"><span class="fams-field-label">实物编码</span>
          <input class="fams-control" v-model="code" :disabled="busy || retryable" /></label>
        <label class="fams-selection"><input v-model="checked" type="checkbox" :disabled="busy || retryable" />已实际收到并核对</label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="receive(false)">确认调拨接收</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="receive(true)">反馈实物不符</button>
        </div>
      </div>
    </section>

    <details v-if="admin" class="fams-panel fams-stack">
      <summary class="fams-panel-title">人员责任交接清单</summary>
      <div class="fams-stack">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="loadPersonnelChoices">加载原责任人员</button>
        <label class="fams-field"><span class="fams-field-label">原责任人员</span>
          <select class="fams-control" v-model="personId" :disabled="busy"><option value="">请选择（含已停用人员）</option><option v-for="person in personnelChoices" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="personnel">查询未交接责任</button>
        </div>
        <p>{{ personNotice }}</p>
        <p class="fams-panel-subtitle">{{ personTasks }}</p>
        <p v-for="asset in personAssets" :key="asset.id">{{ asset.assetCode }} · {{ asset.name }} · {{ asset.lifecycleStatus }}；在用资产通过上方调拨交接，借出资产通过归还关闭责任。</p>
      </div>
    </details>
  </AppChrome>
</template>
