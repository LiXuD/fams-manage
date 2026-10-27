<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { ReviewApiError } from "@/inventory-review/api";
import { InventoryPhotoUnavailable, listInventoryPhotos, readInventoryPhoto, readablePhoto, type PhotoReference } from "@/inventory-review/photos";
const props = defineProps<{ taskId: string; eventId: string; photoIds: string[] }>();
const emit = defineEmits<{ invalid: [] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.csrfToken, session.user?.roles]));
const scope = computed(() => JSON.stringify([props.taskId, props.eventId, props.photoIds, identity.value]));
const references = ref<PhotoReference[]>([]), busy = ref(false), loaded = ref(false), error = ref("");
const preview = ref(""), filename = ref("");
let live = true, generation = 0;
const current = (epoch: number, captured: string) => live && epoch === generation && captured === scope.value;
function closePreview() { if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ""; filename.value = ""; }
function clear() { generation++; closePreview(); references.value = []; loaded.value = false; busy.value = false; error.value = ""; }
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
  const epoch = ++generation, captured = scope.value;
  closePreview(); references.value = []; loaded.value = false; busy.value = true; error.value = "";
  try {
    const rows = await listInventoryPhotos(props.taskId, props.eventId, props.photoIds, () => current(epoch, captured));
    if (current(epoch, captured)) { references.value = rows; loaded.value = true; }
  } catch (cause) { if (current(epoch, captured)) failed(cause); }
  finally { if (current(epoch, captured)) busy.value = false; }
}
async function open(row: PhotoReference) {
  if (busy.value || !row.photo || !readablePhoto(row.photo) || !references.value.includes(row)) return;
  const epoch = ++generation, captured = scope.value, photo = row.photo;
  busy.value = true; error.value = ""; closePreview();
  try {
    const blob = await readInventoryPhoto(props.taskId, props.eventId, photo, () => current(epoch, captured));
    if (current(epoch, captured)) { preview.value = URL.createObjectURL(blob); filename.value = photo.filename; }
  } catch (cause) { if (current(epoch, captured)) failed(cause); }
  finally { if (current(epoch, captured)) busy.value = false; }
}
watch(scope, clear, { flush: "sync" });
onBeforeUnmount(() => { live = false; clear(); });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="原始观察照片">
    <p class="fams-panel-subtitle">原始引用 {{ photoIds.length }} 张；未使用的上传不计入本次观察。</p>
    <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">{{ loaded ? '刷新原始照片资料' : '查看原始照片资料' }}</button>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <ul class="fams-timeline">
      <li v-for="(row, index) in references" :key="`${index}:${row.objectId}`">
        <template v-if="row.photo"><span>{{ row.photo.filename }}</span>
          <button v-if="readablePhoto(row.photo)" class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="busy" @click="open(row)">读取照片 {{ index + 1 }}</button>
          <span v-else>尚未通过扫描或格式不可读取，原始引用仍保留。</span>
        </template><span v-else>照片 {{ index + 1 }} 元数据暂不可读取，原始引用 {{ row.objectId }} 仍保留。</span>
      </li>
    </ul>
    <figure v-if="preview" class="fams-card">
      <img :src="preview" :alt="`原始观察照片：${filename}`">
      <figcaption>{{ filename }}</figcaption>
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="closePreview">关闭照片</button>
    </figure>
  </section>
</template>
