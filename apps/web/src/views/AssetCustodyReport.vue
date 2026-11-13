<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import AssetDistribution from "./AssetDistribution.vue";
import { useSessionStore } from "@/auth/sessionStore";

interface AssetRow {
  assetId: string; assetCode: string; name: string; lifecycle: string; exception: string;
  custody: string | null; organization: string | null; responsible: string | null;
  warehouse: string | null; location: string | null; lastInventory: string | null;
}
interface Page { items: AssetRow[]; nextBefore: string | null }
const props = defineProps<{ lifecycleLabels: Record<string, string>; exceptionLabels: Record<string, string> }>();
const filters = ref({ organizationId: "", categoryId: "", responsibleId: "", warehouseId: "", locationId: "",
  custody: "", inventoryBefore: "" });
const query = computed(() => new URLSearchParams(
  Object.entries(filters.value).filter(([, value]) => value !== "")).toString());
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const rows = ref<AssetRow[]>([]);
const next = ref<string | null>(null);
const applied = ref<string | null>(null);
const before = ref<string | null>(null);
const history = ref<(string | null)[]>([]);
const busy = ref(false);
const error = ref("");
let generation = 0;

async function load(cursor: string | null = null, movement = 0) {
  const request = ++generation;
  const selection = query.value;
  const params = new URLSearchParams(selection);
  if (cursor) params.set("before", cursor);
  busy.value = true;
  error.value = "";
  try {
    const response = await fetch(`/api/v1/operations-reports/assets?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    const page = await response.json() as Page;
    if (request !== generation) return;
    if (movement > 0) history.value.push(before.value);
    else if (movement < 0) history.value.pop();
    else history.value = [];
    before.value = cursor;
    rows.value = page.items;
    next.value = page.nextBefore;
    applied.value = selection;
  } catch {
    if (request !== generation) return;
    rows.value = [];
    applied.value = null;
    error.value = "无法读取保管报表，请核对筛选条件或权限。";
  } finally {
    if (request === generation) busy.value = false;
  }
}

function selectDistribution(field: "organizationId" | "categoryId", id: string) {
  filters.value = { organizationId: "", categoryId: "", responsibleId: "", warehouseId: "", locationId: "",
    custody: "", inventoryBefore: "", [field]: id };
  void load();
}

watch(identity, () => {
  generation++;
  applied.value = null;
  rows.value = [];
  busy.value = false;
  error.value = "";
}, { flush: "sync" });
onBeforeUnmount(() => generation++);
</script>

<template>
  <section>
    <AssetDistribution :key="identity" @select="selectDistribution" />
    <h3>资产保管与未盘点清单</h3>
    <form class="actions" @submit.prevent="load()">
      <label>使用部门编号（0 表示无使用部门） <input v-model.trim="filters.organizationId" inputmode="numeric" pattern="0|[1-9][0-9]*"></label>
      <label>分类编号（0 表示未分类） <input v-model.trim="filters.categoryId" inputmode="numeric" pattern="0|[1-9][0-9]*"></label>
      <label>责任人员编号 <input v-model.trim="filters.responsibleId" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>当前仓库编号 <input v-model.trim="filters.warehouseId" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>当前位置编号 <input v-model.trim="filters.locationId" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>保管方式
        <select v-model="filters.custody">
          <option value="">全部</option>
          <option value="STORAGE">仓库存放</option>
          <option value="PERSONAL">个人保管</option>
          <option value="SHARED">部门公用</option>
        </select>
      </label>
      <label>在指定日期前未盘点（含从未盘点） <input v-model="filters.inventoryBefore" type="date"></label>
      <button type="submit" :disabled="busy">查询保管清单</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="applied === query && !busy">
      <a :href="`/api/v1/operations-reports/assets.csv?${applied}`">下载当前筛选的保管清单 CSV</a>
      <p v-if="!rows.length">没有符合条件的有效资产。</p>
      <table v-else>
        <thead><tr><th>资产</th><th>状态</th><th>使用部门</th><th>责任人</th><th>仓库与位置</th><th>最近盘点</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.assetId">
            <td><router-link :to="{ path: '/assets', query: { assetId: row.assetId, assetCode: row.assetCode } }">
              {{ row.assetCode }} · {{ row.name }}
            </router-link></td>
            <td>{{ props.lifecycleLabels[row.lifecycle] }} · {{ props.exceptionLabels[row.exception] }}</td>
            <td>{{ row.organization ?? "—" }}</td><td>{{ row.responsible ?? "—" }}</td>
            <td>{{ [row.warehouse, row.location].filter(Boolean).join(" · ") || "—" }}</td>
            <td>{{ row.lastInventory ?? "从未盘点" }}</td>
          </tr>
        </tbody>
      </table>
      <div class="actions">
        <button :disabled="history.length === 0" @click="load(history.at(-1) ?? null, -1)">上一页</button>
        <button :disabled="next === null" @click="load(next, 1)">下一页</button>
      </div>
    </div>
  </section>
</template>
