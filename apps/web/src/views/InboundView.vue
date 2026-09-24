<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { activeReferences, getAsset, listAssets, type AssetCard, type AssetReference } from "@/asset/api";
import {
  acceptItem, cancelReceipt, claimReceipt, createReceipt, decideReceipt, getReceipt,
  listReceipts, submitReceipt, type InboundItem, type InboundReceipt,
} from "@/inbound/api";

const session = useSessionStore();
const receipts = ref<InboundReceipt[]>([]);
const selected = ref<InboundReceipt | null>(null);
const assets = ref<AssetCard[]>([]);
const warehouses = ref<AssetReference[]>([]);
const locations = ref<AssetReference[]>([]);
const assetIds = ref<string[]>([]);
const warehouseId = ref("");
const locationId = ref("");
const businessDate = ref(new Date().toISOString().slice(0, 10));
const error = ref("");
const notice = ref("");
const busy = ref(false);
const warehouseRole = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;
const adminRole = session.user?.roles.includes("ASSET_ADMIN") ?? false;

function selectedRef(items: AssetReference[], id: string): AssetReference | undefined {
  return items.find((item) => item.id === id);
}

async function load(): Promise<void> {
  try {
    const result = await listReceipts();
    receipts.value = result.items;
    if (warehouseRole) {
      const [assetPage, warehousePage, locationPage] = await Promise.all([
        listAssets(), activeReferences("/warehouses"), activeReferences("/locations"),
      ]);
      assets.value = assetPage.items.filter((item) => item.cardStage === "DRAFT");
      warehouses.value = warehousePage.items;
      locations.value = locationPage.items;
    }
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "入库单加载失败"; }
}

async function open(id: string): Promise<void> {
  try { selected.value = await getReceipt(id); error.value = ""; }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "详情加载失败"; }
}

async function create(): Promise<void> {
  const warehouse = selectedRef(warehouses.value, warehouseId.value);
  const location = selectedRef(locations.value, locationId.value);
  const chosen = assetIds.value.map((id) => selectedRef(assets.value, id));
  if (!chosen.length || chosen.some((item) => !item) || !warehouse || !location) {
    error.value = "请选择资产、仓库和位置"; return;
  }
  await execute(async () => {
    selected.value = await createReceipt({ businessDate: businessDate.value,
      items: chosen.map((asset) => ({ asset: { id: asset!.id, version: asset!.version },
        targetWarehouse: { id: warehouse.id, version: warehouse.version },
        targetLocation: { id: location.id, version: location.version } })) });
    notice.value = `入库草稿 ${selected.value.receiptNumber} 已建立`;
  });
}

async function act(action: "submit" | "cancel" | "approve" | "reject" | "claim"): Promise<void> {
  if (!selected.value) return;
  const current = selected.value;
  let reason: string | undefined;
  if (action === "cancel" || action === "reject") {
    reason = window.prompt(action === "cancel" ? "取消原因" : "驳回原因")?.trim();
    if (!reason) return;
  }
  await execute(async () => {
    if (action === "submit") selected.value = await submitReceipt(current);
    if (action === "cancel") selected.value = await cancelReceipt(current, reason!);
    if (action === "approve") selected.value = await decideReceipt(current, true);
    if (action === "reject") selected.value = await decideReceipt(current, false, reason);
    if (action === "claim") selected.value = await claimReceipt(current);
    notice.value = "入库单状态已更新";
  });
}

async function accept(item: InboundItem): Promise<void> {
  if (!selected.value) return;
  const observed = window.prompt(`核对实物编码：${item.assetSnapshot.assetCode}`)?.trim();
  if (!observed) return;
  await execute(async () => {
    const asset = await getAsset(item.asset.id);
    const result = await acceptItem(selected.value!, item, asset.version, observed);
    selected.value = result.receipt;
    notice.value = `${item.assetSnapshot.assetCode} 已验收入库`;
  });
}

async function execute(operation: () => Promise<void>): Promise<void> {
  busy.value = true; error.value = "";
  try { await operation(); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "操作失败"; }
  finally { busy.value = false; }
}

onMounted(load);
</script>

<template>
  <AppChrome>
    <div class="breadcrumb">工作台 / 入库管理</div>
    <div class="asset-heading"><div><h1>入库管理</h1>
      <p>管理员确认后，由一名库管认领并逐项核对实物。</p></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="asset-notice" role="status">{{ notice }}</p>
    <section v-if="warehouseRole" class="asset-panel asset-form" aria-label="建立入库单">
      <h2>建立入库单</h2>
      <form @submit.prevent="create">
        <label>业务日期<input v-model="businessDate" type="date" required /></label>
        <label>待入库资产（可多选）<select v-model="assetIds" multiple required>
          <option v-for="item in assets" :key="item.id" :value="item.id">{{ item.assetCode }} · {{ item.name }}</option>
        </select></label>
        <label>目标仓库<select v-model="warehouseId" required><option value="">请选择</option>
          <option v-for="item in warehouses" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option>
        </select></label>
        <label>目标位置<select v-model="locationId" required><option value="">请选择</option>
          <option v-for="item in locations.filter((value) => value.warehouseId === warehouseId)" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option>
        </select></label>
        <button class="primary-button" type="submit" :disabled="busy">保存草稿</button>
      </form>
    </section>
    <section class="asset-panel" aria-label="入库单列表">
      <h2>入库单</h2>
      <div class="asset-table-wrap"><table class="asset-table">
        <thead><tr><th>单号</th><th>业务日期</th><th>状态</th><th>资产数</th><th>操作</th></tr></thead>
        <tbody><tr v-for="value in receipts" :key="value.id">
          <td>{{ value.receiptNumber }}</td><td>{{ value.businessDate }}</td>
          <td>{{ value.status }}</td><td>{{ value.items.length }}</td>
          <td><button class="text-button" type="button" @click="open(value.id)">详情</button></td>
        </tr><tr v-if="!receipts.length"><td colspan="5">暂无入库单</td></tr></tbody>
      </table></div>
    </section>
    <section v-if="selected" class="asset-panel" aria-label="入库单详情">
      <h2>{{ selected.receiptNumber }} · {{ selected.status }}</h2>
      <p v-if="selected.currentProcessor">当前处理人：{{ selected.currentProcessor.processor.username }}</p>
      <p v-if="selected.decision">管理员决定：{{ selected.decision.result }}</p>
      <div class="asset-actions">
        <button v-if="warehouseRole && selected.status === 'DRAFT'" class="primary-button" type="button" :disabled="busy" @click="act('submit')">提交审批</button>
        <button v-if="warehouseRole && ['DRAFT', 'PENDING_ADMIN_DECISION'].includes(selected.status)" class="secondary-button" type="button" :disabled="busy" @click="act('cancel')">取消</button>
        <button v-if="adminRole && selected.status === 'PENDING_ADMIN_DECISION'" class="primary-button" type="button" :disabled="busy" @click="act('approve')">批准</button>
        <button v-if="adminRole && selected.status === 'PENDING_ADMIN_DECISION'" class="secondary-button" type="button" :disabled="busy" @click="act('reject')">驳回</button>
        <button v-if="warehouseRole && selected.status === 'WAITING_WAREHOUSE_CLAIM'" class="primary-button" type="button" :disabled="busy" @click="act('claim')">认领整单</button>
      </div>
      <div class="asset-table-wrap"><table class="asset-table">
        <thead><tr><th>资产编码</th><th>资产名称</th><th>执行状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="item in selected.items" :key="item.id">
          <td>{{ item.assetSnapshot.assetCode }}</td><td>{{ item.assetSnapshot.assetName }}</td>
          <td>{{ item.executionStatus }}</td>
          <td><button v-if="warehouseRole && selected.status === 'WAREHOUSE_PROCESSING' && item.executionStatus === 'PENDING' && selected.currentProcessor?.processor.id === session.user?.id" class="text-button" type="button" :disabled="busy" @click="accept(item)">验收入库</button></td>
        </tr></tbody>
      </table></div>
    </section>
  </AppChrome>
</template>
