<script setup lang="ts">
import { ref } from "vue";
import { BusinessApiError as LoanApiError, businessRequest as loanRequest, type BusinessMutation as LoanMutation } from "@/api/business";

const props = defineProps<{ assetId: string; version: number }>();
const token = ref("");
const open = ref(false);
const pending = ref<LoanMutation>();
const busy = ref(false);
const error = ref("");
const saved = ref(false);

async function confirm() {
  if (busy.value) return;
  if (!pending.value) {
    const value = token.value.trim();
    if (!/^[A-Za-z0-9_-]{43}$/.test(value)) {
      error.value = "请扫描已贴在该资产上的二维码。";
      return;
    }
    pending.value = { path: `/assets/${props.assetId}/tag/placement`, key: crypto.randomUUID(),
      version: props.version, body: { token: value } };
    token.value = "";
  }
  busy.value = true;
  error.value = "";
  try {
    await loanRequest(pending.value.path, pending.value);
    pending.value = undefined;
    open.value = false;
    saved.value = true;
  } catch (cause) {
    if (cause instanceof LoanApiError && cause.status >= 400 && cause.status < 500) {
      pending.value = undefined;
      error.value = "确认未完成，请核对实物标签和当前资产，再刷新后重试。";
    } else {
      error.value = "提交结果尚未确认，请重试原确认。";
    }
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div>
    <button v-if="!open" type="button" @click="open = true; saved = false">确认实物贴标</button>
    <form v-if="open" @submit.prevent="confirm">
      <label v-if="!pending">实物二维码 <input v-model="token" :disabled="busy" maxlength="43"
          required autocomplete="off" spellcheck="false"></label>
      <button type="submit" :disabled="busy">{{ pending ? "重试原确认" : "确认已贴标" }}</button>
      <button v-if="!pending" type="button" :disabled="busy" @click="open = false; token = ''; error = ''">取消</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="saved" role="status">贴标确认已登记</p>
  </div>
</template>
