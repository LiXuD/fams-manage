<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loanMessage, loanRequest, type LoanMutation } from "@/loan/api";
import { requisitionRequest } from "@/requisition/api";

type Target = { custodyType: string; organizationId: string; organizationVersion: number;
  employeeId: string; employeeVersion: number; locationId: string; locationVersion: number };
type Snapshot = { businessId: string; itemId: string; version: number; assetCode: string;
  itemStatus: string; target: Target; handover: { destinationText: string; deliveredAt: string }[] };
type Proposal = { id: string; business_id: string; item_id: string; version: number; change_status: string;
  source_snapshot_json: Snapshot; proposed_target_json: Target; reason: string; physical_verification: string;
  creator_account_id: string; approver_account_id: string | null; decision_reason: string | null };
type Choice = { id: string; name: string; version: number; organizationId?: string };
const route = useRoute(); const session = useSessionStore();
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const rows = ref<Proposal[]>([]); const selected = ref<Proposal | null>(null); const snapshot = ref<Snapshot | null>(null);
const target = ref<Target | null>(null); const catalogs = ref<Record<string, Choice[]>>({});
const page = ref(1); const total = ref(0); const error = ref(""); const busy = ref(false);
const reason = ref(""); const physicalVerification = ref(""); const physicalChecked = ref(false); const observedCode = ref("");
const decisionReason = ref(""); const pending = ref<LoanMutation | null>(null);
const locked = computed(() => busy.value || !!pending.value);
const labels: Record<string, string> = { PENDING: "待管理员批准", APPROVED: "已批准", REJECTED: "已驳回" };
function name(kind: string, id: string) { return catalogs.value[kind]?.find(row => row.id === id)?.name ?? id; }
async function list() {
  const result = await loanRequest<{ items: Proposal[]; total: number }>(`/requisition-target-changes?page=${page.value}&pageSize=20`);
  rows.value = result.items; total.value = result.total;
  if (selected.value) selected.value = rows.value.find(row => row.id === selected.value?.id) ?? selected.value;
}
async function refresh() {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try { await list(); } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
function setReference(kind: "organization" | "employee" | "location", event: Event) {
  if (!target.value) return;
  const id = (event.target as HTMLSelectElement).value;
  const choice = catalogs.value[kind]?.find(row => row.id === id);
  if (!choice) return;
  target.value[`${kind}Id`] = choice.id; target.value[`${kind}Version`] = choice.version;
  if (kind === "organization") { target.value.employeeId = ""; target.value.employeeVersion = 0; }
}
async function send(path: string, body: unknown, version: number) {
  if (busy.value || pending.value && pending.value.path !== path) return;
  pending.value ??= { path, key: crypto.randomUUID(), version, body: JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const result = await loanRequest<Proposal>(pending.value.path, pending.value);
    await list(); selected.value = result; snapshot.value = null; pending.value = null;
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
function propose() {
  if (!snapshot.value || !target.value || !physicalChecked.value || !window.confirm("提交目标变更供管理员确认？批准前仍以原目标为准。")) return;
  void send("/requisition-target-changes", { businessId: snapshot.value.businessId, itemId: snapshot.value.itemId,
    snapshot: snapshot.value, target: target.value, reason: reason.value, observedAssetCode: observedCode.value,
    physicalVerification: physicalVerification.value, physicalChecked: physicalChecked.value }, snapshot.value.version);
}
function decide(result: string) {
  if (!selected.value || !window.confirm(result === "APPROVED" ? "确认批准此目标变更？责任仍在实际接收后转移。" : "确认驳回此目标变更？")) return;
  void send(`/requisition-target-changes/${selected.value.id}/decision`, { result, reason: decisionReason.value }, selected.value.version);
}
onMounted(async () => {
  busy.value = true;
  try {
    for (const kind of ["organization", "employee", "location"]) {
      const choices: Choice[] = []; let p = 1; let result: { items: Choice[]; total: number };
      do { result = await requisitionRequest(`/catalog/${kind}?page=${p++}&pageSize=200`); choices.push(...result.items); }
      while (result.items.length === 200); catalogs.value[kind] = choices;
    }
    await list();
    if (warehouse.value && route.query.businessId && route.query.itemId) {
      snapshot.value = await loanRequest<Snapshot>(`/requisition-target-changes/context?businessId=${encodeURIComponent(String(route.query.businessId))}&itemId=${encodeURIComponent(String(route.query.itemId))}`);
      target.value = { ...snapshot.value.target };
    }
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome><main class="target-page"><h1>领用与借用目标变更</h1>
    <p>仅变更未完成明细。已交出的实物保持原交出记录和占用锁，批准不会提前转移资产责任。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button :disabled="busy" @click="refresh">刷新</button>
    <button v-if="pending" :disabled="busy" @click="send(pending.path, pending.body, pending.version)">重试原操作</button>
    <form v-if="snapshot && target && warehouse" @submit.prevent="propose">
      <h2>提请目标变更 · {{ snapshot.assetCode }}</h2>
      <p>原目标：{{ name('organization', snapshot.target.organizationId) }} / {{ name('employee', snapshot.target.employeeId) }} / {{ name('location', snapshot.target.locationId) }}</p>
      <p v-for="handover in snapshot.handover" :key="handover.deliveredAt">已交出地点：{{ handover.destinationText }} · {{ handover.deliveredAt }}</p>
      <fieldset :disabled="locked"><label>使用方式<select v-model="target.custodyType"><option value="PERSONAL">个人使用</option><option value="SHARED">部门公用（借用不适用）</option></select></label>
        <label>新使用组织<select :value="target.organizationId" @change="setReference('organization', $event)"><option v-for="c in catalogs.organization" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label>新责任人员<select required :value="target.employeeId" @change="setReference('employee', $event)"><option value="" disabled>请选择</option><option v-for="c in catalogs.employee?.filter(c => c.organizationId === target!.organizationId)" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label>新固定位置<select :value="target.locationId" @change="setReference('location', $event)"><option v-for="c in catalogs.location" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label>变更原因<textarea v-model="reason" required maxlength="1000" /></label>
        <label>实物资产编码<input v-model="observedCode" required maxlength="128"></label>
        <label>实物去向与核对记录<textarea v-model="physicalVerification" required maxlength="1000" /></label>
        <label><input v-model="physicalChecked" type="checkbox" required>已核对实物及去向，未将已交出资产自动退回库存</label>
        <button type="submit">提交目标变更</button></fieldset>
    </form>
    <h2>变更记录</h2>
    <button v-for="row in rows" :key="row.id" :disabled="locked" @click="selected = row">{{ row.source_snapshot_json.assetCode }} · {{ labels[row.change_status] }}</button>
    <div><button :disabled="locked || page === 1" @click="page--; refresh()">上一页</button> {{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }} <button :disabled="locked || page * 20 >= total" @click="page++; refresh()">下一页</button></div>
    <section v-if="selected"><h2>目标变更 #{{ selected.id }} · {{ labels[selected.change_status] }}</h2>
      <p>原目标：{{ name('organization', selected.source_snapshot_json.target.organizationId) }} / {{ name('employee', selected.source_snapshot_json.target.employeeId) }} / {{ name('location', selected.source_snapshot_json.target.locationId) }}</p>
      <p>新目标：{{ selected.proposed_target_json.custodyType === 'PERSONAL' ? '个人使用' : '部门公用' }} · {{ name('organization', selected.proposed_target_json.organizationId) }} / {{ name('employee', selected.proposed_target_json.employeeId) }} / {{ name('location', selected.proposed_target_json.locationId) }}</p>
      <p>原因：{{ selected.reason }}</p><p>实物核对：{{ selected.physical_verification }}</p>
      <p v-for="h in selected.source_snapshot_json.handover" :key="h.deliveredAt">原交出地点：{{ h.destinationText }}</p>
      <p v-if="selected.decision_reason">管理员意见：{{ selected.decision_reason }}</p>
      <router-link :to="{ path: '/requisitions', query: { id: selected.business_id } }">查看原申请</router-link>
      <fieldset v-if="admin && selected.change_status === 'PENDING'" :disabled="locked"><label>核验意见<textarea v-model="decisionReason" maxlength="1000" /></label><button :disabled="!decisionReason.trim()" @click="decide('APPROVED')">批准目标变更</button><button :disabled="!decisionReason.trim()" @click="decide('REJECTED')">驳回</button></fieldset>
    </section>
  </main></AppChrome>
</template>
<style scoped>
.target-page { max-width: 1100px; margin: auto; padding: 24px; }
label { display: block; margin: 12px 0; } input:not([type=checkbox]), select, textarea { display: block; min-width: 320px; padding: 8px; }
button { margin: 8px; padding: 8px 14px; } form, section { margin: 20px 0; padding: 20px; border: 1px solid #d7dee8; }
[role=alert] { color: #b42318; }
</style>
