<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { RepairApiError, repairProblem, repairRequest, repairStates, type Repair, type RepairAsset, type RepairMutation } from "@/features/repair/api";
const props = defineProps<{ blocked?: boolean }>();
const emit = defineEmits<{ created: [repair: Repair]; busyChange: [value: boolean] }>();
interface SourceRow { id: string; return_id: string; asset_id: string; followup_status: string; processor_account_id: string; reason: string;
  linked_repair_id: string | null; last_repair_id: string | null; repair_status: string | null }
interface Source { followupId: string; version: number; returnId: string; closedLoanId: string | null; processorAccountId: string;
  inspectedAt: string; inspectionReason: string; lockVersion: number; asset: RepairAsset }
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const rows = ref<SourceRow[]>([]), total = shallowRef(0), page = shallowRef(1), source = ref<Source | null>(null);
const fault = shallowRef(""), impact = ref("MEDIUM"), error = shallowRef(""), busy = shallowRef(false);
const prepared = ref<RepairMutation | null>(null), pending = ref<RepairMutation | null>(null);
const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
const liveness = useRequestLiveness(() => [identity.value]);
function clear() { liveness.invalidate(); rows.value = []; total.value = 0; page.value = 1; source.value = null; fault.value = "";
  prepared.value = null; pending.value = null; busy.value = false; }
function failure(cause: unknown, mutation = false) {
  if (cause instanceof RepairApiError) {
    if ([401, 403, 404].includes(cause.status)) clear();
    else if (mutation && cause.status >= 400 && cause.status < 500) { pending.value = null; source.value = null; }
  }
  error.value = repairProblem(cause);
}
async function refresh(delta = 0) {
  if (busy.value || props.blocked || delta && locked.value || page.value + delta < 1) return;
  const turn = liveness.begin(); busy.value = true; error.value = ""; page.value += delta;
  try { const value = await repairRequest<{ items: SourceRow[]; total: number }>(`/return-sources?page=${page.value}&pageSize=20`);
    if (liveness.isCurrent(turn)) { rows.value = value.items; total.value = value.total; if (!pending.value && !prepared.value) source.value = null; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function select(id: string) {
  if (locked.value || props.blocked) return;
  const turn = liveness.begin(); busy.value = true; source.value = null; error.value = "";
  try { const value = await repairRequest<Source>(`/return-sources/${id}`);
    if (liveness.isCurrent(turn)) { source.value = value; fault.value = value.inspectionReason; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
function prepare() {
  if (locked.value || props.blocked || !source.value || fault.value.trim().length < 3) return;
  const row = source.value;
  prepared.value = { path: `/return-sources/${row.followupId}`, version: row.version, key: crypto.randomUUID(),
    body: { assetVersion: row.asset.version, lockVersion: row.lockVersion, fault: fault.value.trim(), impact: impact.value } };
}
async function send() {
  if (busy.value || props.blocked || !pending.value) return;
  const turn = liveness.begin(), operation = pending.value; busy.value = true; error.value = "";
  try { const result = await repairRequest<Repair>(operation.path, operation);
    if (liveness.isCurrent(turn)) { pending.value = null; source.value = null; emit("created", result); }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause, true); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function confirm() { if (busy.value || props.blocked || !prepared.value) return; pending.value = prepared.value; prepared.value = null; await send(); }
watch(identity, () => { clear(); error.value = ""; }, { immediate: true, flush: "sync" });
watch(locked, value => emit("busyChange", value), { immediate: true, flush: "sync" });
onBeforeUnmount(() => { clear(); emit("busyChange", false); });
const sourceRows = computed(() => rows.value.map(row => ({ ...row,
  followupStatusText: row.followup_status === 'PENDING' ? '待接续' : row.followup_status === 'CLOSED' ? '已实际完成' : '维修处理中',
  repairStateText: repairStates[row.repair_status || ''] || '原维修',
  selectable: row.followup_status === 'PENDING' })));
const prevDisabled = computed(() => locked.value || props.blocked || page.value <= 1);
const nextDisabled = computed(() => locked.value || props.blocked || page.value * 20 >= total.value);
const inspectedAtText = computed(() => source.value ? new Date(source.value.inspectedAt).toLocaleString() : '');
</script>
<template>
  <section class="fams-card fams-stack" aria-label="损坏归还接续维修">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">损坏归还接续维修</h3>
      <div v-if="pending" class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || blocked" @click="send">重试原接续申请</button>
      </div>
    </div>
    <p>保留原归还及已关闭借用。新维修继承原库管与同一资产锁，仍需管理员批准范围和库管实际接修、验收入库。</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || blocked" @click="refresh()">查询损坏归还后续</button>
    <article v-for="row in sourceRows" :key="row.id" class="fams-card">
      <p>归还 {{ row.return_id }} · 资产 {{ row.asset_id }} · {{ row.reason }} · {{ row.followupStatusText }}</p>
      <div class="fams-inline">
        <button v-if="row.selectable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || blocked" @click="select(row.id)">核对损坏来源 {{ row.id }}</button>
        <router-link v-if="row.linked_repair_id || row.last_repair_id" :to="'/repairs/' + (row.linked_repair_id || row.last_repair_id)">查看{{ row.repairStateText }}</router-link>
      </div>
    </article>
    <div class="fams-pagination">
      <span class="fams-pagination-summary">共{{ total }}项</span>
      <div class="fams-pagination-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="refresh(-1)">上一页损坏后续</button>
        <span class="fams-pagination-page">第{{ page }}页</span>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="refresh(1)">下一页损坏后续</button>
      </div>
    </div>
    <fieldset v-if="source" class="fams-card fams-stack" :disabled="locked || blocked">
      <legend class="fams-panel-title">核对原损坏验收</legend>
      <p>{{ source.asset.assetCode }} · {{ source.asset.name }} · 原归还 {{ source.returnId }} · 原库管 {{ source.processorAccountId }}</p>
      <p>实际损坏验收 {{ inspectedAtText }} · {{ source.inspectionReason }}。原借用 {{ source.closedLoanId || '无' }} 不会重新打开。</p>
      <div class="fams-field-grid">
        <label class="fams-field fams-field-wide"><span class="fams-field-label">维修故障依据</span><textarea class="fams-control" v-model="fault" maxlength="2000" /></label>
        <label class="fams-field"><span class="fams-field-label">影响程度</span>
          <select class="fams-control" v-model="impact"><option value="LOW">轻度</option><option value="MEDIUM">中度</option><option value="HIGH">严重</option></select></label>
      </div>
      <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare">核对接续申请</button></div>
    </fieldset>
    <div v-if="prepared" class="fams-card fams-stack" role="group" aria-label="确认损坏接续">
      <p>将创建待批准的正式维修并继承同一资产锁；不会自动批准、虚构接修或重开借用。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || blocked" @click="confirm">确认提交损坏接续</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="prepared=null">返回修改接续</button>
      </div>
    </div>
  </section>
</template>
