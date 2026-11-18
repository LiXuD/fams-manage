<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";
import SealedInventoryTasks from "./SealedInventoryTasks.vue";
import InventoryProgressReport from "./InventoryProgressReport.vue";
import InventoryTraceabilityReport from "./InventoryTraceabilityReport.vue";
import ExceptionDiscoveryReport from "./ExceptionDiscoveryReport.vue";
import TagCoverageReport from "./TagCoverageReport.vue";
import AssetCustodyReport from "./AssetCustodyReport.vue";
import AssetMovementTrend from "./AssetMovementTrend.vue";
import AssetMovementReport from "./AssetMovementReport.vue";
import OverdueLoanReport from "./OverdueLoanReport.vue";
import LossOperationsReport from "./LossOperationsReport.vue";
import ScrapDispositionReport from "./ScrapDispositionReport.vue";
import InboundTimingReport from "./InboundTimingReport.vue";
import BusinessExceptionReport from "./BusinessExceptionReport.vue";
import InventoryLateReport from "./InventoryLateReport.vue";
import AssetTimelineReport from "./AssetTimelineReport.vue";
import RepairOperationsReport from "./RepairOperationsReport.vue";
import CoreBusinessSamplingReport from "./CoreBusinessSamplingReport.vue";

interface Summary {
  calculatedAt: string;
  inventory: { sealedTasks: number; expected: number; accepted: number; notObserved: number };
  openingBatches: { status: string; count: number }[];
  currentAssets: { lifecycle: string; exception: string; count: number }[];
  importErrorQuality?: { locatedRows: number; failedRows: number };
}
interface BatchRow { key: string; statusText: string; countText: string }
interface AssetGroupRow { key: string; label: string; lifecycle: string; exception: string }

const lifecycleLabels: Record<string, string> = {
  IN_STOCK: "在库", IN_USE: "在用", BORROWED: "借出", UNDER_REPAIR: "维修中",
  FACTORY_REPAIR: "返厂维修", PENDING_SCRAP: "待报废",
};
const exceptionLabels: Record<string, string> = { NORMAL: "正常", SEARCHING: "查找中", LOST: "已遗失" };
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const today = new Date().toISOString().slice(0, 10);
const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
const from = shallowRef(today.slice(0, 8) + "01");
const toExclusive = shallowRef(tomorrow);
const query = computed(() => new URLSearchParams({ from: from.value, toExclusive: toExclusive.value }).toString());
const result = ref<Summary>();
const custodyReport = ref<InstanceType<typeof AssetCustodyReport>>();
const effectiveAssetCount = computed(() => result.value?.currentAssets.reduce((total, row) => total + row.count, 0) ?? 0);
const loadedQuery = shallowRef("");
const error = shallowRef("");
const busy = shallowRef(false);
const liveness = useRequestLiveness(() => [identity.value]);
const showSummary = computed(() => !!result.value && query.value === loadedQuery.value);
const inventoryCsvHref = computed(() => `/api/v1/operations-reports/inventory.csv?${loadedQuery.value}`);
const sealedTasksKey = computed(() => identity.value + loadedQuery.value);
const reportWindowKey = sealedTasksKey;
const trendKey = computed(() => `movement-trend:${identity.value}`);
const repairKey = computed(() => `repair-operations:${identity.value}`);
const timelineKey = computed(() => `asset-timeline:${identity.value}`);
const lateKey = computed(() => `inventory-late:${identity.value}`);
const exceptionsKey = computed(() => `business-exceptions:${identity.value}`);
const timingKey = computed(() => `inbound-timing:${identity.value}`);
const hasNoBatches = computed(() => result.value?.openingBatches.length === 0);
const batchRows = computed<BatchRow[]>(() => (result.value?.openingBatches ?? []).map((row) => ({
  key: row.status,
  statusText: row.status === "ASSET_IMPORT_PUBLISHED" ? "已发布且仍有效" : "已整批撤销",
  countText: `${row.count} 批`,
})));
const assetGroupRows = computed<AssetGroupRow[]>(() => (result.value?.currentAssets ?? []).map((row) => ({
  key: row.lifecycle + row.exception,
  label: `${lifecycleLabels[row.lifecycle]} · ${exceptionLabels[row.exception]} · ${row.count} 件`,
  lifecycle: row.lifecycle,
  exception: row.exception,
})));
const inventorySummaryText = computed(() => `任务 ${result.value?.inventory.sealedTasks ?? 0} 项 · 已采信 ${result.value?.inventory.accepted ?? 0} /`
  + ` 应盘 ${result.value?.inventory.expected ?? 0} · 未盘 ${result.value?.inventory.notObserved ?? 0}`);
const totalCountText = computed(() => `资产总数 ${effectiveAssetCount.value} 件`);
const dataTimeText = computed(() => `数据时间 ${result.value?.calculatedAt ?? ""}`);
const importErrorQuality = computed(() => result.value?.importErrorQuality);
const importErrorQualityText = computed(() => {
  const quality = result.value?.importErrorQuality;
  if (!quality) return "";
  return `${quality.locatedRows} / ${quality.failedRows} 行 · `
    + `${(quality.locatedRows / quality.failedRows * 100).toFixed(1)}%`;
});

async function load() {
  const turn = liveness.begin();
  const requested = query.value;
  result.value = undefined;
  error.value = "";
  busy.value = true;
  try {
    const value = await reportRequest<Summary>(`/operations-reports?${requested}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = value;
    loadedQuery.value = requested;
  } catch {
    if (liveness.isCurrent(turn)) error.value = "无法读取报表。请检查日期范围和当前权限。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

function selectAllCustody() { custodyReport.value?.select({}); }
function selectCustody(row: AssetGroupRow) { custodyReport.value?.select({ lifecycle: row.lifecycle, exception: row.exception }); }

watch(identity, () => {
  liveness.invalidate();
  result.value = undefined;
  error.value = "";
  busy.value = false;
  if (session.user?.roles.includes("ASSET_ADMIN")) void load();
}, { immediate: true, flush: "sync" });
</script>

<template>
  <section class="fams-panel fams-stack" aria-label="资产运营报表">
    <div class="fams-panel-head">
      <h2 class="fams-panel-title">资产运营报表</h2>
    </div>
    <InventoryProgressReport :key="identity" />
    <TagCoverageReport :key="identity" />
    <form class="fams-field-row" @submit.prevent="load">
      <div class="fams-field">
        <label for="report-from">开始日期（UTC）</label>
        <input id="report-from" class="fams-control" v-model="from" required type="date">
      </div>
      <div class="fams-field">
        <label for="report-to-exclusive">结束日期（UTC，不含当日）</label>
        <input id="report-to-exclusive" class="fams-control" v-model="toExclusive" required type="date">
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="showSummary">
      <InventoryTraceabilityReport :key="reportWindowKey" :query="loadedQuery" />
      <ExceptionDiscoveryReport :key="reportWindowKey" :query="loadedQuery" />
      <h3>已封存盘点</h3>
      <p>{{ inventorySummaryText }}</p>
      <p>统计范围为窗口内已封存报告。未盘计入应盘，草稿、取消、终止和未封存任务排除。</p>
      <SealedInventoryTasks :key="sealedTasksKey" :query="loadedQuery" />
      <div class="fams-inline">
        <a class="fams-button" data-kind="link" :href="inventoryCsvHref">下载盘点 CSV</a>
      </div>
      <h3>期初批次</h3>
      <p v-if="hasNoBatches">该时段没有仍有效的发布或整批撤销记录。</p>
      <ul v-else class="fams-timeline">
        <li v-for="row in batchRows" :key="row.key">{{ row.statusText }} {{ row.countText }}</li>
      </ul>
      <section v-if="importErrorQuality" class="fams-stack">
        <h3>导入错误可定位率</h3>
        <p v-if="importErrorQuality.failedRows">{{ importErrorQualityText }}</p>
        <p v-else>该时段没有预检失败行。</p>
        <p>按批次上传时间筛选，取各批次当前预检。通过行和文件级拒绝不计入分母。</p>
      </section>
      <router-link class="fams-button" data-kind="link" data-size="sm" to="/asset-import-reviews">查看期初核验</router-link>
      <h3>当前有效资产</h3>
      <div class="fams-inline">
        <button class="fams-button" data-kind="secondary" type="button" @click="selectAllCustody">{{ totalCountText }}</button>
      </div>
      <ul class="fams-timeline">
        <li v-for="row in assetGroupRows" :key="row.key">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="selectCustody(row)">
            {{ row.label }}
          </button>
        </li>
      </ul>
      <router-link class="fams-button" data-kind="link" data-size="sm" to="/assets">查看资产台账</router-link>
      <p>{{ dataTimeText }}</p>
    </div>
    <AssetCustodyReport ref="custodyReport" :lifecycle-labels="lifecycleLabels" :exception-labels="exceptionLabels" />
    <AssetMovementTrend :key="trendKey" />
    <AssetMovementReport :key="identity" :from="from" :to-exclusive="toExclusive" />
    <OverdueLoanReport :key="identity" />
    <LossOperationsReport :key="identity" />
    <ScrapDispositionReport :key="identity" />
    <RepairOperationsReport :key="repairKey" :from="from" :to-exclusive="toExclusive" />
    <AssetTimelineReport :key="timelineKey" />
    <InventoryLateReport :key="lateKey" />
    <BusinessExceptionReport :key="exceptionsKey" />
    <InboundTimingReport :key="timingKey" />
    <CoreBusinessSamplingReport :key="identity" :from="from" :to-exclusive="toExclusive" />
  </section>
</template>
