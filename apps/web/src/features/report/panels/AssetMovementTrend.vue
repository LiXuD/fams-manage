<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import AssetMovementReport from "./AssetMovementReport.vue";
import { reportRequest } from "@/features/report/api";

const kinds = { INBOUND: "入库验收", REQUISITION: "领用签收", TRANSFER: "调拨完成", REPAIR: "报修提交", SCRAP: "报废执行" };
type Kind = keyof typeof kinds;
interface Month { from: string; toExclusive: string; counts: { kind: Kind; count: number }[] }
interface Cell { kind: Kind; count: number; maxCount: number; ariaLabel: string; pressed: boolean }
interface MonthRow { month: Month; from: string; monthText: string; cells: Cell[] }

const months = ref<Month[]>();
const maxima = computed(() => Object.fromEntries(Object.keys(kinds).map(kind => [
  kind, Math.max(1, ...(months.value ?? []).flatMap(month => month.counts
    .filter(entry => entry.kind === kind).map(entry => entry.count))),
])));
const selection = ref<{ month: Month; kind: Kind }>();
const busy = shallowRef(false);
const error = shallowRef("");
const monthRows = computed<MonthRow[]>(() => (months.value ?? []).map((month) => {
  const monthText = month.from.slice(0, 7);
  return { month, from: month.from, monthText, cells: month.counts.map((entry) => ({
    ...entry,
    maxCount: maxima.value[entry.kind],
    ariaLabel: `${monthText} ${kinds[entry.kind]} ${entry.count} 条，查看流水`,
    pressed: selection.value?.month.from === month.from && selection.value?.kind === entry.kind,
  })) };
}));
const reportFrom = computed(() => selection.value?.month.from ?? "");
const reportToExclusive = computed(() => selection.value?.month.toExclusive ?? "");
const reportKind = computed(() => selection.value?.kind);
const reportKey = computed(() => `${selection.value?.month.from ?? ""}:${selection.value?.kind ?? ""}`);

function chooseCell(month: Month, kind: Kind) {
  selection.value = { month, kind };
}

const liveness = useRequestLiveness();

async function load() {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  months.value = undefined;
  selection.value = undefined;
  try {
    const fetched = await reportRequest<Month[]>("/operations-reports/movement-trend");
    if (!liveness.isCurrent(turn)) return;
    months.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取业务趋势。请检查当前权限后重试。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">最近六个月业务趋势</h3>
    </div>
    <div class="fams-inline">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">查询六个月趋势</button>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询</p>
    <template v-if="months">
      <p>按 UTC 月统计。入库、领用和调拨按实物记录计数，报修和报废按单计数。</p>
      <div class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">月份</th><th v-for="label in kinds" :key="label" scope="col">{{ label }}</th></tr></thead>
        <tbody>
          <tr v-for="row in monthRows" :key="row.from">
            <th scope="row" data-column-kind="identity">{{ row.monthText }}</th>
            <td v-for="cell in row.cells" :key="cell.kind">
              <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :aria-label="cell.ariaLabel"
                :aria-pressed="cell.pressed" @click="chooseCell(row.month, cell.kind)">
                <meter aria-hidden="true" :max="cell.maxCount" :value="cell.count"></meter>
                {{ cell.count }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
    </template>
    <AssetMovementReport v-if="selection" :key="reportKey"
      :from="reportFrom" :to-exclusive="reportToExclusive" :initial-kind="reportKind" />
  </section>
</template>
