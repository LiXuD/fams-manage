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
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" @click="refresh">刷新状态</button>
    </template>

    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>

    <section v-if="current" class="fams-panel fams-stack" aria-label="当前开放窗口">
      <div class="fams-panel-head">
        <h2 class="fams-panel-title">当前开放窗口</h2>
      </div>
      <dl class="fams-facts">
        <div><dt>窗口编号</dt><dd>{{ current.id }}</dd></div>
        <div><dt>仓库操作员</dt><dd>{{ operators.find((item) => item.id === current?.assignedOperatorId)?.username
          ?? current.assignedOperatorId }}</dd></div>
        <div><dt>截止时间</dt><dd>{{ current.expiresAt }}</dd></div>
      </dl>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="close">关闭窗口</button>
      </div>
    </section>
    <section v-else class="fams-panel fams-stack" aria-label="开放期初窗口">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">开放期初窗口</h2>
          <p class="fams-panel-subtitle">窗口最长 30 天；到期后需重新开放。</p>
        </div>
      </div>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">仓库操作员</span>
          <select class="fams-control" v-model="operatorId"><option value="">请选择</option>
            <option v-for="item in operators" :key="item.id" :value="item.id">{{ item.username }}</option>
          </select></label>
        <label class="fams-field"><span class="fams-field-label">截止时间</span>
          <input class="fams-control" v-model="expiresAt" type="datetime-local" /></label>
        <label class="fams-field"><span class="fams-field-label">开放原因</span>
          <input class="fams-control" v-model="reason" maxlength="500" /></label>
      </div>
      <div class="fams-form-actions">
        <button class="fams-button" data-kind="primary" type="button"
          :disabled="busy || !operatorId || !expiresAt || !reason.trim()" @click="open">开放窗口</button>
      </div>
    </section>
  </AppChrome>
</template>
