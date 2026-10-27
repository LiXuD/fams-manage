<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import {
  downloadImportResult, downloadImportTemplate, downloadPreflightReport,
  getImport, importColumns, importRows,
  ownImportReview, ownImportRollback, previewImport, queueImportRollback,
  requestImportReview, requestImportRollback, uploadImport,
  type ImportReview, type ImportRollback, type ImportRow, type ImportTicket,
} from "@/assetimport/api";

const route = useRoute();
const router = useRouter();
const fields = [
  { key: "assetCode", label: "资产编码" }, { key: "name", label: "名称" },
  { key: "category", label: "分类编码" }, { key: "organization", label: "所属组织编码" },
  { key: "model", label: "型号" }, { key: "unit", label: "单位" },
  { key: "serialNumber", label: "序列号" }, { key: "state", label: "资产状态" },
  { key: "warehouse", label: "仓库编码" }, { key: "location", label: "位置编码" },
  { key: "usingOrganization", label: "使用组织编码" }, { key: "employee", label: "使用人编号" },
  { key: "borrowedDate", label: "借出日期" }, { key: "expectedReturnDate", label: "预计归还日" },
];
const file = ref<File | null>(null);
const uploadKey = ref(crypto.randomUUID());
const ticket = ref<ImportTicket | null>(null);
const columns = ref<string[]>([]);
const mapping = ref<Record<string, string>>({});
const rows = ref<ImportRow[]>([]);
const total = ref(0);
const page = ref(1);
const rowFilter = ref<"READY" | "ERROR">("ERROR");
const readyRows = ref(0);
const errorRows = ref(0);
const error = ref("");
const notice = ref("");
const busy = ref(false);
const review = ref<ImportReview | null>(null);
const rollback = ref<ImportRollback | null>(null);
const rollbackReason = ref("");

function selectedFile(event: Event): void {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
  uploadKey.value = crypto.randomUUID();
  error.value = "";
}

async function loadBatch(id: string): Promise<void> {
  ticket.value = await getImport(id);
  review.value = await ownImportReview(id).catch((cause: unknown) => {
    if (cause instanceof Error && "code" in cause && cause.code === "ASSET_IMPORT_REVIEW_NOT_FOUND") return null;
    throw cause;
  });
  rollback.value = await ownImportRollback(id).catch((cause: unknown) => {
    if (cause instanceof Error && "code" in cause && cause.code === "ASSET_IMPORT_ROLLBACK_NOT_FOUND") return null;
    throw cause;
  });
  columns.value = await importColumns(id);
  mapping.value = Object.fromEntries(fields.map((field) =>
    [field.key, ticket.value?.mapping?.[field.key] ??
      (columns.value.includes(field.label) ? field.label : "")]));
  if (ticket.value.status !== "UPLOADED") {
    const [ready, errors] = await Promise.all([
      importRows(id, "READY", 1), importRows(id, "ERROR", 1),
    ]);
    readyRows.value = ready.total;
    errorRows.value = errors.total;
    rowFilter.value = errors.total > 0 ? "ERROR" : "READY";
    rows.value = errors.total > 0 ? errors.items : ready.items;
    total.value = errors.total > 0 ? errors.total : ready.total;
    page.value = 1;
  }
}

async function refresh(): Promise<void> {
  if (!ticket.value) return;
  error.value = "";
  try { await loadBatch(ticket.value.id); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "刷新失败"; }
}

async function submitReview(): Promise<void> {
  if (!ticket.value) return;
  busy.value = true; error.value = "";
  try {
    review.value = await requestImportReview(ticket.value.id, ticket.value.previewVersion);
    notice.value = "已提交资产管理员核验。";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "提交核验失败"; }
  finally { busy.value = false; }
}

async function submitRollback(): Promise<void> {
  if (!ticket.value) return;
  busy.value = true; error.value = "";
  try {
    rollback.value = await requestImportRollback(ticket.value.id, rollbackReason.value.trim());
    notice.value = "整批撤销申请已提交资产管理员。";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "申请撤销失败"; }
  finally { busy.value = false; }
}

async function executeRollback(): Promise<void> {
  if (!ticket.value) return;
  busy.value = true; error.value = "";
  try {
    rollback.value = await queueImportRollback(ticket.value.id);
    notice.value = "整批撤销已排队，请稍后刷新状态。";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "执行撤销失败"; }
  finally { busy.value = false; }
}

async function upload(): Promise<void> {
  if (!file.value) { error.value = "请选择 XLSX 文件"; return; }
  if (file.value.size > 20 * 1024 * 1024) { error.value = "单个文件不能超过 20 MB"; return; }
  busy.value = true;
  error.value = "";
  try {
    const created = await uploadImport(file.value, uploadKey.value);
    await router.replace({ name: "asset-import", query: { batch: created.id } });
    await loadBatch(created.id);
    notice.value = `批次 ${created.id} 已保存，请检查字段映射并预检。`;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "上传失败"; }
  finally { busy.value = false; }
}

async function preview(): Promise<void> {
  if (!ticket.value) return;
  busy.value = true;
  error.value = "";
  try {
    const selected = Object.fromEntries(Object.entries(mapping.value).filter(([, column]) => column));
    const result = await previewImport(ticket.value.id, ticket.value.previewVersion, selected);
    ticket.value = { ...ticket.value, status: "PREVIEWED", previewVersion: result.version };
    readyRows.value = result.readyRows;
    errorRows.value = result.errorRows;
    page.value = 1;
    rowFilter.value = result.errorRows > 0 ? "ERROR" : "READY";
    await loadRows();
    notice.value = `预检完成：正确 ${result.readyRows} 行，错误 ${result.errorRows} 行。`;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "预检失败"; }
  finally { busy.value = false; }
}

async function loadRows(): Promise<void> {
  if (!ticket.value) return;
  try {
    const result = await importRows(ticket.value.id, rowFilter.value, page.value);
    rows.value = result.items;
    total.value = result.total;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "行结果加载失败"; }
}

async function selectRows(status: "READY" | "ERROR"): Promise<void> {
  rowFilter.value = status;
  page.value = 1;
  await loadRows();
}

async function template(): Promise<void> {
  error.value = "";
  try { await downloadImportTemplate(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "模板下载失败"; }
}

async function report(): Promise<void> {
  if (!ticket.value) return;
  error.value = "";
  try { await downloadPreflightReport(ticket.value.id); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "预检结果下载失败"; }
}

async function resultReport(): Promise<void> {
  if (!ticket.value) return;
  error.value = "";
  try { await downloadImportResult(ticket.value.id); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "导入结果下载失败"; }
}

onMounted(async () => {
  const id = typeof route.query.batch === "string" ? route.query.batch : "";
  if (!id) return;
  try { await loadBatch(id); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "批次恢复失败"; }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" @click="template">下载 Excel 模板</button>
    </template>

    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>

    <section class="fams-panel fams-stack" aria-label="上传资产文件">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">1. 上传文件</h2>
          <p class="fams-panel-subtitle">单个文件最多 20 MB、20,000 行。批次保存后可用页面地址重新打开。</p>
        </div>
      </div>
      <label class="fams-file"><input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        aria-label="选择资产 XLSX 文件" @change="selectedFile" /></label>
      <div class="fams-form-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || !file" @click="upload">上传并保存批次</button>
      </div>
    </section>

    <section v-if="ticket" class="fams-panel fams-stack" aria-label="字段映射">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">2. 字段映射</h2>
          <p class="fams-panel-subtitle">批次 {{ ticket.id }} · 预检版本 {{ ticket.previewVersion }}。编码优先匹配；名称仅在目标范围唯一时采用。</p>
        </div>
      </div>
      <div class="fams-field-grid">
        <label v-for="field in fields" :key="field.key" class="fams-field"><span class="fams-field-label">{{ field.label }}</span>
          <select class="fams-control" v-model="mapping[field.key]">
            <option value="">未映射</option>
            <option v-for="column in columns" :key="column" :value="column">{{ column }}</option>
          </select></label>
      </div>
      <div class="fams-form-actions">
        <button class="fams-button" data-kind="primary" type="button"
          :disabled="busy || !!review && review.status !== 'REJECTED' || !['UPLOADED', 'PREVIEWED'].includes(ticket.status)"
          @click="preview">运行预检</button>
      </div>
    </section>

    <section v-if="ticket && ticket.status !== 'UPLOADED'" class="fams-panel fams-stack" aria-label="预检结果">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">3. 预检结果</h2>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="report">下载预检结果 CSV</button>
          <button v-if="['PUBLISHED', 'ROLLED_BACK'].includes(ticket.status)"
            class="fams-button" data-kind="secondary" data-size="sm" type="button"
            @click="resultReport">下载导入结果 CSV</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新状态</button>
        </div>
      </div>
      <p>正确 {{ readyRows }} 行 · 错误 {{ errorRows }} 行。
        {{ ticket.status === 'PUBLISHED' ? '资产已发布。'
          : ticket.status === 'ROLLED_BACK' ? '批次已撤销，资产编码永久保留为废号。'
          : ticket.status === 'QUEUED' ? '管理员已批准，后台发布排队中。'
            : ticket.status === 'FAILED' ? '后台发布失败，请联系管理员处理。'
              : '当前仅保存预检结果，尚未发布资产。' }}</p>
      <div v-if="ticket.status === 'PREVIEWED'" class="fams-form-actions">
        <p v-if="review">核验状态：{{ review.status }}。{{ review.status === 'REJECTED' ? '可修正后重新预检。' : '' }}</p>
        <button v-if="readyRows > 0 && (!review || review.status === 'REJECTED')"
          class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="submitReview">提交管理员核验</button>
      </div>
      <div v-if="ticket.status === 'PUBLISHED'" class="fams-field-row">
        <p v-if="rollback">整批撤销：{{ rollback.status }}。</p>
        <template v-if="!rollback || rollback.status === 'REJECTED'">
          <label class="fams-field"><span class="fams-field-label">撤销原因</span>
            <input class="fams-control" v-model="rollbackReason" maxlength="500" /></label>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || !rollbackReason.trim()"
            @click="submitRollback">申请整批撤销</button>
        </template>
        <button v-if="rollback?.status === 'APPROVED'" class="fams-button" data-kind="primary" type="button"
          :disabled="busy" @click="executeRollback">执行已批准的撤销</button>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="selectRows('ERROR')">错误行</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="selectRows('READY')">正确行</button>
      </div>
      <div class="fams-table-scroll"><table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">Excel 行</th><th scope="col">名称</th><th scope="col">资产编码</th><th scope="col">状态</th><th scope="col">错误列 / 原值 / 原因</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.sheetRow">
            <th scope="row" data-column-kind="identity">{{ row.sheetRow }}</th>
            <td>{{ row.source[mapping.name] ?? '—' }}</td>
            <td>{{ row.source[mapping.assetCode] ?? '—' }}</td>
            <td><span class="fams-status" :data-tone="row.status === 'READY' ? 'success' : 'warning'">{{ row.status === 'READY' ? '可导入' : '需修正' }}</span></td>
            <td><div v-for="item in row.errors ?? []" :key="item.column + item.reason">
              {{ item.sheet }} / {{ item.column }}：{{ item.original || '（空）' }} · {{ item.reason }}
            </div></td>
          </tr>
          <tr v-if="rows.length === 0" class="fams-table-empty"><td colspan="5"><strong>当前没有{{ rowFilter === 'ERROR' ? '错误' : '正确' }}行</strong>切换上方筛选或翻页查看其余行。</td></tr>
        </tbody>
      </table></div>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 行</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="page <= 1" @click="page--; loadRows()">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="page * 50 >= total"
            @click="page++; loadRows()">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
