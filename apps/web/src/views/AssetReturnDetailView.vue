<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import AssetReturnReassignment from "@/components/AssetReturnReassignment.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loanLabels as labels, loanMessage, loanRequest } from "@/loan/api";
import type { LoanMutation } from "@/loan/api";
import type { AssetReturn, ReturnChoice } from "../../../../packages/api-types/src/loan";

const props = defineProps<{ id: string }>();
const session = useSessionStore();
const selected = ref<AssetReturn | null>(null);
const busy = ref(false); const error = ref(""); const notice = ref(""); const retryable = ref(false);
const reason = ref(""); const observed = ref(""); const result = ref<"NORMAL" | "DAMAGED" | "MISSING">("NORMAL");
const warehouseId = ref(""); const locationId = ref(""); const warehouses = ref<ReturnChoice[]>([]); const locations = ref<ReturnChoice[]>([]);
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const bins = computed(() => locations.value.filter(l => l.warehouseId === warehouseId.value));
const canInspect = computed(() => warehouse.value && selected.value?.returnStatus === "WAREHOUSE_PROCESSING"
  && selected.value.processorAccountId === session.user?.id);
let pending: LoanMutation | null = null;

async function open() { selected.value = await loanRequest<AssetReturn>("/asset-returns/" + props.id); }
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try {
    await open();
    pending = null; retryable.value = false;
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "请先重试上次操作或刷新查询。"; return; }
  pending ??= { path, version, key: crypto.randomUUID(), body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    await loanRequest(pending.path, pending);
    pending = null; retryable.value = false; notice.value = "操作已保存。";
    await open();
  } catch (e) { error.value = loanMessage(e); retryable.value = pending !== null; }
  finally { busy.value = false; }
}
function retry() { if (pending) void mutate(pending.path, pending.version); }
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
  do { part = (await loanRequest<{ items: ReturnChoice[] }>(`/asset-returns/catalog/${kind}?page=${p++}&pageSize=200`)).items; rows.push(...part); }
  while (part.length === 200);
  return rows;
}
onMounted(async () => {
  busy.value = true;
  try {
    if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "没有当前记录或操作的权限。"; return; }
    await open();
    if (warehouse.value) { [warehouses.value, locations.value] = await Promise.all([catalog("warehouse"), catalog("location")]); }
    if (selected.value && new URLSearchParams(window.location.search).get("saved")) {
      notice.value = "操作已保存。";
      window.history.replaceState(null, "", window.location.pathname);
    }
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
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
        <textarea class="fams-control" v-model="reason" :disabled="busy || retryable" maxlength="1000" />
      </label>

      <div class="fams-panel-actions">
        <template v-if="admin && selected.returnStatus === 'PENDING_APPROVAL'">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="decide(selected.id, selected.version, true)">批准退还</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="decide(selected.id, selected.version, false)">驳回退还</button>
        </template>
        <button v-if="warehouse && selected.returnStatus === 'WAITING_WAREHOUSE_CLAIM'" class="fams-button" data-kind="primary" type="button"
          :disabled="busy || retryable" @click="mutate(`/asset-returns/${selected.id}/claim`, selected.version)">领取整单验收</button>
      </div>

      <AssetReturnReassignment v-if="admin && selected.returnStatus === 'WAREHOUSE_PROCESSING'"
        :id="selected.id" :version="selected.version" @saved="refresh" />

      <div v-if="canInspect" class="fams-stack">
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">验收结果</span>
            <select class="fams-control" v-model="result" :disabled="busy || retryable"><option value="NORMAL">正常入库</option><option value="DAMAGED">损坏转报修</option><option value="MISSING">实物缺失转报失</option></select></label>
          <template v-if="result !== 'MISSING'">
            <label class="fams-field"><span class="fams-field-label">实物编码 / 当前二维码</span>
              <input class="fams-control" v-model="observed" :disabled="busy || retryable" /></label>
            <label class="fams-field"><span class="fams-field-label">仓库</span>
              <select class="fams-control" v-model="warehouseId" :disabled="busy || retryable" @change="locationId = ''"><option value="">请选择</option><option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.name }}</option></select></label>
            <label class="fams-field"><span class="fams-field-label">仓位</span>
              <select class="fams-control" v-model="locationId" :disabled="busy || retryable"><option value="">请选择</option><option v-for="l in bins" :key="l.id" :value="l.id">{{ l.name }}</option></select></label>
          </template>
        </div>
        <p v-if="result === 'MISSING'" class="fams-panel-subtitle">实物缺失将保留借用责任，进入报失处理。</p>
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="inspect">提交实物验收</button>
      </div>

      <p v-if="selected.inspectionResult">验收结果 {{ selected.inspectionResult }} · {{ selected.inspectionReason }}</p>
      <p v-for="followup in selected.followups" :key="followup.id">后续 {{ followup.id }} · {{ followup.businessType === 'REPAIR' ? '损坏维修' : '报失处理' }} · {{ followup.followupStatus === 'CLOSED' ? '已实际完成' : followup.followupStatus === 'ACCEPTED' ? '正式处理中' : '待继续处理' }}<span v-if="followup.linkedRepairId || followup.lastRepairId"> · 正式维修 {{ followup.linkedRepairId || followup.lastRepairId }}（{{ followup.repairStatus === 'COMPLETED' ? '已验收入库' : followup.repairStatus === 'REJECTED' || followup.repairStatus === 'CANCELLED' ? '原维修未执行，源义务仍待处理' : '尚未实际完成' }}）</span></p>
    </section>
  </AppChrome>
</template>
