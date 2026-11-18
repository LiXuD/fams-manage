<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

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
interface ExecutorRow {
  key: string;
  accountText: string;
  expected: number;
  accepted: number;
  differences: number;
  notObserved: number;
}
const props = defineProps<{ taskId: string }>();
const labels = { SEALED: '封存执行人明细', CURRENT: '当前执行人明细', RETAINED: '终态保留执行人明细' };
const breakdown = ref<Breakdown>();
const busy = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness();
const requestPath = computed(() => `/operations-reports/inventory/${props.taskId}/executors`);
const csvHref = computed(() => `/api/v1${requestPath.value}.csv`);
const sectionLabel = computed(() => (breakdown.value ? labels[breakdown.value.source] : "执行人明细"));
const showRefresh = computed(() => !breakdown.value || breakdown.value.source === "CURRENT");
const partialText = computed(() => {
  const value = breakdown.value;
  if (!value || value.expected === value.counted) return "";
  return `明细仅覆盖 ${value.counted} / ${value.expected} 项应盘。`;
});
const notObservedLabel = computed(() => (breakdown.value?.source === "CURRENT" ? "未采信" : "未盘"));
const hasRows = computed(() => !!breakdown.value?.items.length);
const executorRows = computed<ExecutorRow[]>(() => (breakdown.value?.items ?? []).map((row) => ({
  key: row.executorAccountId ?? "unassigned",
  accountText: row.executorAccountId ?? "未分配",
  expected: row.expected,
  accepted: row.accepted,
  differences: row.differences,
  notObserved: row.notObserved,
})));

async function load() {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  breakdown.value = undefined;
  try {
    const fetched = await reportRequest<Breakdown>(requestPath.value);
    if (!liveness.isCurrent(turn)) return;
    breakdown.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) error.value = "无法读取执行人明细，请重试。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
onMounted(load);
</script>

<template>
  <section class="fams-card fams-stack" :aria-label="sectionLabel">
    <div class="fams-panel-head">
      <h4 class="fams-panel-title">盘点任务 {{ taskId }} · 执行人明细</h4>
    </div>
    <p v-if="busy" role="status">正在查询执行人明细</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <div v-if="showRefresh" class="fams-inline">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">刷新执行人明细</button>
    </div>
    <template v-if="breakdown">
      <p v-if="partialText">{{ partialText }}</p>
      <div class="fams-inline">
        <a class="fams-button" data-kind="link" :href="csvHref">下载执行人明细 CSV</a>
      </div>
      <div v-if="hasRows" class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">执行人账号</th><th scope="col">应盘</th><th scope="col">已采信</th>
            <th scope="col">采信差异</th><th scope="col">{{ notObservedLabel }}</th></tr></thead>
          <tbody><tr v-for="row in executorRows" :key="row.key">
            <th scope="row" data-column-kind="identity">{{ row.accountText }}</th><td>{{ row.expected }}</td>
            <td>{{ row.accepted }}</td><td>{{ row.differences }}</td><td>{{ row.notObserved }}</td>
          </tr></tbody>
        </table>
      </div>
    </template>
  </section>
</template>
