<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { RepairApiError, repairProblem, repairRequest, type Repair, type RepairMutation } from "@/features/repair/api";
import { useBusy } from "@/features/repair/busyRegistry";
const props = defineProps<{ repair: Repair }>();
const emit = defineEmits<{ changed: []; invalidated: [] }>();
/** 页内互斥：claimed 写本面板自身的占用位，blocked 读「任一其它面板在忙」。 */
const { busy: claimed, blocked } = useBusy("reassignment");
const session = useSessionStore();
interface Context { snapshot: { version: number; processorAccountId: string; ownershipVersion: number; acceptedAt: string | null;
  physicalHandling: unknown[]; outstandingReturn: { handover_status: string } | null }; operators: { id: string; name: string }[] }
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id]));
const admin = computed(() => !!session.user?.roles.includes("ASSET_ADMIN"));
const context = ref<Context | null>(null), target = shallowRef(""), reason = shallowRef(""), evidence = shallowRef(""), checked = shallowRef(false), error = shallowRef("");
const busy = shallowRef(false), prepared = ref<RepairMutation | null>(null), pending = ref<RepairMutation | null>(null);
const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
const liveness = useRequestLiveness(() => [identity.value]);
function clear() { liveness.invalidate(); context.value = null; target.value = ""; reason.value = ""; evidence.value = ""; checked.value = false;
  prepared.value = null; pending.value = null; busy.value = false; }
function failure(cause: unknown) { if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) { clear(); emit("invalidated"); } error.value = repairProblem(cause); }
async function inspect() {
  if (locked.value || blocked.value || !admin.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = "";
  try { const result = await repairRequest<Context>(`/${props.repair.id}/reassignment`);
    if (liveness.isCurrent(turn)) { context.value = result; target.value = ""; checked.value = false; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
function prepare() {
  if (locked.value || blocked.value || !admin.value || !context.value) return;
  if (!context.value.operators.some(row => row.id === target.value) || !checked.value || reason.value.trim().length < 3 || evidence.value.trim().length < 10) {
    error.value = "请选择有效接任库管，核对未同步结果并填写原因及实际交接凭证。"; return;
  }
  prepared.value = { path: `/${props.repair.id}/reassignment`, key: crypto.randomUUID(), version: context.value.snapshot.version,
    body: { processorAccountId: target.value, snapshot: JSON.parse(JSON.stringify(context.value.snapshot)) as unknown,
      unsynchronizedResultsChecked: true, reason: reason.value.trim(), evidence: evidence.value.trim() } };
}
async function send() {
  if (busy.value || blocked.value || !pending.value) return;
  const operation = pending.value, turn = liveness.begin(); busy.value = true; error.value = "";
  try { await repairRequest(operation.path, operation);
    if (liveness.isCurrent(turn)) { pending.value = null; context.value = null; checked.value = false; emit("changed"); }
  } catch (cause) { if (liveness.isCurrent(turn)) { if (cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); } }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function confirm() { if (!prepared.value || busy.value || blocked.value) return; pending.value = prepared.value; prepared.value = null; await send(); }
watch(identity, () => { clear(); error.value = ""; }, { immediate: true, flush: "sync" });
watch(locked, value => { claimed.value = value; }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { clear(); });
const physicalStageText = computed(() => { const snapshot = context.value?.snapshot; if (!snapshot) return "";
  return snapshot.outstandingReturn ? snapshot.outstandingReturn.handover_status === 'REFUSED' ? '已拒收，等待实际收回' : '已交出，等待实际接收'
    : snapshot.physicalHandling.length ? '已离开原保管人，当前库管处理' : snapshot.acceptedAt ? '已原地接修' : '尚未接修'; });
const sectionShown = computed(() => admin.value && props.repair.status === "WAREHOUSE_PROCESSING" || !!pending.value || !!prepared.value);
</script>
<template>
  <section v-if="sectionShown" class="fams-card fams-stack" aria-label="维修受控转派">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">维修受控转派</h3>
      <div v-if="pending" class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || blocked" @click="send">重试原维修转派</button>
      </div>
    </div>
    <p>原库管无法继续时，由管理员核对交接后明确转派。实物去向、原审批、接收人待办及资产锁继续保留。</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <button class="fams-button" data-kind="secondary" type="button" :disabled="locked || blocked" @click="inspect">核对实物阶段及接任库管</button>
    <div v-if="prepared" class="fams-card fams-stack" role="group" aria-label="确认维修转派">
      <p>确认按当前核实结果移交唯一处理权。已交出设备仍须实际接收或收回。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || blocked" @click="confirm">确认保存维修转派</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="prepared=null">返回修改转派</button>
      </div>
    </div>
    <fieldset v-if="context" class="fams-card fams-stack" :disabled="locked || blocked">
      <legend class="fams-panel-title">实物与未同步结果核对</legend>
      <p>当前实物阶段：{{ physicalStageText }}。</p>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">接任库管</span>
          <select class="fams-control" v-model="target"><option value="">请选择</option><option v-for="row in context.operators" :key="row.id" :value="row.id">{{ row.name }}</option></select></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">转派原因</span><textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">实际交接凭证</span><textarea class="fams-control" v-model="evidence" maxlength="2000" /></label>
      </div>
      <label class="fams-selection"><input v-model="checked" type="checkbox">已核对未交出、已交出、待收回、未同步结果和资产锁</label>
      <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare">核对本次维修转派</button></div>
    </fieldset>
  </section>
</template>
