<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import AssetReturnReassignment from "@/components/AssetReturnReassignment.vue";
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
  <AppChrome>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <section class="fams-panel fams-stack" aria-label="借用记录">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">借用记录</h2>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新</button>
          <button v-if="retryable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="retry">重试上次操作</button>
        </div>
      </div>

      <label class="fams-field">
        <span class="fams-field-label">操作原因 / 验收说明</span>
        <textarea class="fams-control" v-model="reason" :disabled="busy || retryable" maxlength="1000" />
      </label>

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">资产</th><th scope="col">预计归还日</th><th scope="col">状态</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in loans" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.assetCode }} · {{ row.name }}</th>
              <td>{{ row.expectedReturnDate }}</td>
              <td><span class="fams-status" :data-tone="row.overdue ? 'warning' : 'info'">{{ labels[row.relationStatus] }}{{ row.overdue ? ' · 已逾期' : '' }}</span></td>
              <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button"
                :disabled="busy || retryable" @click="choose(row.id)">查看 / 延期 / 归还</button></td>
            </tr>
            <tr v-if="!loans.length" class="fams-table-empty"><td colspan="4"><strong>当前范围没有借用记录</strong>领用与借用申请批准后才会生成借用关系。</td></tr>
          </tbody>
        </table>
      </div>

      <div v-if="selected" class="fams-card fams-stack">
        <h3>{{ selected.assetCode }} · {{ selected.name }}</h3>
        <dl class="fams-facts">
          <div><dt>借出日</dt><dd>{{ selected.borrowedDate }}</dd></div>
          <div><dt>原归还日</dt><dd>{{ selected.originalReturnDate }}</dd></div>
          <div><dt>当前归还日</dt><dd>{{ selected.expectedReturnDate }}</dd></div>
        </dl>
        <p v-if="selected.lostLiability" class="fams-notice" data-state="warning">资产已确认遗失，保留责任追踪。</p>
        <ul v-if="selected.reminders?.length" class="fams-timeline">
          <li v-for="reminder in selected.reminders" :key="reminder.id">{{ reminder.reminderBusinessDate }} · {{ reminder.reminderType }}<small v-if="reminder.overdueFrom">逾期起始 {{ reminder.overdueFrom }}</small></li>
        </ul>
        <ul v-if="selected.extensions?.length" class="fams-timeline">
          <li v-for="history in selected.extensions" :key="history.id">延期至 {{ history.requestedDueDate }} · {{ labels[history.extensionStatus] }}<small v-if="history.overdueFrom">历史逾期 {{ history.overdueFrom }} 至 {{ history.overdueThrough }}</small></li>
        </ul>
        <label class="fams-field">
          <span class="fams-field-label">新的预计归还日</span>
          <input class="fams-control" v-model="due" type="date" :disabled="busy || retryable" />
        </label>
        <div v-if="selected.relationStatus === 'OPEN'" class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="extend()">提请延期</button>
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="proposeReturn">申请归还</button>
        </div>
      </div>

      <details class="fams-card">
        <summary class="fams-panel-title">接收前借用延期</summary>
        <div class="fams-stack">
          <p class="fams-panel-subtitle">选择待交接的借用申请；管理员批准后，实际接收时重新校验日期。</p>
          <label class="fams-field"><span class="fams-field-label">借用申请</span>
            <select class="fams-control" v-model="requestId" :disabled="busy || retryable" @change="chooseBorrowRequest"><option value="">请选择</option>
              <option v-for="request in borrowRequests" :key="request.id" :value="request.id">借用 {{ request.id }} · {{ request.expectedReturnDate }}</option></select></label>
          <label class="fams-field"><span class="fams-field-label">预计归还日</span>
            <input class="fams-control" v-model="due" type="date" :disabled="busy || retryable" /></label>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="extend(true)">提请接收前延期</button>
        </div>
      </details>

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

    <section class="fams-panel fams-stack" aria-labelledby="extension-title">
      <div class="fams-panel-head">
        <h2 id="extension-title" class="fams-panel-title">延期审批与历史</h2>
      </div>
      <article v-for="row in extensions" :key="row.id" class="fams-card fams-stack">
        <p>{{ row.id }} · {{ row.originalDueDate }} → {{ row.requestedDueDate }} · {{ labels[row.extensionStatus] }} · {{ row.reason }}</p>
        <div v-if="admin && row.extensionStatus === 'PENDING'" class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="decide(row.id, row.version, true)">批准延期</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="decide(row.id, row.version, false)">驳回延期</button>
        </div>
      </article>
      <p v-if="!extensions.length" class="fams-panel-subtitle">当前没有延期申请。</p>
    </section>

    <section class="fams-panel fams-stack" aria-labelledby="return-title">
      <div class="fams-panel-head">
        <h2 id="return-title" class="fams-panel-title">退还与退库</h2>
      </div>

      <details class="fams-card">
        <summary class="fams-panel-title">在用资产退库 / 停用人员代办</summary>
        <div class="fams-stack">
          <label class="fams-field"><span class="fams-field-label">退库资产</span>
            <select class="fams-control" v-model="assetId" :disabled="busy || retryable" @change="chooseReturnAsset">
              <option value="">请选择当前可退库资产</option><option v-for="asset in returnAssets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option>
            </select></label>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="proposeReturn">提请退库</button>
        </div>
      </details>

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">退还单</th><th scope="col">状态</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in returns" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.id }}</th>
              <td><span class="fams-status">{{ labels[row.returnStatus] }}</span></td>
              <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button"
                :disabled="busy || retryable" @click="choose(row.id, true)">查看 / 审批 / 验收</button></td>
            </tr>
            <tr v-if="!returns.length" class="fams-table-empty"><td colspan="3"><strong>当前没有退还单</strong>借用归还或在用资产退库提交后会出现在这里。</td></tr>
          </tbody>
        </table>
      </div>

      <div v-if="selectedReturn" class="fams-card fams-stack">
        <h3>退还 {{ selectedReturn.id }} · {{ selectedReturn.asset?.assetCode }} · {{ labels[selectedReturn.returnStatus] }}</h3>
        <p>{{ selectedReturn.reason }}</p>
        <div class="fams-panel-actions">
          <template v-if="admin && selectedReturn.returnStatus === 'PENDING_APPROVAL'">
            <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="decide(selectedReturn.id, selectedReturn.version, true, true)">批准退还</button>
            <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="decide(selectedReturn.id, selectedReturn.version, false, true)">驳回退还</button>
          </template>
          <button v-if="warehouse && selectedReturn.returnStatus === 'WAITING_WAREHOUSE_CLAIM'" class="fams-button" data-kind="primary" type="button"
            :disabled="busy || retryable" @click="mutate(`/asset-returns/${selectedReturn.id}/claim`, selectedReturn.version)">领取整单验收</button>
        </div>
        <AssetReturnReassignment v-if="admin && selectedReturn.returnStatus === 'WAREHOUSE_PROCESSING'"
          :id="selectedReturn.id" :version="selectedReturn.version" @saved="refresh" />
        <div v-if="canInspect" class="fams-stack">
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">验收结果</span>
              <select class="fams-control" v-model="result" :disabled="busy || retryable"><option value="NORMAL">正常入库</option><option value="DAMAGED">损坏转报修</option><option value="MISSING">实物缺失转报失</option></select></label>
            <template v-if="result !== 'MISSING'">
              <label class="fams-field"><span class="fams-field-label">实物编码 / 当前二维码</span>
                <input class="fams-control" v-model="observed" :disabled="busy || retryable" /></label>
              <label class="fams-field"><span class="fams-field-label">仓库</span>
                <select class="fams-control" v-model="warehouseId" :disabled="busy || retryable" @change="locationId = ''"><option value="">请选择</option><option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.name }}</option></select></label>
              <label class="fams-field"><span class="fams-field-label">仓位</span>
                <select class="fams-control" v-model="locationId" :disabled="busy || retryable"><option value="">请选择</option><option v-for="l in bins" :key="l.id" :value="l.id">{{ l.name }}</option></select></label>
            </template>
          </div>
          <p v-if="result === 'MISSING'" class="fams-panel-subtitle">实物缺失将保留借用责任，进入报失处理。</p>
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="inspect">提交实物验收</button>
        </div>
        <p v-if="selectedReturn.inspectionResult">验收结果 {{ selectedReturn.inspectionResult }} · {{ selectedReturn.inspectionReason }}</p>
        <p v-for="followup in selectedReturn.followups" :key="followup.id">后续 {{ followup.id }} · {{ followup.businessType === 'REPAIR' ? '损坏维修' : '报失处理' }} · {{ followup.followupStatus === 'CLOSED' ? '已实际完成' : followup.followupStatus === 'ACCEPTED' ? '正式处理中' : '待继续处理' }}<span v-if="followup.linkedRepairId || followup.lastRepairId"> · 正式维修 {{ followup.linkedRepairId || followup.lastRepairId }}（{{ followup.repairStatus === 'COMPLETED' ? '已验收入库' : followup.repairStatus === 'REJECTED' || followup.repairStatus === 'CANCELLED' ? '原维修未执行，源义务仍待处理' : '尚未实际完成' }}）</span></p>
      </div>
    </section>
  </AppChrome>
</template>
