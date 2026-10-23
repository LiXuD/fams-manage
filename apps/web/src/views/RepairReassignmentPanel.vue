<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, repairProblem, repairRequest, type Repair, type RepairMutation } from "@/repair/api";
const props = defineProps<{ repair: Repair; blocked?: boolean }>();
const emit = defineEmits<{ changed: []; invalidated: []; busyChange: [value: boolean] }>();
const session = useSessionStore();
interface Context { snapshot: { version: number; processorAccountId: string; ownershipVersion: number; acceptedAt: string | null;
  physicalHandling: unknown[]; outstandingReturn: { handover_status: string } | null }; operators: { id: string; name: string }[] }
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id]));
const admin = computed(() => !!session.user?.roles.includes("ASSET_ADMIN"));
const context = ref<Context | null>(null), target = ref(""), reason = ref(""), evidence = ref(""), checked = ref(false), error = ref("");
const busy = ref(false), prepared = ref<RepairMutation | null>(null), pending = ref<RepairMutation | null>(null);
const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && generation === epoch && identity.value === owner;
function clear() { generation++; context.value = null; target.value = ""; reason.value = ""; evidence.value = ""; checked.value = false;
  prepared.value = null; pending.value = null; busy.value = false; }
function failure(cause: unknown) { if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) { clear(); emit("invalidated"); } error.value = repairProblem(cause); }
async function inspect() {
  if (locked.value || props.blocked || !admin.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { const result = await repairRequest<Context>(`/${props.repair.id}/reassignment`);
    if (current(epoch, owner)) { context.value = result; target.value = ""; checked.value = false; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function prepare() {
  if (locked.value || props.blocked || !admin.value || !context.value) return;
  if (!context.value.operators.some(row => row.id === target.value) || !checked.value || reason.value.trim().length < 3 || evidence.value.trim().length < 10) {
    error.value = "请选择有效接任库管，核对未同步结果并填写原因及实际交接凭证。"; return;
  }
  prepared.value = { path: `/${props.repair.id}/reassignment`, key: crypto.randomUUID(), version: context.value.snapshot.version,
    body: { processorAccountId: target.value, snapshot: JSON.parse(JSON.stringify(context.value.snapshot)) as unknown,
      unsynchronizedResultsChecked: true, reason: reason.value.trim(), evidence: evidence.value.trim() } };
}
async function send() {
  if (busy.value || props.blocked || !pending.value) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await repairRequest(operation.path, operation);
    if (current(epoch, owner)) { pending.value = null; context.value = null; checked.value = false; emit("changed"); }
  } catch (cause) { if (current(epoch, owner)) { if (cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); } }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function confirm() { if (!prepared.value || busy.value || props.blocked) return; pending.value = prepared.value; prepared.value = null; await send(); }
watch(identity, () => { clear(); error.value = ""; }, { immediate: true, flush: "sync" });
watch(locked, value => emit("busyChange", value), { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; clear(); emit("busyChange", false); });
</script>
<template><section v-if="admin && repair.status === 'WAREHOUSE_PROCESSING' || pending || prepared" aria-label="维修受控转派">
<h3>维修受控转派</h3><p>原库管无法继续时，由管理员核对交接后明确转派。实物去向、原审批、接收人待办及资产锁继续保留。</p>
<p v-if="error" role="alert">{{ error }}</p><button :disabled="locked || blocked" @click="inspect">核对实物阶段及接任库管</button>
<button v-if="pending" :disabled="busy || blocked" @click="send">重试原维修转派</button>
<section v-if="prepared" aria-label="确认维修转派"><p>确认按当前核实结果移交唯一处理权。已交出设备仍须实际接收或收回。</p>
<button :disabled="busy || blocked" @click="confirm">确认保存维修转派</button><button :disabled="busy" @click="prepared=null">返回修改转派</button></section>
<fieldset v-if="context" :disabled="locked || blocked"><legend>实物与未同步结果核对</legend>
<p>当前实物阶段：{{ context.snapshot.outstandingReturn ? context.snapshot.outstandingReturn.handover_status === 'REFUSED' ? '已拒收，等待实际收回' : '已交出，等待实际接收' : context.snapshot.physicalHandling.length ? '已离开原保管人，当前库管处理' : context.snapshot.acceptedAt ? '已原地接修' : '尚未接修' }}。</p>
<label>接任库管<select v-model="target"><option value="">请选择</option><option v-for="row in context.operators" :key="row.id" :value="row.id">{{ row.name }}</option></select></label>
<label>转派原因<textarea v-model="reason" maxlength="1000" /></label><label>实际交接凭证<textarea v-model="evidence" maxlength="2000" /></label>
<label><input v-model="checked" type="checkbox">已核对未交出、已交出、待收回、未同步结果和资产锁</label><button @click="prepare">核对本次维修转派</button></fieldset>
</section></template>
<style scoped>section{margin:18px 0}fieldset{border:1px solid #d9e0e8;padding:16px}label{display:block;margin:12px 0}textarea{display:block;width:min(100%,720px);min-height:70px}button{margin:6px}</style>
