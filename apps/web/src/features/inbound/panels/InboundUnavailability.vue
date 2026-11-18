<script setup lang="ts">
import { ref } from "vue";
import { BusinessApiError as LoanApiError, businessMessage as loanMessage, businessRequest as loanRequest, type BusinessMutation as LoanMutation } from "@/api/business";

const props = defineProps<{ id: string; version: number; ownershipVersion: number; disabled: boolean }>();
const reason = ref("");
const error = ref("");
const busy = ref(false);
const recorded = ref(false);
const pending = ref<LoanMutation>();

async function declare() {
  if (busy.value || props.disabled || recorded.value) return;
  if (!pending.value && !reason.value.trim()) {
    error.value = "请填写无法继续的原因。";
    return;
  }
  const request = pending.value ??= {
    path: `/inbound-receipts/${props.id}/reassignment/unavailable`,
    key: crypto.randomUUID(), version: props.version,
    body: { ownershipVersion: props.ownershipVersion, reason: reason.value.trim() },
  };
  error.value = "";
  busy.value = true;
  try {
    await loanRequest(request.path, request);
    recorded.value = true;
    pending.value = undefined;
  } catch (cause) {
    if (cause instanceof LoanApiError && cause.status >= 400 && cause.status < 500) pending.value = undefined;
    error.value = loanMessage(cause);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section aria-label="登记无法继续处理">
    <h4>无法继续处理</h4>
    <p v-if="recorded" role="status">已通知资产管理员核对交接。转派前处理权仍由你持有。</p>
    <form v-else @submit.prevent="declare">
      <label>无法继续的原因 <textarea v-model="reason" required maxlength="1000" :disabled="disabled || busy || !!pending" /></label>
      <button type="submit" :disabled="disabled || busy">
        {{ pending ? '原请求重试登记' : '登记无法继续' }}
      </button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>
