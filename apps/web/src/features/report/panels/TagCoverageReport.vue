<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

interface Coverage {
  evaluatedAt: string;
  total: number;
  covered: number;
  placement: number;
  inventory: number;
  withoutTag: number;
}
const coverage = ref<Coverage>();
const busy = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness();
const summaryText = computed(() => {
  const value = coverage.value;
  if (!value || !value.total) return "";
  return `${value.covered} / ${value.total} 项 · ${(100 * value.covered / value.total).toFixed(1)}%`;
});
const evidenceText = computed(() => {
  const value = coverage.value;
  if (!value) return "";
  return `实物贴标确认 ${value.placement} · 最近封存盘点验码 ${value.inventory}`
    + ` · 无当前标签 ${value.withoutTag} · 有标签待核验 ${value.total - value.covered - value.withoutTag}。`;
});

async function load() {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  coverage.value = undefined;
  try {
    const fetched = await reportRequest<Coverage>("/operations-reports/tag-coverage");
    if (!liveness.isCurrent(turn)) return;
    coverage.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) error.value = "无法读取二维码覆盖率，请重试。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-card fams-stack" aria-label="有效二维码覆盖率">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">有效二维码覆盖率</h3>
    </div>
    <div class="fams-inline">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">查询当前覆盖率</button>
    </div>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <template v-if="coverage">
      <p v-if="summaryText">{{ summaryText }}</p>
      <p v-else>当前没有应贴标的有效资产。</p>
      <p>{{ evidenceText }}</p>
      <p>两类凭据可同时成立，每张资产只计一次。计算时间
        <time :datetime="coverage.evaluatedAt">{{ coverage.evaluatedAt }}</time></p>
      <div class="fams-inline">
        <a class="fams-button" data-kind="link" href="/api/v1/operations-reports/tag-coverage.csv">下载二维码覆盖明细 CSV</a>
      </div>
    </template>
  </section>
</template>
