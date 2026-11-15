<script setup lang="ts">
import { ref } from "vue";
import InventoryExecutorBreakdown from "./InventoryExecutorBreakdown.vue";

type Source = "CURRENT" | "SEALED" | "RETAINED" | "MISSING";
interface Task {
  taskId: string;
  name: string;
  status: string;
  source: Source;
  publishedAt: string;
  expected: number;
  counted: number;
  traceable: boolean;
}
interface Page {
  counts: { published: number; traceable: number };
  items: Task[];
  nextBefore: string | null;
}
const props = defineProps<{ query: string }>();
const result = ref<Page>();
const executorTask = ref<string>();
const busy = ref(false);
const error = ref("");
const statusLabels: Record<string, string> = {
  READY: "待开始", IN_PROGRESS: "进行中", WAITING_REVIEW: "待复核",
  COMPLETED: "已完成", CANCELLED: "已取消", TERMINATED: "已终止",
};

async function load(before?: string) {
  busy.value = true;
  error.value = "";
  executorTask.value = undefined;
  try {
    const query = new URLSearchParams(props.query);
    if (before) query.set("before", before);
    const response = await fetch(`/api/v1/operations-reports/inventory-traceability?${query}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Page;
  } catch {
    result.value = undefined;
    error.value = "无法读取盘点可追踪率，请重试。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section aria-label="盘点任务可追踪率">
    <h3>盘点任务可追踪率</h3>
    <button type="button" :disabled="busy" @click="load()">查询可追踪率</button>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="result">
      <p v-if="result.counts.published">
        {{ result.counts.traceable }} / {{ result.counts.published }} 项 ·
        {{ (100 * result.counts.traceable / result.counts.published).toFixed(1) }}%
      </p>
      <p v-else>该时段没有已发布盘点任务。</p>
      <p>按任务发布时间统计，包含发布后取消和终止的任务。明细齐全的任务计入分子，未分配单列。</p>
      <a :href="`/api/v1/operations-reports/inventory-traceability.csv?${query}`">下载盘点可追踪率 CSV</a>
      <table v-if="result.items.length">
        <thead><tr><th scope="col">任务</th><th scope="col">状态</th><th scope="col">明细 / 应盘</th>
          <th scope="col">可追踪</th><th scope="col">执行人</th></tr></thead>
        <tbody>
          <tr v-for="task in result.items" :key="task.taskId">
            <td><router-link :to="{ name: 'inventory-tasks', query: { taskId: task.taskId } }">{{ task.name }}</router-link></td>
            <td>{{ statusLabels[task.status] ?? task.status }}</td>
            <td>{{ task.counted }} / {{ task.expected }}</td>
            <td>{{ task.traceable ? '齐全' : '缺少证据' }}</td>
            <td><button v-if="task.source !== 'MISSING'" type="button" :disabled="busy"
                @click="executorTask = task.taskId">按执行人查看</button></td>
          </tr>
        </tbody>
      </table>
      <InventoryExecutorBreakdown v-if="executorTask" :key="executorTask" :task-id="executorTask" />
      <button v-if="result.nextBefore" type="button" :disabled="busy" @click="load(result.nextBefore)">下一页任务</button>
    </template>
  </section>
</template>
