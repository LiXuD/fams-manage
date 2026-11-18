<script setup lang="ts">
import { computed, shallowRef } from "vue";
import { AssetApiError, confirmTagPlacement } from "@/features/asset/api";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";

const props = defineProps<{ assetId: string; version: number }>();
/** 贴标确认的在途意图：资产、版本与令牌在起草时定格，幂等键只由提交底座持有。 */
interface PlacementIntent { assetId: string; version: number; token: string }

const token = shallowRef("");
const open = shallowRef(false);
const busy = shallowRef(false);
const error = shallowRef("");
const saved = shallowRef(false);
const { pending, prepare, confirm: adopt, settle, fail } = useConfirmedSubmit<PlacementIntent>({
  failure: (cause) => cause instanceof AssetApiError && cause.status >= 400 && cause.status < 500,
});
const retrying = computed(() => pending.value !== null);
const submitText = computed(() => (retrying.value ? "重试原确认" : "确认已贴标"));

async function submit() {
  if (busy.value) return;
  let operation = pending.value;
  if (!operation) {
    const value = token.value.trim();
    if (!/^[A-Za-z0-9_-]{43}$/.test(value)) {
      error.value = "请扫描已贴在该资产上的二维码。";
      return;
    }
    prepare(() => ({ assetId: props.assetId, version: props.version, token: value }));
    token.value = "";
    operation = adopt();
  }
  if (!operation) return;
  busy.value = true;
  error.value = "";
  try {
    await confirmTagPlacement(operation.payload, operation.key);
    settle();
    open.value = false;
    saved.value = true;
  } catch (cause) {
    if (fail(cause)) error.value = "确认未完成，请核对实物标签和当前资产，再刷新后重试。";
    else error.value = "提交结果尚未确认，请重试原确认。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="fams-stack">
    <button v-if="!open" class="fams-button" data-kind="secondary" type="button" @click="open = true; saved = false">确认实物贴标</button>
    <form v-if="open" @submit.prevent="submit">
      <label v-if="!retrying" class="fams-field"><span class="fams-field-label">实物二维码</span>
        <input class="fams-control" v-model="token" :disabled="busy" maxlength="43" required autocomplete="off" spellcheck="false"></label>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="submit" :disabled="busy">{{ submitText }}</button>
        <button v-if="!retrying" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="open = false; token = ''; error = ''">取消</button>
      </div>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="saved" role="status">贴标确认已登记</p>
    </form>
  </div>
</template>
