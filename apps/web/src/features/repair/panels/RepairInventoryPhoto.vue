<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { useObjectUrlPreview } from "@/composables/useObjectUrlPreview";
import { reviewRequest, ReviewApiError } from "@/features/inventory/api";
import { readablePhoto, repairEvidencePhotoContent, type InventoryPhoto } from "@/features/inventory/photos";
import FamsMediaPreview from "@/components/FamsMediaPreview.vue";
const props = defineProps<{ repairId: string; observationId: string; objectId: string; blocked?: boolean }>();
const emit = defineEmits<{ invalidated: [] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repairId, props.observationId, props.objectId]));
const busy = shallowRef(false), error = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
const preview = useObjectUrlPreview();
function clear() {
  liveness.invalidate(); preview.close(); busy.value = false;
}
async function read() {
  if (busy.value || props.blocked) return;
  clear(); const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    if (!/^[1-9][0-9]{0,18}$/.test(props.repairId) || !/^[1-9][0-9]{0,18}$/.test(props.observationId)
      || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(props.objectId)) throw new Error("Invalid evidence reference");
    const base = `/inventory-repair-evidence/${props.repairId}/observations/${props.observationId}/photos`;
    const list = await reviewRequest<{ items: InventoryPhoto[] }>(base);
    if (!liveness.isCurrent(turn)) return;
    const photo = list.items.find(row => row.objectId === props.objectId);
    if (!photo || !readablePhoto(photo)) throw new Error("Original photo unavailable");
    const path = `${base}/${props.objectId}`;
    const grant = await reviewRequest<{ url: string }>(path + "/download-grant", { path: path + "/download-grant", key: crypto.randomUUID(), version: 0 });
    if (!liveness.isCurrent(turn)) return;
    const url = new URL(grant.url, window.location.origin), value = url.searchParams.get("grant");
    if (url.origin !== window.location.origin || url.pathname !== "/api/v1" + path + "/content" || url.hash || url.username || url.password
      || [...url.searchParams.keys()].length !== 1 || !value || !/^[A-Za-z0-9_.-]{1,256}$/.test(value)) throw new Error("Invalid photo grant");
    const signal = preview.beginDownload();
    const blob = await repairEvidencePhotoContent(url.toString(), signal);
    if (!liveness.isCurrent(turn)) return;
    if (blob.type !== photo.contentType || blob.size !== photo.sizeBytes) throw new Error("Photo metadata mismatch");
    preview.show(blob);
  } catch (cause) {
    if (liveness.isCurrent(turn)) {
      if (cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)) { clear(); emit("invalidated"); }
      error.value = "原始盘点照片暂不可读取，请在当前权限和维修单下重新核对；证据引用仍保留。";
    }
  } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(identity, () => { clear(); error.value = ""; }, { flush: "sync" });
onBeforeUnmount(() => { clear(); });
</script>
<template>
  <div class="fams-inline">
    <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || blocked" @click="read">读取盘点原图 {{ objectId }}</button>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <FamsMediaPreview v-if="preview.url" @dismiss="clear">
      <figure><img :src="preview.url" alt="维修关联的原始盘点损坏照片"><button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="clear">关闭原图并清理</button></figure>
    </FamsMediaPreview>
  </div>
</template>
