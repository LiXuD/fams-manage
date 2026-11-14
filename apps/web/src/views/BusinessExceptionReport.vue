<script setup lang="ts">
import { computed, ref } from "vue";

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

const kind = ref<keyof typeof kinds>("PROXY_CONFIRMATION");
const businessId = ref("");
const status = ref("");
const options = computed(() => kind.value === "PROXY_CONFIRMATION"
  ? ["SUBMITTED", "APPROVED", "REJECTED", "EXECUTED", "WITHDRAWN"]
  : ["PENDING_ADMIN_DECISION", "WAITING_WAREHOUSE_CLAIM", "PROCESSING", "COMPLETED", "REJECTED"]);
const applied = ref("");
const page = ref<Page>();
const busy = ref(false);
const error = ref("");
const query = computed(() => {
  const params = new URLSearchParams({ kind: kind.value });
  if (businessId.value) params.set("businessId", businessId.value);
  if (status.value) params.set("status", status.value);
  return params.toString();
});
const visible = computed(() => query.value === applied.value ? page.value : undefined);

async function load(before?: string) {
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  page.value = undefined;
  error.value = "";
  busy.value = true;
  if (before) params.set("before", before);
  try {
    const response = await fetch(`/api/v1/operations-reports/business-exceptions?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    page.value = await response.json() as Page;
  } catch {
    error.value = "无法读取异常业务报表。请检查筛选条件和当前权限。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>异常代确认、报废冲正与报废后找回</h3>
    <form class="actions" @submit.prevent="load()">
      <label>业务类型
        <select v-model="kind" @change="status = ''">
          <option v-for="(label, code) in kinds" :key="code" :value="code">{{ label }}</option>
        </select>
      </label>
      <label>原业务单 ID <input v-model.trim="businessId" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>办理状态
        <select v-model="status">
          <option value="">全部</option>
          <option v-for="code in options" :key="code" :value="code">{{ statuses[code] }}</option>
        </select>
      </label>
      <button :disabled="busy" type="submit">查询异常业务</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <a :href="`/api/v1/operations-reports/business-exceptions.csv?${applied}`">下载异常业务 CSV</a>
      <p v-if="!visible.items.length">没有符合条件的记录。</p>
      <table v-else>
        <thead><tr><th scope="col">办理记录</th><th scope="col">原业务单</th><th scope="col">资产编码</th>
          <th scope="col">状态</th><th scope="col">登记时间</th><th scope="col">执行时间</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.recordId">
            <td>
              <router-link v-if="kind === 'PROXY_CONFIRMATION'"
                :to="{ name: 'proxy-confirmations', query: { id: row.recordId } }">{{ row.recordId }}</router-link>
              <template v-else>{{ row.recordId }}</template>
            </td>
            <td>
              <router-link v-if="row.businessType === 'SCRAP'"
                :to="{ name: 'scrap-requests', params: { id: row.businessId } }">{{ row.businessId }}</router-link>
              <template v-else>{{ row.businessId }}</template>
            </td>
            <td>{{ row.assetCode }}</td><td>{{ statuses[row.status] ?? row.status }}</td>
            <td>{{ row.registeredAt }}</td><td>{{ row.processedAt ?? "—" }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
    </div>
  </section>
</template>
