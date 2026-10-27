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
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" @click="loadPending">刷新待办</button>
    </template>

    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>

    <section class="fams-panel fams-stack" aria-label="待核验导入">
      <div class="fams-panel-head">
        <div><h2 class="fams-panel-title">待核验导入</h2></div>
      </div>
      <div class="fams-table-scroll"><table class="fams-table"><thead><tr>
        <th scope="col" data-column-kind="identity">批次</th><th scope="col">操作员</th>
        <th scope="col" data-column-kind="numeric">正确 / 错误行</th><th scope="col">提交时间</th>
        <th scope="col" data-column-kind="action">操作</th>
      </tr></thead><tbody>
        <tr v-for="item in reviews" :key="item.batchId"><th scope="row" data-column-kind="identity">{{ item.batchId }}</th>
          <td>{{ item.operatorUsername }}</td><td data-column-kind="numeric">{{ item.readyRows }} / {{ item.errorRows }}</td>
          <td>{{ item.requestedAt }}</td><td data-column-kind="action"><button class="fams-button" data-kind="secondary" type="button" @click="inspect(item)">查看并核验</button></td></tr>
        <tr v-if="reviews.length === 0" class="fams-table-empty"><td colspan="5"><strong>暂无待核验导入</strong>等待操作员提交导入批次后刷新。</td></tr>
      </tbody></table></div>
      <div class="fams-pagination">
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="reviewPage <= 1"
            @click="reviewPage--; loadPending()">上一页</button>
          <span class="fams-pagination-page">第 {{ reviewPage }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="reviews.length < 50"
            @click="reviewPage++; loadPending()">下一页</button>
        </div>
      </div>
    </section>
    <section v-if="selected" class="fams-panel fams-stack" aria-label="核验批次详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">核验批次 {{ selected.batchId }}</h2>
          <p class="fams-panel-subtitle">预检版本 {{ selected.previewVersion }} · 正确 {{ selected.readyRows }} 行 · 错误 {{ selected.errorRows }} 行</p>
        </div>
      </div>
      <div class="fams-panel-actions"><button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="chooseRows('READY')">正确行</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="chooseRows('ERROR')">错误行</button></div>
      <div class="fams-table-scroll"><table class="fams-table"><thead><tr>
        <th scope="col" data-column-kind="identity">Excel 行</th><th scope="col">原始内容</th><th scope="col">识别结果</th><th scope="col">错误</th>
      </tr></thead><tbody>
        <tr v-for="row in rows" :key="row.sheetRow"><th scope="row" data-column-kind="identity">{{ row.sheetRow }}</th>
          <td><pre>{{ JSON.stringify(row.source, null, 2) }}</pre></td>
          <td><pre>{{ JSON.stringify(row.resolved ?? {}, null, 2) }}</pre></td>
          <td><div v-for="cell in row.errors ?? []" :key="cell.column + cell.reason">
            {{ cell.column }} · {{ cell.original || '（空）' }}：{{ cell.reason }}</div></td></tr>
        <tr v-if="rows.length === 0" class="fams-table-empty"><td colspan="4"><strong>当前没有{{ rowStatus === 'READY' ? '正确' : '错误' }}行</strong>切换上方按钮查看另一类行。</td></tr>
      </tbody></table></div>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ rowTotal }} 行</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="rowPage <= 1"
            @click="rowPage--; loadRows()">上一页</button>
          <span class="fams-pagination-page">第 {{ rowPage }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="rowPage * 50 >= rowTotal"
            @click="rowPage++; loadRows()">下一页</button>
        </div>
      </div>
      <div class="fams-field-grid">
        <label class="fams-selection"><input v-model="sampleVerified" type="checkbox" />已核对预检行数并完成实物抽样核验</label>
        <label class="fams-field"><span class="fams-field-label">决定原因</span>
          <input class="fams-control" v-model="reason" maxlength="500" /></label>
      </div>
      <div class="fams-form-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || !reason.trim() || !sampleVerified"
          @click="decide(true)">批准并发布</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || !reason.trim()"
          @click="decide(false)">驳回</button>
      </div>
    </section>
    <section class="fams-panel fams-stack" aria-label="待核验整批撤销">
      <div class="fams-panel-head">
        <div><h2 class="fams-panel-title">待核验整批撤销</h2></div>
      </div>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">决定原因</span>
          <input class="fams-control" v-model="rollbackReason" maxlength="500" /></label>
      </div>
      <div class="fams-table-scroll"><table class="fams-table"><thead><tr>
        <th scope="col" data-column-kind="identity">批次</th><th scope="col">操作员</th>
        <th scope="col" data-column-kind="numeric">资产数</th><th scope="col">申请原因</th>
        <th scope="col" data-column-kind="action">操作</th>
      </tr></thead><tbody>
        <tr v-for="item in rollbacks" :key="item.batchId"><th scope="row" data-column-kind="identity">{{ item.batchId }}</th>
          <td>{{ item.operatorUsername }}</td><td data-column-kind="numeric">{{ item.assetCount }}</td><td>{{ item.reason }}</td>
          <td data-column-kind="action"><div class="fams-inline">
            <button class="fams-button" data-kind="primary" type="button" :disabled="busy || !rollbackReason.trim()"
              @click="decideRollback(item, true)">批准</button>
            <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || !rollbackReason.trim()"
              @click="decideRollback(item, false)">驳回</button></div></td></tr>
        <tr v-if="rollbacks.length === 0" class="fams-table-empty"><td colspan="5"><strong>暂无撤销申请</strong>收到申请后填写决定原因并刷新待办。</td></tr>
      </tbody></table></div>
      <div class="fams-pagination">
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="rollbackPage <= 1"
            @click="rollbackPage--; loadPending()">上一页</button>
          <span class="fams-pagination-page">第 {{ rollbackPage }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="rollbacks.length < 50"
            @click="rollbackPage++; loadPending()">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
