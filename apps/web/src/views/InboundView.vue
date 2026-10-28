<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import InboundReassignment from "@/components/InboundReassignment.vue";
import AttachmentPanel from "@/components/AttachmentPanel.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { activeReferences, getAsset, listAssets, type AssetCard, type AssetReference } from "@/asset/api";
import {
  acceptItem, cancelReceipt, claimReceipt, createReceipt, decideReceipt, getReceipt,
  listReceipts, submitReceipt, updateReceipt, type InboundItem, type InboundReceipt,
} from "@/inbound/api";

const session = useSessionStore();
const receipts = ref<InboundReceipt[]>([]);
const selected = ref<InboundReceipt | null>(null);
const editing = ref<InboundReceipt | null>(null);
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
    const linkedReceipt = new URLSearchParams(window.location.search).get("receiptId");
    if (!selected.value && linkedReceipt && /^[1-9][0-9]{0,18}$/.test(linkedReceipt)) selected.value = await getReceipt(linkedReceipt);
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
    const payload = { businessDate: businessDate.value,
      items: chosen.map((asset) => ({ asset: { id: asset!.id, version: asset!.version },
        targetWarehouse: { id: warehouse.id, version: warehouse.version },
        targetLocation: { id: location.id, version: location.version } })) };
    selected.value = editing.value
      ? await updateReceipt(editing.value, payload) : await createReceipt(payload);
    notice.value = `入库草稿 ${selected.value.receiptNumber} 已保存`;
    editing.value = null;
    assetIds.value = [];
  });
}

function editDraft(): void {
  if (!selected.value) return;
  editing.value = selected.value;
  businessDate.value = selected.value.businessDate;
  assetIds.value = selected.value.items.map((item) => item.asset.id);
  warehouseId.value = selected.value.items[0]?.targetWarehouse.id ?? "";
  locationId.value = selected.value.items[0]?.targetLocation.id ?? "";
  notice.value = "修改下方草稿内容后保存；所选资产使用同一目标仓库和位置";
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
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
    <section v-if="warehouseRole" class="fams-panel fams-stack" aria-label="建立入库单">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">{{ editing ? `修订草稿 ${editing.receiptNumber}` : '建立入库单' }}</h2>
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
        <div class="fams-form-actions">
          <button class="fams-button" data-kind="primary" type="submit" :disabled="busy">{{ editing ? '保存修订' : '保存草稿' }}</button>
          <button v-if="editing" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="editing = null; assetIds = []">退出修订</button>
        </div>
      </form>
    </section>
    <section class="fams-panel fams-stack" aria-label="入库单列表">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">入库单</h2>
        </div>
      </div>
      <div class="fams-table-scroll"><table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">单号</th><th scope="col">业务日期</th><th scope="col">状态</th>
          <th scope="col" data-column-kind="numeric">资产数</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
        <tbody><tr v-for="value in receipts" :key="value.id">
          <th scope="row" data-column-kind="identity">{{ value.receiptNumber }}</th><td>{{ value.businessDate }}</td>
          <td><span class="fams-status">{{ value.status }}</span></td><td data-column-kind="numeric">{{ value.items.length }}</td>
          <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="busy" @click="open(value.id)">详情</button></td>
        </tr><tr v-if="!receipts.length" class="fams-table-empty"><td colspan="5"><strong>暂无入库单</strong>由库管新建入库草稿后在此查看。</td></tr></tbody>
      </table></div>
    </section>
    <section v-if="selected" class="fams-panel fams-stack" aria-label="入库单详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">{{ selected.receiptNumber }} · {{ selected.status }}</h2>
        </div>
      </div>
      <p v-if="selected.currentProcessor">当前处理人：{{ selected.currentProcessor.processor.username }}</p>
      <p v-if="selected.decision">管理员决定：{{ selected.decision.result }}</p>
      <div class="fams-panel-actions">
        <button v-if="warehouseRole && selected.status === 'DRAFT' && selected.createdBy.id === session.user?.id" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="editDraft">修订草稿</button>
        <button v-if="warehouseRole && selected.status === 'DRAFT'" class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="act('submit')">提交审批</button>
        <button v-if="warehouseRole && ['DRAFT', 'PENDING_ADMIN_DECISION'].includes(selected.status)" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="act('cancel')">取消</button>
        <button v-if="adminRole && selected.status === 'PENDING_ADMIN_DECISION'" class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="act('approve')">批准</button>
        <button v-if="adminRole && selected.status === 'PENDING_ADMIN_DECISION'" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="act('reject')">驳回</button>
        <button v-if="warehouseRole && selected.status === 'WAITING_WAREHOUSE_CLAIM'" class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="act('claim')">认领整单</button>
      </div>
      <div class="fams-table-scroll"><table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">资产编码</th><th scope="col">资产名称</th><th scope="col">执行状态</th>
          <th scope="col" data-column-kind="action">操作</th></tr></thead>
        <tbody><tr v-for="item in selected.items" :key="item.id">
          <th scope="row" data-column-kind="identity">{{ item.assetSnapshot.assetCode }}</th><td>{{ item.assetSnapshot.assetName }}</td>
          <td><span class="fams-status">{{ item.executionStatus }}</span></td>
          <td data-column-kind="action"><button v-if="warehouseRole && selected.status === 'WAREHOUSE_PROCESSING' && item.executionStatus === 'PENDING' && selected.currentProcessor?.processor.id === session.user?.id" class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="busy" @click="accept(item)">验收入库</button></td>
        </tr></tbody>
      </table></div>
      <InboundReassignment v-if="adminRole && selected.status === 'WAREHOUSE_PROCESSING'"
        :id="selected.id" :version="selected.version" @saved="open(selected.id); load()" />
      <AttachmentPanel :key="selected.id" :receipt="selected" :disabled="busy"
        @busy="busy = $event" @uploaded="selected.version = $event" />
    </section>
  </AppChrome>
</template>
