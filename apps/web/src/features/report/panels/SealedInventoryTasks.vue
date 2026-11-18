<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";
import InventoryExecutorBreakdown from "./InventoryExecutorBreakdown.vue";

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
interface Page { items: Task[]; nextBefore: string | null }
interface TaskRow extends Task { rateText: string }

const props = defineProps<{ query: string }>();
const result = ref<Page>();
const executorTask = ref<string>();
const busy = shallowRef(false);
const error = shallowRef("");
const csvHref = computed(() => `/api/v1/operations-reports/inventory-detail.csv?${props.query}`);
const hasNoRows = computed(() => !result.value?.items.length);
const taskRows = computed<TaskRow[]>(() => (result.value?.items ?? []).map((task) => ({
  ...task,
  rateText: `${task.accepted}/${task.expected}（${(100 * task.accepted / task.expected).toFixed(1)}%）`,
})));

const liveness = useRequestLiveness();

async function load(before?: string) {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  executorTask.value = undefined;
  try {
    const params = new URLSearchParams(props.query);
    if (before) params.set("before", before);
    const fetched = await reportRequest<Page>(`/operations-reports/inventory?${params}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取封存任务，请重试。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-card fams-stack" aria-label="封存盘点明细">
    <div class="fams-inline">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load()">查看封存任务明细</button>
      <a class="fams-button" data-kind="link" :href="csvHref">下载封存任务明细 CSV</a>
    </div>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <template v-if="result">
      <p v-if="hasNoRows">该日期范围没有封存任务。</p>
      <div v-else class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">盘点任务</th><th scope="col">封存时间</th><th scope="col">完成率</th>
            <th scope="col">未盘</th><th scope="col">差异证据</th><th scope="col">冲突证据</th><th scope="col">执行人</th></tr></thead>
          <tbody>
            <tr v-for="task in taskRows" :key="task.taskId">
              <th scope="row" data-column-kind="identity"><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'inventory-tasks', query: { taskId: task.taskId } }">{{ task.name }}</router-link></th>
              <td>{{ task.sealedAt }}</td>
              <td>{{ task.rateText }}</td>
              <td>{{ task.notObserved }}</td><td>{{ task.differenceEvidence }}</td><td>{{ task.conflictEvidence }}</td>
              <td><button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="executorTask = task.taskId">按执行人查看</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <InventoryExecutorBreakdown v-if="executorTask" :key="executorTask" :task-id="executorTask" />
      <div class="fams-pagination-actions">
        <button v-if="result.nextBefore" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(result.nextBefore)">下一页</button>
      </div>
    </template>
  </section>
</template>
