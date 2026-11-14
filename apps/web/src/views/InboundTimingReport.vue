<script setup lang="ts">
import { computed, ref } from "vue";

const kinds = { APPROVAL: "管理员审批", CLAIM: "独占领取", FIRST_EXECUTION: "首次实物验收", REASSIGNMENT: "受控转派" };
const statuses: Record<string, string> = {
  APPROVED: "已批准", REJECTED: "已驳回", CLAIMED: "已领取", ACCEPTED: "已验收", REASSIGNED: "已转派",
};
interface Row {
  factId: string;
  receiptNo: string;
  receiptId: string;
  processorAccountId: string | null;
  status: string;
  startedAt: string | null;
  finishedAt: string;
  elapsedSeconds: number | null;
}
interface Page { items: Row[]; nextBefore: string | null }

const kind = ref<keyof typeof kinds>("APPROVAL");
const receiptId = ref("");
const status = ref("");
const options = computed(() => kind.value === "APPROVAL" ? ["APPROVED", "REJECTED"] : []);
const applied = ref("");
const page = ref<Page>();
const busy = ref(false);
const error = ref("");
const query = computed(() => {
  const params = new URLSearchParams({ kind: kind.value });
  if (receiptId.value) params.set("receiptId", receiptId.value);
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
    const response = await fetch(`/api/v1/operations-reports/inbound-timing?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    page.value = await response.json() as Page;
  } catch {
    error.value = "无法读取入库时效报表。请检查筛选条件和当前权限。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>入库审批与执行时效</h3>
    <form class="actions" @submit.prevent="load()">
      <label>入库办理阶段
        <select v-model="kind" @change="status = ''">
          <option v-for="(label, code) in kinds" :key="code" :value="code">{{ label }}</option>
        </select>
      </label>
      <label>入库单 ID <input v-model.trim="receiptId" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label v-if="options.length">审批结果
        <select v-model="status">
          <option value="">全部</option>
          <option v-for="code in options" :key="code" :value="code">{{ statuses[code] }}</option>
        </select>
      </label>
      <button :disabled="busy" type="submit">查询入库时效</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <a :href="`/api/v1/operations-reports/inbound-timing.csv?${applied}`">下载入库时效 CSV</a>
      <p v-if="!visible.items.length">没有符合条件的记录。</p>
      <table v-else>
        <thead><tr><th scope="col">入库单</th><th scope="col">经办账号 ID</th><th scope="col">结果</th>
          <th scope="col">开始时间</th><th scope="col">完成时间</th><th scope="col">耗时（秒）</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.factId">
            <td><router-link :to="{ name: 'inbound', query: { receiptId: row.receiptId } }">{{ row.receiptNo }}</router-link></td>
            <td>{{ row.processorAccountId ?? "—" }}</td><td>{{ statuses[row.status] ?? row.status }}</td>
            <td>{{ row.startedAt ?? "—" }}</td><td>{{ row.finishedAt }}</td><td>{{ row.elapsedSeconds ?? "—" }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
    </div>
  </section>
</template>
