<script setup lang="ts">
import { computed, ref } from "vue";
import AssetMovementReport from "./AssetMovementReport.vue";

const kinds = { INBOUND: "入库验收", REQUISITION: "领用签收", TRANSFER: "调拨完成", REPAIR: "报修提交", SCRAP: "报废执行" };
type Kind = keyof typeof kinds;
interface Month { from: string; toExclusive: string; counts: { kind: Kind; count: number }[] }
const months = ref<Month[]>();
const maxima = computed(() => Object.fromEntries(Object.keys(kinds).map(kind => [
  kind, Math.max(1, ...(months.value ?? []).flatMap(month => month.counts
    .filter(entry => entry.kind === kind).map(entry => entry.count))),
])));
const selection = ref<{ month: Month; kind: Kind }>();
const busy = ref(false);
const error = ref("");

async function load() {
  busy.value = true;
  error.value = "";
  months.value = undefined;
  selection.value = undefined;
  try {
    const response = await fetch("/api/v1/operations-reports/movement-trend", { credentials: "include", cache: "no-store" });
    if (!response.ok) throw new Error(String(response.status));
    months.value = await response.json() as Month[];
  } catch {
    error.value = "无法读取业务趋势。请检查当前权限后重试。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>最近六个月业务趋势</h3>
    <button type="button" :disabled="busy" @click="load">查询六个月趋势</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询</p>
    <template v-if="months">
      <p>按 UTC 月统计。入库、领用和调拨按实物记录计数，报修和报废按单计数。</p>
      <table>
        <thead><tr><th scope="col">月份</th><th v-for="label in kinds" :key="label" scope="col">{{ label }}</th></tr></thead>
        <tbody>
          <tr v-for="month in months" :key="month.from">
            <th scope="row">{{ month.from.slice(0, 7) }}</th>
            <td v-for="entry in month.counts" :key="entry.kind">
              <button type="button" :aria-label="`${month.from.slice(0, 7)} ${kinds[entry.kind]} ${entry.count} 条，查看流水`"
                :aria-pressed="selection?.month.from === month.from && selection?.kind === entry.kind"
                @click="selection = { month, kind: entry.kind }">
                <meter aria-hidden="true" :max="maxima[entry.kind]" :value="entry.count"></meter>
                {{ entry.count }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
    <AssetMovementReport v-if="selection" :key="`${selection.month.from}:${selection.kind}`"
      :from="selection.month.from" :to-exclusive="selection.month.toExclusive" :initial-kind="selection.kind" />
  </section>
</template>
