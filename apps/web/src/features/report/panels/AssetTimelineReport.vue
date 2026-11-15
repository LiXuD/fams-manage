<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

interface Cursor { occurredAt: string; eventId: string }
interface Event extends Cursor {
  assetCode: string;
  eventType: string;
  businessType: string;
  businessId: string;
  factoryRoundId: string | null;
  summary: string;
}
interface Page { items: Event[]; nextBefore: Cursor | null }

const businessRoutes: Record<string, string> = { REPAIR: "repair-detail", LOSS: "loss-reports", SCRAP: "scrap-requests" };
const assetId = shallowRef("");
const eventType = shallowRef("");
const applied = shallowRef("");
const result = ref<Page>();
const busy = shallowRef(false);
const error = shallowRef("");
const query = computed(() => {
  const params = new URLSearchParams({ assetId: assetId.value });
  if (eventType.value) params.set("eventType", eventType.value);
  return params.toString();
});
const visible = computed(() => applied.value === query.value ? result.value : undefined);
const csvHref = computed(() => `/api/v1/operations-reports/asset-timeline.csv?${applied.value}`);
const hasNoRows = computed(() => visible.value?.items.length === 0);

const liveness = useRequestLiveness();

async function load(before?: Cursor) {
  const turn = liveness.begin();
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  result.value = undefined;
  busy.value = true;
  error.value = "";
  if (before) {
    params.set("beforeAt", before.occurredAt);
    params.set("beforeId", before.eventId);
  }
  try {
    const fetched = await reportRequest<Page>(`/operations-reports/asset-timeline?${params}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取资产履历。请检查筛选条件和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">资产履历</h3>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="timeline-asset">履历资产 ID</label>
        <input id="timeline-asset" class="fams-control" v-model.trim="assetId" required inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="timeline-event-type">事件类型代码</label>
        <input id="timeline-event-type" class="fams-control" v-model.trim="eventType" maxlength="80" pattern="[A-Z][A-Z0-9_]{0,79}">
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询资产履历</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <a class="fams-button" data-kind="link" :href="csvHref">下载资产履历 CSV</a>
      <p v-if="hasNoRows">没有符合条件的履历事件。</p>
      <div v-else class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">资产</th><th scope="col">发生时间</th><th scope="col">事件</th>
          <th scope="col">来源单据</th><th scope="col">摘要</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.eventId">
            <th scope="row" data-column-kind="identity"><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'assets', query: { assetId, assetCode: row.assetCode } }">
              {{ row.assetCode }}</router-link></th>
            <td>{{ row.occurredAt }}</td><td>{{ row.eventType }}</td>
            <td>
              <router-link v-if="businessRoutes[row.businessType]" class="fams-button" data-kind="link" data-size="sm"
                :to="{ name: businessRoutes[row.businessType], params: { id: row.businessId } }">
                {{ row.businessType }} {{ row.businessId }}
              </router-link>
              <template v-else>{{ row.businessType }} {{ row.businessId }}</template>
              <span v-if="row.factoryRoundId"> · 返厂轮次 {{ row.factoryRoundId }}</span>
            </td>
            <td>{{ row.summary }}</td>
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
