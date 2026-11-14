<script setup lang="ts">
import { computed, ref } from "vue";

const props = defineProps<{ from: string; toExclusive: string }>();
const kind = ref<"INTERNAL" | "FACTORY">("INTERNAL");
const assetId = ref("");
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
const query = computed(() => {
  const params = new URLSearchParams({ kind: kind.value, from: props.from, toExclusive: props.toExclusive });
  if (assetId.value) params.set("assetId", assetId.value);
  return params.toString();
});
const applied = ref("");
const result = ref<Page>();
const visible = computed(() => applied.value === query.value ? result.value : undefined);
const busy = ref(false);
const error = ref("");

async function load(before?: string) {
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  if (before) params.set("before", before);
  result.value = undefined;
  busy.value = true;
  error.value = "";
  try {
    const response = await fetch(`/api/v1/operations-reports/repair-operations?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Page;
  } catch {
    error.value = "无法读取维修报表。请检查上方日期范围、资产编号和当前权限。";
  } finally {
    busy.value = false;
  }
}

function money(cost: number | null) {
  return cost === null ? "未确认" : cost.toFixed(2);
}
</script>

<template>
  <section>
    <h3>维修办理与返厂费用</h3>
    <form class="actions" @submit.prevent="load()">
      <label>办理方式
        <select v-model="kind">
          <option value="INTERNAL">内部维修受理</option>
          <option value="FACTORY">返厂发出轮次</option>
        </select>
      </label>
      <label>资产 ID <input v-model.trim="assetId" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <button :disabled="busy" type="submit">查询维修报表</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <p>记录 {{ visible.totals.count }} 条
        <template v-if="kind === 'FACTORY'">
          · 费用已确认 {{ visible.totals.confirmedCostCount }} 轮 · 已确认费用合计 {{ money(visible.totals.actualCost) }}
        </template>
      </p>
      <a :href="`/api/v1/operations-reports/repair-operations.csv?${applied}`">下载维修报表 CSV</a>
      <p v-if="visible.items.length === 0">没有符合条件的维修记录。</p>
      <table v-else>
        <thead><tr><th scope="col">资产与维修详情</th><th scope="col">发生时间</th><th scope="col">办理情况</th>
          <th v-if="kind === 'FACTORY'" scope="col">已确认费用</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.factId">
            <td><router-link :to="{ name: 'repairs', params: { id: row.repairId } }">{{ row.assetCode }}</router-link></td>
            <td>{{ row.occurredAt }}</td><td>{{ statuses[row.status] ?? row.status }}</td>
            <td v-if="kind === 'FACTORY'">{{ money(row.actualCost) }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
    </div>
  </section>
</template>
