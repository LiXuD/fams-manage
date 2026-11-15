<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

const props = defineProps<{ from: string; toExclusive: string }>();
const kind = shallowRef<"INTERNAL" | "FACTORY">("INTERNAL");
const assetId = shallowRef("");
const statuses: Record<string, string> = {
  REPAIR_ACCEPTED_IN_PLACE: "就地受理", REPAIR_ACCEPTED_AWAY: "移交受理",
  DISPATCHED: "已发出", RECEIVED: "已实收",
};
interface Row {
  factId: string;
  repairId: string;
  assetCode: string;
  occurredAt: string;
  status: string;
  actualCost: number | null;
}
interface Page {
  totals: { count: number; confirmedCostCount: number; actualCost: number | null };
  items: Row[];
  nextBefore: string | null;
}
interface RepairRow extends Row { statusText: string; costText: string }
const query = computed(() => {
  const params = new URLSearchParams({ kind: kind.value, from: props.from, toExclusive: props.toExclusive });
  if (assetId.value) params.set("assetId", assetId.value);
  return params.toString();
});
const applied = shallowRef("");
const result = ref<Page>();
const visible = computed(() => applied.value === query.value ? result.value : undefined);
const busy = shallowRef(false);
const error = shallowRef("");
const showFactory = computed(() => kind.value === "FACTORY");
const csvHref = computed(() => `/api/v1/operations-reports/repair-operations.csv?${applied.value}`);
const hasNoRows = computed(() => visible.value?.items.length === 0);
const totalsText = computed(() => `记录 ${visible.value?.totals.count ?? 0} 条`);
const factoryTotalsText = computed(() => `费用已确认 ${visible.value?.totals.confirmedCostCount ?? 0} 轮 · 已确认费用合计 ${money(visible.value?.totals.actualCost ?? null)}`);
const repairRows = computed<RepairRow[]>(() => (visible.value?.items ?? []).map((row) => ({
  ...row,
  statusText: statuses[row.status] ?? row.status,
  costText: money(row.actualCost),
})));

const liveness = useRequestLiveness();

async function load(before?: string) {
  const turn = liveness.begin();
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  if (before) params.set("before", before);
  result.value = undefined;
  busy.value = true;
  error.value = "";
  try {
    const fetched = await reportRequest<Page>(`/operations-reports/repair-operations?${params}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取维修报表。请检查上方日期范围、资产编号和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

function money(cost: number | null) {
  return cost === null ? "未确认" : cost.toFixed(2);
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">维修办理与返厂费用</h3>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="repair-kind">办理方式</label>
        <select id="repair-kind" class="fams-control" v-model="kind">
          <option value="INTERNAL">内部维修受理</option>
          <option value="FACTORY">返厂发出轮次</option>
        </select>
      </div>
      <div class="fams-field">
        <label for="repair-asset-id">资产 ID</label>
        <input id="repair-asset-id" class="fams-control" v-model.trim="assetId" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询维修报表</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <p>{{ totalsText }}<template v-if="showFactory"> · {{ factoryTotalsText }}</template></p>
      <a class="fams-button" data-kind="link" :href="csvHref">下载维修报表 CSV</a>
      <p v-if="hasNoRows">没有符合条件的维修记录。</p>
      <div v-else class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">资产与维修详情</th><th scope="col">发生时间</th><th scope="col">办理情况</th>
          <th v-if="showFactory" scope="col">已确认费用</th></tr></thead>
        <tbody>
          <tr v-for="row in repairRows" :key="row.factId">
            <td><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'repair-detail', params: { id: row.repairId } }">{{ row.assetCode }}</router-link></td>
            <td>{{ row.occurredAt }}</td><td>{{ row.statusText }}</td>
            <td v-if="showFactory">{{ row.costText }}</td>
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
