<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { LoanApiError, loanMessage, loanRequest, type LoanMutation } from "@/loan/api";
import type { Repair, RepairReturnTarget } from "@/repair/api";
const props = defineProps<{ repair: Repair; blocked?: boolean }>();
const emit = defineEmits<{ changed: []; invalidated: []; busyChange: [value: boolean] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id]));
const admin = computed(() => !!session.user?.roles.includes("ASSET_ADMIN"));
interface Choice { id: string; name: string; version: number; organizationId?: string; locationType?: string; sharedEligible?: boolean }
interface Snapshot { version: number; businessId: string; itemId: string; assetCode: string; target: Record<string, unknown> }
interface Proposal { id: string; business_id: string; version: number; change_status: string; reason: string;
  physical_verification: string; targetDisplay?: { employeeName: string; organizationName: string; locationName: string }; proposed_target_json: Record<string, unknown>; decision_reason: string | null }
const base = "/item-target-changes", type = "businessType=REPAIR";
const rows = ref<Proposal[]>([]), snapshot = ref<Snapshot | null>(null);
const page = ref(1), total = ref(0), busy = ref(false), error = ref("");
const choices = ref<Choice[]>([]), kind = ref<"organization" | "employee" | "location">("organization");
const catalogPage = ref(1), catalogMore = ref(false);
const organization = ref<Choice | null>(null), employee = ref<Choice | null>(null), location = ref<Choice | null>(null);
const custody = ref("PERSONAL"), reason = ref(""), code = ref(""), evidence = ref(""), checked = ref(false), decision = ref("");
const prepared = ref<LoanMutation | null>(null), pending = ref<LoanMutation | null>(null);
const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
function clear() {
  generation++; rows.value = []; snapshot.value = null; choices.value = []; organization.value = null;
  employee.value = null; location.value = null; checked.value = false; reason.value = ""; code.value = "";
  evidence.value = ""; decision.value = ""; prepared.value = null; pending.value = null;
  busy.value = false; page.value = 1; total.value = 0; catalogPage.value = 1; catalogMore.value = false;
}
function failure(cause: unknown) {
  if (cause instanceof LoanApiError && [401, 403, 404].includes(cause.status)) { clear(); emit("invalidated"); }
  error.value = loanMessage(cause);
}
async function list(delta = 0) {
  const next = page.value + delta;
  if (locked.value || props.blocked || next < 1 || delta > 0 && (next - 1) * 20 >= total.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const result = await loanRequest<{ items: Proposal[]; total: number }>(base + "?" + type + "&businessId=" + props.repair.id + "&page=" + next + "&pageSize=20");
    if (current(epoch, owner)) { rows.value = result.items; total.value = result.total; page.value = next; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function begin() {
  if (locked.value || props.blocked || !props.repair.actions?.changeReturnTarget) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const result = await loanRequest<Snapshot>(base + "/context?" + type + "&businessId=" + props.repair.id + "&itemId=" + props.repair.assetId);
    if (current(epoch, owner)) { snapshot.value = result; organization.value = null; employee.value = null; location.value = null; checked.value = false; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function catalog(value: typeof kind.value, delta = 0) {
  const next = value === kind.value ? catalogPage.value + delta : 1;
  if (locked.value || props.blocked || next < 1 || value === "employee" && !organization.value) return;
  if (value === kind.value && delta > 0 && !catalogMore.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const result = await loanRequest<{ items: Choice[] }>("/transfers/catalog/" + value
      + "?page=" + next + "&pageSize=20" + (value === "employee" ? "&organizationId=" + organization.value!.id : ""));
    if (current(epoch, owner)) { kind.value = value; choices.value = result.items.filter(row => value === "location" ? row.locationType !== "BIN"
      : value !== "employee" || custody.value !== "SHARED" || row.sharedEligible);
      catalogPage.value = next; catalogMore.value = result.items.length === 20; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function choose(event: Event) {
  if (locked.value || props.blocked) return;
  const row = choices.value.find(row => row.id === (event.target as HTMLSelectElement).value);
  if (!row) return;
  if (kind.value === "organization") { organization.value = row; employee.value = null; }
  else if (kind.value === "employee") employee.value = row;
  else location.value = row;
}
function propose() {
  if (locked.value || props.blocked || !props.repair.actions?.changeReturnTarget || !snapshot.value) return;
  if (!organization.value || !employee.value || !location.value || !checked.value || reason.value.trim().length < 3
    || evidence.value.trim().length < 10 || !code.value.trim()) { error.value = "请选择有效新组织、责任人和位置，并明确实物核实及变更原因。"; return; }
  const target = { custodyType: custody.value, organizationId: organization.value.id, organizationVersion: organization.value.version,
    employeeId: employee.value.id, employeeVersion: employee.value.version, locationId: location.value.id, locationVersion: location.value.version };
  prepared.value = { path: base + "?" + type, key: crypto.randomUUID(), version: snapshot.value.version,
    body: structuredClone({ businessId: props.repair.id, itemId: props.repair.assetId, snapshot: JSON.parse(JSON.stringify(snapshot.value)) as unknown,
      target, reason: reason.value.trim(), observedAssetCode: code.value.trim(), physicalVerification: evidence.value.trim(), physicalChecked: true }) };
}
function decide(row: Proposal, result: "APPROVED" | "REJECTED") {
  if (locked.value || props.blocked || !admin.value || row.business_id !== props.repair.id || decision.value.trim().length < 3) return;
  prepared.value = { path: base + "/" + row.id + "/decision?" + type, key: crypto.randomUUID(), version: row.version,
    body: { result, reason: decision.value.trim() } };
}
async function send() {
  if (busy.value || props.blocked || !pending.value) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    await loanRequest(operation.path, operation);
    if (current(epoch, owner)) { pending.value = null; snapshot.value = null; checked.value = false; rows.value = []; emit("changed"); }
  } catch (cause) {
    if (current(epoch, owner)) { if (cause instanceof LoanApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); }
  } finally { if (current(epoch, owner)) busy.value = false; }
}
async function confirm() { if (!prepared.value || busy.value || props.blocked) return; pending.value = prepared.value; prepared.value = null; await send(); }
watch(identity, () => { clear(); error.value = ""; }, { immediate: true, flush: "sync" });
watch(locked, value => emit("busyChange", value), { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; clear(); emit("busyChange", false); });
const states: Record<string, string> = { PENDING: "待管理员决定", APPROVED: "已批准新目标", REJECTED: "已驳回" };
const approved = computed(() => props.repair.approvedReturnTargets ?? []);
const targetText = (target: RepairReturnTarget) => [target.employeeName, target.organizationName, target.locationName].join(" · ");
</script>
<template>
  <section v-if="admin || repair.actions?.changeReturnTarget || approved.length || pending || prepared || snapshot" class="fams-card fams-stack" aria-label="维修返还目标变更">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">维修返还目标变更</h3>
        <p class="fams-panel-subtitle">原目标失效或拒收时，先核对实物去向；已交出的设备必须实际收回。管理员批准后，仍由库管实际交出、新责任人实际接收。借用不得在此变更借用人或机构。</p>
      </div>
      <div v-if="pending" class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || blocked" @click="send">重试原目标变更操作</button>
      </div>
    </div>
    <ul v-if="approved.length" class="fams-timeline">
      <li v-for="row in approved" :key="row.id">已批准返还目标：{{ targetText(row.target_json) }}<small>{{ new Date(row.approved_at).toLocaleString() }}</small></li>
    </ul>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <div v-if="prepared" class="fams-card fams-stack" role="group" aria-label="确认维修目标变更">
      <p>核对新目标和实物依据。批准只决定返还目标，当前维修和资产锁继续保留。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || blocked" @click="confirm">确认保存目标变更操作</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="prepared=null">返回修改目标变更</button>
      </div>
    </div>
    <button v-if="repair.actions?.changeReturnTarget" class="fams-button" data-kind="secondary" type="button" :disabled="locked || blocked" @click="begin">核实本单并提请新返还目标</button>
    <fieldset v-if="snapshot" class="fams-card fams-stack" :disabled="locked || blocked">
      <legend class="fams-panel-title">本次实际新目标</legend>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">新保管方式</span><select class="fams-control" v-model="custody"><option value="PERSONAL">个人使用</option><option value="SHARED">部门公用</option></select></label>
      </div>
      <p>已选组织：{{ organization?.name || '未选择' }} · 责任人：{{ employee?.name || '未选择' }} · 位置：{{ location?.name || '未选择' }}</p>
      <div class="fams-inline">
        <button class="fams-button" data-kind="secondary" type="button" @click="catalog('organization')">查询新使用组织</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="!organization" @click="catalog('employee')">查询新责任人员</button>
        <button class="fams-button" data-kind="secondary" type="button" @click="catalog('location')">查询新固定位置</button>
      </div>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">当前页新目标选项</span><select class="fams-control" @change="choose"><option value="">请选择当前有效选项</option><option v-for="row in choices" :key="kind+row.id" :value="row.id">{{ row.name }}</option></select></label>
      </div>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">当前页{{ choices.length }}项</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="catalogPage===1" @click="catalog(kind,-1)">上一页目标选项</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="!catalogMore" @click="catalog(kind,1)">下一页目标选项</button>
        </div>
      </div>
      <div class="fams-field-grid">
        <label class="fams-field fams-field-wide"><span class="fams-field-label">目标变更原因</span><textarea class="fams-control" v-model="reason" maxlength="1000"/></label>
        <label class="fams-field"><span class="fams-field-label">目标变更实物编码</span><input class="fams-control" v-model="code" maxlength="128"></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">目标变更实物核对依据</span><textarea class="fams-control" v-model="evidence" maxlength="1000"/></label>
      </div>
      <label class="fams-selection"><input v-model="checked" type="checkbox">我已核实实物与新接收人及位置，批准不会提前转移责任</label>
      <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="propose">核对提请目标变更</button></div>
    </fieldset>
    <button v-if="admin || repair.actions?.changeReturnTarget" class="fams-button" data-kind="secondary" type="button" :disabled="locked || blocked" @click="list()">查询维修目标变更申请</button>
    <article v-for="row in rows.filter(row=>row.business_id===repair.id)" :key="row.id" class="fams-card fams-stack">
      <h4 class="fams-panel-title">{{ states[row.change_status] }}</h4>
      <p>{{ row.reason }} · {{ row.physical_verification }}</p>
      <p>新组织 {{ row.targetDisplay?.organizationName || "待核实" }} · 新责任人 {{ row.targetDisplay?.employeeName || "待核实" }} · 新位置 {{ row.targetDisplay?.locationName || "待核实" }}</p>
      <p v-if="row.decision_reason">{{ row.decision_reason }}</p>
      <fieldset v-if="admin && row.change_status==='PENDING'" class="fams-card fams-stack" :disabled="locked || blocked">
        <label class="fams-field fams-field-wide"><span class="fams-field-label">新目标决定原因</span><textarea class="fams-control" v-model="decision" maxlength="1000"/></label>
        <div class="fams-inline">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="decide(row,'APPROVED')">核对批准本单新目标</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="decide(row,'REJECTED')">核对驳回本单新目标</button>
        </div>
      </fieldset>
    </article>
    <div class="fams-pagination">
      <span class="fams-pagination-summary">本单维修变更申请第{{ page }}页，共{{ total }}条。</span>
      <div class="fams-pagination-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || blocked || page===1" @click="list(-1)">上一页变更申请</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || blocked || page*20>=total" @click="list(1)">下一页变更申请</button>
      </div>
    </div>
  </section>
</template>
