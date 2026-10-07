<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import QRCode from "qrcode";
import { useSessionStore } from "@/auth/sessionStore";
import {
  activeReferences, cancelAsset, copyAsset, createAsset, getAsset, getTag, listAssets,
  reprintTag, updateAsset,
  type AssetCard, type AssetReference, type AssetTag,
} from "@/asset/api";

const session = useSessionStore();
const rows = ref<AssetCard[]>([]);
const selected = ref<AssetCard | null>(null);
const tag = ref<AssetTag | null>(null);
const qrImage = ref("");
const total = ref(0);
const page = ref(1);
const code = ref("");
const nameFilter = ref("");
const error = ref("");
const notice = ref("");
const busy = ref(false);
const formOpen = ref(false);
const name = ref("");
const model = ref("");
const unit = ref("");
const categoryId = ref("");
const organizationId = ref("");
const warehouseId = ref("");
const locationId = ref("");
const categories = ref<AssetReference[]>([]);
const organizations = ref<AssetReference[]>([]);
const warehouses = ref<AssetReference[]>([]);
const locations = ref<AssetReference[]>([]);

const canWrite = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;

function custodyLabel(asset: AssetCard): string {
  const custody = asset.currentCustody;
  if (!custody) return "尚未入库";
  const referenceId = (value: unknown): string => {
    if (typeof value !== "object" || value === null || !("id" in value)) return "—";
    return String(value.id);
  };
  if (custody.custodyType === "STORAGE") return `在库 · 仓库 ${referenceId(custody.warehouse)}`;
  if (custody.custodyType === "SHARED") return `部门公用 · 责任人 ${referenceId(custody.departmentResponsibleEmployee)}`;
  return `个人保管 · 责任人 ${referenceId(custody.responsibleEmployee)}`;
}

function selectedReference(items: AssetReference[], id: string): AssetReference | undefined {
  return items.find((item) => item.id === id);
}

async function load(): Promise<void> {
  error.value = "";
  try {
    const result = await listAssets(page.value, code.value, nameFilter.value);
    rows.value = result.items;
    total.value = result.total;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "台账加载失败"; }
}

async function openCard(id: string): Promise<void> {
  error.value = "";
  tag.value = null;
  qrImage.value = "";
  try {
    selected.value = await getAsset(id);
    tag.value = await getTag(id);
    qrImage.value = await QRCode.toDataURL(tag.value.qrCode, { width: 160, margin: 1 });
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "资产详情加载失败"; }
}

async function openForm(): Promise<void> {
  formOpen.value = true;
  error.value = "";
  try {
    const [categoryPage, organizationPage, warehousePage, locationPage] = await Promise.all([
      activeReferences("/asset-categories"), activeReferences("/organizations"),
      activeReferences("/warehouses"), activeReferences("/locations"),
    ]);
    categories.value = categoryPage.items;
    organizations.value = organizationPage.items;
    warehouses.value = warehousePage.items;
    locations.value = locationPage.items;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "基础资料加载失败"; }
}

async function submit(): Promise<void> {
  if (!name.value.trim()) { error.value = "请填写资产名称"; return; }
  busy.value = true;
  error.value = "";
  try {
    const payload: Record<string, unknown> = { name: name.value.trim() };
    if (model.value.trim()) payload.model = model.value.trim();
    if (unit.value.trim()) payload.unit = unit.value.trim();
    const category = selectedReference(categories.value, categoryId.value);
    const organization = selectedReference(organizations.value, organizationId.value);
    const warehouse = selectedReference(warehouses.value, warehouseId.value);
    const location = selectedReference(locations.value, locationId.value);
    if (category) payload.category = { id: category.id, version: category.version };
    if (organization) payload.organization = { id: organization.id, version: organization.version };
    if (warehouse) payload.plannedInboundWarehouse = { id: warehouse.id, version: warehouse.version };
    if (location) payload.plannedInboundLocation = { id: location.id, version: location.version };
    const created = await createAsset(payload);
    notice.value = `草稿 ${created.assetCode} 已保存`;
    formOpen.value = false;
    name.value = ""; model.value = ""; unit.value = "";
    await load();
    await openCard(created.id);
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "保存失败"; }
  finally { busy.value = false; }
}

async function correctNotes(): Promise<void> {
  if (!selected.value) return;
  const notes = window.prompt("备注修正", "");
  if (notes === null) return;
  busy.value = true;
  try {
    selected.value = await updateAsset(selected.value.id, selected.value.version, { notes });
    notice.value = "备注已更新";
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "更新失败"; }
  finally { busy.value = false; }
}

async function copyCurrent(): Promise<void> {
  if (!selected.value) return;
  const quantity = Number(window.prompt("复制建卡数量（1–100）", "1"));
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 100) return;
  busy.value = true;
  try {
    const result = await copyAsset(selected.value.id, selected.value.version, quantity);
    notice.value = `已创建 ${result.assets.length} 张独立草稿`;
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "复制失败"; }
  finally { busy.value = false; }
}

async function cancelCurrent(): Promise<void> {
  if (!selected.value) return;
  const reason = window.prompt("取消原因");
  if (!reason?.trim()) return;
  busy.value = true;
  try {
    selected.value = await cancelAsset(selected.value.id, selected.value.version, reason.trim());
    notice.value = "草稿已取消，编码永久保留";
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "取消失败"; }
  finally { busy.value = false; }
}

async function printCurrent(): Promise<void> {
  if (!selected.value) return;
  busy.value = true;
  try {
    const result = await reprintTag(selected.value.id, selected.value.version);
    tag.value = result.tag;
    qrImage.value = await QRCode.toDataURL(result.tag.qrCode, { width: 160, margin: 1 });
    notice.value = `打印任务 ${result.jobId} 已完成；标签身份保持不变`;
    window.print();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "标签打印失败"; }
  finally { busy.value = false; }
}

onMounted(load);
</script>

<template>
  <AppChrome>
    <div class="breadcrumb">工作台 / 资产台账</div>
    <div class="asset-heading">
      <div><h1>资产台账</h1><p>查看资产状态、建卡和标签。保管关系由正式业务流程维护。</p></div>
      <button v-if="canWrite" class="primary-button" type="button" @click="openForm">单个建卡</button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="asset-notice" role="status">{{ notice }}</p>
    <section class="asset-panel" aria-label="台账筛选与列表">
      <form class="asset-filters" @submit.prevent="page = 1; load()">
        <label>资产编码<input v-model="code" placeholder="编码包含" /></label>
        <label>资产名称<input v-model="nameFilter" placeholder="名称包含" /></label>
        <button class="secondary-button" type="submit">查询</button>
      </form>
      <div class="asset-table-wrap"><table class="asset-table">
        <thead><tr><th>资产编码</th><th>名称</th><th>型号</th><th>生命周期</th><th>建卡阶段</th><th>正式保管</th><th>实物去向</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="asset in rows" :key="asset.id">
            <td>{{ asset.assetCode }}</td><td>{{ asset.name }}</td><td>{{ asset.model ?? "—" }}</td>
            <td>{{ asset.lifecycle }}</td><td>{{ asset.cardStage }}</td>
            <td>{{ custodyLabel(asset) }}</td><td>{{ asset.physicalDestination?.destination ?? "—" }}</td><td><button class="text-button" type="button" @click="openCard(asset.id)">详情</button></td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="8">暂无符合条件的资产</td></tr>
        </tbody>
      </table></div>
      <div class="asset-pagination"><span>共 {{ total }} 项 · 第 {{ page }} 页</span>
        <button class="secondary-button" type="button" :disabled="page <= 1" @click="page--; load()">上一页</button>
        <button class="secondary-button" type="button" :disabled="page * 20 >= total" @click="page++; load()">下一页</button>
      </div>
    </section>

    <section v-if="formOpen" class="asset-panel asset-form" aria-labelledby="create-title">
      <h2 id="create-title">单个建卡 · 保存草稿</h2>
      <form @submit.prevent="submit">
        <label>资产名称 *<input v-model="name" maxlength="256" required /></label>
        <label>规格型号<input v-model="model" maxlength="128" /></label>
        <label>单位<input v-model="unit" maxlength="32" /></label>
        <label>分类<select v-model="categoryId"><option value="">稍后填写</option><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option></select></label>
        <label>所属组织<select v-model="organizationId"><option value="">稍后填写</option><option v-for="item in organizations" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option></select></label>
        <label>计划仓库<select v-model="warehouseId"><option value="">稍后填写</option><option v-for="item in warehouses" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option></select></label>
        <label>计划位置<select v-model="locationId"><option value="">稍后填写</option><option v-for="item in locations" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option></select></label>
        <div class="asset-actions"><button class="primary-button" :disabled="busy" type="submit">保存草稿</button><button class="secondary-button" type="button" @click="formOpen = false">取消</button></div>
      </form>
    </section>

    <section v-if="selected" class="asset-panel" aria-labelledby="detail-title">
      <div class="asset-heading"><div><h2 id="detail-title">{{ selected.name }}</h2><p>{{ selected.assetCode }} · {{ selected.cardStage }} · 版本 {{ selected.version }}</p></div><button class="secondary-button" type="button" @click="selected = null">关闭</button></div>
      <p v-if="selected.physicalDestination">实物去向：{{ selected.physicalDestination.destination }} · {{ selected.physicalDestination.status }}；正式保管仍以上方资产事实为准。</p>
      <dl class="asset-facts"><div><dt>分类</dt><dd>{{ selected.category?.id ?? "—" }}</dd></div><div><dt>型号</dt><dd>{{ selected.model ?? "—" }}</dd></div><div><dt>所属组织</dt><dd>{{ selected.organization?.id ?? "—" }}</dd></div><div><dt>当前保管</dt><dd>{{ custodyLabel(selected) }}</dd></div></dl>
      <div class="asset-actions">
        <button v-if="canWrite" class="secondary-button" :disabled="busy" type="button" @click="copyCurrent">复制建卡</button>
        <button v-if="canWrite && selected.cardStage !== 'PENDING_INBOUND' && selected.cardStage !== 'CANCELLED'" class="secondary-button" :disabled="busy" type="button" @click="correctNotes">修正备注</button>
        <button v-if="canWrite && selected.cardStage === 'DRAFT'" class="secondary-button" :disabled="busy" type="button" @click="cancelCurrent">取消草稿</button>
        <button v-if="canWrite && selected.cardStage === 'ACTIVE'" class="secondary-button" :disabled="busy" type="button" @click="printCurrent">打印标签</button>
      </div>
      <div v-if="tag" class="asset-tag-preview"><span>{{ tag.enterpriseName }}</span><strong>{{ tag.assetName }}</strong><code>{{ tag.assetCode }}</code><img v-if="qrImage" :src="qrImage" alt="资产二维码" width="160" height="160" /><small>扫码标识仅用于定位，权限由当前会话决定</small></div>
    </section>
  </AppChrome>
</template>
