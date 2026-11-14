<script setup lang="ts">
import { computed, ref } from "vue";

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

const organization = ref("");
const borrower = ref("");
const filters = computed(() => {
  const params = new URLSearchParams();
  if (organization.value) params.set("organizationId", organization.value);
  if (borrower.value) params.set("borrowerId", borrower.value);
  return params.toString();
});
const applied = ref("");
const result = ref<Page>();
const visible = computed(() => applied.value === filters.value ? result.value : undefined);
const busy = ref(false);
const error = ref("");

async function load(before?: string) {
  const params = new URLSearchParams(filters.value);
  applied.value = params.toString();
  result.value = undefined;
  busy.value = true;
  error.value = "";
  if (before) params.set("before", before);
  try {
    const response = await fetch(`/api/v1/operations-reports/overdue-loans?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Page;
  } catch {
    error.value = "无法读取逾期借用。请检查筛选条件和当前权限。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>当前借用逾期</h3>
    <p>按每笔借用记录的企业时区计算。确认遗失的借用进入遗失责任追踪。</p>
    <form class="actions" @submit.prevent="load()">
      <label>使用部门 ID <input v-model.trim="organization" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>借用人 ID <input v-model.trim="borrower" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <button :disabled="busy" type="submit">查询逾期借用</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询逾期借用</p>
    <div v-if="visible">
      <p>查询时间 {{ visible.calculatedAt }}</p>
      <a :href="`/api/v1/operations-reports/overdue-loans.csv?${applied}`">下载逾期借用 CSV</a>
      <p v-if="visible.items.length === 0">当前没有符合条件的逾期借用。</p>
      <table v-else>
        <thead><tr><th scope="col">资产与借用详情</th><th scope="col">借用人</th><th scope="col">使用部门</th>
          <th scope="col">应还日期</th><th scope="col">业务日期</th><th scope="col">逾期天数</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.loanId">
            <td><router-link :to="{ path: '/loans', query: { loan: row.loanId } }">{{ row.assetCode }}</router-link></td>
            <td>{{ row.borrower }}</td><td>{{ row.organization }}</td><td>{{ row.expectedReturnDate }}</td>
            <td>{{ row.businessDate }}</td><td>{{ row.overdueDays }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">
        下一页
      </button>
    </div>
  </section>
</template>
