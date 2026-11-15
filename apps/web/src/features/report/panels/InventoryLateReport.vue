<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

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
interface LateRow extends Row {
  assetCodeText: string;
  comparisonText: string;
  resultText: string;
  linkedHrefQuery: string;
  linkedStatusText: string;
}

const taskId = shallowRef("");
const result = shallowRef("");
const applied = shallowRef("");
const page = ref<Page>();
const busy = shallowRef(false);
const error = shallowRef("");
const query = computed(() => {
  const params = new URLSearchParams();
  if (taskId.value) params.set("taskId", taskId.value);
  if (result.value) params.set("result", result.value);
  return params.toString();
});
const visible = computed(() => query.value === applied.value ? page.value : undefined);
const csvHref = computed(() => `/api/v1/operations-reports/inventory-late.csv?${applied.value}`);
const hasNoRows = computed(() => !visible.value?.items.length);
const lateRows = computed<LateRow[]>(() => (visible.value?.items ?? []).map((row) => ({
  ...row,
  assetCodeText: row.assetCode ?? "—",
  comparisonText: comparisons[row.comparison] ?? row.comparison,
  resultText: results[row.result],
  linkedHrefQuery: row.linkedTaskId ?? "",
  linkedStatusText: row.linkedTaskStatus ? taskStatuses[row.linkedTaskStatus] ?? row.linkedTaskStatus : "—",
})));

const liveness = useRequestLiveness();

async function load(before?: string) {
  const turn = liveness.begin();
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  page.value = undefined;
  error.value = "";
  busy.value = true;
  if (before) params.set("before", before);
  try {
    const fetched = await reportRequest<Page>(`/operations-reports/inventory-late?${params}`);
    if (!liveness.isCurrent(turn)) return;
    page.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取迟到盘点证据。请检查筛选条件和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">迟到盘点证据与补盘</h3>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="late-task-id">原盘点任务 ID</label>
        <input id="late-task-id" class="fams-control" v-model.trim="taskId" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="late-result">复核结果</label>
        <select id="late-result" class="fams-control" v-model="result">
          <option value="">全部</option>
          <option v-for="(label, code) in results" :key="code" :value="code">{{ label }}</option>
        </select>
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询迟到证据</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <a class="fams-button" data-kind="link" :href="csvHref">下载迟到证据 CSV</a>
      <p v-if="hasNoRows">没有符合条件的迟到证据。</p>
      <div v-else class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">原盘点任务</th><th scope="col">资产编码</th><th scope="col">接收时间</th>
            <th scope="col">比较结果</th><th scope="col">复核结果</th><th scope="col">关联补盘</th></tr></thead>
          <tbody>
            <tr v-for="row in lateRows" :key="row.observationId">
              <th scope="row" data-column-kind="identity"><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'inventory-tasks', query: { taskId: row.taskId } }">{{ row.taskName }}</router-link></th>
              <td>{{ row.assetCodeText }}</td><td>{{ row.receivedAt }}</td>
              <td>{{ row.comparisonText }}</td><td><span class="fams-status">{{ row.resultText }}</span></td>
              <td>
                <router-link v-if="row.linkedHrefQuery" class="fams-button" data-kind="link" data-size="sm"
                  :to="{ name: 'inventory-tasks', query: { taskId: row.linkedHrefQuery } }">任务 {{ row.linkedTaskId }}</router-link>
                {{ row.linkedStatusText }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="fams-pagination-actions">
        <button v-if="visible.nextBefore" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
      </div>
    </div>
  </section>
</template>
