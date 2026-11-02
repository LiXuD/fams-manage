<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { AssetApiError, listFactoryReplacements, type FactoryReplacementLink } from "@/features/asset/api";
const props = defineProps<{ assetId: string }>();
const emit = defineEmits<{ "open-card": [string] }>();
const session = useSessionStore();
const items = ref<FactoryReplacementLink[] | null>(null), busy = shallowRef(false), error = shallowRef("");
const identity = computed(() => JSON.stringify([props.assetId, session.user?.id, session.user?.roles, session.csrfToken]));
const liveness = useRequestLiveness(() => [identity.value]);
/** 行显示模型：序列号缺省与整句文案在此拼装，模板只读现成字段。 */
const rows = computed(() => (items.value ?? []).map(item => ({
  sourceId: item.sourceId, originalAssetId: item.originalAssetId, replacementAssetId: item.replacementAssetId,
  description: `旧机编码 ${item.originalAssetCode} · 序列号 ${item.originalSerialNumber ?? "原记录未登记"}；新机编码 ${item.replacementAssetCode} · 序列号 ${item.replacementSerialNumber}。新机独立入库，旧机责任按实际单据保留。`,
})));
const noTrace = computed(() => items.value !== null && items.value.length === 0);
async function load() {
  if (busy.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = ""; items.value = null;
  try {
    const result = await listFactoryReplacements(props.assetId);
    if (liveness.isCurrent(turn)) items.value = result.items;
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = cause instanceof AssetApiError
      ? "当前身份无法读取换机关联，请刷新核对权限和资产。 "
      : cause instanceof Error ? cause.message : "换机关联读取失败，请重试。";
  } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(identity, () => { liveness.invalidate(); items.value = null; busy.value = false; error.value = ""; }, { flush: "sync" });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="新旧实物换机追溯">
    <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">核对新旧实物换机关联</button>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="noTrace">当前权限下暂无可见换机关联。</p>
    <article v-for="row in rows" :key="row.sourceId" class="fams-card fams-stack">
      <p>{{ row.description }}</p>
      <button class="fams-button" data-kind="link" data-size="sm" type="button" @click="emit('open-card', row.originalAssetId)">打开旧机资产卡</button>
      <button class="fams-button" data-kind="link" data-size="sm" type="button" @click="emit('open-card', row.replacementAssetId)">打开新机资产卡</button>
    </article>
  </section>
</template>
