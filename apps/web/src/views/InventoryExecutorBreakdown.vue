<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

interface Executor {
  executorAccountId: string | null;
  expected: number;
  accepted: number;
  differences: number;
  notObserved: number;
}
interface Breakdown {
  taskId: string;
  source: "SEALED" | "CURRENT" | "RETAINED";
  expected: number;
  counted: number;
  items: Executor[];
}
const props = defineProps<{ taskId: string }>();
const endpoint = computed(() => `/api/v1/operations-reports/inventory/${props.taskId}/executors`);
const breakdown = ref<Breakdown>();
const busy = ref(false);
const labels = { SEALED: '封存执行人明细', CURRENT: '当前执行人明细', RETAINED: '终态保留执行人明细' };
const error = ref("");

async function load() {
  busy.value = true;
  error.value = "";
  breakdown.value = undefined;
  try {
    const response = await fetch(endpoint.value, { credentials: "include", cache: "no-store" });
    if (!response.ok) throw new Error(String(response.status));
    breakdown.value = await response.json() as Breakdown;
  } catch {
    error.value = "无法读取执行人明细，请重试。";
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>

<template>
  <section :aria-label="breakdown ? labels[breakdown.source] : '执行人明细'">
    <h4>盘点任务 {{ taskId }} · 执行人明细</h4>
    <p v-if="busy" role="status">正在查询执行人明细</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button v-if="!breakdown || breakdown.source === 'CURRENT'" type="button" :disabled="busy" @click="load">刷新执行人明细</button>
    <template v-if="breakdown">
      <p v-if="breakdown.expected !== breakdown.counted">
        明细仅覆盖 {{ breakdown.counted }} / {{ breakdown.expected }} 项应盘。
      </p>
      <a :href="`${endpoint}.csv`">下载执行人明细 CSV</a>
      <table v-if="breakdown.items.length">
        <thead><tr><th scope="col">执行人账号</th><th scope="col">应盘</th><th scope="col">已采信</th>
          <th scope="col">采信差异</th><th scope="col">{{ breakdown.source === 'CURRENT' ? '未采信' : '未盘' }}</th>
        </tr></thead>
        <tbody><tr v-for="row in breakdown.items" :key="row.executorAccountId ?? 'unassigned'">
          <td>{{ row.executorAccountId ?? '未分配' }}</td><td>{{ row.expected }}</td>
          <td>{{ row.accepted }}</td><td>{{ row.differences }}</td><td>{{ row.notObserved }}</td>
        </tr></tbody>
      </table>
    </template>
  </section>
</template>
