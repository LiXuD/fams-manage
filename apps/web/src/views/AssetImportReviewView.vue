<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import {
  decideImportReview, decideImportRollback, pendingImportReviews, pendingImportRollbacks,
  reviewImportRows, type ImportRow, type PendingImportReview, type PendingImportRollback,
} from "@/assetimport/api";

const reviews = ref<PendingImportReview[]>([]);
const rollbacks = ref<PendingImportRollback[]>([]);
const selected = ref<PendingImportReview | null>(null);
const rows = ref<ImportRow[]>([]);
const rowStatus = ref<"READY" | "ERROR">("ERROR");
const rowPage = ref(1);
const rowTotal = ref(0);
const reviewPage = ref(1);
const rollbackPage = ref(1);
const sampleVerified = ref(false);
const reason = ref("");
const rollbackReason = ref("");
const error = ref("");
const notice = ref("");
const busy = ref(false);

async function loadPending(): Promise<void> {
  const [r, b] = await Promise.all([
    pendingImportReviews(reviewPage.value), pendingImportRollbacks(rollbackPage.value),
  ]);
  reviews.value = r;
  rollbacks.value = b;
}
async function inspect(item: PendingImportReview): Promise<void> {
  selected.value = item;
  sampleVerified.value = false;
  rowStatus.value = item.errorRows > 0 ? "ERROR" : "READY";
  rowPage.value = 1;
  await loadRows();
}
async function loadRows(): Promise<void> {
  if (!selected.value) return;
  error.value = "";
  try {
    const result = await reviewImportRows(selected.value.batchId, rowStatus.value, rowPage.value);
    rows.value = result.items;
    rowTotal.value = result.total;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "行数据加载失败"; }
}
async function chooseRows(status: "READY" | "ERROR"): Promise<void> {
  rowStatus.value = status;
  rowPage.value = 1;
  await loadRows();
}
async function decide(approved: boolean): Promise<void> {
  if (!selected.value) return;
  busy.value = true; error.value = "";
  try {
    await decideImportReview(selected.value.batchId, approved, reason.value.trim(),
      selected.value.readyRows, selected.value.errorRows, sampleVerified.value);
    notice.value = approved ? "核验通过，发布任务已排队。" : "已驳回导入申请。";
    selected.value = null;
    await loadPending();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "核验决定失败"; }
  finally { busy.value = false; }
}
async function decideRollback(item: PendingImportRollback, approved: boolean): Promise<void> {
  busy.value = true; error.value = "";
  try {
    await decideImportRollback(item.batchId, approved, rollbackReason.value.trim());
    notice.value = approved ? "撤销已批准，等待原操作员执行。" : "已驳回撤销申请。";
    await loadPending();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "撤销决定失败"; }
  finally { busy.value = false; }
}
onMounted(async () => {
  try { await loadPending(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "待办加载失败"; }
});
</script>

<template>
  <AppChrome>
    <div class="breadcrumb">工作台 / 资产台账 / 期初核验</div>
    <div class="asset-heading"><div><h1>期初导入核验</h1><p>核对预检结果与实物抽样，再决定发布或撤销。</p></div>
      <button class="secondary-button" type="button" @click="loadPending">刷新待办</button></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="asset-notice" role="status">{{ notice }}</p>
    <section class="asset-panel">
      <h2>待核验导入</h2>
      <div class="asset-table-wrap"><table class="asset-table"><thead><tr>
        <th>批次</th><th>操作员</th><th>正确 / 错误行</th><th>提交时间</th><th>操作</th>
      </tr></thead><tbody>
        <tr v-for="item in reviews" :key="item.batchId"><td>{{ item.batchId }}</td>
          <td>{{ item.operatorUsername }}</td><td>{{ item.readyRows }} / {{ item.errorRows }}</td>
          <td>{{ item.requestedAt }}</td><td><button class="secondary-button" type="button" @click="inspect(item)">查看并核验</button></td></tr>
        <tr v-if="reviews.length === 0"><td colspan="5">暂无待核验导入</td></tr>
      </tbody></table></div>
      <div class="asset-filters"><button class="secondary-button" type="button" :disabled="reviewPage <= 1"
        @click="reviewPage--; loadPending()">上一页</button><span>第 {{ reviewPage }} 页</span>
        <button class="secondary-button" type="button" :disabled="reviews.length < 50"
          @click="reviewPage++; loadPending()">下一页</button></div>
    </section>
    <section v-if="selected" class="asset-panel">
      <h2>核验批次 {{ selected.batchId }}</h2>
      <p>预检版本 {{ selected.previewVersion }} · 正确 {{ selected.readyRows }} 行 · 错误 {{ selected.errorRows }} 行</p>
      <div class="asset-filters"><button class="secondary-button" type="button" @click="chooseRows('READY')">正确行</button>
        <button class="secondary-button" type="button" @click="chooseRows('ERROR')">错误行</button></div>
      <div class="asset-table-wrap"><table class="asset-table"><thead><tr>
        <th>Excel 行</th><th>原始内容</th><th>识别结果</th><th>错误</th>
      </tr></thead><tbody>
        <tr v-for="row in rows" :key="row.sheetRow"><td>{{ row.sheetRow }}</td>
          <td><pre>{{ JSON.stringify(row.source, null, 2) }}</pre></td>
          <td><pre>{{ JSON.stringify(row.resolved ?? {}, null, 2) }}</pre></td>
          <td><div v-for="cell in row.errors ?? []" :key="cell.column + cell.reason">
            {{ cell.column }} · {{ cell.original || '（空）' }}：{{ cell.reason }}</div></td></tr>
        <tr v-if="rows.length === 0"><td colspan="4">当前没有{{ rowStatus === 'READY' ? '正确' : '错误' }}行</td></tr>
      </tbody></table></div>
      <div class="asset-filters"><button class="secondary-button" type="button" :disabled="rowPage <= 1"
        @click="rowPage--; loadRows()">上一页</button><span>第 {{ rowPage }} 页 · 共 {{ rowTotal }} 行</span>
        <button class="secondary-button" type="button" :disabled="rowPage * 50 >= rowTotal"
          @click="rowPage++; loadRows()">下一页</button></div>
      <label><input v-model="sampleVerified" type="checkbox" /> 已核对预检行数并完成实物抽样核验</label>
      <div class="asset-filters"><label>决定原因 <input v-model="reason" maxlength="500" /></label>
        <button class="primary-button" type="button" :disabled="busy || !reason.trim() || !sampleVerified"
          @click="decide(true)">批准并发布</button>
        <button class="secondary-button" type="button" :disabled="busy || !reason.trim()"
          @click="decide(false)">驳回</button></div>
    </section>
    <section class="asset-panel">
      <h2>待核验整批撤销</h2>
      <label>决定原因 <input v-model="rollbackReason" maxlength="500" /></label>
      <div class="asset-table-wrap"><table class="asset-table"><thead><tr>
        <th>批次</th><th>操作员</th><th>资产数</th><th>申请原因</th><th>操作</th>
      </tr></thead><tbody>
        <tr v-for="item in rollbacks" :key="item.batchId"><td>{{ item.batchId }}</td>
          <td>{{ item.operatorUsername }}</td><td>{{ item.assetCount }}</td><td>{{ item.reason }}</td>
          <td><button class="primary-button" type="button" :disabled="busy || !rollbackReason.trim()"
            @click="decideRollback(item, true)">批准</button>
            <button class="secondary-button" type="button" :disabled="busy || !rollbackReason.trim()"
              @click="decideRollback(item, false)">驳回</button></td></tr>
        <tr v-if="rollbacks.length === 0"><td colspan="5">暂无撤销申请</td></tr>
      </tbody></table></div>
      <div class="asset-filters"><button class="secondary-button" type="button" :disabled="rollbackPage <= 1"
        @click="rollbackPage--; loadPending()">上一页</button><span>第 {{ rollbackPage }} 页</span>
        <button class="secondary-button" type="button" :disabled="rollbacks.length < 50"
          @click="rollbackPage++; loadPending()">下一页</button></div>
    </section>
  </AppChrome>
</template>
