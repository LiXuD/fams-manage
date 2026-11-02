<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { businessMessage, businessRequest } from "@/api/business";
import { loanLabels as labels } from "@/features/loan/api";
import type { BusinessMutation } from "@/api/business";
import type { BusinessPage, Loan, LoanExtension } from "@fams/api-types/loan";

const session = useSessionStore();
const loans = ref<Loan[]>([]); const extensions = ref<LoanExtension[]>([]);
const busy = shallowRef(false); const error = shallowRef(""); const notice = shallowRef(""); const retryable = shallowRef(false);
const page = shallowRef(1); const total = shallowRef(0); const reason = shallowRef("");
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const liveness = useRequestLiveness();
let pending: BusinessMutation | null = null;

const loanRows = computed(() => loans.value.map((row) => ({
  ...row, statusTone: row.overdue ? "warning" : "info", overdueText: row.overdue ? " · 已逾期" : "",
})));
const formDisabled = computed(() => busy.value || retryable.value);
const prevDisabled = computed(() => formDisabled.value || page.value === 1);
const nextDisabled = computed(() => formDisabled.value || page.value * 20 >= total.value);
interface ExtensionRow extends LoanExtension { decisionShown: boolean }
const extensionRows = computed<ExtensionRow[]>(() => extensions.value.map((row) => ({ ...row,
  decisionShown: !!admin.value && row.extensionStatus === "PENDING" })));

async function load() {
  const turn = liveness.begin();
  const [loanPage, extensionPage] = await Promise.all([
    businessRequest<BusinessPage<Loan>>(`/loans?page=${page.value}&pageSize=20`),
    businessRequest<BusinessPage<LoanExtension>>(`/loans/extensions?page=${page.value}&pageSize=20`)]);
  if (!liveness.isCurrent(turn)) return;
  loans.value = loanPage.items; extensions.value = extensionPage.items;
  total.value = Math.max(loanPage.total, extensionPage.total);
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try {
    await load();
    pending = null; retryable.value = false;
  } catch (e) { error.value = businessMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "请先重试上次操作或刷新查询。"; return; }
  pending ??= { path, version, key: crypto.randomUUID(), body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    await businessRequest(pending.path, pending);
    pending = null; retryable.value = false; notice.value = "操作已保存。";
    await load();
  } catch (e) { error.value = businessMessage(e); retryable.value = pending !== null; }
  finally { busy.value = false; }
}
function retry() { if (pending) void mutate(pending.path, pending.version); }
function decide(id: string, version: number, approved: boolean) {
  if (!reason.value.trim()) { error.value = "请填写决定原因。"; return; }
  void mutate(`/loans/extensions/${id}/decision`, version, { approved, reason: reason.value.trim() });
}
onMounted(refresh);
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

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">资产</th><th scope="col">预计归还日</th><th scope="col">状态</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in loanRows" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.assetCode }} · {{ row.name }}</th>
              <td>{{ row.expectedReturnDate }}</td>
              <td><span class="fams-status" :data-tone="row.statusTone">{{ labels[row.relationStatus] }}{{ row.overdueText }}</span></td>
              <td data-column-kind="action"><router-link class="fams-button" data-kind="link" data-size="sm"
                :to="`/loans/${row.id}`">查看 / 延期 / 归还</router-link></td>
            </tr>
            <tr v-if="!loans.length" class="fams-table-empty"><td colspan="4"><strong>当前范围没有借用记录</strong>领用与借用申请批准后才会生成借用关系。</td></tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled"
            @click="page--; refresh()">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled"
            @click="page++; refresh()">下一页</button>
        </div>
      </div>
    </section>

    <section class="fams-panel fams-stack" aria-labelledby="extension-title">
      <div class="fams-panel-head">
        <h2 id="extension-title" class="fams-panel-title">延期审批与历史</h2>
      </div>
      <label class="fams-field">
        <span class="fams-field-label">操作原因 / 验收说明</span>
        <textarea class="fams-control" v-model="reason" :disabled="formDisabled" maxlength="1000" />
      </label>
      <article v-for="row in extensionRows" :key="row.id" class="fams-card fams-stack">
        <p>{{ row.id }} · {{ row.originalDueDate }} → {{ row.requestedDueDate }} · {{ labels[row.extensionStatus] }} · {{ row.reason }}</p>
        <div v-if="row.decisionShown" class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="formDisabled" @click="decide(row.id, row.version, true)">批准延期</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="formDisabled" @click="decide(row.id, row.version, false)">驳回延期</button>
        </div>
      </article>
      <p v-if="!extensions.length" class="fams-panel-subtitle">当前没有延期申请。</p>
    </section>
  </AppChrome>
</template>
