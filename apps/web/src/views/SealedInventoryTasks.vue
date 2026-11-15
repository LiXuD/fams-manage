<script setup lang="ts">
import { ref } from "vue";

interface Task {
  taskId: string;
  name: string;
  sealedAt: string;
  expected: number;
  accepted: number;
  notObserved: number;
  differenceEvidence: number;
  conflictEvidence: number;
}
interface Executor { executorAccountId: string | null; expected: number; accepted: number;
  differences: number; notObserved: number }
interface ExecutorBreakdown { taskId: string; expected: number; counted: number; items: Executor[] }
interface Page { items: Task[]; nextBefore: string | null }
const props = defineProps<{ query: string }>();
const result = ref<Page>();
const busy = ref(false);
const breakdown = ref<ExecutorBreakdown>();
const error = ref("");

async function load(before?: string) {
  busy.value = true;
  error.value = "";
  breakdown.value = undefined;
  try {
    const params = new URLSearchParams(props.query);
    if (before) params.set("before", before);
    const response = await fetch(`/api/v1/operations-reports/inventory?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Page;
  } catch {
    error.value = "无法读取封存任务，请重试。";
  } finally {
    busy.value = false;
  }
}

async function loadExecutors(taskId: string) {
  busy.value = true;
  error.value = "";
  breakdown.value = undefined;
  try {
    const response = await fetch(`/api/v1/operations-reports/inventory/${taskId}/executors`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    breakdown.value = await response.json() as ExecutorBreakdown;
  } catch {
    error.value = "无法读取封存执行人明细，请重试。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section aria-label="封存盘点明细">
    <button type="button" :disabled="busy" @click="load()">查看封存任务明细</button>
    <a :href="`/api/v1/operations-reports/inventory-detail.csv?${query}`">下载封存任务明细 CSV</a>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="result">
      <p v-if="!result.items.length">该日期范围没有封存任务。</p>
      <table v-else>
        <thead><tr><th scope="col">盘点任务</th><th scope="col">封存时间</th><th scope="col">完成率</th>
          <th scope="col">未盘</th><th scope="col">差异证据</th><th scope="col">冲突证据</th><th scope="col">执行人</th></tr></thead>
        <tbody>
          <tr v-for="task in result.items" :key="task.taskId">
            <td><router-link :to="{ name: 'inventory-tasks', query: { taskId: task.taskId } }">{{ task.name }}</router-link></td>
            <td>{{ task.sealedAt }}</td>
            <td>{{ task.accepted }}/{{ task.expected }}（{{ (100 * task.accepted / task.expected).toFixed(1) }}%）</td>
            <td>{{ task.notObserved }}</td><td>{{ task.differenceEvidence }}</td><td>{{ task.conflictEvidence }}</td>
            <td><button type="button" :disabled="busy" @click="loadExecutors(task.taskId)">按执行人查看</button></td>
          </tr>
        </tbody>
      </table>
      <section v-if="breakdown" aria-label="封存执行人明细">
        <h4>盘点任务 {{ breakdown.taskId }} · 执行人明细</h4>
        <p v-if="breakdown.expected !== breakdown.counted">
          封存明细仅覆盖 {{ breakdown.counted }} / {{ breakdown.expected }} 项应盘。
        </p>
        <a :href="`/api/v1/operations-reports/inventory/${breakdown.taskId}/executors.csv`">下载执行人明细 CSV</a>
        <table v-if="breakdown.items.length">
          <thead><tr><th scope="col">执行人账号</th><th scope="col">应盘</th><th scope="col">已采信</th>
            <th scope="col">采信差异</th><th scope="col">未盘</th></tr></thead>
          <tbody><tr v-for="row in breakdown.items" :key="row.executorAccountId ?? 'unassigned'">
            <td>{{ row.executorAccountId ?? '未分配' }}</td><td>{{ row.expected }}</td>
            <td>{{ row.accepted }}</td><td>{{ row.differences }}</td><td>{{ row.notObserved }}</td>
          </tr></tbody>
        </table>
      </section>
      <button v-if="result.nextBefore" type="button" :disabled="busy" @click="load(result.nextBefore)">下一页</button>
    </template>
  </section>
</template>
