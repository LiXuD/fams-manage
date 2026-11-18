<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";
import InventoryExecutorBreakdown from "./InventoryExecutorBreakdown.vue";

interface Row { taskId: string; name: string; status: string; dueAt: string; expected: number; accepted: number }
interface Page { items: Row[]; nextBefore: string | null }
const page = ref<Page>();
const executorTask = ref<string>();
const busy = shallowRef(false);
const error = shallowRef("");
const statuses: Record<string, string> = { READY: "待开始", IN_PROGRESS: "进行中", WAITING_REVIEW: "待复核" };
const hasNoRows = computed(() => !page.value?.items.length);

const liveness = useRequestLiveness();

async function load(before?: string) {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  executorTask.value = undefined;
  try {
    const query = before ? "?" + new URLSearchParams({ before }) : "";
    const fetched = await reportRequest<Page>("/operations-reports/inventory-progress" + query);
    if (!liveness.isCurrent(turn)) return;
    page.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      page.value = undefined;
      error.value = "无法读取当前盘点进度，请重试。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-card fams-stack" aria-label="当前盘点进度">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">当前盘点进度</h3>
    </div>
    <div class="fams-inline">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load()">查询当前盘点任务</button>
    </div>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <template v-if="page">
      <p v-if="hasNoRows">没有进行中的盘点任务。</p>
      <div v-else class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">任务</th><th scope="col">状态</th><th scope="col">截止时间</th>
            <th scope="col">已采信 / 应盘</th><th scope="col">执行人</th></tr></thead>
          <tbody>
            <tr v-for="task in page.items" :key="task.taskId">
              <th scope="row" data-column-kind="identity"><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'inventory-tasks', query: { taskId: task.taskId } }">{{ task.name }}</router-link></th>
              <td><span class="fams-status">{{ statuses[task.status] }}</span></td><td>{{ task.dueAt }}</td><td>{{ task.accepted }}/{{ task.expected }}</td>
              <td><button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="executorTask = task.taskId">按执行人查看</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <InventoryExecutorBreakdown v-if="executorTask" :key="executorTask" :task-id="executorTask" />
      <div class="fams-pagination-actions">
        <button v-if="page.nextBefore" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(page.nextBefore)">下一页盘点任务</button>
      </div>
    </template>
  </section>
</template>
