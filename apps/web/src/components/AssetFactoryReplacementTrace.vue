<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
const props = defineProps<{ assetId: string }>();
const emit = defineEmits<{ "open-card": [string] }>();
interface Link { sourceId: string; originalAssetId: string; replacementAssetId: string; originalAssetCode: string; replacementAssetCode: string; originalSerialNumber: string | null; replacementSerialNumber: string }
const session = useSessionStore(), items = ref<Link[] | null>(null), busy = ref(false), error = ref("");
const identity = computed(() => JSON.stringify([props.assetId, session.user?.id, session.user?.roles, session.csrfToken]));
let generation = 0, live = true;
watch(identity, () => { generation++; items.value = null; busy.value = false; error.value = ""; }, { flush: "sync" });
async function load() {
  if (busy.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = ""; items.value = null;
  try {
    const response = await fetch(`/api/v1/assets/${props.assetId}/factory-replacements`, { credentials: "include" });
    if (!response.ok) throw new Error("当前身份无法读取换机关联，请刷新核对权限和资产。 ");
    const result = await response.json() as { items: Link[] };
    if (live && epoch === generation && owner === identity.value) items.value = result.items;
  } catch (cause) { if (live && epoch === generation && owner === identity.value) error.value = cause instanceof Error ? cause.message : "换机关联读取失败，请重试。"; }
  finally { if (live && epoch === generation && owner === identity.value) busy.value = false; }
}
onBeforeUnmount(() => { live = false; generation++; });
</script>
<template>
  <section aria-label="新旧实物换机追溯"><button :disabled="busy" @click="load">核对新旧实物换机关联</button><p v-if="error" role="alert">{{ error }}</p><p v-if="items && !items.length">当前权限下暂无可见换机关联。</p>
    <article v-for="item in items" :key="item.sourceId"><p>旧机编码 {{ item.originalAssetCode }} · 序列号 {{ item.originalSerialNumber ?? '原记录未登记' }}；新机编码 {{ item.replacementAssetCode }} · 序列号 {{ item.replacementSerialNumber }}。新机独立入库，旧机责任按实际单据保留。</p><button @click="emit('open-card', item.originalAssetId)">打开旧机资产卡</button><button @click="emit('open-card', item.replacementAssetId)">打开新机资产卡</button></article>
  </section>
</template>
