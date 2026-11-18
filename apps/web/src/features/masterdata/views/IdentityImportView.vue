<script setup lang="ts">
import { computed, ref, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";

interface ImportResult { processedRows: number; createdRows: number; updatedRows: number; disabledRows: number }
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const filename = ref("");
const csv = ref("");
const busy = ref(false);
const error = ref("");
const result = ref<ImportResult | null>(null);
const pending = ref<{ key: string; csv: string } | null>(null);
let generation = 0;

async function download(): Promise<void> {
  if (!identity.value || busy.value || pending.value) return;
  const run = generation;
  busy.value = true; error.value = "";
  try {
    const response = await fetch("/api/v1/identity-imports/template", { credentials: "include" });
    if (!response.ok) throw new Error("当前身份无法下载模板，请重新登录核对。");
    const text = await response.text();
    if (run !== generation) return;
    const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a");
    anchor.href = url; anchor.download = "组织人员导入模板.csv";
    document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  } catch (cause) {
    if (run === generation) error.value = cause instanceof Error ? cause.message : "模板下载失败，请重试。";
  } finally {
    if (run === generation) busy.value = false;
  }
}

async function selectFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!identity.value || busy.value || pending.value || !file) return;
  const run = ++generation;
  busy.value = true; error.value = ""; result.value = null; csv.value = ""; filename.value = "";
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer()).replace(/^\uFEFF/, "");
    if (run !== generation) return;
    if (!text.trim()) throw new Error("导入文件为空。");
    filename.value = file.name; csv.value = text;
  } catch (cause) {
    if (run === generation) error.value = cause instanceof TypeError
      ? "文件须保存为 UTF-8 编码的 CSV，请转换编码后重新选择。"
      : cause instanceof Error ? cause.message : "文件读取失败，请重新选择。";
  } finally {
    if (run === generation) busy.value = false;
  }
}

async function submit(): Promise<void> {
  if (!identity.value || busy.value || !csv.value) return;
  if (!pending.value) {
    if (!window.confirm(`确认导入 ${filename.value}？文件中的更新和停用操作将同时执行。`)) return;
    pending.value = { key: crypto.randomUUID(), csv: csv.value };
  }
  const operation = pending.value;
  const run = generation;
  busy.value = true; error.value = ""; result.value = null;
  try {
    if (!session.csrfToken) {
      pending.value = null;
      throw new Error("登录已失效，请重新登录。");
    }
    const response = await fetch("/api/v1/identity-imports", {
      method: "POST", credentials: "include",
      headers: { "Content-Type": "text/csv;charset=utf-8", "X-CSRF-Token": session.csrfToken,
        "Idempotency-Key": operation.key },
      body: operation.csv,
    });
    if (!response.ok) {
      if (run !== generation) return;
      if (response.status >= 400 && response.status < 500) pending.value = null;
      const failure = await response.json().catch(() => ({})) as { message?: string };
      if (response.status === 401 || response.status === 403) throw new Error("当前身份或系统管理员权限已失效。");
      if (response.status === 409) throw new Error("资料版本或依赖关系已变化，请核对当前资料后修改文件。");
      if (response.status === 400) throw new Error(failure.message ?? "文件内容不符合标准模板，请核对列、内容和版本。");
      throw new Error("导入结果尚未确认，请使用原请求重试。");
    }
    const reply = await response.json() as ImportResult;
    if (run !== generation) return;
    result.value = reply; pending.value = null;
    csv.value = ""; filename.value = "";
  } catch (cause) {
    if (run === generation) error.value = pending.value
      ? "导入结果尚未确认，请使用原请求重试。"
      : cause instanceof Error ? cause.message : "导入未完成，请核对文件。";
  } finally {
    if (run === generation) busy.value = false;
  }
}

watch(identity, () => {
  generation++; filename.value = ""; csv.value = ""; pending.value = null;
  busy.value = false; error.value = ""; result.value = null;
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <h1>组织人员导入</h1>
    <p v-if="!identity" role="alert">只有系统管理员可以导入组织与人员。</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p>
      <p>使用 UTF-8 CSV 标准模板，最多 500 条记录，按文件顺序执行。任一行失败时整批回滚。</p>
      <button :disabled="busy || !!pending" @click="download">下载身份资料模板</button>
      <router-link to="/identity">查询当前组织、工号与版本</router-link>
      <dl>
        <dt>recordType</dt><dd>ORGANIZATION 为组织，EMPLOYEE 为员工。</dd>
        <dt>action</dt><dd>CREATE 新建、UPDATE 更新、DISABLE 停用。</dd>
        <dt>code / displayName</dt><dd>组织编码或员工工号 / 名称。</dd>
        <dt>parentCode / organizationCode</dt><dd>上级组织编码 / 员工所属组织编码。</dd>
        <dt>phone</dt><dd>新建员工的企业登记电话，已有号码的变更须经过核验纠正。</dd>
        <dt>expectedVersion</dt><dd>更新、停用时填当前版本。</dd>
      </dl>
      <label>选择 UTF-8 CSV<input :key="identity" type="file" accept=".csv,text/csv" :disabled="busy || !!pending" @change="selectFile" /></label>
      <section v-if="csv" aria-label="导入文件预览">
        <h2>{{ filename }}</h2>
        <label>文件内容<textarea :value="csv" readonly rows="12" /></label>
        <button :disabled="busy" @click="submit">{{ pending ? "原请求重试导入" : "确认导入" }}</button>
      </section>
      <section v-if="result" role="status" aria-label="导入结果">
        <h2>导入完成</h2>
        <p>处理 {{ result.processedRows }} 条，新增 {{ result.createdRows }} 条，
          更新 {{ result.updatedRows }} 条，停用 {{ result.disabledRows }} 条。</p>
      </section>
    </template>
  </AppChrome>
</template>
