<script setup lang="ts">
import { ref } from "vue";
import type { LossIntent, LossReport } from "@/loss/api";
const props = defineProps<{ report: LossReport; locked: boolean }>();
const emit = defineEmits<{ prepare: [intent: LossIntent] }>();
const custodian = ref(""), scope = ref(""), searchedAt = ref(""), clues = ref(""), inability = ref(""), evidence = ref(""), confirmedAt = ref("");
const findings = ref(""), reviewedAt = ref(""), reason = ref(""), nextDate = ref(""), error = ref("");
function instant(value: string) { const date = new Date(value); if (!value || !Number.isFinite(date.getTime())) throw new Error("实际时间缺失"); return date.toISOString(); }
function prepare(action: "confirmLost" | "followUp") {
  if (props.locked || !props.report.actions?.[action]) return;
  const row = props.report; error.value = "";
  try {
    let body: unknown, summary: string;
    if (action === "confirmLost") {
      if ([custodian.value,scope.value,clues.value,inability.value,evidence.value].some(value => value.trim().length < 10) || !Number.isSafeInteger(row.lockVersion)) throw new Error("请填写每项至少10字的核实、范围、线索、判断理由及责任证据。");
      body = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion, custodianVerification: custodian.value.trim(), searchedScope: scope.value.trim(), searchedAt: instant(searchedAt.value), relatedClues: clues.value.trim(), inabilityReason: inability.value.trim(), responsibilityEvidence: evidence.value.trim(), confirmedAt: instant(confirmedAt.value) };
      summary = `确认 ${row.source.assetCode} 已遗失；理由：${inability.value.trim()}；责任证据：${evidence.value.trim()}。释放本查找锁，原责任和借用保留，默认30日跟进。`;
    } else {
      if (findings.value.trim().length < 10 || reason.value.trim().length < 3) throw new Error("请填写至少10字跟进结果及至少3字继续保留/延期原因。");
      body = { ownershipVersion: row.ownershipVersion, reviewedAt: instant(reviewedAt.value), findings: findings.value.trim(), reason: reason.value.trim(), nextReviewDate: nextDate.value || undefined };
      summary = `记录 ${row.source.assetCode} 的持续跟进：${findings.value.trim()}；原因：${reason.value.trim()}；下次跟进 ${nextDate.value || '默认30自然日后'}。原责任和借用继续保留。`;
    }
    emit("prepare", Object.freeze({ path: `/${row.id}/${action === "confirmLost" ? "confirm-lost" : "follow-up"}`, version: row.version, key: crypto.randomUUID(), body, summary }));
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "请核对本次实际证据。"; }
}
</script>
<template>
 <section aria-label="遗失确认与持续跟进"><p v-if="error" role="alert">{{ error }}</p><fieldset :disabled="locked">
  <template v-if="report.actions?.confirmLost"><h3>凭完整证据确认遗失</h3><p>原责任、借用及历史逾期保留；确认后结束本次查找并释放锁，进入默认30日跟进。</p>
   <label>最后保管人核实结果<textarea v-model="custodian" maxlength="2000" /></label><label>已查找范围<textarea v-model="scope" maxlength="2000" /></label><label>实际查找时间<input v-model="searchedAt" type="datetime-local" step="1" /></label>
   <label>交接、维修及盘点线索<textarea v-model="clues" maxlength="2000" /></label><label>无法找回的判断理由<textarea v-model="inability" maxlength="2000" /></label><label>责任证据<textarea v-model="evidence" maxlength="2000" /></label><label>确认遗失实际时间<input v-model="confirmedAt" type="datetime-local" step="1" /></label><button @click="prepare('confirmLost')">凭证据确认遗失</button>
  </template>
  <template v-if="report.actions?.followUp"><h3>遗失持续跟进</h3><label>本次核查结果<textarea v-model="findings" maxlength="2000" /></label><label>实际复核时间<input v-model="reviewedAt" type="datetime-local" step="1" /></label><label>继续保留或延期原因<textarea v-model="reason" maxlength="1000" /></label><label>下次跟进日（留空默认30自然日后）<input v-model="nextDate" type="date" /></label><button @click="prepare('followUp')">记录持续跟进</button></template>
 </fieldset></section>
</template>
<style scoped>label{display:flex;flex-direction:column;gap:6px;margin:12px 0;max-width:640px}fieldset{border:0;padding:0}input,textarea{padding:8px;border:1px solid #cbd5e1;border-radius:5px}button{padding:8px 12px;margin:5px;border:1px solid #cbd5e1;border-radius:5px;background:#f8fafc}button:disabled{opacity:.5}[role=alert]{color:#b91c1c}</style>
