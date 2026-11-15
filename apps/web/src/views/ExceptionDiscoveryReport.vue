<script setup lang="ts">
import { ref } from "vue";

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
const props = defineProps<{ query: string }>();
const result = ref<Summary>();
const busy = ref(false);
const error = ref("");

async function load() {
  busy.value = true;
  error.value = "";
  result.value = undefined;
  try {
    const response = await fetch(`/api/v1/operations-reports/exception-discovery?${props.query}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Summary;
  } catch {
    error.value = "无法读取关键异常可发现率，请重试。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section aria-label="关键异常可发现率">
    <h3>关键异常可发现率</h3>
    <button type="button" :disabled="busy" @click="load">查询可发现率</button>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="result">
      <p v-if="result.total">
        {{ result.onTime }} / {{ result.total }} 项 · {{ (100 * result.onTime / result.total).toFixed(1) }}%
      </p>
      <p v-else>该时段没有关键异常。</p>
      <p>以异常发生时间归入查询窗口。五分钟内首次进入站内异常清单的记录计入分子。</p>
      <p>计算时间 <time :datetime="result.evaluatedAt">{{ result.evaluatedAt }}</time></p>
      <table>
        <thead>
          <tr><th scope="col">类别</th><th scope="col">五分钟内</th><th scope="col">迟到</th>
            <th scope="col">缺少首见</th><th scope="col">计时中</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in result.categories" :key="row.kind">
            <th scope="row">{{ categories[row.kind] }}</th>
            <td>{{ row.onTime }}</td><td>{{ row.late }}</td><td>{{ row.missing }}</td><td>{{ row.pending }}</td>
          </tr>
        </tbody>
      </table>
      <a :href="`/api/v1/operations-reports/exception-discovery.csv?${query}`">下载关键异常明细 CSV</a>
    </template>
  </section>
</template>
