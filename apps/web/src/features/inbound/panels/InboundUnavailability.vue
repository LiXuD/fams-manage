<script setup lang="ts">
import { computed, shallowRef } from "vue";
import { BusinessApiError, businessMessage, businessRequest } from "@/api/business";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";

const props = defineProps<{ id: string; version: number; ownershipVersion: number; disabled: boolean }>();
/** 无法继续登记的在途意图：路径、版本与请求体在起草时定格，幂等键只由提交底座持有。 */
interface UnavailabilityIntent { path: string; version: number; body: { ownershipVersion: number; reason: string } }

const reason = shallowRef("");
const error = shallowRef("");
const busy = shallowRef(false);
const recorded = shallowRef(false);
const { pending, prepare, confirm: adopt, settle, fail } = useConfirmedSubmit<UnavailabilityIntent>({
  failure: (cause) => cause instanceof BusinessApiError && cause.status >= 400 && cause.status < 500,
});
const formDisabled = computed(() => props.disabled || busy.value || pending.value !== null);
const submitDisabled = computed(() => props.disabled || busy.value);
const submitText = computed(() => (pending.value ? "原请求重试登记" : "登记无法继续"));

async function declare() {
  if (busy.value || props.disabled || recorded.value) return;
  let operation = pending.value;
  if (!operation) {
    if (!reason.value.trim()) {
      error.value = "请填写无法继续的原因。";
      return;
    }
    prepare(() => ({ path: `/inbound-receipts/${props.id}/reassignment/unavailable`,
      version: props.version, body: { ownershipVersion: props.ownershipVersion, reason: reason.value.trim() } }));
    operation = adopt();
  }
  if (!operation) return;
  error.value = "";
  busy.value = true;
  try {
    await businessRequest(operation.payload.path, { ...operation.payload, key: operation.key });
    settle();
    recorded.value = true;
  } catch (cause) {
    fail(cause);
    error.value = businessMessage(cause);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="fams-card fams-stack" aria-label="登记无法继续处理">
    <h4 class="fams-panel-title">无法继续处理</h4>
    <p v-if="recorded" role="status">已通知资产管理员核对交接。转派前处理权仍由你持有。</p>
    <form v-else @submit.prevent="declare">
      <label class="fams-field"><span class="fams-field-label">无法继续的原因</span>
        <textarea class="fams-control" v-model="reason" required maxlength="1000" :disabled="formDisabled" /></label>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="submit" :disabled="submitDisabled">{{ submitText }}</button>
      </div>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
  </section>
</template>
