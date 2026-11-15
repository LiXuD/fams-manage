<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

const kinds = { PROXY_CONFIRMATION: "异常代确认", SCRAP_REVERSAL: "报废冲正", SCRAP_RECOVERY: "报废后找回" };
const statuses: Record<string, string> = {
  SUBMITTED: "待管理员批准", APPROVED: "已批准", EXECUTED: "已执行", WITHDRAWN: "已撤回",
  PENDING_ADMIN_DECISION: "待管理员批准", WAITING_WAREHOUSE_CLAIM: "待库管领取",
  PROCESSING: "办理中", COMPLETED: "已完成", REJECTED: "已驳回",
};
interface Row {
  recordId: string;
  businessType: string;
  businessId: string;
  assetCode: string;
  status: string;
  registeredAt: string;
  processedAt: string | null;
}
interface Page { items: Row[]; nextBefore: string | null }
interface ExceptionRow extends Row { statusText: string; processedAtText: string; hasRecordLink: boolean; hasScrapLink: boolean }

const kind = shallowRef<keyof typeof kinds>("PROXY_CONFIRMATION");
const businessId = shallowRef("");
const status = shallowRef("");
const options = computed(() => kind.value === "PROXY_CONFIRMATION"
  ? ["SUBMITTED", "APPROVED", "REJECTED", "EXECUTED", "WITHDRAWN"]
  : ["PENDING_ADMIN_DECISION", "WAITING_WAREHOUSE_CLAIM", "PROCESSING", "COMPLETED", "REJECTED"]);
const applied = shallowRef("");
const page = ref<Page>();
const busy = shallowRef(false);
const error = shallowRef("");
const query = computed(() => {
  const params = new URLSearchParams({ kind: kind.value });
  if (businessId.value) params.set("businessId", businessId.value);
  if (status.value) params.set("status", status.value);
  return params.toString();
});
const visible = computed(() => query.value === applied.value ? page.value : undefined);
const csvHref = computed(() => `/api/v1/operations-reports/business-exceptions.csv?${applied.value}`);
const hasNoRows = computed(() => !visible.value?.items.length);
const isProxyKind = computed(() => kind.value === "PROXY_CONFIRMATION");
const exceptionRows = computed<ExceptionRow[]>(() => (visible.value?.items ?? []).map((row) => ({
  ...row,
  statusText: statuses[row.status] ?? row.status,
  processedAtText: row.processedAt ?? "—",
  hasRecordLink: isProxyKind.value,
  hasScrapLink: row.businessType === "SCRAP",
})));

const liveness = useRequestLiveness();

async function load(before?: string) {
  const turn = liveness.begin();
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  page.value = undefined;
  error.value = "";
  busy.value = true;
  if (before) params.set("before", before);
  try {
    const fetched = await reportRequest<Page>(`/operations-reports/business-exceptions?${params}`);
    if (!liveness.isCurrent(turn)) return;
    page.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取异常业务报表。请检查筛选条件和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">异常代确认、报废冲正与报废后找回</h3>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="exception-kind">业务类型</label>
        <select id="exception-kind" class="fams-control" v-model="kind" @change="status = ''">
          <option v-for="(label, code) in kinds" :key="code" :value="code">{{ label }}</option>
        </select>
      </div>
      <div class="fams-field">
        <label for="exception-business-id">原业务单 ID</label>
        <input id="exception-business-id" class="fams-control" v-model.trim="businessId" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="exception-status">办理状态</label>
        <select id="exception-status" class="fams-control" v-model="status">
          <option value="">全部</option>
          <option v-for="code in options" :key="code" :value="code">{{ statuses[code] }}</option>
        </select>
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询异常业务</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <a class="fams-button" data-kind="link" :href="csvHref">下载异常业务 CSV</a>
      <p v-if="hasNoRows">没有符合条件的记录。</p>
      <div v-else class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">办理记录</th><th scope="col">原业务单</th><th scope="col">资产编码</th>
          <th scope="col">状态</th><th scope="col">登记时间</th><th scope="col">执行时间</th></tr></thead>
        <tbody>
          <tr v-for="row in exceptionRows" :key="row.recordId">
            <td>
              <router-link v-if="row.hasRecordLink" class="fams-button" data-kind="link" data-size="sm"
                :to="{ name: 'proxy-confirmations', query: { id: row.recordId } }">{{ row.recordId }}</router-link>
              <template v-else>{{ row.recordId }}</template>
            </td>
            <td>
              <router-link v-if="row.hasScrapLink" class="fams-button" data-kind="link" data-size="sm"
                :to="{ name: 'scrap-requests', params: { id: row.businessId } }">{{ row.businessId }}</router-link>
              <template v-else>{{ row.businessId }}</template>
            </td>
            <td>{{ row.assetCode }}</td><td>{{ row.statusText }}</td>
            <td>{{ row.registeredAt }}</td><td>{{ row.processedAtText }}</td>
          </tr>
        </tbody>
      </table>
      </div>
      <div class="fams-pagination-actions">
        <button v-if="visible.nextBefore" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
      </div>
    </div>
  </section>
</template>
