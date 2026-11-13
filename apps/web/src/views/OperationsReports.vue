<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
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

interface Summary {
  calculatedAt: string;
  inventory: { sealedTasks: number; expected: number; accepted: number; notObserved: number };
  openingBatches: { status: string; count: number }[];
  currentAssets: { lifecycle: string; exception: string; count: number }[];
}

const lifecycleLabels: Record<string, string> = {
  IN_STOCK: "在库", IN_USE: "在用", BORROWED: "借出", UNDER_REPAIR: "维修中",
  FACTORY_REPAIR: "返厂维修", PENDING_SCRAP: "待报废",
};
const exceptionLabels: Record<string, string> = { NORMAL: "正常", SEARCHING: "查找中", LOST: "已遗失" };
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const today = new Date().toISOString().slice(0, 10);
const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
const from = ref(today.slice(0, 8) + "01");
const toExclusive = ref(tomorrow);
const query = computed(() => new URLSearchParams({ from: from.value, toExclusive: toExclusive.value }).toString());
const result = ref<Summary>();
const loadedQuery = ref("");
const error = ref("");
const busy = ref(false);
const sampleCount = ref<number>();
const missingCount = ref<number>();
const traceability = computed(() => {
  const total = sampleCount.value;
  const missing = missingCount.value;
  if (!Number.isSafeInteger(total) || !Number.isSafeInteger(missing)
      || total === undefined || missing === undefined || total <= 0 || missing < 0 || missing > total) {
    return "样本不足或数量无效，无法评价";
  }
  return `${total - missing}/${total} = ${((total - missing) / total * 100).toFixed(1)}%`;
});
let generation = 0;

async function load() {
  const current = ++generation;
  const requested = query.value;
  result.value = undefined;
  error.value = "";
  busy.value = true;
  try {
    const response = await fetch(`/api/v1/operations-reports?${requested}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    const value = await response.json() as Summary;
    if (generation !== current) return;
    result.value = value;
    loadedQuery.value = requested;
  } catch {
    if (generation === current) error.value = "无法读取报表。请检查日期范围和当前权限。";
  } finally {
    if (generation === current) busy.value = false;
  }
}

watch(identity, () => {
  generation++;
  result.value = undefined;
  error.value = "";
  busy.value = false;
  sampleCount.value = undefined;
  missingCount.value = undefined;
  if (session.user?.roles.includes("ASSET_ADMIN")) void load();
}, { immediate: true, flush: "sync" });
</script>

<template>
  <section>
    <h2>资产运营报表</h2>
    <form class="actions" @submit.prevent="load">
      <label>开始日期（UTC） <input v-model="from" required type="date"></label>
      <label>结束日期（UTC，不含当日） <input v-model="toExclusive" required type="date"></label>
      <button :disabled="busy" type="submit">查询</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="result && query === loadedQuery">
      <h3>已封存盘点</h3>
      <p>任务 {{ result.inventory.sealedTasks }} 项 · 已采信 {{ result.inventory.accepted }} /
        应盘 {{ result.inventory.expected }} · 未盘 {{ result.inventory.notObserved }}</p>
      <p>统计范围为窗口内已封存报告。未盘计入应盘，草稿、取消、终止和未封存任务排除。</p>
      <div class="actions">
        <router-link to="/inventory-tasks">查看盘点任务</router-link>
        <a :href="`/api/v1/operations-reports/inventory.csv?${loadedQuery}`">下载盘点 CSV</a>
      </div>
      <h3>期初批次</h3>
      <p v-if="result.openingBatches.length === 0">该时段没有仍有效的发布或整批撤销记录。</p>
      <ul v-else>
        <li v-for="row in result.openingBatches" :key="row.status">
          {{ row.status === "ASSET_IMPORT_PUBLISHED" ? "已发布且仍有效" : "已整批撤销" }} {{ row.count }} 批
        </li>
      </ul>
      <router-link to="/asset-import-reviews">查看期初核验</router-link>
      <h3>当前有效资产</h3>
      <ul>
        <li v-for="row in result.currentAssets" :key="row.lifecycle + row.exception">
          {{ lifecycleLabels[row.lifecycle] }} · {{ exceptionLabels[row.exception] }} · {{ row.count }} 件
        </li>
      </ul>
      <router-link to="/assets">查看资产台账</router-link>
      <p>数据时间 {{ result.calculatedAt }}</p>
    </div>
    <AssetCustodyReport :lifecycle-labels="lifecycleLabels" :exception-labels="exceptionLabels" />
    <AssetMovementTrend :key="'movement-trend:' + identity" />
    <AssetMovementReport :key="identity" :from="from" :to-exclusive="toExclusive" />
    <OverdueLoanReport :key="identity" />
    <LossOperationsReport :key="identity" />
    <ScrapDispositionReport :key="identity" />
    <RepairOperationsReport :key="'repair-operations:' + identity" :from="from" :to-exclusive="toExclusive" />
    <AssetTimelineReport :key="'asset-timeline:' + identity" />
    <InventoryLateReport :key="'inventory-late:' + identity" />
    <BusinessExceptionReport :key="'business-exceptions:' + identity" />
    <InboundTimingReport :key="'inbound-timing:' + identity" />
    <details>
      <summary>抽样留痕率核算</summary>
      <p>请按抽查范围和选样方法，用线下证据核实实际业务。同一笔业务只计一次，待核实线索另列。</p>
      <div class="actions">
        <label>核实样本数 <input v-model.number="sampleCount" type="number" min="0" step="1"></label>
        <label>原样本漏记数 <input v-model.number="missingCount" type="number" min="0" step="1"></label>
      </div>
      <p>本次样本观察结果 <output>{{ traceability }}</output></p>
    </details>
  </section>
</template>
