<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { loanLabels as labels, loanMessage, loanRequest } from "@/loan/api";
import type { LoanMutation } from "@/loan/api";
import type { BorrowRequestChoice, BusinessPage, Loan } from "../../../../packages/api-types/src/loan";

const props = defineProps<{ id: string }>();
const router = useRouter();
const selected = ref<Loan | null>(null);
const busy = ref(false); const error = ref(""); const notice = ref(""); const retryable = ref(false);
const due = ref(""); const reason = ref("");
const requestId = ref(""); const requestVersion = ref(0);
const assetId = ref(""); const assetVersion = ref(0);
const borrowRequests = ref<BorrowRequestChoice[]>([]);
let pending: LoanMutation | null = null;

async function open() {
  selected.value = await loanRequest<Loan>("/loans/" + props.id);
  assetId.value = selected.value.assetId; assetVersion.value = selected.value.currentAssetVersion;
}
async function loadRequests() {
  const requestPage = await loanRequest<BusinessPage<BorrowRequestChoice>>("/loans/borrow-requests?page=1&pageSize=20");
  borrowRequests.value = requestPage.items;
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try {
    await open(); await loadRequests();
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
    if (completedPath === "/asset-returns") { await router.push(`/asset-returns/${encodeURIComponent(value.id)}?saved=1`); return; }
    await open(); await loadRequests();
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
function chooseBorrowRequest() {
  const request = borrowRequests.value.find(row => row.id === requestId.value);
  if (request) { requestVersion.value = request.version; due.value = request.expectedReturnDate; }
}
onMounted(async () => {
  busy.value = true;
  try {
    if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "没有当前记录或操作的权限。"; return; }
    await open(); await loadRequests();
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/loans">返回借用列表</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <section v-if="selected" class="fams-panel fams-stack" aria-label="借用详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">{{ selected.assetCode }} · {{ selected.name }}</h2>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新</button>
          <button v-if="retryable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="retry">重试上次操作</button>
        </div>
      </div>

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
        <span class="fams-field-label">操作原因 / 验收说明</span>
        <textarea class="fams-control" v-model="reason" :disabled="busy || retryable" maxlength="1000" />
      </label>
      <label class="fams-field">
        <span class="fams-field-label">新的预计归还日</span>
        <input class="fams-control" v-model="due" type="date" :disabled="busy || retryable" />
      </label>
      <div v-if="selected.relationStatus === 'OPEN'" class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="extend()">提请延期</button>
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="proposeReturn">申请归还</button>
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
    </section>
  </AppChrome>
</template>
