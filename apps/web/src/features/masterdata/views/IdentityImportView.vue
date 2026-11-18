<script setup lang="ts">
import { computed, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { apiUrl, requestBlob } from "@/api/http";
import { identityImportCsv, IdentityImportApiError } from "@/features/masterdata/api";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";

interface ImportResult { processedRows: number; createdRows: number; updatedRows: number; disabledRows: number }
/** 导入意图只带起草时的文件正文；幂等键一律由 useConfirmedSubmit 底座生成，重试不换键。 */
type ImportIntent = { csv: string };

const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const filename = shallowRef("");
const csv = shallowRef("");
const busy = shallowRef(false);
const error = shallowRef("");
const result = shallowRef<ImportResult | null>(null);
const liveness = useRequestLiveness(() => [identity.value]);
const { pending: pendingImport, prepare: draftImport, confirm: adoptImport,
  settle: settleImport, abandon: abandonImport, reset: resetImport } = useConfirmedSubmit<ImportIntent>();
const controlsDisabled = computed(() => busy.value || pendingImport.value !== null);
const submitDisabled = computed(() => busy.value);
const submitLabel = computed(() => pendingImport.value ? "原请求重试导入" : "确认导入");

async function download(): Promise<void> {
  if (!identity.value || busy.value || pendingImport.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const blob = await requestBlob(apiUrl("/identity-imports/template"),
      () => new Error("当前身份无法下载模板，请重新登录核对。"));
    const text = await blob.text();
    if (!liveness.isCurrent(turn)) return;
    const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a");
    anchor.href = url; anchor.download = "组织人员导入模板.csv";
    document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = cause instanceof Error ? cause.message : "模板下载失败，请重试。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function selectFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!identity.value || busy.value || pendingImport.value || !file) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; result.value = null; csv.value = ""; filename.value = "";
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer()).replace(/^\uFEFF/, "");
    if (!liveness.isCurrent(turn)) return;
    if (!text.trim()) throw new Error("导入文件为空。");
    filename.value = file.name; csv.value = text;
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = cause instanceof TypeError
      ? "文件须保存为 UTF-8 编码的 CSV，请转换编码后重新选择。"
      : cause instanceof Error ? cause.message : "文件读取失败，请重新选择。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

/** CSV 导入失败的状态码到中文文案映射逐字取自功能线；400 优先播报服务端 message。 */
function importProblem(cause: IdentityImportApiError): string {
  if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效。";
  if (cause.status === 409) return "资料版本或依赖关系已变化，请核对当前资料后修改文件。";
  if (cause.status === 400) return cause.serverMessage ?? "文件内容不符合标准模板，请核对列、内容和版本。";
  return "导入结果尚未确认，请使用原请求重试。";
}

async function submit(): Promise<void> {  if (!identity.value || busy.value || !csv.value) return;
  let operation = pendingImport.value;
  if (!operation) {
    if (!window.confirm(`确认导入 ${filename.value}？文件中的更新和停用操作将同时执行。`)) return;
    draftImport(() => ({ csv: csv.value }));
    operation = adoptImport();
  }
  if (!operation) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; result.value = null;
  try {
    if (!session.csrfToken) {
      abandonImport();
      throw new Error("登录已失效，请重新登录。");
    }
    const reply = await identityImportCsv<ImportResult>(operation.payload.csv, operation.key);
    if (!liveness.isCurrent(turn)) return;
    result.value = reply; settleImport();
    csv.value = ""; filename.value = "";
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    if (cause instanceof IdentityImportApiError) {
      if (cause.status >= 400 && cause.status < 500) abandonImport();
      error.value = importProblem(cause);
    } else {
      error.value = pendingImport.value
        ? "导入结果尚未确认，请使用原请求重试。"
        : cause instanceof Error ? cause.message : "导入未完成，请核对文件。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

watch(identity, () => {
  liveness.invalidate(); filename.value = ""; csv.value = ""; resetImport();
  busy.value = false; error.value = ""; result.value = null;
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="noaccess" role="alert"><strong>只有系统管理员可以导入组织与人员。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p>使用 UTF-8 CSV 标准模板，最多 500 条记录，按文件顺序执行。任一行失败时整批回滚。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="controlsDisabled" @click="download">下载身份资料模板</button>
        <router-link class="fams-button" data-kind="link" data-size="sm" to="/identity">查询当前组织、工号与版本</router-link>
      </div>
      <dl class="fams-facts">
        <dt>recordType</dt><dd>ORGANIZATION 为组织，EMPLOYEE 为员工。</dd>
        <dt>action</dt><dd>CREATE 新建、UPDATE 更新、DISABLE 停用。</dd>
        <dt>code / displayName</dt><dd>组织编码或员工工号 / 名称。</dd>
        <dt>parentCode / organizationCode</dt><dd>上级组织编码 / 员工所属组织编码。</dd>
        <dt>phone</dt><dd>新建员工的企业登记电话，已有号码的变更须经过核验纠正。</dd>
        <dt>expectedVersion</dt><dd>更新、停用时填当前版本。</dd>
      </dl>
      <label class="fams-file"><span class="fams-field-label">选择 UTF-8 CSV</span>
        <input :key="identity" type="file" accept=".csv,text/csv" :disabled="controlsDisabled" @change="selectFile" />
      </label>
      <section v-if="csv" class="fams-panel fams-stack" aria-label="导入文件预览">
        <h2 class="fams-panel-title">{{ filename }}</h2>
        <label class="fams-field"><span class="fams-field-label">文件内容</span>
          <textarea class="fams-control" :value="csv" readonly rows="12" />
        </label>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button"
          :disabled="submitDisabled" @click="submit">{{ submitLabel }}</button></div>
      </section>
      <section v-if="result" class="fams-panel fams-stack" role="status" aria-label="导入结果">
        <h2 class="fams-panel-title">导入完成</h2>
        <p>处理 {{ result.processedRows }} 条，新增 {{ result.createdRows }} 条，
          更新 {{ result.updatedRows }} 条，停用 {{ result.disabledRows }} 条。</p>
      </section>
    </template>
  </AppChrome>
</template>
