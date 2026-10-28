<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { activeReferences, listAssets, type AssetCard, type AssetReference } from "@/asset/api";
import { createReceipt, getReceipt, updateReceipt, type InboundReceipt } from "@/inbound/api";

const props = defineProps<{ id?: string }>();
const router = useRouter();
const session = useSessionStore();
const warehouseRole = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;
const editing = ref<InboundReceipt | null>(null);
const assets = ref<AssetCard[]>([]);
const warehouses = ref<AssetReference[]>([]);
const locations = ref<AssetReference[]>([]);
const assetIds = ref<string[]>([]);
const warehouseId = ref("");
const locationId = ref("");
const businessDate = ref(new Date().toISOString().slice(0, 10));
const error = ref("");
const busy = ref(false);

function selectedRef(items: AssetReference[], id: string): AssetReference | undefined {
  return items.find((item) => item.id === id);
}

async function create(): Promise<void> {
  const warehouse = selectedRef(warehouses.value, warehouseId.value);
  const location = selectedRef(locations.value, locationId.value);
  const chosen = assetIds.value.map((id) => selectedRef(assets.value, id));
  if (!chosen.length || chosen.some((item) => !item) || !warehouse || !location) {
    error.value = "请选择资产、仓库和位置"; return;
  }
  const payload = { businessDate: businessDate.value,
    items: chosen.map((asset) => ({ asset: { id: asset!.id, version: asset!.version },
      targetWarehouse: { id: warehouse.id, version: warehouse.version },
      targetLocation: { id: location.id, version: location.version } })) };
  busy.value = true; error.value = "";
  try {
    const saved = editing.value ? await updateReceipt(editing.value, payload) : await createReceipt(payload);
    await router.push(`/inbound/${encodeURIComponent(saved.id)}?saved=1`);
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "操作失败"; }
  finally { busy.value = false; }
}

onMounted(async () => {
  if (!warehouseRole) return;
  busy.value = true;
  try {
    const [assetPage, warehousePage, locationPage] = await Promise.all([
      listAssets(), activeReferences("/warehouses"), activeReferences("/locations"),
    ]);
    assets.value = assetPage.items.filter((item) => item.cardStage === "DRAFT");
    warehouses.value = warehousePage.items;
    locations.value = locationPage.items;
    if (props.id && /^[1-9][0-9]{0,18}$/.test(props.id)) {
      const receipt = await getReceipt(props.id);
      editing.value = receipt;
      businessDate.value = receipt.businessDate;
      assetIds.value = receipt.items.map((item) => item.asset.id);
      warehouseId.value = receipt.items[0]?.targetWarehouse.id ?? "";
      locationId.value = receipt.items[0]?.targetLocation.id ?? "";
    }
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "入库单加载失败"; }
  finally { busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/inbound">返回入库单列表</router-link>
    </template>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <section v-if="warehouseRole" class="fams-panel fams-stack" aria-label="建立入库单">
      <div class="fams-panel-head">
        <div>
          <h2 v-if="editing" class="fams-panel-title">{{ `修订草稿 ${editing.receiptNumber}` }}</h2>
        </div>
      </div>
      <form class="fams-stack" @submit.prevent="create">
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">业务日期</span>
            <input class="fams-control" v-model="businessDate" type="date" required /></label>
          <label class="fams-field"><span class="fams-field-label">目标仓库</span>
            <select class="fams-control" v-model="warehouseId" required><option value="">请选择</option>
              <option v-for="item in warehouses" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option>
            </select></label>
          <label class="fams-field"><span class="fams-field-label">目标位置</span>
            <select class="fams-control" v-model="locationId" required><option value="">请选择</option>
              <option v-for="item in locations.filter((value) => value.warehouse?.id === warehouseId)" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option>
            </select></label>
          <div class="fams-field fams-field-wide"><span class="fams-field-label">待入库资产（可多选）</span>
            <div class="fams-selection-grid" role="group" aria-label="待入库资产（可多选）">
              <label v-for="item in assets" :key="item.id" class="fams-selection">
                <input v-model="assetIds" type="checkbox" :value="item.id" />
                <span>{{ item.assetCode }} · {{ item.name }}</span></label>
            </div></div>
        </div>
        <p v-if="editing" class="fams-panel-subtitle">修改下方草稿内容后保存；所选资产使用同一目标仓库和位置</p>
        <div class="fams-form-actions">
          <button class="fams-button" data-kind="primary" type="submit" :disabled="busy">{{ editing ? '保存修订' : '保存草稿' }}</button>
          <router-link v-if="editing" class="fams-button" data-kind="secondary" :to="`/inbound/${editing.id}`">退出修订</router-link>
        </div>
      </form>
    </section>
  </AppChrome>
</template>
