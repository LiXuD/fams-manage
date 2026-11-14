<script setup lang="ts">
import { computed, ref } from "vue";

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

const businessRoutes: Record<string, string> = { REPAIR: "repairs", LOSS: "loss-reports", SCRAP: "scrap-requests" };
const assetId = ref("");
const eventType = ref("");
const applied = ref("");
const result = ref<Page>();
const busy = ref(false);
const error = ref("");
const query = computed(() => {
  const params = new URLSearchParams({ assetId: assetId.value });
  if (eventType.value) params.set("eventType", eventType.value);
  return params.toString();
});
const visible = computed(() => applied.value === query.value ? result.value : undefined);

async function load(before?: Cursor) {
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
    const response = await fetch(`/api/v1/operations-reports/asset-timeline?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Page;
  } catch {
    error.value = "无法读取资产履历。请检查筛选条件和当前权限。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>资产履历</h3>
    <form class="actions" @submit.prevent="load()">
      <label>履历资产 ID <input v-model.trim="assetId" required inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>事件类型代码 <input v-model.trim="eventType" maxlength="80" pattern="[A-Z][A-Z0-9_]{0,79}"></label>
      <button :disabled="busy" type="submit">查询资产履历</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <a :href="`/api/v1/operations-reports/asset-timeline.csv?${applied}`">下载资产履历 CSV</a>
      <p v-if="visible.items.length === 0">没有符合条件的履历事件。</p>
      <table v-else>
        <thead><tr><th scope="col">资产</th><th scope="col">发生时间</th><th scope="col">事件</th>
          <th scope="col">来源单据</th><th scope="col">摘要</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.eventId">
            <td><router-link :to="{ name: 'assets', query: { assetId, assetCode: row.assetCode } }">
              {{ row.assetCode }}</router-link></td>
            <td>{{ row.occurredAt }}</td><td>{{ row.eventType }}</td>
            <td>
              <router-link v-if="businessRoutes[row.businessType]"
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
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
    </div>
  </section>
</template>
