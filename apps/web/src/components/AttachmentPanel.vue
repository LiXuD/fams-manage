<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { listAttachments, uploadAttachment, downloadAttachment, type Attachment } from "@/attachment/api";
import type { InboundReceipt } from "@/inbound/api";

const props = defineProps<{ receipt: InboundReceipt; disabled?: boolean }>();
const emit = defineEmits<{ uploaded: [version: number]; busy: [value: boolean] }>();
const session = useSessionStore();
const items = ref<Attachment[]>([]);
const error = ref("");
const notice = ref("");
const noticeGood = ref(false);
const busy = ref(false);
const loading = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
const pending = ref<{ receiptId: string; version: number; file: File; key: string } | null>(null);
let generation = 0;
const writable = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR")
  && ((props.receipt.status === "DRAFT" && props.receipt.createdBy.id === session.user.id)
    || (props.receipt.status === "WAREHOUSE_PROCESSING"
      && props.receipt.currentProcessor?.processor.id === session.user.id)));
const scanText = { CLEAN: "可下载", INFECTED: "感染文件 · 已隔离", UNAVAILABLE: "扫描不可用 · 已隔离" };

async function refresh(): Promise<void> {
  const current = ++generation;
  const id = props.receipt.id;
  loading.value = true; error.value = "";
  try {
    const result = await listAttachments(id);
    if (generation === current && props.receipt.id === id) items.value = result;
  } catch (cause) {
    if (generation === current) error.value = cause instanceof Error ? cause.message : "附件列表加载失败";
  } finally { if (generation === current) loading.value = false; }
}

function selectFile(event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0];
  pending.value = null; error.value = ""; notice.value = "";
  if (!file) return;
  const pdf = /\.pdf$/i.test(file.name);
  if (!/\.(jpg|jpeg|png|webp|pdf)$/i.test(file.name) || file.size === 0
    || file.size > (pdf ? 20 : 10) * 1024 * 1024) {
    error.value = "请选择非空 JPG、PNG、WEBP 或 PDF，图片不超过 10 MB，PDF 不超过 20 MB。";
    if (fileInput.value) fileInput.value.value = "";
    return;
  }
  pending.value = { receiptId: props.receipt.id, version: props.receipt.version, file, key: crypto.randomUUID() };
}

async function upload(): Promise<void> {
  const request = pending.value;
  if (!request || !writable.value || busy.value || props.disabled) return;
  busy.value = true; emit("busy", true); error.value = "";
  try {
    const result = await uploadAttachment(request.receiptId, request.version, request.file, request.key);
    if (props.receipt.id !== request.receiptId) return;
    emit("uploaded", result.businessVersion);
    pending.value = null;
    if (fileInput.value) fileInput.value.value = "";
    noticeGood.value = result.scanStatus === "CLEAN";
    notice.value = noticeGood.value ? "附件已上传，可授权下载。" : "附件已隔离，尚不可下载。";
    await refresh();
  } catch (cause) {
    if (props.receipt.id === request.receiptId) {
      error.value = cause instanceof Error ? cause.message : "上传未完成，请重试";
    }
  } finally { busy.value = false; emit("busy", false); }
}

async function download(item: Attachment): Promise<void> {
  if (busy.value || props.disabled || item.scanStatus !== "CLEAN") return;
  busy.value = true; error.value = "";
  try { await downloadAttachment(item); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "下载未完成"; }
  finally { busy.value = false; }
}

watch(() => props.receipt.id, () => {
  pending.value = null; items.value = []; error.value = ""; notice.value = "";
  if (fileInput.value) fileInput.value.value = "";
  void refresh();
}, { immediate: true });
</script>

<template>
  <section aria-label="业务附件">
    <h3>业务附件</h3>
    <button class="text-button" type="button" :disabled="busy || disabled || loading" @click="refresh">刷新附件</button>
    <p>支持 JPG、PNG、WEBP、PDF；每单最多 9 张图片、5 个 PDF，总量 50 MB。</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="notice" :class="noticeGood ? 'asset-notice' : 'attachment-notice'" role="status">{{ notice }}</p>
    <form v-if="writable" class="asset-actions" @submit.prevent="upload">
      <label>选择附件<input ref="fileInput" type="file" accept=".jpg,.jpeg,.png,.webp,.pdf"
        :disabled="busy || disabled" @change="selectFile" /></label>
      <button class="secondary-button" type="submit" :disabled="!pending || busy || disabled">
        {{ busy ? '上传中…' : '上传附件 / 重试' }}
      </button>
    </form>
    <p v-else>当前单据只读，可查看已登记附件。</p>
    <p v-if="loading" role="status">正在加载附件…</p>
    <div class="asset-table-wrap"><table class="asset-table">
      <thead><tr><th>文件</th><th>大小</th><th>扫描状态</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="item in items" :key="item.objectId">
          <td>{{ item.filename }}</td><td>{{ (item.sizeBytes / 1024).toFixed(1) }} KB</td>
          <td>{{ scanText[item.scanStatus] }}</td>
          <td><button class="text-button" type="button"
            :disabled="busy || disabled || item.scanStatus !== 'CLEAN'" @click="download(item)">下载</button></td>
        </tr>
        <tr v-if="!loading && !items.length"><td colspan="4">暂无附件</td></tr>
      </tbody>
    </table></div>
  </section>
</template>

<style scoped>
button:disabled { cursor: not-allowed; opacity: 0.55; }
.attachment-notice { color: var(--muted); font-size: 14px; }
</style>
