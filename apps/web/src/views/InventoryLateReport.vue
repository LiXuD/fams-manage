<script setup lang="ts">
import { computed, ref } from "vue";

const results = { OPEN: "待复核", INFORMATION: "补充线索", REVIEWED: "已复核", FOLLOWUP_OPEN: "待补盘" };
const comparisons: Record<string, string> = { FROZEN_CONFLICT: "与封存结果冲突", NO_ACCEPTED: "无采信结果",
  TIME_UNVERIFIED: "时间待核实", FINDING: "差异线索", FACTS_MATCH: "与原结果一致" };
const taskStatuses: Record<string, string> = { DRAFT: "草稿", READY: "待开始", IN_PROGRESS: "进行中",
  WAITING_REVIEW: "待复核", COMPLETED: "已完成", CANCELLED: "已取消", TERMINATED: "已终止", DELETED: "已删除" };
interface Row {
  observationId: string;
  taskId: string;
  taskName: string;
  assetCode: string | null;
  receivedAt: string;
  comparison: string;
  result: keyof typeof results;
  linkedTaskId: string | null;
  linkedTaskStatus: string | null;
}
interface Page { items: Row[]; nextBefore: string | null }

const taskId = ref("");
const result = ref("");
const applied = ref("");
const page = ref<Page>();
const busy = ref(false);
const error = ref("");
const query = computed(() => {
  const params = new URLSearchParams();
  if (taskId.value) params.set("taskId", taskId.value);
  if (result.value) params.set("result", result.value);
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
    const response = await fetch(`/api/v1/operations-reports/inventory-late?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    page.value = await response.json() as Page;
  } catch {
    error.value = "无法读取迟到盘点证据。请检查筛选条件和当前权限。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>迟到盘点证据与补盘</h3>
    <form class="actions" @submit.prevent="load()">
      <label>原盘点任务 ID <input v-model.trim="taskId" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>复核结果
        <select v-model="result">
          <option value="">全部</option>
          <option v-for="(label, code) in results" :key="code" :value="code">{{ label }}</option>
        </select>
      </label>
      <button :disabled="busy" type="submit">查询迟到证据</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <a :href="`/api/v1/operations-reports/inventory-late.csv?${applied}`">下载迟到证据 CSV</a>
      <p v-if="!visible.items.length">没有符合条件的迟到证据。</p>
      <table v-else>
        <thead><tr><th scope="col">原盘点任务</th><th scope="col">资产编码</th><th scope="col">接收时间</th>
          <th scope="col">比较结果</th><th scope="col">复核结果</th><th scope="col">关联补盘</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.observationId">
            <td><router-link :to="{ name: 'inventory-tasks', query: { taskId: row.taskId } }">{{ row.taskName }}</router-link></td>
            <td>{{ row.assetCode ?? "—" }}</td><td>{{ row.receivedAt }}</td>
            <td>{{ comparisons[row.comparison] ?? row.comparison }}</td><td>{{ results[row.result] }}</td>
            <td>
              <router-link v-if="row.linkedTaskId"
                :to="{ name: 'inventory-tasks', query: { taskId: row.linkedTaskId } }">任务 {{ row.linkedTaskId }}</router-link>
              {{ row.linkedTaskStatus ? taskStatuses[row.linkedTaskStatus] ?? row.linkedTaskStatus : "—" }}
            </td>
          </tr>
        </tbody>
      </table>
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
    </div>
  </section>
</template>
