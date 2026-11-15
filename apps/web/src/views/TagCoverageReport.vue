<script setup lang="ts">
import { ref } from "vue";

interface Coverage {
  evaluatedAt: string;
  total: number;
  covered: number;
  placement: number;
  inventory: number;
  withoutTag: number;
}
const coverage = ref<Coverage>();
const busy = ref(false);
const error = ref("");

async function load() {
  busy.value = true;
  error.value = "";
  coverage.value = undefined;
  try {
    const result = await fetch("/api/v1/operations-reports/tag-coverage", {
      credentials: "include", cache: "no-store",
    });
    if (!result.ok) throw new Error(String(result.status));
    coverage.value = await result.json() as Coverage;
  } catch {
    error.value = "无法读取二维码覆盖率，请重试。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section aria-label="有效二维码覆盖率">
    <h3>有效二维码覆盖率</h3>
    <button type="button" :disabled="busy" @click="load">查询当前覆盖率</button>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="coverage">
      <p v-if="coverage.total">
        {{ coverage.covered }} / {{ coverage.total }} 项 · {{ (100 * coverage.covered / coverage.total).toFixed(1) }}%
      </p>
      <p v-else>当前没有应贴标的有效资产。</p>
      <p>实物贴标确认 {{ coverage.placement }} · 最近封存盘点验码 {{ coverage.inventory }} ·
        无当前标签 {{ coverage.withoutTag }} · 有标签待核验 {{ coverage.total - coverage.covered - coverage.withoutTag }}。</p>
      <p>两类凭据可同时成立，每张资产只计一次。计算时间
        <time :datetime="coverage.evaluatedAt">{{ coverage.evaluatedAt }}</time></p>
      <a href="/api/v1/operations-reports/tag-coverage.csv">下载二维码覆盖明细 CSV</a>
    </template>
  </section>
</template>
