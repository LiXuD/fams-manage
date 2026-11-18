<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import AssetDistribution from "./AssetDistribution.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

interface AssetRow {
  assetId: string; assetCode: string; name: string; lifecycle: string; exception: string;
  custody: string | null; organization: string | null; responsible: string | null;
  warehouse: string | null; location: string | null; lastInventory: string | null;
}
interface Page { items: AssetRow[]; nextBefore: string | null }
interface CustodyRow extends AssetRow {
  statusText: string; organizationText: string; responsibleText: string;
  placeText: string; lastInventoryText: string;
}
const props = defineProps<{ lifecycleLabels: Record<string, string>; exceptionLabels: Record<string, string> }>();
const emptyFilters = () => ({ organizationId: "", categoryId: "", responsibleId: "", warehouseId: "", locationId: "",
  custody: "", inventoryBefore: "", lifecycle: "", exception: "", custodyComplete: "" });
const filters = ref(emptyFilters());
const query = computed(() => new URLSearchParams(
  Object.entries(filters.value).filter(([, value]) => value !== "")).toString());
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const rows = ref<AssetRow[]>([]);
const next = shallowRef<string | null>(null);
const applied = shallowRef<string | null>(null);
const before = shallowRef<string | null>(null);
const history = ref<(string | null)[]>([]);
const busy = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
const csvHref = computed(() => `/api/v1/operations-reports/assets.csv?${applied.value ?? ""}`);
const showResults = computed(() => applied.value === query.value && !busy.value);
const hasNoRows = computed(() => !rows.value.length);
const prevDisabled = computed(() => history.value.length === 0);
const nextDisabled = computed(() => next.value === null);
const lastHistoryCursor = computed(() => history.value.at(-1) ?? null);
const displayRows = computed<CustodyRow[]>(() => rows.value.map((row) => ({
  ...row,
  statusText: `${props.lifecycleLabels[row.lifecycle]} · ${props.exceptionLabels[row.exception]}`,
  organizationText: row.organization ?? "—",
  responsibleText: row.responsible ?? "—",
  placeText: [row.warehouse, row.location].filter(Boolean).join(" · ") || "—",
  lastInventoryText: row.lastInventory ?? "从未盘点",
})));

async function load(cursor: string | null = null, movement = 0) {
  const turn = liveness.begin();
  const selection = query.value;
  const params = new URLSearchParams(selection);
  if (cursor) params.set("before", cursor);
  busy.value = true;
  error.value = "";
  try {
    const page = await reportRequest<Page>(`/operations-reports/assets?${params}`);
    if (!liveness.isCurrent(turn)) return;
    if (movement > 0) history.value.push(before.value);
    else if (movement < 0) history.value.pop();
    else history.value = [];
    before.value = cursor;
    rows.value = page.items;
    next.value = page.nextBefore;
    applied.value = selection;
  } catch {
    if (!liveness.isCurrent(turn)) return;
    rows.value = [];
    applied.value = null;
    error.value = "无法读取保管报表，请核对筛选条件或权限。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

function select(criteria: Partial<ReturnType<typeof emptyFilters>>) {
  filters.value = { ...emptyFilters(), ...criteria };
  void load();
}

defineExpose({ select });
watch(identity, () => {
  liveness.invalidate();
  applied.value = null;
  rows.value = [];
  busy.value = false;
  error.value = "";
}, { flush: "sync" });
</script>

<template>
  <section class="fams-panel fams-stack">
    <AssetDistribution :key="identity" @select="(field, id) => select({ [field]: id })" />
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">资产保管与未盘点清单</h3>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="custody-organization">使用部门编号（0 表示无使用部门）</label>
        <input id="custody-organization" class="fams-control" v-model.trim="filters.organizationId" inputmode="numeric" pattern="0|[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="custody-category">分类编号（0 表示未分类）</label>
        <input id="custody-category" class="fams-control" v-model.trim="filters.categoryId" inputmode="numeric" pattern="0|[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="custody-responsible">责任人员编号</label>
        <input id="custody-responsible" class="fams-control" v-model.trim="filters.responsibleId" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="custody-warehouse">当前仓库编号</label>
        <input id="custody-warehouse" class="fams-control" v-model.trim="filters.warehouseId" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="custody-location">当前位置编号</label>
        <input id="custody-location" class="fams-control" v-model.trim="filters.locationId" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="custody-lifecycle">保管状态</label>
        <select id="custody-lifecycle" class="fams-control" v-model="filters.lifecycle">
          <option value="">全部</option>
          <option v-for="(label, code) in lifecycleLabels" :key="code" :value="code">{{ label }}</option>
        </select>
      </div>
      <div class="fams-field">
        <label for="custody-exception">异常状态</label>
        <select id="custody-exception" class="fams-control" v-model="filters.exception">
          <option value="">全部</option>
          <option v-for="(label, code) in exceptionLabels" :key="code" :value="code">{{ label }}</option>
        </select>
      </div>
      <div class="fams-field">
        <label for="custody-method">保管方式</label>
        <select id="custody-method" class="fams-control" v-model="filters.custody">
          <option value="">全部</option>
          <option value="STORAGE">仓库存放</option>
          <option value="PERSONAL">个人保管</option>
          <option value="SHARED">部门公用</option>
        </select>
      </div>
      <div class="fams-field">
        <label for="custody-inventory-before">在指定日期前未盘点（含从未盘点）</label>
        <input id="custody-inventory-before" class="fams-control" v-model="filters.inventoryBefore" type="date">
      </div>
      <div class="fams-field">
        <label for="custody-complete">保管信息完整性</label>
        <select id="custody-complete" class="fams-control" v-model="filters.custodyComplete">
          <option value="">全部</option>
          <option value="true">完整</option>
          <option value="false">不完整</option>
        </select>
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询保管清单</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <div v-if="showResults">
      <a class="fams-button" data-kind="link" :href="csvHref">下载当前筛选的保管清单 CSV</a>
      <p v-if="hasNoRows">没有符合条件的有效资产。</p>
      <div v-else class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">资产</th><th scope="col">状态</th><th scope="col">使用部门</th><th scope="col">责任人</th><th scope="col">仓库与位置</th><th scope="col">最近盘点</th></tr></thead>
          <tbody>
            <tr v-for="row in displayRows" :key="row.assetId">
              <th scope="row" data-column-kind="identity"><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ path: '/assets', query: { assetId: row.assetId, assetCode: row.assetCode } }">
                {{ row.assetCode }} · {{ row.name }}
              </router-link></th>
              <td>{{ row.statusText }}</td>
              <td>{{ row.organizationText }}</td><td>{{ row.responsibleText }}</td>
              <td>{{ row.placeText }}</td>
              <td>{{ row.lastInventoryText }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="fams-pagination-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="load(lastHistoryCursor, -1)">上一页</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="load(next, 1)">下一页</button>
      </div>
    </div>
  </section>
</template>
