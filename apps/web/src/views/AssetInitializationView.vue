<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import {
  closeImportWindow, currentImportWindow, eligibleImportOperators, openImportWindow,
  type EligibleOperator, type InitializationWindow,
} from "@/assetimport/api";

const operators = ref<EligibleOperator[]>([]);
const current = ref<InitializationWindow | null>(null);
const operatorId = ref("");
const reason = ref("");
const expiresAt = ref("");
const error = ref("");
const notice = ref("");
const busy = ref(false);

async function refresh(): Promise<void> {
  error.value = "";
  try {
    operators.value = await eligibleImportOperators();
    current.value = await currentImportWindow().catch((cause: unknown) => {
      if (cause instanceof Error && "code" in cause && cause.code === "ASSET_IMPORT_WINDOW_NOT_FOUND") return null;
      throw cause;
    });
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "窗口状态加载失败"; }
}
async function open(): Promise<void> {
  if (!operatorId.value || !expiresAt.value || !reason.value.trim()) return;
  busy.value = true; error.value = "";
  try {
    current.value = await openImportWindow(operatorId.value, reason.value.trim(),
      new Date(expiresAt.value).toISOString());
    notice.value = "期初窗口已开放。";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "开放窗口失败"; }
  finally { busy.value = false; }
}
async function close(): Promise<void> {
  if (!current.value) return;
  busy.value = true; error.value = "";
  try {
    await closeImportWindow(current.value.id);
    current.value = null;
    notice.value = "期初窗口已关闭。";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "关闭窗口失败"; }
  finally { busy.value = false; }
}
onMounted(refresh);
</script>

<template>
  <AppChrome>
    <div class="breadcrumb">工作台 / 资产台账 / 期初窗口</div>
    <div class="asset-heading"><div><h1>期初导入窗口</h1><p>指定仓库操作员和截止时间。仅窗口内可提交与发布期初资产。</p></div>
      <button class="secondary-button" type="button" @click="refresh">刷新状态</button></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="asset-notice" role="status">{{ notice }}</p>
    <section v-if="current" class="asset-panel">
      <h2>当前开放窗口</h2>
      <p>窗口 {{ current.id }} · 操作员 {{ operators.find((item) => item.id === current?.assignedOperatorId)?.username
        ?? current.assignedOperatorId }} · 截止 {{ current.expiresAt }}</p>
      <button class="secondary-button" type="button" :disabled="busy" @click="close">关闭窗口</button>
    </section>
    <section v-else class="asset-panel">
      <h2>开放期初窗口</h2>
      <div class="asset-filters">
        <label>仓库操作员 <select v-model="operatorId"><option value="">请选择</option>
          <option v-for="item in operators" :key="item.id" :value="item.id">{{ item.username }}</option>
        </select></label>
        <label>截止时间 <input v-model="expiresAt" type="datetime-local" /></label>
        <label>开放原因 <input v-model="reason" maxlength="500" /></label>
      </div>
      <p>窗口最长 30 天；到期后需重新开放。</p>
      <button class="primary-button" type="button"
        :disabled="busy || !operatorId || !expiresAt || !reason.trim()" @click="open">开放窗口</button>
    </section>
  </AppChrome>
</template>
