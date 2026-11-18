<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

const categories = {
  LOAN_OVERDUE: "借用逾期", INVENTORY_CONFLICT: "盘点冲突",
  INVENTORY_DIFFERENCE: "盘点差异", FACTORY_OVERDUE: "返厂超期",
};
interface Counts {
  kind: keyof typeof categories;
  onTime: number;
  late: number;
  missing: number;
  pending: number;
}
interface Summary {
  evaluatedAt: string;
  total: number;
  onTime: number;
  categories: Counts[];
}
interface CategoryRow {
  kind: string;
  name: string;
  onTime: number;
  late: number;
  missing: number;
  pending: number;
}
const props = defineProps<{ query: string }>();
const result = ref<Summary>();
const busy = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness();
const summaryText = computed(() => {
  const value = result.value;
  if (!value || !value.total) return "";
  return `${value.onTime} / ${value.total} 项 · ${(100 * value.onTime / value.total).toFixed(1)}%`;
});
const categoryRows = computed<CategoryRow[]>(() => (result.value?.categories ?? []).map((row) => ({
  kind: row.kind,
  name: categories[row.kind],
  onTime: row.onTime,
  late: row.late,
  missing: row.missing,
  pending: row.pending,
})));
const csvHref = computed(() => `/api/v1/operations-reports/exception-discovery.csv?${props.query}`);

async function load() {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  result.value = undefined;
  try {
    const fetched = await reportRequest<Summary>(`/operations-reports/exception-discovery?${props.query}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) error.value = "无法读取关键异常可发现率，请重试。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-card fams-stack" aria-label="关键异常可发现率">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">关键异常可发现率</h3>
    </div>
    <div class="fams-inline">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">查询可发现率</button>
    </div>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <template v-if="result">
      <p v-if="summaryText">{{ summaryText }}</p>
      <p v-else>该时段没有关键异常。</p>
      <p>以异常发生时间归入查询窗口。五分钟内首次进入站内异常清单的记录计入分子。</p>
      <p>计算时间 <time :datetime="result.evaluatedAt">{{ result.evaluatedAt }}</time></p>
      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead>
            <tr><th scope="col">类别</th><th scope="col">五分钟内</th><th scope="col">迟到</th>
              <th scope="col">缺少首见</th><th scope="col">计时中</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in categoryRows" :key="row.kind">
              <th scope="row" data-column-kind="identity">{{ row.name }}</th>
              <td>{{ row.onTime }}</td><td>{{ row.late }}</td><td>{{ row.missing }}</td><td>{{ row.pending }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="fams-inline">
        <a class="fams-button" data-kind="link" :href="csvHref">下载关键异常明细 CSV</a>
      </div>
    </template>
  </section>
</template>
