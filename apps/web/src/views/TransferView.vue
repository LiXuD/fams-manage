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
  <AppChrome><section class="panel"><h1>调拨与人员交接</h1>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <button :disabled="busy" @click="refresh">刷新</button><button v-if="retryable" :disabled="busy" @click="retry">重试上次操作</button>
    <label>申请 / 决定原因 <textarea v-model="reason" maxlength="1000" :disabled="busy || retryable" /></label>
    <h2>新建调拨</h2>
    <label>来源资产 <select v-model="assetId" :disabled="busy || retryable" @change="chooseAsset"><option value="">请选择当前有操作权的资产</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option></select></label>
    <p v-if="currentAsset">原责任模式 {{ custodyName(currentAsset.custodyType) }} · 原使用组织 {{ organization(currentAsset.usingOrganizationId) }} · 原位置 {{ place(currentAsset.currentLocationId) }}</p>
    <label>目标所属组织 <select v-model="owning" :disabled="busy || retryable"><option value="">未指定</option><option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option></select></label>
    <template v-if="!stock">
      <label>目标使用组织 <select v-model="using" :disabled="busy || retryable" @change="chooseOrganization()"><option value="">请选择</option><option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option></select></label>
      <label>目标责任模式 <select v-model="custody" :disabled="busy || retryable"><option value="PERSONAL">个人</option><option value="SHARED">部门公用</option></select></label>
      <label>实际目标责任人 <select v-model="employee" :disabled="busy || retryable"><option value="">请选择</option><option v-for="person in employees.filter(p => custody !== 'SHARED' || p.sharedEligible)" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
    </template>
    <label v-else>目标仓库 <select v-model="warehouse" :disabled="busy || retryable" @change="location = ''"><option value="">请选择</option><option v-for="wh in warehouses" :key="wh.id" :value="wh.id">{{ wh.name }}</option></select></label>
    <label>目标位置 <select v-model="location" :disabled="busy || retryable"><option value="">请选择</option><option v-for="loc in bins" :key="loc.id" :value="loc.id">{{ loc.name }}</option></select></label>
    <button :disabled="busy || retryable" @click="addItem">添加调拨明细</button>
    <div v-for="(item, index) in draftItems" :key="item.asset.id"><p>{{ item.asset.assetCode }}：{{ place(item.asset.currentLocationId) }} → {{ place(item.target.locationId) }}；{{ custodyName(item.asset.custodyType) }} → {{ custodyName(item.target.custodyType) }}；{{ organization(item.target.usingOrganizationId) }}</p><button :disabled="busy || retryable" @click="draftItems.splice(index, 1)">移除此明细</button></div>
    <p v-if="draftItems.length">本单 {{ draftItems.length }} 项，由一名库管记录全部执行；需要多名库管分别负责时，建议按来源/目标位置分组分别提交。</p>
    <button :disabled="busy || retryable || !draftItems.length" @click="create">保存调拨草稿</button>
    <h2>申请与执行</h2><button v-for="row in rows" :key="row.id" :disabled="busy || retryable" @click="open(row.id)">调拨 {{ row.id }} · {{ labels[row.requestStatus] }}</button>
    <div v-if="selected" class="panel"><h3>调拨 {{ selected.id }} · {{ labels[selected.requestStatus] }}</h3><p>{{ selected.reason }}</p>
      <p>{{ selected.totalQuantity }} 项；完成 {{ selected.completedQuantity }}，取消 {{ selected.cancelledQuantity }}，待处理 {{ selected.pendingQuantity }}。来源位置 {{ selected.sourceGroups }} 组，目标 {{ selected.targetGroups }} 组。</p>
      <p>整单由一名库管记录全部执行；如需多人负责，批准前建议按分组分别提交。</p>
      <TransferReassignment v-if="admin && ['PROCESSING','WAITING_HANDOVER'].includes(selected.requestStatus)" :key="selected.id" :id="selected.id" :version="selected.version" @saved="refresh" />
      <button v-if="selected.creatorAccountId === session.user?.id && selected.requestStatus === 'DRAFT'" :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/submit`, selected.version)">提交调拨</button>
      <button v-if="selected.creatorAccountId === session.user?.id && ['DRAFT', 'PENDING_APPROVAL'].includes(selected.requestStatus)" :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/cancel`, selected.version)">取消申请</button>
      <template v-if="admin && selected.requestStatus === 'PENDING_APPROVAL'"><button :disabled="busy || retryable" @click="decide(true)">批准调拨</button><button :disabled="busy || retryable" @click="decide(false)">驳回调拨</button></template>
      <button v-if="operator && selected.requestStatus === 'WAITING_WAREHOUSE_CLAIM'" :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/claim`, selected.version)">领取整单调拨</button>
      <div v-if="operator && selected.processorAccountId === session.user?.id && ['PROCESSING','WAITING_HANDOVER'].includes(selected.requestStatus)">
        <label>实物编码 / 当前二维码 <input v-model="code" :disabled="busy || retryable" /></label><label>实际去向 <input v-model="destination" :disabled="busy || retryable" maxlength="500" /></label>
        <label>现场交接 / 收回依据 <textarea v-model="evidence" :disabled="busy || retryable" maxlength="1000" /></label>
        <label>目标现场实际签收人 <input v-model="signer" :disabled="busy || retryable" maxlength="200" /></label><label>现场收妥时间 <input v-model="receivedAt" type="datetime-local" :disabled="busy || retryable" /></label>
        <label><input v-model="checked" type="checkbox" :disabled="busy || retryable" />已实际收回并核对实物</label>
      </div>
      <div v-for="item in selected.items" :key="item.id" class="panel"><h4>{{ item.sourceSnapshot.asset_code }} · {{ labels[item.itemStatus] }}</h4>
        <p>来源：{{ custodyName(item.sourceSnapshot.custody_type) }} / {{ organization(item.sourceSnapshot.using_organization_id) }} / {{ personName(item.sourceSnapshot.responsible_employee_id ?? item.sourceSnapshot.department_responsible_employee_id) }} / {{ place(item.sourceSnapshot.current_location_id) }}</p>
        <p>目标：{{ custodyName(item.targetSnapshot.custodyType) }} / {{ organization(item.targetSnapshot.usingOrganizationId) }} / {{ personName(item.targetSnapshot.employeeId) }} / {{ place(item.targetSnapshot.locationId) }}</p>
        <p v-if="item.destination">实际去向 {{ item.destination }} · {{ item.deliveryEvidence }}</p>
        <template v-if="operator && selected.processorAccountId === session.user?.id">
          <router-link v-if="item.itemStatus === 'DELIVERED' && item.receiptRequired" :to="{ path: '/proxy-confirmations', query: { businessType: 'TRANSFER', businessId: selected.id, itemId: item.id } }">申请异常代确认</router-link>
          <template v-if="item.itemStatus === 'READY'"><button :disabled="busy || retryable" @click="physical(item, item.receiptRequired ? 'deliver' : 'verify')">{{ item.receiptRequired ? '记录交出，等待目标人接收' : '记录现场收妥并完成' }}</button><button :disabled="busy || retryable" @click="physical(item, 'cancel')">未交出取消</button></template>
          <button v-if="['DELIVERED','REFUSED'].includes(item.itemStatus)" :disabled="busy || retryable" @click="physical(item, 'recovery')">开始实物收回</button><button v-if="item.itemStatus === 'RECOVERY_PENDING'" :disabled="busy || retryable" @click="physical(item, 'recover')">确认实物收回后取消</button>
        </template><p v-if="item.itemStatus === 'COMPLETED'">正式目标已更新，原责任和交接证据保留。</p>
      </div>
    </div>
    <h2>本人待接收</h2><button v-for="row in receipts" :key="row.id" :disabled="busy || retryable" @click="openReceipt(row)">{{ row.assetCode }} · {{ row.name }} · {{ row.destination }}</button>
    <div v-if="receipt"><p>{{ receipt.assetCode }} · {{ receipt.name }}；请核对实际收到的实物。</p><label>实物编码 <input v-model="code" :disabled="busy || retryable" /></label><label><input v-model="checked" type="checkbox" :disabled="busy || retryable" />已实际收到并核对</label><button :disabled="busy || retryable" @click="receive(false)">确认调拨接收</button><button :disabled="busy || retryable" @click="receive(true)">反馈实物不符</button></div>
    <details v-if="admin"><summary>人员责任交接清单</summary><button :disabled="busy" @click="loadPersonnelChoices">加载原责任人员</button><label>原责任人员 <select v-model="personId" :disabled="busy"><option value="">请选择（含已停用人员）</option><option v-for="person in personnelChoices" :key="person.id" :value="person.id">{{ person.name }}</option></select></label><button :disabled="busy" @click="personnel">查询未交接责任</button><p>{{ personNotice }}</p><p>{{ personTasks }}</p><p v-for="asset in personAssets" :key="asset.id">{{ asset.assetCode }} · {{ asset.name }} · {{ asset.lifecycleStatus }}；在用资产通过上方调拨交接，借出资产通过归还关闭责任。</p></details>
    <button :disabled="busy || retryable || page === 1" @click="page--; refresh()">上一页</button> {{ page }} <button :disabled="busy || retryable || page * 20 >= total" @click="page++; refresh()">下一页</button>
  </section></AppChrome>
</template>
