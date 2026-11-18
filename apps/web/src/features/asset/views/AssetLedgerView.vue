<script setup lang="ts">
import { computed, shallowRef } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { usePagedResourceList } from "@/composables/usePagedResourceList";
import { assetLedgerCsv, cardStageLabels, custodyLabel, lifecycleLabels, listAssets,
  type AssetCard, type AssetLedgerFilters } from "@/features/asset/api";

const session = useSessionStore();
const route = useRoute();
const router = useRouter();
const entryQuery = route?.query ?? {};
const code = shallowRef(typeof entryQuery.assetCode === "string" ? entryQuery.assetCode : "");
const nameFilter = shallowRef("");
const overdueFilter = shallowRef("");
const currentProcessorId = shallowRef("");
const businessReference = shallowRef("");
const inventoryBefore = shallowRef("");
const appliedFilters = shallowRef<AssetLedgerFilters & { code: string; name: string; accountId: string }>();
const exporting = shallowRef(false);
const exportError = shallowRef("");

const canWrite = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;
const canExport = computed(() => session.permissions?.fieldPermissions.includes("BULK_EXPORT") ?? false);
const ASSET_ID = /^[1-9][0-9]*$/;

const { rows, total, page, error: requestError, restart, pages, busy } = usePagedResourceList<AssetCard>(
  async (page) => {
    const filters = {
      code: code.value.trim(), name: nameFilter.value.trim(),
      borrowOverdue: overdueFilter.value === "" ? undefined : overdueFilter.value === "true",
      currentProcessorId: currentProcessorId.value.trim(), businessReference: businessReference.value.trim(),
      inventoryBefore: inventoryBefore.value,
    };
    const accountId = session.user?.id;
    const result = await listAssets(page, filters.code, filters.name, filters);
    appliedFilters.value = accountId ? { ...filters, accountId } : undefined;
    return result;
  },
  { problem: (cause) => cause instanceof Error ? cause.message : "台账加载失败" });
const error = computed(() => requestError.value || exportError.value);

async function exportCurrent(): Promise<void> {
  const filters = appliedFilters.value;
  if (!canExport.value || !filters || busy.value || exporting.value || filters.accountId !== session.user?.id) return;
  exporting.value = true;
  exportError.value = "";
  try {
    const file = await assetLedgerCsv(filters.code, filters.name, filters);
    if (filters.accountId !== session.user?.id || !canExport.value) return;
    const url = URL.createObjectURL(file);
    const link = document.createElement("a");
    link.href = url;
    link.download = "asset-ledger.csv";
    try { link.click(); }
    finally { window.setTimeout(() => URL.revokeObjectURL(url), 0); }
  } catch (cause) {
    if (filters.accountId === session.user?.id) {
      exportError.value = cause instanceof Error ? cause.message : "资产台账导出失败。";
    }
  } finally { exporting.value = false; }
}

const displayRows = computed(() => rows.value.map((asset) => ({
  ...asset,
  custodyText: custodyLabel(asset),
})));
const hasNoRows = computed(() => rows.value.length === 0);
const prevDisabled = computed(() => page.value <= 1);
const nextDisabled = computed(() => page.value * 20 >= total.value);

// 深链入口：报表下钻带 assetId 直接进详情，工作台快捷入口带 action=create 直接进建卡。
if (router) {
  const assetId = typeof entryQuery.assetId === "string" ? entryQuery.assetId : "";
  if (ASSET_ID.test(assetId)) void router.replace({ name: "asset-detail", params: { id: assetId } });
  else if (canWrite && entryQuery.action === "create") void router.replace({ name: "asset-new" });
}
</script>

<template>
  <AppChrome>
    <template v-if="canWrite" #actions>
      <router-link class="fams-button" data-kind="primary" data-size="sm" to="/assets/new">单个建卡</router-link>
    </template>

    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section class="fams-panel fams-stack" aria-label="台账筛选与列表">
      <form class="fams-field-row" @submit.prevent="restart()">
        <div class="fams-field">
          <label for="ledger-code">资产编码</label>
          <input id="ledger-code" class="fams-control" v-model="code" placeholder="编码包含" />
        </div>
        <div class="fams-field">
          <label for="ledger-name">资产名称</label>
          <input id="ledger-name" class="fams-control" v-model="nameFilter" placeholder="名称包含" />
        </div>
        <div class="fams-field">
          <label for="ledger-processor">当前处理库管账号 ID</label>
          <input id="ledger-processor" class="fams-control" v-model="currentProcessorId" inputmode="numeric" maxlength="19" />
        </div>
        <div class="fams-field">
          <label for="ledger-business-ref">业务单号</label>
          <input id="ledger-business-ref" class="fams-control" v-model="businessReference" maxlength="128" />
        </div>
        <div class="fams-field">
          <label for="ledger-inventory-before">在指定日期前未盘点（含从未盘点）</label>
          <input id="ledger-inventory-before" class="fams-control" v-model="inventoryBefore" type="date" />
        </div>
        <div class="fams-field">
          <label for="ledger-overdue">借用逾期</label>
          <select id="ledger-overdue" class="fams-control" v-model="overdueFilter">
            <option value="">全部</option>
            <option value="true">已逾期</option>
            <option value="false">无逾期</option>
          </select>
        </div>
        <button class="fams-button" data-kind="secondary" type="submit">查询</button>
        <button v-if="canExport" class="fams-button" data-kind="secondary" type="button"
          :disabled="busy || exporting || !appliedFilters" @click="exportCurrent">导出当前查询结果 CSV</button>
      </form>

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead>
            <tr>
              <th scope="col" data-column-kind="identity">资产编码</th>
              <th scope="col">名称</th>
              <th scope="col">型号</th>
              <th scope="col">生命周期</th>
              <th scope="col">建卡阶段</th>
              <th scope="col">正式保管</th>
              <th scope="col">实物去向</th>
              <th scope="col" data-column-kind="action">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="asset in displayRows" :key="asset.id">
              <th scope="row" data-column-kind="identity">{{ asset.assetCode }}</th>
              <td>{{ asset.name }}</td>
              <td class="u-secondary">{{ asset.model ?? "—" }}</td>
              <td><span class="fams-status">{{ lifecycleLabels[asset.lifecycle] }}</span></td>
              <td><span class="fams-status">{{ cardStageLabels[asset.cardStage] }}</span></td>
              <td>{{ asset.custodyText }}</td>
              <td>{{ asset.physicalDestination?.destination ?? "—" }}</td>
              <td data-column-kind="action">
                <router-link class="fams-button" data-kind="link" data-size="sm" :to="`/assets/${asset.id}`">详情</router-link>
              </td>
            </tr>
            <tr v-if="hasNoRows" class="fams-table-empty">
              <td colspan="8"><strong>暂无符合条件的资产</strong>调整资产编码或名称条件后重新查询。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 项</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled"
            @click="pages(-1)">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled"
            @click="pages(1)">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
