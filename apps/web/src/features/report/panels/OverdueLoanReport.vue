<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

interface Row {
  loanId: string;
  assetCode: string;
  borrower: string;
  organization: string;
  expectedReturnDate: string;
  businessDate: string;
  overdueDays: number;
}
interface Page { calculatedAt: string; items: Row[]; nextBefore: string | null }

const organization = shallowRef("");
const borrower = shallowRef("");
const filters = computed(() => {
  const params = new URLSearchParams();
  if (organization.value) params.set("organizationId", organization.value);
  if (borrower.value) params.set("borrowerId", borrower.value);
  return params.toString();
});
const applied = shallowRef("");
const result = ref<Page>();
const visible = computed(() => applied.value === filters.value ? result.value : undefined);
const busy = shallowRef(false);
const error = shallowRef("");
const csvHref = computed(() => `/api/v1/operations-reports/overdue-loans.csv?${applied.value}`);
const hasNoRows = computed(() => visible.value?.items.length === 0);
const nextCursor = computed(() => visible.value?.nextBefore ?? "");

const liveness = useRequestLiveness();

async function load(before?: string): Promise<void> {
  const turn = liveness.begin();
  const params = new URLSearchParams(filters.value);
  applied.value = params.toString();
  result.value = undefined;
  busy.value = true;
  error.value = "";
  if (before) params.set("before", before);
  try {
    const fetched = await reportRequest<Page>(`/operations-reports/overdue-loans?${params}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取逾期借用。请检查筛选条件和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">当前借用逾期</h3>
        <p class="fams-panel-subtitle">按每笔借用记录的企业时区计算。确认遗失的借用进入遗失责任追踪。</p>
      </div>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="overdue-org">使用部门 ID</label>
        <input id="overdue-org" class="fams-control" v-model.trim="organization" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="overdue-borrower">借用人 ID</label>
        <input id="overdue-borrower" class="fams-control" v-model.trim="borrower" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询逾期借用</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询逾期借用</p>
    <div v-if="visible">
      <p>查询时间 {{ visible.calculatedAt }}</p>
      <a class="fams-button" data-kind="link" :href="csvHref">下载逾期借用 CSV</a>
      <p v-if="hasNoRows">当前没有符合条件的逾期借用。</p>
      <div v-else class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col">资产与借用详情</th><th scope="col">借用人</th><th scope="col">使用部门</th>
            <th scope="col">应还日期</th><th scope="col">业务日期</th><th scope="col">逾期天数</th></tr></thead>
          <tbody>
            <tr v-for="row in visible.items" :key="row.loanId">
              <td><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ path: '/loans', query: { loan: row.loanId } }">{{ row.assetCode }}</router-link></td>
              <td>{{ row.borrower }}</td><td>{{ row.organization }}</td><td>{{ row.expectedReturnDate }}</td>
              <td>{{ row.businessDate }}</td><td>{{ row.overdueDays }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="fams-pagination-actions">
        <button v-if="nextCursor" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(nextCursor)">下一页</button>
      </div>
    </div>
  </section>
</template>
