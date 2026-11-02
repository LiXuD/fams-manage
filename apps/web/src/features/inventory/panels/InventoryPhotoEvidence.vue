<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { useObjectUrlPreview } from "@/composables/useObjectUrlPreview";
import { ReviewApiError } from "@/features/inventory/api";
import { InventoryPhotoUnavailable, listInventoryPhotos, readInventoryPhoto, readablePhoto, type PhotoReference } from "@/features/inventory/photos";
import FamsMediaPreview from "@/components/FamsMediaPreview.vue";
const props = defineProps<{ taskId: string; eventId: string; photoIds: string[] }>();
const emit = defineEmits<{ invalid: [] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.csrfToken, session.user?.roles]));
const scope = computed(() => JSON.stringify([props.taskId, props.eventId, props.photoIds, identity.value]));
const references = ref<PhotoReference[]>([]), busy = shallowRef(false), loaded = shallowRef(false), error = shallowRef("");
const preview = useObjectUrlPreview();
const actionText = computed(() => loaded.value ? "刷新原始照片资料" : "查看原始照片资料");
const previewAlt = computed(() => `原始观察照片：${preview.name}`);
const photoRows = computed(() => references.value.map((row, index) => ({ objectId: row.objectId, source: row,
  order: index + 1, readable: !!row.photo && readablePhoto(row.photo) })));
const liveness = useRequestLiveness(() => [scope.value]);
function clear() { liveness.invalidate(); preview.close(); references.value = []; loaded.value = false; busy.value = false; error.value = ""; }
function failed(cause: unknown) {
  if (cause instanceof InventoryPhotoUnavailable) {
    references.value = props.photoIds.map(objectId => ({ objectId, photo: null })); loaded.value = true; error.value = cause.message; return;
  }
  if (cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)) {
    clear(); error.value = "照片身份、权限或记录已失效，已隐藏原资料。"; emit("invalid"); return;
  }
  error.value = cause instanceof ReviewApiError
    ? cause.code === "ATTACHMENT_NOT_CLEAN" ? "照片尚未通过扫描，暂不可读取。" : "照片读取未完成，请重新核对。"
    : cause instanceof Error ? cause.message : "照片暂不可读取，请重试。";
}
async function load() {
  if (busy.value) return;
  const turn = liveness.begin();
  preview.close(); references.value = []; loaded.value = false; busy.value = true; error.value = "";
  try {
    const rows = await listInventoryPhotos(props.taskId, props.eventId, props.photoIds, () => liveness.isCurrent(turn));
    if (liveness.isCurrent(turn)) { references.value = rows; loaded.value = true; }
  } catch (cause) { if (liveness.isCurrent(turn)) failed(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function open(row: PhotoReference) {
  if (busy.value || !row.photo || !readablePhoto(row.photo) || !references.value.includes(row)) return;
  const turn = liveness.begin(), photo = row.photo;
  busy.value = true; error.value = ""; preview.close();
  try {
    const blob = await readInventoryPhoto(props.taskId, props.eventId, photo, () => liveness.isCurrent(turn));
    if (liveness.isCurrent(turn)) preview.show(blob, photo.filename);
  } catch (cause) { if (liveness.isCurrent(turn)) failed(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(scope, clear, { flush: "sync" });
onBeforeUnmount(() => { clear(); });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="原始观察照片">
    <p class="fams-panel-subtitle">原始引用 {{ photoIds.length }} 张；未使用的上传不计入本次观察。</p>
    <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">{{ actionText }}</button>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <ul class="fams-timeline">
      <li v-for="row in photoRows" :key="row.objectId">
        <template v-if="row.source.photo"><span>{{ row.source.photo.filename }}</span>
          <button v-if="row.readable" class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="busy" @click="open(row.source)">读取照片 {{ row.order }}</button>
          <span v-else>尚未通过扫描或格式不可读取，原始引用仍保留。</span>
        </template><span v-else>照片 {{ row.order }} 元数据暂不可读取，原始引用 {{ row.source.objectId }} 仍保留。</span>
      </li>
    </ul>
    <FamsMediaPreview v-if="preview.url" @dismiss="preview.close()">
      <figure>
      <img :src="preview.url" :alt="previewAlt">
      <figcaption>{{ preview.name }}</figcaption>
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="preview.close()">关闭照片</button>
      </figure>
    </FamsMediaPreview>
  </section>
</template>
