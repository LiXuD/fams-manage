<script setup lang="ts">
import { computed, onMounted, ref } from "vue";


const kinds = {
  INBOUND: "入库验收",
  REQUISITION: "领用签收",
  REQUISITION_RETURN: "领用退库验收",
  BORROW: "借用签收",
  BORROW_RETURN: "借用归还验收",
  TRANSFER: "调拨完成",
  REPAIR: "报修提交",
  SCRAP: "报废执行",
};
const props = defineProps<{ from: string; toExclusive: string; initialKind?: keyof typeof kinds }>();
const outcomes: Record<string, string> = {
  ACCEPTED: "已验收", RECEIVED: "已签收", PROXY_RECEIVED: "已代确认",
  NORMAL: "正常", DAMAGED: "损坏", MISSING: "缺失并转报失", COMPLETED: "已完成",
  SUBMITTED: "已提交", EXECUTED: "已执行",
};

interface Cursor { occurredAt: string; factId: string }
interface Movement {
  factId: string;
  businessId: string;
  assetId: string;
  assetCode: string;
  occurredAt: string;
  outcome: string;
}
interface Page { items: Movement[]; nextBefore: Cursor | null }

const kind = ref<keyof typeof kinds>(props.initialKind ?? "INBOUND");
const assetId = ref("");
const filters = computed(() => {
  const params = new URLSearchParams({ kind: kind.value, from: props.from, toExclusive: props.toExclusive });
  if (assetId.value) params.set("assetId", assetId.value);
  return params.toString();
});
const applied = ref("");
const result = ref<Page>();
const busy = ref(false);
const error = ref("");
const visible = computed(() => applied.value === filters.value && result.value);

async function load(cursor?: Cursor) {
  const params = new URLSearchParams(filters.value);
  applied.value = params.toString();
  result.value = undefined;
  busy.value = true;
  error.value = "";
  if (cursor) {
    params.set("beforeAt", cursor.occurredAt);
    params.set("beforeId", cursor.factId);
  }
  try {
    const response = await fetch(`/api/v1/operations-reports/movements?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Page;
  } catch {
    error.value = "无法读取业务流水。请检查统计日期、资产 ID 和当前权限。";
  } finally {
    busy.value = false;
  }
}
onMounted(() => { if (props.initialKind) void load(); });
</script>

<template>
  <section>
    <h3>资产业务流水</h3>
    <p>UTC 日期范围 {{ from }} 至 {{ toExclusive }}（不含当日）。归还验收显示正常、损坏和缺失。</p>
    <form class="actions" @submit.prevent="load()">
      <label>业务类型
        <select v-model="kind">
          <option v-for="(label, value) in kinds" :key="value" :value="value">{{ label }}</option>
        </select>
      </label>
      <label>资产 ID <input v-model.trim="assetId" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <button type="submit" :disabled="busy">查询流水</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询流水</p>
    <div v-if="visible">
      <a :href="`/api/v1/operations-reports/movements.csv?${applied}`">下载流水 CSV</a>
      <p v-if="visible.items.length === 0">该范围内没有已落库的流水。</p>
      <table v-else>
        <thead><tr><th scope="col">资产</th><th scope="col">单据 ID</th><th scope="col">发生时间（UTC）</th>
          <th scope="col">结果</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.factId">
            <td><router-link :to="{ path: '/assets', query: { assetId: row.assetId, assetCode: row.assetCode } }">
              {{ row.assetCode }}
            </router-link></td>
            <td>{{ row.businessId }}</td><td>{{ row.occurredAt }}</td><td>{{ outcomes[row.outcome] }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">
        下一页
      </button>
    </div>
  </section>
</template>
