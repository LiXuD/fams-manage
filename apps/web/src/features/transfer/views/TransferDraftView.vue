<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { businessMessage, businessRequest } from "@/api/business";
import type { BusinessMutation } from "@/api/business";
import type { TransferAsset, TransferChoice, TransferTarget } from "@fams/api-types/transfer";

const router = useRouter();
const assets = ref<TransferAsset[]>([]);
const organizations = ref<TransferChoice[]>([]), employees = ref<TransferChoice[]>([]), warehouses = ref<TransferChoice[]>([]), locations = ref<TransferChoice[]>([]);
const draftItems = ref<{ asset: TransferAsset; target: TransferTarget }[]>([]);
const assetId = shallowRef(""), owning = shallowRef(""), using = shallowRef(""), custody = ref("PERSONAL"), employee = shallowRef(""), warehouse = shallowRef(""), location = shallowRef("");
const reason = shallowRef("");
const busy = shallowRef(false), error = shallowRef(""), notice = shallowRef(""), retryable = shallowRef(false);
const currentAsset = computed(() => assets.value.find(a => a.id === assetId.value));
const stock = computed(() => currentAsset.value?.lifecycleStatus === "IN_STOCK");
const bins = computed(() => locations.value.filter(l => stock.value ? l.locationType === "BIN" && l.warehouseId === warehouse.value : l.locationType !== "BIN"));
const eligibleEmployees = computed(() => employees.value.filter(p => custody.value !== "SHARED" || p.sharedEligible));
const previewCustody = computed(() => custodyName(currentAsset.value?.custodyType));
const previewOrganization = computed(() => organization(currentAsset.value?.usingOrganizationId));
const previewPlace = computed(() => place(currentAsset.value?.currentLocationId));
const draftRows = computed(() => draftItems.value.map(item => ({ ...item,
  line: `${item.asset.assetCode}：${place(item.asset.currentLocationId)} → ${place(item.target.locationId)}；${custodyName(item.asset.custodyType)} → ${custodyName(item.target.custodyType)}；${organization(item.target.usingOrganizationId)}` })));
/** 确认式幂等提交：在途意图槽与幂等键簿记归底座；busy/retryable、URL 与文案留在本视图。 */
const { pending, prepare: draft, confirm: adopt, settle, reset } = useConfirmedSubmit<Omit<BusinessMutation, "key">>();
const liveness = useRequestLiveness(() => [using.value]);
async function catalog(kind: string, org?: string) {
  const result: TransferChoice[] = []; let p = 1, part: TransferChoice[];
  do { part = (await businessRequest<{ items: TransferChoice[] }>(`/transfers/catalog/${kind}?page=${p++}&pageSize=200${org ? `&organizationId=${org}` : ""}`)).items; result.push(...part); }
  while (part.length === 200);
  return result;
}
async function load() {
  const turn = liveness.begin();
  const result = await businessRequest<{ items: TransferAsset[] }>("/transfers/eligible-assets?page=1&pageSize=20");
  if (liveness.isCurrent(turn)) assets.value = result.items;
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try {
    await load();
    const turn = liveness.begin();
    const [orgs, whs, locs] = await Promise.all([catalog("organization"), catalog("warehouse"), catalog("location")]);
    if (liveness.isCurrent(turn)) { organizations.value = orgs; warehouses.value = whs; locations.value = locs; }
    reset(); retryable.value = false;
  } catch (e) { error.value = businessMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  let operation = pending.value;
  if (operation && operation.payload.path !== path) { error.value = "请先重试上次操作或刷新核对。"; return; }
  if (!operation) { draft(() => ({ path, version, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown })); operation = adopt(); }
  if (!operation) return;
  busy.value = true; error.value = "";
  try {
    const result = await businessRequest<{ id: string }>(operation.payload.path, { ...operation.payload, key: operation.key });
    settle(); retryable.value = false; notice.value = "操作已保存。";
    draftItems.value = [];
    await router.push(`/transfers/${encodeURIComponent(result.id)}?saved=1`);
  } catch (e) { error.value = businessMessage(e); retryable.value = pending.value !== null; }
  finally { busy.value = false; }
}
function retry() { const operation = pending.value; if (operation) void mutate(operation.payload.path, operation.payload.version); }
function place(id: unknown) { return locations.value.find(row => row.id === String(id))?.name ?? "原登记位置"; }
function organization(id: unknown) { return organizations.value.find(row => row.id === String(id))?.name ?? "未指定组织"; }
function custodyName(value: unknown) { return value === "PERSONAL" ? "个人" : value === "SHARED" ? "部门公用" : "仓库保管"; }
async function chooseAsset() {
  const asset = currentAsset.value; if (!asset) return;
  owning.value = asset.owningOrganizationId ?? ""; using.value = asset.usingOrganizationId ?? ""; custody.value = asset.custodyType;
  employee.value = asset.responsibleEmployeeId ?? asset.departmentResponsibleEmployeeId ?? "";
  warehouse.value = asset.storageWarehouseId ?? ""; location.value = asset.currentLocationId;
  if (using.value) await chooseOrganization(false);
}
async function chooseOrganization(reset = true) {
  if (reset) employee.value = ""; employees.value = []; if (!using.value) return;
  busy.value = true;
  const turn = liveness.begin();
  try {
    const result = await catalog("employee", using.value);
    if (liveness.isCurrent(turn)) employees.value = result;
  }
  catch (e) { error.value = businessMessage(e); } finally { busy.value = false; }
}
function addItem() {
  const asset = currentAsset.value; if (!asset || !location.value || draftItems.value.some(i => i.asset.id === asset.id)) { error.value = "请选择未重复的来源资产和目标位置。"; return; }
  const loc = bins.value.find(l => l.id === location.value), wh = warehouses.value.find(w => w.id === warehouse.value);
  const org = organizations.value.find(o => o.id === using.value), owner = organizations.value.find(o => o.id === owning.value);
  const person = employees.value.find(e => e.id === employee.value);
  if (!loc || stock.value && !wh || !stock.value && (!org || !person || custody.value === "SHARED" && !person.sharedEligible)) { error.value = "请核对有效目标部门、责任人和位置；公用责任人须为目标部门资产员。"; return; }
  const versions: Record<string, number> = { location: loc.version };
  if (owner) versions.owningOrganization = owner.version;
  if (stock.value) versions.warehouse = wh!.version;
  else { versions.usingOrganization = org!.version; versions.employee = person!.version; }
  draftItems.value.push({ asset, target: { owningOrganizationId: owner?.id ?? null, custodyType: stock.value ? "STORAGE" : custody.value,
    usingOrganizationId: stock.value ? null : org!.id, employeeId: stock.value ? null : person!.id,
    warehouseId: stock.value ? wh!.id : null, locationId: loc.id, referenceVersions: versions } });
}
function create() {
  if (!draftItems.value.length || !reason.value.trim()) { error.value = "请添加调拨明细并填写原因。"; return; }
  void mutate("/transfers", 0, { reason: reason.value.trim(), items: draftItems.value.map(i => ({ assetId: i.asset.id, assetVersion: i.asset.version, target: i.target })) });
}
onMounted(refresh);
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/transfers">返回调拨列表</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <section class="fams-panel fams-stack" aria-labelledby="create-title">
      <div class="fams-panel-head">
        <div>
          <h2 id="create-title" class="fams-panel-title">新建调拨</h2>
          <p class="fams-panel-subtitle">先添加调拨明细再保存草稿；一名库管记录整单执行，多人负责时按分组分别提交。</p>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新</button>
          <button v-if="retryable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="retry">重试上次操作</button>
        </div>
      </div>

      <label class="fams-field">
        <span class="fams-field-label">申请 / 决定原因</span>
        <textarea class="fams-control" v-model="reason" maxlength="1000" :disabled="busy || retryable" />
      </label>

      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">来源资产</span>
          <select class="fams-control" v-model="assetId" :disabled="busy || retryable" @change="chooseAsset"><option value="">请选择当前有操作权的资产</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">目标所属组织</span>
          <select class="fams-control" v-model="owning" :disabled="busy || retryable"><option value="">未指定</option><option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option></select></label>
        <template v-if="!stock">
          <label class="fams-field"><span class="fams-field-label">目标使用组织</span>
            <select class="fams-control" v-model="using" :disabled="busy || retryable" @change="chooseOrganization()"><option value="">请选择</option><option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option></select></label>
          <label class="fams-field"><span class="fams-field-label">目标责任模式</span>
            <select class="fams-control" v-model="custody" :disabled="busy || retryable"><option value="PERSONAL">个人</option><option value="SHARED">部门公用</option></select></label>
          <label class="fams-field"><span class="fams-field-label">实际目标责任人</span>
            <select class="fams-control" v-model="employee" :disabled="busy || retryable"><option value="">请选择</option><option v-for="person in eligibleEmployees" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
        </template>
        <label v-else class="fams-field"><span class="fams-field-label">目标仓库</span>
          <select class="fams-control" v-model="warehouse" :disabled="busy || retryable" @change="location = ''"><option value="">请选择</option><option v-for="wh in warehouses" :key="wh.id" :value="wh.id">{{ wh.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">目标位置</span>
          <select class="fams-control" v-model="location" :disabled="busy || retryable"><option value="">请选择</option><option v-for="loc in bins" :key="loc.id" :value="loc.id">{{ loc.name }}</option></select></label>
      </div>
      <p v-if="currentAsset" class="fams-panel-subtitle">原责任模式 {{ previewCustody }} · 原使用组织 {{ previewOrganization }} · 原位置 {{ previewPlace }}</p>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="addItem">添加调拨明细</button>

      <article v-for="(item, index) in draftRows" :key="item.asset.id" class="fams-card fams-stack">
        <p>{{ item.line }}</p>
        <button class="fams-button" data-kind="danger" data-size="sm" type="button" :disabled="busy || retryable" @click="draftItems.splice(index, 1)">移除此明细</button>
      </article>
      <p v-if="draftItems.length" class="fams-panel-subtitle">本单 {{ draftItems.length }} 项，由一名库管记录全部执行；需要多名库管分别负责时，建议按来源/目标位置分组分别提交。</p>

      <div class="fams-form-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable || !draftItems.length" @click="create">保存调拨草稿</button>
      </div>
    </section>
  </AppChrome>
</template>
