<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";


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

const kind = shallowRef<keyof typeof kinds>(props.initialKind ?? "INBOUND");
const assetId = shallowRef("");
const filters = computed(() => {
  const params = new URLSearchParams({ kind: kind.value, from: props.from, toExclusive: props.toExclusive });
  if (assetId.value) params.set("assetId", assetId.value);
  return params.toString();
});
const applied = shallowRef("");
const result = ref<Page>();
const busy = shallowRef(false);
const error = shallowRef("");
const visible = computed(() => applied.value === filters.value ? result.value : undefined);
const csvHref = computed(() => `/api/v1/operations-reports/movements.csv?${applied.value}`);
const hasNoRows = computed(() => visible.value?.items.length === 0);

const liveness = useRequestLiveness();

async function load(cursor?: Cursor) {
  const turn = liveness.begin();
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
    const fetched = await reportRequest<Page>(`/operations-reports/movements?${params}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取业务流水。请检查统计日期、资产 ID 和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
onMounted(() => { if (props.initialKind) void load(); });
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">资产业务流水</h3>
      <p class="fams-panel-subtitle">UTC 日期范围 {{ from }} 至 {{ toExclusive }}（不含当日）。归还验收显示正常、损坏和缺失。</p>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="movement-kind">业务类型</label>
        <select id="movement-kind" class="fams-control" v-model="kind">
          <option v-for="(label, value) in kinds" :key="value" :value="value">{{ label }}</option>
        </select>
      </div>
      <div class="fams-field">
        <label for="movement-asset-id">资产 ID</label>
        <input id="movement-asset-id" class="fams-control" v-model.trim="assetId" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询流水</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询流水</p>
    <div v-if="visible">
      <a class="fams-button" data-kind="link" :href="csvHref">下载流水 CSV</a>
      <p v-if="hasNoRows">该范围内没有已落库的流水。</p>
      <div v-else class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">资产</th><th scope="col">单据 ID</th><th scope="col">发生时间（UTC）</th>
          <th scope="col">结果</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.factId">
            <th scope="row" data-column-kind="identity"><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ path: '/assets', query: { assetId: row.assetId, assetCode: row.assetCode } }">
              {{ row.assetCode }}
            </router-link></th>
            <td>{{ row.businessId }}</td><td>{{ row.occurredAt }}</td><td>{{ outcomes[row.outcome] }}</td>
          </tr>
        </tbody>
      </table>
      </div>
      <div class="fams-pagination-actions">
        <button v-if="visible.nextBefore" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(visible.nextBefore)">
        下一页
      </button>
      </div>
    </div>
  </section>
</template>
