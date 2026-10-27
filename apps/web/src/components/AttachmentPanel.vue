<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { listAttachments, uploadAttachment, correctAttachment, downloadAttachment, type Attachment } from "@/attachment/api";
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
const correction = ref<Attachment | null>(null);
const correctionReason = ref("");
const pending = ref<{ receiptId: string; version: number; file: File; key: string;
  previousObjectId?: string; reason?: string } | null>(null);

function chooseCorrection(item: Attachment | null): void {
  if (busy.value || props.disabled) return;
  correction.value = item; correctionReason.value = ""; pending.value = null;
  error.value = ""; notice.value = "";
  if (fileInput.value) fileInput.value.value = "";
}
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
  pending.value = { receiptId: props.receipt.id, version: props.receipt.version, file, key: crypto.randomUUID(),
    previousObjectId: correction.value?.objectId };
}

async function upload(): Promise<void> {
  const request = pending.value;
  if (!request || !writable.value || busy.value || props.disabled) return;
  if (request.previousObjectId && !request.reason) {
    if (!correctionReason.value.trim() || correctionReason.value.length > 500) {
      error.value = "请填写纠错原因（最多 500 字）。"; return;
    }
    request.reason = correctionReason.value.trim();
  }
  busy.value = true; emit("busy", true); error.value = "";
  try {
    const result = request.previousObjectId
      ? await correctAttachment(request.receiptId, request.version, request.previousObjectId,
        request.reason!, request.file, request.key)
      : await uploadAttachment(request.receiptId, request.version, request.file, request.key);
    if (props.receipt.id !== request.receiptId) return;
    emit("uploaded", result.businessVersion);
    pending.value = null; correction.value = null; correctionReason.value = "";
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
  pending.value = null; correction.value = null; correctionReason.value = ""; items.value = []; error.value = ""; notice.value = "";
  if (fileInput.value) fileInput.value.value = "";
  void refresh();
}, { immediate: true });
</script>

<template>
  <section class="fams-card fams-stack" aria-label="业务附件">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">业务附件</h3>
        <p class="fams-panel-subtitle">支持 JPG、PNG、WEBP、PDF；每单最多 9 张图片、5 个 PDF，总量 50 MB。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="busy || disabled || loading" @click="refresh">刷新附件</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" :data-state="noticeGood ? 'success' : 'warning'" role="status"><strong>{{ notice }}</strong></p>
    <form v-if="writable" class="fams-stack" @submit.prevent="upload">
      <p v-if="correction">纠错：{{ correction.filename }} · 原件将保留</p>
      <label v-if="correction" class="fams-field"><span class="fams-field-label">纠错原因</span><input class="fams-control" v-model="correctionReason" maxlength="500"
        :disabled="busy || disabled || !!pending?.reason" /></label>
      <button v-if="correction" class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="busy || disabled"
        @click="chooseCorrection(null)">取消纠错</button>
      <label class="fams-file"><span class="fams-field-label">选择附件</span><input ref="fileInput" type="file" accept=".jpg,.jpeg,.png,.webp,.pdf"
        :disabled="busy || disabled" @change="selectFile" /></label>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="!pending || busy || disabled">
        {{ busy ? '上传中…' : correction ? '提交纠错 / 重试' : '上传附件 / 重试' }}
      </button>
    </form>
    <p v-else>当前单据只读，可查看已登记附件。</p>
    <p v-if="loading" role="status">正在加载附件…</p>
    <div class="fams-table-scroll"><table class="fams-table">
      <thead><tr><th scope="col" data-column-kind="identity">文件</th><th scope="col" data-column-kind="numeric">大小</th>
        <th scope="col">扫描状态</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
      <tbody>
        <tr v-for="item in items" :key="item.objectId">
          <th scope="row" data-column-kind="identity">{{ item.filename }} <span>· 第 {{ item.revisionNumber ?? 1 }} 版{{ item.nextObjectId ? ' · 已有新版' : '' }}</span>
            <p v-if="item.correctionReason">纠错原因：{{ item.correctionReason }}</p></th><td data-column-kind="numeric">{{ (item.sizeBytes / 1024).toFixed(1) }} KB</td>
          <td><span class="fams-status" :data-tone="item.scanStatus === 'CLEAN' ? 'success' : 'warning'">{{ scanText[item.scanStatus] }}</span></td>
          <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button"
            :disabled="busy || disabled || item.scanStatus !== 'CLEAN'" @click="download(item)">下载</button>
            <button v-if="writable && !item.nextObjectId" class="fams-button" data-kind="link" data-size="sm" type="button"
              :disabled="busy || disabled" @click="chooseCorrection(item)">追加纠错版本</button></td>
        </tr>
        <tr v-if="!loading && !items.length" class="fams-table-empty"><td colspan="4"><strong>暂无附件</strong>上传并扫描通过后在此查看与下载。</td></tr>
      </tbody>
    </table></div>
  </section>
</template>
