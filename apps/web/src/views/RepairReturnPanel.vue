<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, repairProblem, repairRequest, type Repair, type RepairMutation, type RepairReturnTarget } from "@/repair/api";
const props = defineProps<{ repair: Repair; blocked?: boolean }>();
const emit = defineEmits<{ changed: []; invalidated: []; busyChange: [value: boolean] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id]));
type Mode = "delivery" | "receipt" | "refusal" | "recollection";
interface Location { id: string; name: string; version: number }
const mode = ref<Mode>("delivery"), target = ref<RepairReturnTarget | null>(null);
const observed = ref(""), actual = ref(""), evidence = ref(""), checked = ref(false), error = ref(""), busy = ref(false);
const locations = ref<Location[]>([]), location = ref(""), page = ref(1), total = ref(0);
const prepared = ref<RepairMutation | null>(null), pending = ref<RepairMutation | null>(null);
const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
const handover = computed(() => props.repair.returnHandovers?.find(row => ["WAITING_RECEIPT", "REFUSED"].includes(row.handover_status)));
const allowed = (value: Mode) => !!props.repair.actions?.[({ delivery: "deliverReturn", receipt: "receiveReturn", refusal: "refuseReturn", recollection: "recollectReturn" } as const)[value]];
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
function clear() { generation++; target.value = null; locations.value = []; location.value = ""; page.value = 1; total.value = 0; observed.value = ""; actual.value = ""; evidence.value = ""; checked.value = false; prepared.value = null; pending.value = null; busy.value = false; }
function failure(cause: unknown) { if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) { clear(); emit("invalidated"); } error.value = repairProblem(cause); }
async function select(value: Mode) {
  if (locked.value || props.blocked || !allowed(value)) return;
  mode.value = value; target.value = null; checked.value = false; location.value = ""; error.value = "";
  const now = new Date(); actual.value = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 23);
  if (value !== "delivery") return;
  const epoch = ++generation, owner = identity.value; busy.value = true;
  try { const result = await repairRequest<RepairReturnTarget>(`/${props.repair.id}/return-target`); if (current(epoch, owner)) target.value = result; }
  catch (cause) { if (current(epoch, owner)) failure(cause); } finally { if (current(epoch, owner)) busy.value = false; }
}
async function catalog(delta = 0) {
  const next = page.value + delta;
  if (locked.value || props.blocked || next < 1 || delta > 0 && (next - 1) * 20 >= total.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { const result = await repairRequest<{ items: Location[]; total: number }>(`/physical-catalog/location?page=${next}&pageSize=20`);
    if (current(epoch, owner)) { locations.value = result.items; total.value = result.total; page.value = next; location.value = ""; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); } finally { if (current(epoch, owner)) busy.value = false; }
}
function prepare() {
  if (locked.value || props.blocked || !allowed(mode.value)) return;
  const row = props.repair, time = new Date(actual.value), place = locations.value.find(row => row.id === location.value);
  if (!observed.value.trim() || !Number.isFinite(time.getTime()) || evidence.value.trim().length < 10 || row.lockVersion == null
    || mode.value === "delivery" && !target.value || mode.value !== "delivery" && !handover.value
    || ["receipt", "refusal"].includes(mode.value) && !checked.value || mode.value === "recollection" && !place) {
    error.value = "请核对实物编码、实际时间和至少10字依据；接收或拒收须明确实物核实，收回须选择有效实际位置。"; return;
  }
  const body: Record<string, unknown> = { assetVersion: row.assetVersion, lockVersion: row.lockVersion, observedCode: observed.value.trim(), occurredAt: time.toISOString(), evidence: evidence.value.trim() };
  if (mode.value === "delivery") Object.assign(body, { ownershipVersion: row.ownershipVersion, loanVersion: row.loanVersion ?? null,
    employeeVersion: target.value!.employeeVersion, organizationVersion: target.value!.organizationVersion, locationVersion: target.value!.locationVersion });
  else { body.handoverId = handover.value!.id;
    if (mode.value === "recollection") Object.assign(body, { ownershipVersion: row.ownershipVersion, actualLocationId: place!.id, locationVersion: place!.version });
    else Object.assign(body, { loanVersion: row.loanVersion ?? null, physicalChecked: true });
  }
  prepared.value = { path: `/${row.id}/return-${mode.value}`, key: crypto.randomUUID(), version: row.version, body }; error.value = "";
}
async function send() {
  if (busy.value || props.blocked || !pending.value) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await repairRequest(operation.path, operation); await repairRequest<Repair>(`/${props.repair.id}`);
    if (current(epoch, owner)) { pending.value = null; target.value = null; checked.value = false; observed.value = ""; evidence.value = ""; emit("changed"); }
  } catch (cause) { if (current(epoch, owner)) { if (cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); } }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function confirm() { if (!prepared.value || busy.value || props.blocked) return; pending.value = prepared.value; prepared.value = null; await send(); }
watch(identity, () => { clear(); error.value = ""; }, { immediate: true, flush: "sync" });
watch(locked, value => emit("busyChange", value), { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; clear(); emit("busyChange", false); });
const states: Record<string, string> = { WAITING_RECEIPT: "已交出，待实际接收", REFUSED: "原目标拒收，待实际收回", RECEIVED: "原目标已实际接收", RECOLLECTED: "库管已实际收回" };
</script>
<template><section aria-label="维修实际返还"><h3>维修实际返还</h3>
<p>交出后继续维修中并持锁，只有原个人或公用责任人实际接收才恢复使用。逾期借用须先在借用台账获批延期；拒收或目标失效可由库管实际收回后验收入库。</p>
<p v-if="repair.loanExpectedReturnDate">当前原借用预计归还日：{{ repair.loanExpectedReturnDate }}</p>
<p v-for="row in repair.returnHandovers" :key="row.id">{{ states[row.handover_status] }} · {{ row.target_snapshot_json.employeeName }} · {{ row.target_snapshot_json.organizationName }} · {{ row.target_snapshot_json.locationName }} · 交出 {{ new Date(row.delivered_at).toLocaleString() }} · {{ row.delivery_evidence }}<span v-if="row.receipt_at"> · 接收决定 {{ new Date(row.receipt_at).toLocaleString() }} · {{ row.receipt_evidence }}</span></p>
<p v-for="row in repair.returnRecollections" :key="row.handover_id">实际收回 {{ new Date(row.occurred_at).toLocaleString() }} · {{ row.evidence }}</p>
<p v-if="error" role="alert">{{ error }}</p><button v-if="pending" :disabled="busy || blocked" @click="send">重试原返还办理</button>
<section v-if="prepared" aria-label="确认实际返还办理"><p>请按实物事实确认：交出保留维修和锁；接收后恢复原责任与原借用；拒收继续持锁，实际收回才可回库。</p><button :disabled="busy || blocked" @click="confirm">确认保存实际返还办理</button><button :disabled="busy" @click="prepared=null">返回修改返还依据</button></section>
<fieldset v-if="allowed('delivery') || allowed('receipt') || allowed('refusal') || allowed('recollection')" :disabled="locked || blocked"><legend>按实物事实选择办理</legend>
<button v-if="allowed('delivery')" @click="select('delivery')">核实原目标并记录实际交出</button><button v-if="allowed('receipt')" @click="select('receipt')">我已实际接收修复设备</button><button v-if="allowed('refusal')" @click="select('refusal')">记录实际拒收及原因</button><button v-if="allowed('recollection')" @click="select('recollection')">核实库管已实际收回</button>
<p v-if="mode==='delivery' && target">本次原目标：{{ target.employeeName }} · {{ target.organizationName }} · {{ target.locationName }}。请确认已经修复并实际交出，不能代替接收人确认。</p>
<template v-if="mode==='recollection'"><button @click="catalog()">查询实际收回位置</button><label>实际收回位置<select v-model="location"><option value="">请选择当前页有效位置</option><option v-for="row in locations" :key="row.id" :value="row.id">{{ row.name }}</option></select></label><button :disabled="page===1" @click="catalog(-1)">上一页收回位置</button>共{{ total }}项<button :disabled="page*20>=total" @click="catalog(1)">下一页收回位置</button></template>
<label>本次实物编码<input v-model="observed" maxlength="2048"></label><label>本次实际时间<input v-model="actual" type="datetime-local" step="0.001"></label><label v-if="mode==='receipt' || mode==='refusal'"><input v-model="checked" type="checkbox">我已亲自核实本次实物及接收决定</label><label>本次实际交接或拒收依据<textarea v-model="evidence" maxlength="2000"/></label><button @click="prepare">核对本次返还办理</button></fieldset>
</section></template>
<style scoped>section{margin:18px 0;}fieldset{border:1px solid #d9e0e8;padding:16px;}label{display:block;margin:12px 0;}textarea{display:block;width:min(100%,720px);min-height:70px;}button{margin:6px;}</style>
