<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";
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
interface TaskRow {
  taskId: string;
  name: string;
  statusText: string;
  countedText: string;
  traceableText: string;
  showExecutor: boolean;
}
const props = defineProps<{ query: string }>();
const result = ref<Page>();
const executorTask = ref<string>();
const busy = shallowRef(false);
const error = shallowRef("");
const statusLabels: Record<string, string> = {
  READY: "待开始", IN_PROGRESS: "进行中", WAITING_REVIEW: "待复核",
  COMPLETED: "已完成", CANCELLED: "已取消", TERMINATED: "已终止",
};
const liveness = useRequestLiveness();
const summaryText = computed(() => {
  const value = result.value;
  if (!value || !value.counts.published) return "";
  return `${value.counts.traceable} / ${value.counts.published} 项 · `
    + `${(100 * value.counts.traceable / value.counts.published).toFixed(1)}%`;
});
const csvHref = computed(() => `/api/v1/operations-reports/inventory-traceability.csv?${props.query}`);
const hasRows = computed(() => !!result.value?.items.length);
const taskRows = computed<TaskRow[]>(() => (result.value?.items ?? []).map((task) => ({
  taskId: task.taskId,
  name: task.name,
  statusText: statusLabels[task.status] ?? task.status,
  countedText: `${task.counted} / ${task.expected}`,
  traceableText: task.traceable ? "齐全" : "缺少证据",
  showExecutor: task.source !== "MISSING",
})));

async function load(before?: string) {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  executorTask.value = undefined;
  try {
    const query = new URLSearchParams(props.query);
    if (before) query.set("before", before);
    const fetched = await reportRequest<Page>(`/operations-reports/inventory-traceability?${query}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      result.value = undefined;
      error.value = "无法读取盘点可追踪率，请重试。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-card fams-stack" aria-label="盘点任务可追踪率">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">盘点任务可追踪率</h3>
    </div>
    <div class="fams-inline">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load()">查询可追踪率</button>
    </div>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <template v-if="result">
      <p v-if="summaryText">{{ summaryText }}</p>
      <p v-else>该时段没有已发布盘点任务。</p>
      <p>按任务发布时间统计，包含发布后取消和终止的任务。明细齐全的任务计入分子，未分配单列。</p>
      <div class="fams-inline">
        <a class="fams-button" data-kind="link" :href="csvHref">下载盘点可追踪率 CSV</a>
      </div>
      <div v-if="hasRows" class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">任务</th><th scope="col">状态</th><th scope="col">明细 / 应盘</th>
            <th scope="col">可追踪</th><th scope="col">执行人</th></tr></thead>
          <tbody>
            <tr v-for="task in taskRows" :key="task.taskId">
              <th scope="row" data-column-kind="identity"><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'inventory-tasks', query: { taskId: task.taskId } }">{{ task.name }}</router-link></th>
              <td><span class="fams-status">{{ task.statusText }}</span></td>
              <td>{{ task.countedText }}</td>
              <td>{{ task.traceableText }}</td>
              <td><button v-if="task.showExecutor" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy"
                  @click="executorTask = task.taskId">按执行人查看</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <InventoryExecutorBreakdown v-if="executorTask" :key="executorTask" :task-id="executorTask" />
      <div class="fams-pagination-actions">
        <button v-if="result.nextBefore" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(result.nextBefore)">下一页任务</button>
      </div>
    </template>
  </section>
</template>
