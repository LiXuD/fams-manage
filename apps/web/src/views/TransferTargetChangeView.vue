<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loanMessage, loanRequest, type LoanMutation } from "@/loan/api";

type Choice = { id: string; name: string; version: number; locationType?: string; warehouseId?: string; sharedEligible?: boolean };
type Target = { owningOrganizationId: string | null; custodyType: string; usingOrganizationId: string | null;
  employeeId: string | null; warehouseId: string | null; locationId: string; referenceVersions: Record<string, number> };
type Snapshot = { businessId: string; itemId: string; version: number; assetCode: string; itemStatus: string;
  target: Target; source: { lifecycleStatus: string }; physical: { destination?: string; deliveryEvidence?: string; deliveredAt?: string } };
type Change = { id: string; business_id: string; item_id: string; change_status: string; version: number;
  source_snapshot_json: Snapshot; proposed_target_json: Target; reason: string; physical_verification: string; decision_reason?: string };
const route = useRoute(); const session = useSessionStore();
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const operator = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const rows = ref<Change[]>([]); const selected = ref<Change | null>(null); const snapshot = ref<Snapshot | null>(null);
const target = ref<Target | null>(null); const catalogs = ref<Record<string, Choice[]>>({}); const employees = ref<Choice[]>([]);
const page = ref(1); const total = ref(0); const busy = ref(false); const error = ref(""); const pending = ref<LoanMutation | null>(null);
const reason = ref(""); const code = ref(""); const physical = ref(""); const checked = ref(false); const decision = ref("");
const locked = computed(() => busy.value || !!pending.value); const stock = computed(() => snapshot.value?.source.lifecycleStatus === "IN_STOCK");
const places = computed(() => catalogs.value.location?.filter(row => stock.value
  ? row.locationType === "BIN" && row.warehouseId === target.value?.warehouseId : row.locationType !== "BIN") ?? []);
const labels: Record<string, string> = { PENDING: "待管理员批准", APPROVED: "已批准", REJECTED: "已驳回" };
const base = "/item-target-changes";
async function catalog(kind: string, organization?: string) {
  const all: Choice[] = []; let p = 1; let items: Choice[];
  do { items = (await loanRequest<{ items: Choice[] }>(`/transfers/catalog/${kind}?page=${p++}&pageSize=200${organization ? `&organizationId=${organization}` : ""}`)).items; all.push(...items); }
  while (items.length === 200); return all;
}
function name(kind: string, id: string | null) { return id ? catalogs.value[kind]?.find(row => row.id === id)?.name ?? id : "未指定"; }
function targetText(value: Target) {
  return `${value.custodyType === "STORAGE" ? "仓库保管" : value.custodyType === "SHARED" ? "部门公用" : "个人使用"} / ${name("organization", value.usingOrganizationId)} / ${value.employeeId ?? name("warehouse", value.warehouseId)} / ${name("location", value.locationId)}`;
}
async function list() {
  const value = await loanRequest<{ items: Change[]; total: number }>(`${base}?businessType=TRANSFER&page=${page.value}&pageSize=20`);
  rows.value = value.items; total.value = value.total;
  if (selected.value) selected.value = rows.value.find(row => row.id === selected.value?.id) ?? null;
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await list(); } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function chooseOrganization() {
  if (!target.value || locked.value) return;
  target.value.employeeId = null; busy.value = true; error.value = "";
  try { employees.value = target.value.usingOrganizationId ? await catalog("employee", target.value.usingOrganizationId) : []; }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
function captureTarget(): Target | null {
  const value = target.value; if (!value) return null;
  const location = places.value.find(row => row.id === value.locationId);
  const owner = catalogs.value.organization?.find(row => row.id === value.owningOrganizationId);
  const organization = catalogs.value.organization?.find(row => row.id === value.usingOrganizationId);
  const employee = employees.value.find(row => row.id === value.employeeId);
  const warehouse = catalogs.value.warehouse?.find(row => row.id === value.warehouseId);
  if (!location || value.owningOrganizationId && !owner || stock.value && !warehouse
    || !stock.value && (!organization || !employee || value.custodyType === "SHARED" && !employee.sharedEligible)) {
    error.value = "请选择有效目标组织、责任人或仓库及位置；部门公用责任人须为有效部门资产员。"; return null;
  }
  const versions: Record<string, number> = { location: location.version };
  if (owner) versions.owningOrganization = owner.version;
  if (stock.value) versions.warehouse = warehouse!.version;
  else { versions.usingOrganization = organization!.version; versions.employee = employee!.version; }
  return { owningOrganizationId: owner?.id ?? null, custodyType: stock.value ? "STORAGE" : value.custodyType,
    usingOrganizationId: stock.value ? null : organization!.id, employeeId: stock.value ? null : employee!.id,
    warehouseId: stock.value ? warehouse!.id : null, locationId: location.id, referenceVersions: versions };
}
async function send(path: string, body?: unknown, version = selected.value?.version ?? 0) {
  if (busy.value || pending.value && pending.value.path !== path) return;
  pending.value ??= { path, key: crypto.randomUUID(), version, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try { const result = await loanRequest<Change>(pending.value.path, pending.value); await list(); selected.value = result; pending.value = null; snapshot.value = null; }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
function propose() {
  const proposed = captureTarget(); if (!snapshot.value || !proposed || !checked.value || !reason.value.trim() || !code.value.trim() || !physical.value.trim()) return;
  if (!window.confirm("提请调拨目标变更？已交出实物和原责任保持，批准后新目标仍需按原业务实际接收。")) return;
  void send(`${base}?businessType=TRANSFER`, { businessId: snapshot.value.businessId, itemId: snapshot.value.itemId,
    snapshot: snapshot.value, target: proposed, reason: reason.value.trim(), observedAssetCode: code.value.trim(),
    physicalVerification: physical.value.trim(), physicalChecked: true }, snapshot.value.version);
}
function decide(result: string) {
  if (!selected.value || !decision.value.trim() || !window.confirm(result === "APPROVED" ? "批准本次新目标？这不会完成接收或解除实物锁。" : "驳回此目标变更？")) return;
  void send(`${base}/${selected.value.id}/decision?businessType=TRANSFER`, { result, reason: decision.value.trim() }, selected.value.version);
}
async function paginate(delta: number) { if (!locked.value) { page.value += delta; selected.value = null; await refresh(); } }
onMounted(async () => {
  busy.value = true;
  try {
    for (const kind of ["organization", "warehouse", "location"]) catalogs.value[kind] = await catalog(kind);
    await list();
    if (operator.value && route.query.businessId && route.query.itemId) {
      snapshot.value = await loanRequest<Snapshot>(`${base}/context?businessType=TRANSFER&businessId=${encodeURIComponent(String(route.query.businessId))}&itemId=${encodeURIComponent(String(route.query.itemId))}`);
      target.value = { ...snapshot.value.target, referenceVersions: { ...snapshot.value.target.referenceVersions } };
      if (target.value.usingOrganizationId) employees.value = await catalog("employee", target.value.usingOrganizationId);
    }
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>
<template>
  <AppChrome>
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="refresh">刷新</button>
    </template>
    <section class="fams-panel fams-stack" aria-label="调拨目标变更">
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="send(pending.path)">重试原操作</button>
      <form v-if="snapshot && target && operator" class="fams-card fams-stack" @submit.prevent="propose">
        <div class="fams-panel-head">
          <div>
            <h3 class="fams-panel-title">提请目标变更 · {{ snapshot.assetCode }}</h3>
            <p class="fams-panel-subtitle">原批准目标：{{ targetText(snapshot.target) }}</p>
          </div>
        </div>
        <p v-if="snapshot.physical.destination">原交出地点：{{ snapshot.physical.destination }} · {{ snapshot.physical.deliveryEvidence }}</p>
        <fieldset class="fams-stack" :disabled="locked">
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">新所属组织</span><select class="fams-control" v-model="target.owningOrganizationId"><option :value="null">未指定</option><option v-for="c in catalogs.organization" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
            <template v-if="!stock">
              <label class="fams-field"><span class="fams-field-label">新使用组织</span><select class="fams-control" v-model="target.usingOrganizationId" @change="chooseOrganization"><option :value="null">请选择</option><option v-for="c in catalogs.organization" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
              <label class="fams-field"><span class="fams-field-label">新责任模式</span><select class="fams-control" v-model="target.custodyType"><option value="PERSONAL">个人使用</option><option value="SHARED">部门公用</option></select></label>
              <label class="fams-field"><span class="fams-field-label">新责任人员</span><select class="fams-control" v-model="target.employeeId"><option :value="null">请选择</option><option v-for="c in employees.filter(row => target!.custodyType !== 'SHARED' || row.sharedEligible)" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
            </template>
            <label v-else class="fams-field"><span class="fams-field-label">新目标仓库</span><select class="fams-control" v-model="target.warehouseId" @change="target.locationId = ''"><option :value="null">请选择</option><option v-for="c in catalogs.warehouse" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
            <label class="fams-field"><span class="fams-field-label">新固定位置</span><select class="fams-control" v-model="target.locationId"><option value="">请选择</option><option v-for="c in places" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">变更原因</span><textarea class="fams-control" v-model="reason" required maxlength="1000" /></label>
            <label class="fams-field"><span class="fams-field-label">实物资产编码</span><input class="fams-control" v-model="code" required maxlength="128"></label>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">实物去向与核对记录</span><textarea class="fams-control" v-model="physical" required maxlength="1000" /></label>
          </div>
          <label class="fams-selection"><input v-model="checked" type="checkbox" required>已核对实物及去向，未自动收回或解除业务锁</label>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit">提交调拨目标变更</button></div>
        </fieldset>
      </form>
      <div class="fams-panel-head"><h3 class="fams-panel-title">调拨目标变更记录</h3></div>
      <ul class="fams-timeline">
        <li v-for="row in rows" :key="row.id"><button class="fams-button" data-size="sm" type="button" :disabled="locked" @click="selected = row; decision = ''">{{ row.source_snapshot_json.assetCode }} · {{ labels[row.change_status] }}</button></li>
      </ul>
      <section v-if="selected" class="fams-card fams-stack">
        <div class="fams-panel-head"><h3 class="fams-panel-title">目标变更 #{{ selected.id }} · {{ labels[selected.change_status] }}</h3></div>
        <p>原目标：{{ targetText(selected.source_snapshot_json.target) }}</p><p>新目标：{{ targetText(selected.proposed_target_json) }}</p>
        <p>原因：{{ selected.reason }}</p><p>实物核对：{{ selected.physical_verification }}</p><p v-if="selected.source_snapshot_json.physical.destination">原交出地点：{{ selected.source_snapshot_json.physical.destination }}</p>
        <p v-if="selected.decision_reason">决定意见：{{ selected.decision_reason }}</p><router-link class="fams-button" data-kind="link" :to="{ path: '/transfers', query: { id: selected.business_id } }">查看原调拨单</router-link>
        <fieldset v-if="admin && selected.change_status === 'PENDING'" class="fams-stack" :disabled="locked">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">核验意见</span><textarea class="fams-control" v-model="decision" maxlength="1000" /></label>
          <div class="fams-inline">
            <button class="fams-button" data-kind="primary" type="button" :disabled="!decision.trim()" @click="decide('APPROVED')">批准调拨目标变更</button>
            <button class="fams-button" data-kind="danger" type="button" :disabled="!decision.trim()" @click="decide('REJECTED')">驳回</button>
          </div>
        </fieldset>
      </section>
      <div class="fams-pagination">
        <span class="fams-pagination-page">{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page === 1" @click="paginate(-1)">上一页</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page * 20 >= total" @click="paginate(1)">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
