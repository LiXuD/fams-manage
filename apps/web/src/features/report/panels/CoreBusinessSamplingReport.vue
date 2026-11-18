<script setup lang="ts">
import { computed, reactive, ref } from "vue";

const props = defineProps<{ from: string; toExclusive: string }>();
const sample = reactive({
  from: props.from,
  toExclusive: props.toExclusive,
  scope: "",
  businessTypes: "",
  method: "",
  limitations: "",
  evidenceReference: "",
  total: undefined as number | undefined,
  missing: undefined as number | undefined,
  pending: 0,
});
const error = ref("");
const counts = computed(() => {
  const { total, missing, pending } = sample;
  if (total === undefined || missing === undefined
      || ![total, missing, pending].every(value => Number.isSafeInteger(value) && value >= 0)
      || missing > total) return null;
  return { total, missing, pending, matched: total - missing };
});
const rate = computed(() => counts.value?.total
  ? ((counts.value.matched / counts.value.total) * 100).toFixed(1) + "%" : "");

function csvCell(value: string | number) {
  const raw = String(value);
  const safe = typeof value === "string" && /^[=+\-@]/.test(raw.trimStart()) ? "'" + raw : raw;
  return `"${safe.replaceAll('"', '""')}"`;
}

function download(filename: string, rows: (string | number)[][]) {
  const csv = rows.map(row => row.map(csvCell).join(",")).join("\r\n") + "\r\n";
  const url = URL.createObjectURL(new Blob(["\uFEFF", csv], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function downloadEvidenceSheet() {
  download("核心业务抽样明细空表.csv", [[
    "样本编号", "业务类型", "资产编码或交接对象", "实际发生时间及其时区", "发现来源与凭据编号",
    "核实结果（已匹配、漏记、待核实）", "匹配单号", "漏记或待核实原因",
  ]]);
}

function exportResult() {
  error.value = "";
  const current = counts.value;
  if (!current) {
    error.value = "请输入有效的样本数、漏记数和待核实数。";
    return;
  }
  if (!sample.from || !sample.toExclusive || sample.from >= sample.toExclusive) {
    error.value = "结束日期必须晚于开始日期。";
    return;
  }
  if (![sample.scope, sample.businessTypes, sample.method, sample.limitations].every(value => value.trim())
      || current.total > 0 && !sample.evidenceReference.trim()) {
    error.value = "请补齐抽样口径，并为已核实的样本填写明细档案编号。";
    return;
  }
  download(`核心业务抽样留痕率-${sample.from}.csv`, [
    ["报告", "开始日期（UTC）", "结束日期（UTC，不含当日）", "抽查部门或位置", "业务类型", "选样方法",
      "覆盖限制", "明细档案编号", "核实样本数", "匹配单据数", "原样本漏记数", "待核实线索数", "留痕率", "计算时间（UTC）"],
    [current.total ? "本次样本观察结果" : "样本不足／无法评价", sample.from, sample.toExclusive, sample.scope.trim(),
      sample.businessTypes.trim(), sample.method.trim(), sample.limitations.trim(), sample.evidenceReference.trim(),
      current.total, current.matched, current.missing, current.pending, rate.value, new Date().toISOString()],
  ]);
}
</script>

<template>
  <details>
    <summary>抽样留痕率核算</summary>
    <p>从线下凭据核实实际业务。同一笔业务只计一次，待核实线索另列，补录后保留原样本结果。</p>
    <button type="button" @click="downloadEvidenceSheet">下载抽样明细空表</button>
    <form @submit.prevent="exportResult">
      <div class="actions">
        <label>抽样开始日期（UTC） <input v-model="sample.from" required type="date"></label>
        <label>抽样结束日期（UTC，不含当日） <input v-model="sample.toExclusive" required type="date"></label>
        <label>抽查部门或位置 <input v-model="sample.scope" maxlength="500" required></label>
        <label>业务类型 <input v-model="sample.businessTypes" maxlength="500" required></label>
        <label>选样方法 <textarea v-model="sample.method" maxlength="1000" required></textarea></label>
        <label>覆盖限制 <textarea v-model="sample.limitations" maxlength="1000" required></textarea></label>
        <label>明细档案编号 <input v-model="sample.evidenceReference" maxlength="500" :required="(counts?.total ?? 0) > 0"></label>
        <label>核实样本数 <input v-model.number="sample.total" required type="number" min="0" step="1"></label>
        <label>原样本漏记数 <input v-model.number="sample.missing" required type="number" min="0" step="1"></label>
        <label>待核实线索数 <input v-model.number="sample.pending" required type="number" min="0" step="1"></label>
      </div>
      <p v-if="rate && counts">本次样本观察结果 <output>{{ counts.matched }}/{{ counts.total }} = {{ rate }}</output></p>
      <p v-else>样本不足或数量无效，无法评价。</p>
      <p v-if="counts && counts.total > 0 && counts.total < 30">样本少于 30 笔，请在覆盖限制中说明核实范围。</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <button type="submit">下载本次抽样结果 CSV</button>
    </form>
  </details>
</template>
