<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loanMessage, loanRequest } from "@/loan/api";
import type { LoanMutation } from "@/loan/api";
import type { AssetReturn, BusinessPage, Loan, LoanExtension, ReturnChoice, ReturnAssetChoice, BorrowRequestChoice } from "../../../../packages/api-types/src/loan";

const session = useSessionStore(); const route = useRoute(); const router = useRouter();
const loans = ref<Loan[]>([]); const extensions = ref<LoanExtension[]>([]); const returns = ref<AssetReturn[]>([]);
const selected = ref<Loan | null>(null); const selectedReturn = ref<AssetReturn | null>(null);
const busy = ref(false); const error = ref(""); const notice = ref(""); const retryable = ref(false);
const page = ref(1); const total = ref(0); const due = ref(""); const reason = ref("");
const requestId = ref(""); const requestVersion = ref(0); const assetId = ref(""); const assetVersion = ref(0);
const returnAssets = ref<ReturnAssetChoice[]>([]);
const borrowRequests = ref<BorrowRequestChoice[]>([]);
const observed = ref(""); const result = ref<"NORMAL" | "DAMAGED" | "MISSING">("NORMAL");
const warehouseId = ref(""); const locationId = ref(""); const warehouses = ref<ReturnChoice[]>([]); const locations = ref<ReturnChoice[]>([]);
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const bins = computed(() => locations.value.filter(l => l.warehouseId === warehouseId.value));
const canInspect = computed(() => warehouse.value && selectedReturn.value?.returnStatus === "WAREHOUSE_PROCESSING"
  && selectedReturn.value.processorAccountId === session.user?.id);
const labels: Record<string, string> = { OPEN: "借用中", RETURNED: "已归还", VOID: "期初已撤销", PENDING: "待审批",
  APPROVED: "已批准", REJECTED: "已驳回", PENDING_APPROVAL: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管领取",
  WAREHOUSE_PROCESSING: "待实物验收", COMPLETED: "验收完成", TRANSFERRED: "已转后续处理" };
let pending: LoanMutation | null = null;

async function load() {
  const [loanPage, extensionPage, returnPage, assetPage, requestPage] = await Promise.all([
    loanRequest<BusinessPage<Loan>>(`/loans?page=${page.value}&pageSize=20`),
    loanRequest<BusinessPage<LoanExtension>>(`/loans/extensions?page=${page.value}&pageSize=20`),
    loanRequest<BusinessPage<AssetReturn>>(`/asset-returns?page=${page.value}&pageSize=20`),
    loanRequest<BusinessPage<ReturnAssetChoice>>(`/asset-returns/eligible-assets?page=${page.value}&pageSize=20`),
    loanRequest<BusinessPage<BorrowRequestChoice>>(`/loans/borrow-requests?page=${page.value}&pageSize=20`)]);
  loans.value = loanPage.items; extensions.value = extensionPage.items; returns.value = returnPage.items;
  returnAssets.value = assetPage.items;
  borrowRequests.value = requestPage.items;
  total.value = Math.max(loanPage.total, extensionPage.total, returnPage.total, assetPage.total, requestPage.total);
}
async function openLoan(id: string) {
  selected.value = await loanRequest<Loan>("/loans/" + id);
  assetId.value = selected.value.assetId; assetVersion.value = selected.value.currentAssetVersion;
  await router.replace({ query: { loan: id } });
}
async function openReturn(id: string) {
  selectedReturn.value = await loanRequest<AssetReturn>("/asset-returns/" + id);
  await router.replace({ query: { return: id } });
}
async function choose(id: string, isReturn = false) {
  if (busy.value || pending) return; busy.value = true; error.value = "";
  try { if (isReturn) await openReturn(id); else await openLoan(id); }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try {
    await load();
    if (selected.value) await openLoan(selected.value.id);
    if (selectedReturn.value) await openReturn(selectedReturn.value.id);
    pending = null; retryable.value = false;
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "请先重试上次操作或刷新查询。"; return; }
  pending ??= { path, version, key: crypto.randomUUID(), body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const value = await loanRequest<{ id: string }>(pending.path, pending);
    const completedPath = pending.path; pending = null; retryable.value = false; notice.value = "操作已保存。";
    if (completedPath.startsWith("/asset-returns")) await openReturn(value.id);
    if (selected.value) await openLoan(selected.value.id);
    await load();
  } catch (e) { error.value = loanMessage(e); retryable.value = pending !== null; }
  finally { busy.value = false; }
}
function retry() { if (pending) void mutate(pending.path, pending.version); }
function extend(beforeReceipt = false) {
  const id = beforeReceipt ? requestId.value.trim() : selected.value?.id;
  if (!id || !due.value || !reason.value.trim()) { error.value = "请填写归还日期及延期原因。"; return; }
  void mutate(beforeReceipt ? `/loans/borrow-requests/${id}/extensions` : `/loans/${id}/extensions`,
    beforeReceipt ? requestVersion.value : selected.value!.version, { expectedReturnDate: due.value, reason: reason.value.trim() });
}
function proposeReturn() {
  if (!assetId.value.trim() || !reason.value.trim()) { error.value = "请选择借用或填写退库资产及原因。"; return; }
  void mutate("/asset-returns", 0, { assetId: assetId.value.trim(), assetVersion: assetVersion.value, reason: reason.value.trim() });
}
function chooseReturnAsset() {
  const asset = returnAssets.value.find(row => row.id === assetId.value);
  if (asset) assetVersion.value = asset.version;
}
function chooseBorrowRequest() {
  const request = borrowRequests.value.find(row => row.id === requestId.value);
  if (request) { requestVersion.value = request.version; due.value = request.expectedReturnDate; }
}
function decide(id: string, version: number, approved: boolean, isReturn = false) {
  if (!reason.value.trim()) { error.value = "请填写决定原因。"; return; }
  void mutate(`${isReturn ? "/asset-returns" : "/loans/extensions"}/${id}/decision`, version, { approved, reason: reason.value.trim() });
}
function inspect() {
  const row = selectedReturn.value;
  if (!row || !reason.value.trim()) { error.value = "请填写验收说明。"; return; }
  const body: Record<string, unknown> = { result: result.value, reason: reason.value.trim(), assetVersion: row.asset?.version,
    lockVersion: row.lockVersion };
  if (result.value !== "MISSING") {
    const wh = warehouses.value.find(w => w.id === warehouseId.value), bin = bins.value.find(l => l.id === locationId.value);
    if (!wh || !bin || !observed.value.trim()) { error.value = "请核对实物编码并选择仓库、仓位。"; return; }
    Object.assign(body, { observedCode: observed.value.trim(), warehouseId: wh.id, warehouseVersion: wh.version,
      locationId: bin.id, locationVersion: bin.version });
  }
  void mutate(`/asset-returns/${row.id}/inspect`, row.version, body);
}
async function catalog(kind: string) {
  const rows: ReturnChoice[] = []; let p = 1; let part: ReturnChoice[];
  do { part = (await loanRequest<{ items: ReturnChoice[] }>(`/asset-returns/catalog/${kind}?page=${p++}&pageSize=200`)).items; rows.push(...part); }
  while (part.length === 200);
  return rows;
}
onMounted(async () => {
  busy.value = true;
  try {
    await load();
    if (warehouse.value) { [warehouses.value, locations.value] = await Promise.all([catalog("warehouse"), catalog("location")]); }
    if (typeof route.query.loan === "string") await openLoan(route.query.loan);
    if (typeof route.query.return === "string") await openReturn(route.query.return);
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome><section class="panel">
    <h1>借用、延期与退还</h1>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <button :disabled="busy" @click="refresh">刷新</button>
    <button v-if="retryable" :disabled="busy" @click="retry">重试上次操作</button>
    <label>操作原因 / 验收说明 <textarea v-model="reason" :disabled="busy || retryable" maxlength="1000" /></label>
    <h2>借用记录</h2>
    <table><thead><tr><th>资产</th><th>预计归还日</th><th>状态</th><th>操作</th></tr></thead><tbody>
      <tr v-for="row in loans" :key="row.id"><td>{{ row.assetCode }} · {{ row.name }}</td><td>{{ row.expectedReturnDate }}</td>
        <td>{{ labels[row.relationStatus] }}{{ row.overdue ? ' · 已逾期' : '' }}</td>
        <td><button :disabled="busy || retryable" @click="choose(row.id)">查看 / 延期 / 归还</button></td></tr>
    </tbody></table><p v-if="!loans.length">当前范围没有借用记录。</p>
    <div v-if="selected" class="panel">
      <h3>{{ selected.assetCode }} · {{ selected.name }}</h3>
      <p>借出日 {{ selected.borrowedDate }}；原归还日 {{ selected.originalReturnDate }}；当前归还日 {{ selected.expectedReturnDate }}</p>
      <p v-if="selected.lostLiability">资产已确认遗失，保留责任追踪。</p>
      <p v-for="reminder in selected.reminders" :key="reminder.id">{{ reminder.reminderBusinessDate }} · {{ reminder.reminderType }}
        <span v-if="reminder.overdueFrom"> · 逾期起始 {{ reminder.overdueFrom }}</span></p>
      <p v-for="history in selected.extensions" :key="history.id">延期至 {{ history.requestedDueDate }} · {{ labels[history.extensionStatus] }}
        <span v-if="history.overdueFrom"> · 历史逾期 {{ history.overdueFrom }} 至 {{ history.overdueThrough }}</span></p>
      <label>新的预计归还日 <input v-model="due" type="date" :disabled="busy || retryable" /></label>
      <button v-if="selected.relationStatus === 'OPEN'" :disabled="busy || retryable" @click="extend()">提请延期</button>
      <button v-if="selected.relationStatus === 'OPEN'" :disabled="busy || retryable" @click="proposeReturn">申请归还</button>
    </div>
    <details><summary>接收前借用延期</summary><p>选择待交接的借用申请；管理员批准后，实际接收时重新校验日期。</p>
      <label>借用申请 <select v-model="requestId" :disabled="busy || retryable" @change="chooseBorrowRequest"><option value="">请选择</option>
        <option v-for="request in borrowRequests" :key="request.id" :value="request.id">借用 {{ request.id }} · {{ request.expectedReturnDate }}</option></select></label>
      <label>预计归还日 <input v-model="due" type="date" :disabled="busy || retryable" /></label>
      <button :disabled="busy || retryable" @click="extend(true)">提请接收前延期</button>
    </details>
    <h2>延期审批与历史</h2>
    <div v-for="row in extensions" :key="row.id" class="panel"><p>{{ row.id }} · {{ row.originalDueDate }} → {{ row.requestedDueDate }} · {{ labels[row.extensionStatus] }} · {{ row.reason }}</p>
      <template v-if="admin && row.extensionStatus === 'PENDING'"><button :disabled="busy || retryable" @click="decide(row.id, row.version, true)">批准延期</button>
        <button :disabled="busy || retryable" @click="decide(row.id, row.version, false)">驳回延期</button></template>
    </div>
    <h2>退还与退库</h2>
    <details><summary>在用资产退库 / 停用人员代办</summary>
      <label>退库资产 <select v-model="assetId" :disabled="busy || retryable" @change="chooseReturnAsset">
        <option value="">请选择当前可退库资产</option><option v-for="asset in returnAssets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option>
      </select></label>
      <button :disabled="busy || retryable" @click="proposeReturn">提请退库</button>
    </details>
    <div v-for="row in returns" :key="row.id"><button :disabled="busy || retryable" @click="choose(row.id, true)">退还 {{ row.id }} · {{ labels[row.returnStatus] }}</button></div>
    <div v-if="selectedReturn" class="panel">
      <h3>退还 {{ selectedReturn.id }} · {{ selectedReturn.asset?.assetCode }} · {{ labels[selectedReturn.returnStatus] }}</h3><p>{{ selectedReturn.reason }}</p>
      <template v-if="admin && selectedReturn.returnStatus === 'PENDING_APPROVAL'"><button :disabled="busy || retryable" @click="decide(selectedReturn.id, selectedReturn.version, true, true)">批准退还</button>
        <button :disabled="busy || retryable" @click="decide(selectedReturn.id, selectedReturn.version, false, true)">驳回退还</button></template>
      <button v-if="warehouse && selectedReturn.returnStatus === 'WAITING_WAREHOUSE_CLAIM'" :disabled="busy || retryable"
        @click="mutate(`/asset-returns/${selectedReturn.id}/claim`, selectedReturn.version)">领取整单验收</button>
      <div v-if="canInspect">
        <label>验收结果 <select v-model="result" :disabled="busy || retryable"><option value="NORMAL">正常入库</option><option value="DAMAGED">损坏转报修</option><option value="MISSING">实物缺失转报失</option></select></label>
        <template v-if="result !== 'MISSING'">
          <label>实物编码 / 当前二维码 <input v-model="observed" :disabled="busy || retryable" /></label>
          <label>仓库 <select v-model="warehouseId" :disabled="busy || retryable" @change="locationId = ''"><option value="">请选择</option><option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.name }}</option></select></label>
          <label>仓位 <select v-model="locationId" :disabled="busy || retryable"><option value="">请选择</option><option v-for="l in bins" :key="l.id" :value="l.id">{{ l.name }}</option></select></label>
        </template>
        <p v-else>实物缺失将保留借用责任，进入报失处理。</p><button :disabled="busy || retryable" @click="inspect">提交实物验收</button>
      </div>
      <p v-if="selectedReturn.inspectionResult">验收结果 {{ selectedReturn.inspectionResult }} · {{ selectedReturn.inspectionReason }}</p>
      <p v-for="followup in selectedReturn.followups" :key="followup.id">后续 {{ followup.id }} · {{ followup.businessType }} · {{ followup.followupStatus }}</p>
    </div>
    <div><button :disabled="busy || retryable || page === 1" @click="page--; refresh()">上一页</button> {{ page }}
      <button :disabled="busy || retryable || page * 20 >= total" @click="page++; refresh()">下一页</button></div>
  </section></AppChrome>
</template>
