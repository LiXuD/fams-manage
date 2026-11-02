<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import AssetReturnReassignment from "@/features/asset-return/panels/AssetReturnReassignment.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness, type LivenessToken } from "@/composables/useRequestLiveness";
import { businessMessage, businessRequest } from "@/api/business";
import { inspectionLabels } from "@/features/asset-return/api";
import { loanLabels as labels } from "@/features/loan/api";
import type { BusinessMutation } from "@/api/business";
import type { AssetReturn, ReturnChoice } from "@fams/api-types/loan";

const props = defineProps<{ id: string }>();
const session = useSessionStore();
const selected = ref<AssetReturn | null>(null);
const busy = shallowRef(false); const error = shallowRef(""); const notice = shallowRef(""); const retryable = shallowRef(false);
const reason = shallowRef(""); const observed = shallowRef(""); const result = ref<"NORMAL" | "DAMAGED" | "MISSING">("NORMAL");
const warehouseId = shallowRef(""); const locationId = shallowRef(""); const warehouses = ref<ReturnChoice[]>([]); const locations = ref<ReturnChoice[]>([]);
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const bins = computed(() => locations.value.filter(l => l.warehouseId === warehouseId.value));
const canInspect = computed(() => warehouse.value && selected.value?.returnStatus === "WAREHOUSE_PROCESSING"
  && selected.value.processorAccountId === session.user?.id);
const canDecide = computed(() => admin.value && selected.value?.returnStatus === "PENDING_APPROVAL");
const canClaim = computed(() => warehouse.value && selected.value?.returnStatus === "WAITING_WAREHOUSE_CLAIM");
const canReassign = computed(() => admin.value && selected.value?.returnStatus === "WAREHOUSE_PROCESSING");
const inspectionNeedsBin = computed(() => result.value !== "MISSING");
const inspectionIsMissing = computed(() => result.value === "MISSING");
const formDisabled = computed(() => busy.value || retryable.value);
const followupRows = computed(() => (selected.value?.followups ?? []).map((followup) => ({
  ...followup,
  businessTypeText: followup.businessType === "REPAIR" ? "损坏维修" : "报失处理",
  followupStatusText: followup.followupStatus === "CLOSED" ? "已实际完成"
    : followup.followupStatus === "ACCEPTED" ? "正式处理中" : "待继续处理",
  hasRepair: !!(followup.linkedRepairId || followup.lastRepairId),
  repairTarget: followup.linkedRepairId || followup.lastRepairId || "",
  repairStatusText: followup.repairStatus === "COMPLETED" ? "已验收入库"
    : followup.repairStatus === "REJECTED" || followup.repairStatus === "CANCELLED" ? "原维修未执行，源义务仍待处理" : "尚未实际完成",
})));
/** 确认式幂等提交：在途意图槽与幂等键簿记归底座；busy/retryable、URL 与文案留在本视图。 */
const { pending, prepare: draft, confirm: adopt, settle, reset } = useConfirmedSubmit<Omit<BusinessMutation, "key">>();
const identity = computed(() => JSON.stringify([props.id, session.user?.id, session.user?.roles, session.csrfToken]));
const liveness = useRequestLiveness(() => [identity.value]);

async function open(turn: LivenessToken = liveness.begin()) {
  const result = await businessRequest<AssetReturn>("/asset-returns/" + props.id);
  if (!liveness.isCurrent(turn)) return;
  selected.value = result;
}
async function refresh() {
  if (busy.value) return; const turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    await open(turn);
    if (liveness.isCurrent(turn)) { reset(); retryable.value = false; }
  } catch (e) { if (liveness.isCurrent(turn)) error.value = businessMessage(e); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function mutate(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  let operation = pending.value;
  if (operation && operation.payload.path !== path) { error.value = "请先重试上次操作或刷新查询。"; return; }
  if (!operation) { draft(() => ({ path, version, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown })); operation = adopt(); }
  if (!operation) return;
  busy.value = true; error.value = "";
  try {
    await businessRequest(operation.payload.path, { ...operation.payload, key: operation.key });
    settle(); retryable.value = false; notice.value = "操作已保存。";
    await open();
  } catch (e) { error.value = businessMessage(e); retryable.value = pending.value !== null; }
  finally { busy.value = false; }
}
function retry() { const operation = pending.value; if (operation) void mutate(operation.payload.path, operation.payload.version); }
function decide(id: string, version: number, approved: boolean) {
  if (!reason.value.trim()) { error.value = "请填写决定原因。"; return; }
  void mutate(`/asset-returns/${id}/decision`, version, { approved, reason: reason.value.trim() });
}
function inspect() {
  const row = selected.value;
  if (!row || !reason.value.trim()) { error.value = "请填写验收说明。"; return; }
  const body: Record<string, unknown> = { result: result.value, reason: reason.value.trim(), assetVersion: row.asset?.version,
    lockVersion: row.lockVersion };
  if (result.value !== "MISSING") {
    const wh = warehouses.value.find(w => w.id === warehouseId.value), bin = bins.value.find(l => l.id === locationId.value);
    if (!wh || !bin || !observed.value.trim()) { error.value = "请核对实物编码并选择仓库、仓位。"; return; }
    Object.assign(body, { observedCode: observed.value.trim(), warehouseId: wh.id, warehouseVersion: wh.version,
      locationId: bin.id, locationVersion: bin.version });
  }
  void mutate(`/asset-returns/${row.id}/inspect`, row.version, body);
}
async function catalog(kind: string) {
  const rows: ReturnChoice[] = []; let p = 1; let part: ReturnChoice[];
  do { part = (await businessRequest<{ items: ReturnChoice[] }>(`/asset-returns/catalog/${kind}?page=${p++}&pageSize=200`)).items; rows.push(...part); }
  while (part.length === 200);
  return rows;
}
onMounted(async () => {
  const turn = liveness.begin();
  busy.value = true;
  try {
    if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "没有当前记录或操作的权限。"; return; }
    await open(turn);
    if (warehouse.value) {
      const [nextWarehouses, nextLocations] = await Promise.all([catalog("warehouse"), catalog("location")]);
      if (liveness.isCurrent(turn)) { warehouses.value = nextWarehouses; locations.value = nextLocations; }
    }
    if (liveness.isCurrent(turn) && selected.value && new URLSearchParams(window.location.search).get("saved")) {
      notice.value = "操作已保存。";
      window.history.replaceState(null, "", window.location.pathname);
    }
  } catch (e) { if (liveness.isCurrent(turn)) error.value = businessMessage(e); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/asset-returns">返回退还列表</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <section v-if="selected" class="fams-panel fams-stack" aria-label="退还单详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">退还 {{ selected.id }} · {{ selected.asset?.assetCode }} · {{ labels[selected.returnStatus] }}</h2>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新</button>
          <button v-if="retryable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="retry">重试上次操作</button>
        </div>
      </div>

      <p>{{ selected.reason }}</p>

      <label class="fams-field">
        <span class="fams-field-label">操作原因 / 验收说明</span>
        <textarea class="fams-control" v-model="reason" :disabled="formDisabled" maxlength="1000" />
      </label>

      <div class="fams-panel-actions">
        <template v-if="canDecide">
          <button class="fams-button" data-kind="primary" type="button" :disabled="formDisabled" @click="decide(selected.id, selected.version, true)">批准退还</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="formDisabled" @click="decide(selected.id, selected.version, false)">驳回退还</button>
        </template>
        <button v-if="canClaim" class="fams-button" data-kind="primary" type="button"
          :disabled="formDisabled" @click="mutate(`/asset-returns/${selected.id}/claim`, selected.version)">领取整单验收</button>
      </div>

      <AssetReturnReassignment v-if="canReassign"
        :id="selected.id" :version="selected.version" @saved="refresh" />

      <div v-if="canInspect" class="fams-stack">
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">验收结果</span>
            <select class="fams-control" v-model="result" :disabled="formDisabled"><option value="NORMAL">正常入库</option><option value="DAMAGED">损坏转报修</option><option value="MISSING">实物缺失转报失</option></select></label>
          <template v-if="inspectionNeedsBin">
            <label class="fams-field"><span class="fams-field-label">实物编码 / 当前二维码</span>
              <input class="fams-control" v-model="observed" :disabled="formDisabled" /></label>
            <label class="fams-field"><span class="fams-field-label">仓库</span>
              <select class="fams-control" v-model="warehouseId" :disabled="formDisabled" @change="locationId = ''"><option value="">请选择</option><option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.name }}</option></select></label>
            <label class="fams-field"><span class="fams-field-label">仓位</span>
              <select class="fams-control" v-model="locationId" :disabled="formDisabled"><option value="">请选择</option><option v-for="l in bins" :key="l.id" :value="l.id">{{ l.name }}</option></select></label>
          </template>
        </div>
        <p v-if="inspectionIsMissing" class="fams-panel-subtitle">实物缺失将保留借用责任，进入报失处理。</p>
        <button class="fams-button" data-kind="primary" type="button" :disabled="formDisabled" @click="inspect">提交实物验收</button>
      </div>

      <p v-if="selected.inspectionResult">验收结果 {{ inspectionLabels[selected.inspectionResult] }} · {{ selected.inspectionReason }}</p>
      <p v-for="row in followupRows" :key="row.id">后续 {{ row.id }} · {{ row.businessTypeText }} · {{ row.followupStatusText }}<span v-if="row.hasRepair"> · 正式维修 {{ row.repairTarget }}（{{ row.repairStatusText }}）</span></p>
    </section>
  </AppChrome>
</template>
