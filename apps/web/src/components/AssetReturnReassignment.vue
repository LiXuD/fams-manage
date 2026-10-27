<script setup lang="ts">
import { ref, watch } from "vue";
import { loanRequest, loanMessage } from "@/loan/api";
import type { LoanMutation } from "@/loan/api";

const props = defineProps<{ id: string; version: number }>();
const emit = defineEmits<{ saved: [] }>();
interface Snapshot {
  id: string; version: number; ownershipVersion: number; processorAccountId: string;
  operators: { id: string; name: string }[];
  items: { id: string; version: number; status: string; assetVersion: number; lockVersion: number; assetCode: string }[];
}
const snapshot = ref<Snapshot | null>(null), target = ref(""), reason = ref(""), evidence = ref("");
const checked = ref(false), busy = ref(false), error = ref(""), retryable = ref(false);
let pending: LoanMutation | null = null, generation = 0;
const labels: Record<string, string> = { WAREHOUSE_PROCESSING: "待实物验收" };
watch(() => [props.id, props.version], () => {
  generation++; snapshot.value = null; pending = null; retryable.value = false;
  target.value = ""; reason.value = ""; evidence.value = ""; checked.value = false;
});
async function inspect() {
  if (busy.value) return;
  const turn = ++generation; busy.value = true; error.value = "";
  try {
    const result = await loanRequest<Snapshot>(`/asset-returns/${props.id}/reassignment`);
    if (turn !== generation) return;
    snapshot.value = result; pending = null; retryable.value = false; checked.value = false;
  } catch (e) { if (turn === generation) error.value = loanMessage(e); }
  finally { busy.value = false; }
}
async function submit() {
  if (busy.value || !snapshot.value) return;
  if (!pending && (!target.value || !reason.value.trim() || !evidence.value.trim() || !checked.value)) {
    error.value = "请选择接任库管，填写原因及交接凭证，并核对未同步结果。"; return;
  }
  pending ??= { path: `/asset-returns/${snapshot.value.id}/reassignment`, version: snapshot.value.version,
    key: crypto.randomUUID(), body: { processorAccountId: target.value, ownershipVersion: snapshot.value.ownershipVersion,
      reason: reason.value.trim(), evidence: evidence.value.trim(), unsynchronizedResultsChecked: true,
      items: snapshot.value.items.map(i => ({ id: i.id, version: i.version, status: i.status, assetVersion: i.assetVersion, lockVersion: i.lockVersion })) } };
  busy.value = true; error.value = ""; const turn = generation;
  try {
    await loanRequest(pending.path, pending);
    if (turn !== generation) return;
    pending = null; retryable.value = false; snapshot.value = null; emit("saved");
  } catch (e) { if (turn === generation) { error.value = loanMessage(e); retryable.value = true; } }
  finally { busy.value = false; }
}
</script>

<template>
  <section class="fams-card fams-stack">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">受控转派整单处理权</h3>
        <p class="fams-panel-subtitle">核对每项实物阶段、去向、资产锁及未同步结果后，交给一名有效库管继续处理。</p>
      </div>
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="inspect">加载 / 重新核对转派清单</button>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <template v-if="snapshot">
      <p class="fams-panel-subtitle">当前处理账号 {{ snapshot.processorAccountId }} · 处理权版本 {{ snapshot.ownershipVersion }}</p>
      <ul class="fams-timeline">
        <li v-for="item in snapshot.items" :key="item.id">明细 {{ item.id }} · {{ item.assetCode }} · {{ labels[item.status] }}<small>锁版本 {{ item.lockVersion < 0 ? '已释放' : item.lockVersion }}</small></li>
      </ul>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">接任库管</span>
          <select class="fams-control" v-model="target" :disabled="busy || retryable"><option value="">请选择</option><option v-for="person in snapshot.operators" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">转派原因</span>
          <textarea class="fams-control" v-model="reason" maxlength="1000" :disabled="busy || retryable" /></label>
        <label class="fams-field"><span class="fams-field-label">实物阶段与资产锁交接凭证</span>
          <textarea class="fams-control" v-model="evidence" maxlength="1000" :disabled="busy || retryable" /></label>
      </div>
      <label class="fams-selection"><input v-model="checked" type="checkbox" :disabled="busy || retryable" />已与处理人或交接记录核对未同步结果</label>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="submit">{{ retryable ? '原请求重试转派' : '确认转派整单' }}</button>
      </div>
    </template>
  </section>
</template>
