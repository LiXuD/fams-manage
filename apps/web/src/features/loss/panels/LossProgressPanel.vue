<script setup lang="ts">
import { computed, shallowRef } from "vue";
import type { LossIntent, LossReport } from "@/features/loss/api";
const props = defineProps<{ report: LossReport; locked: boolean }>();
/** 只起草，不提交：幂等键、确认态与在途态由父视图的提交底座持有。 */
const emit = defineEmits<{ prepare: [intent: LossIntent] }>();
const custodian = shallowRef(""), scope = shallowRef(""), searchedAt = shallowRef(""), clues = shallowRef(""), inability = shallowRef(""), evidence = shallowRef(""), confirmedAt = shallowRef("");
const findings = shallowRef(""), reviewedAt = shallowRef(""), reason = shallowRef(""), nextDate = shallowRef(""), error = shallowRef("");
const showConfirmLost = computed(() => !!props.report.actions?.confirmLost);
const showFollowUp = computed(() => !!props.report.actions?.followUp);
function instant(value: string) { const date = new Date(value); if (!value || !Number.isFinite(date.getTime())) throw new Error("实际时间缺失"); return date.toISOString(); }
function prepare(action: "confirmLost" | "followUp") {
  if (props.locked || !props.report.actions?.[action]) return;
  const row = props.report; error.value = "";
  try {
    let body: unknown, summary: string;
    if (action === "confirmLost") {
      if ([custodian.value, scope.value, clues.value, inability.value, evidence.value].some((value) => value.trim().length < 10) || !Number.isSafeInteger(row.lockVersion)) throw new Error("请填写每项至少10字的核实、范围、线索、判断理由及责任证据。");
      body = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion, custodianVerification: custodian.value.trim(), searchedScope: scope.value.trim(), searchedAt: instant(searchedAt.value), relatedClues: clues.value.trim(), inabilityReason: inability.value.trim(), responsibilityEvidence: evidence.value.trim(), confirmedAt: instant(confirmedAt.value) };
      summary = `确认 ${row.source.assetCode} 已遗失；理由：${inability.value.trim()}；责任证据：${evidence.value.trim()}。释放本查找锁，原责任和借用保留，默认30日跟进。`;
    } else {
      if (findings.value.trim().length < 10 || reason.value.trim().length < 3) throw new Error("请填写至少10字跟进结果及至少3字继续保留/延期原因。");
      body = { ownershipVersion: row.ownershipVersion, reviewedAt: instant(reviewedAt.value), findings: findings.value.trim(), reason: reason.value.trim(), nextReviewDate: nextDate.value || undefined };
      summary = `记录 ${row.source.assetCode} 的持续跟进：${findings.value.trim()}；原因：${reason.value.trim()}；下次跟进 ${nextDate.value || "默认30自然日后"}。原责任和借用继续保留。`;
    }
    emit("prepare", Object.freeze({ path: `/${row.id}/${action === "confirmLost" ? "confirm-lost" : "follow-up"}`, version: row.version, body, summary }));
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "请核对本次实际证据。"; }
}
</script>
<template>
  <section class="fams-panel fams-stack" aria-label="遗失确认与持续跟进">
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <fieldset class="fams-card fams-stack" :disabled="locked">
      <template v-if="showConfirmLost">
        <div class="fams-panel-head">
          <div>
            <h3 class="fams-panel-title">凭完整证据确认遗失</h3>
            <p class="fams-panel-subtitle">原责任、借用及历史逾期保留；确认后结束本次查找并释放锁，进入默认30日跟进。</p>
          </div>
        </div>
        <label class="fams-field"><span class="fams-field-label">最后保管人核实结果</span><textarea class="fams-control" v-model="custodian" maxlength="2000" /></label>
        <label class="fams-field"><span class="fams-field-label">已查找范围</span><textarea class="fams-control" v-model="scope" maxlength="2000" /></label>
        <label class="fams-field"><span class="fams-field-label">实际查找时间</span><input class="fams-control" v-model="searchedAt" type="datetime-local" step="1" /></label>
        <label class="fams-field"><span class="fams-field-label">交接、维修及盘点线索</span><textarea class="fams-control" v-model="clues" maxlength="2000" /></label>
        <label class="fams-field"><span class="fams-field-label">无法找回的判断理由</span><textarea class="fams-control" v-model="inability" maxlength="2000" /></label>
        <label class="fams-field"><span class="fams-field-label">责任证据</span><textarea class="fams-control" v-model="evidence" maxlength="2000" /></label>
        <label class="fams-field"><span class="fams-field-label">确认遗失实际时间</span><input class="fams-control" v-model="confirmedAt" type="datetime-local" step="1" /></label>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare('confirmLost')">凭证据确认遗失</button></div>
      </template>
      <template v-if="showFollowUp">
        <div class="fams-panel-head"><h3 class="fams-panel-title">遗失持续跟进</h3></div>
        <label class="fams-field"><span class="fams-field-label">本次核查结果</span><textarea class="fams-control" v-model="findings" maxlength="2000" /></label>
        <label class="fams-field"><span class="fams-field-label">实际复核时间</span><input class="fams-control" v-model="reviewedAt" type="datetime-local" step="1" /></label>
        <label class="fams-field"><span class="fams-field-label">继续保留或延期原因</span><textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
        <label class="fams-field"><span class="fams-field-label">下次跟进日（留空默认30自然日后）</span><input class="fams-control" v-model="nextDate" type="date" /></label>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare('followUp')">记录持续跟进</button></div>
      </template>
    </fieldset>
  </section>
</template>
