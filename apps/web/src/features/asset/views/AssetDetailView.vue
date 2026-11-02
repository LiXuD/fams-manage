<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import QRCode from "qrcode";
import { useSessionStore } from "@/auth/sessionStore";
import {
  cancelAsset, cardStageLabels, copyAsset, custodyLabel, destinationLabels, getAsset, getTag,
  reprintTag, updateAsset, type AssetCard, type AssetTag,
} from "@/features/asset/api";

type Action = "notes" | "copy" | "cancel";
const props = defineProps<{ id: string }>();
const route = useRoute();
const session = useSessionStore();
const selected = ref<AssetCard | null>(null);
const tag = ref<AssetTag | null>(null);
const qrImage = shallowRef("");
const error = shallowRef("");
const notice = shallowRef("");
const busy = shallowRef(false);
const pending = ref<Action | null>(null);
const notes = shallowRef("");
const quantity = ref("1");
const reason = shallowRef("");

const canWrite = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;
const locked = computed(() => busy.value || pending.value !== null);
const fieldLabels: Record<Action, string> = { notes: "备注修正", copy: "复制建卡数量（1–100）", cancel: "取消原因" };
const confirmLabels: Record<Action, string> = { notes: "确认修正备注", copy: "确认复制建卡", cancel: "确认取消草稿" };
const custodyText = computed(() => (selected.value ? custodyLabel(selected.value) : ""));
const canCorrectNotes = computed(() => canWrite && selected.value !== null
  && selected.value.cardStage !== "PENDING_INBOUND" && selected.value.cardStage !== "CANCELLED");
const canCancelDraft = computed(() => canWrite && selected.value?.cardStage === "DRAFT");
const canPrintTag = computed(() => canWrite && selected.value?.cardStage === "ACTIVE");
const pendingIsNotes = computed(() => pending.value === "notes");
const pendingIsCopy = computed(() => pending.value === "copy");

async function open(): Promise<void> {
  error.value = "";
  tag.value = null;
  qrImage.value = "";
  try {
    selected.value = await getAsset(props.id);
    tag.value = await getTag(props.id);
    qrImage.value = await QRCode.toDataURL(tag.value.qrCode, { width: 160, margin: 1 });
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "资产详情加载失败"; }
}

function openAction(action: Action): void {
  if (locked.value) return;
  error.value = "";
  notes.value = "";
  quantity.value = "1";
  reason.value = "";
  pending.value = action;
}

async function correctNotes(): Promise<void> {
  if (!selected.value) return;
  busy.value = true;
  try {
    selected.value = await updateAsset(selected.value.id, selected.value.version, { notes: notes.value });
    pending.value = null;
    notice.value = "备注已更新";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "更新失败"; }
  finally { busy.value = false; }
}

async function copyCurrent(count: number): Promise<void> {
  if (!selected.value) return;
  busy.value = true;
  try {
    const result = await copyAsset(selected.value.id, selected.value.version, count);
    pending.value = null;
    notice.value = `已创建 ${result.assets.length} 张独立草稿`;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "复制失败"; }
  finally { busy.value = false; }
}

async function cancelCurrent(trimmed: string): Promise<void> {
  if (!selected.value) return;
  busy.value = true;
  try {
    selected.value = await cancelAsset(selected.value.id, selected.value.version, trimmed);
    pending.value = null;
    notice.value = "草稿已取消，编码永久保留";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "取消失败"; }
  finally { busy.value = false; }
}

async function confirm(): Promise<void> {
  if (!selected.value || busy.value || !pending.value) return;
  if (pending.value === "copy") {
    const count = Number(quantity.value);
    if (!Number.isInteger(count) || count < 1 || count > 100) return;
    await copyCurrent(count);
    return;
  }
  if (pending.value === "cancel") {
    const trimmed = reason.value.trim();
    if (!trimmed) return;
    await cancelCurrent(trimmed);
    return;
  }
  await correctNotes();
}

async function printCurrent(): Promise<void> {
  if (!selected.value) return;
  busy.value = true;
  try {
    const result = await reprintTag(selected.value.id, selected.value.version);
    tag.value = result.tag;
    qrImage.value = await QRCode.toDataURL(result.tag.qrCode, { width: 160, margin: 1 });
    notice.value = `打印任务 ${result.jobId} 已完成；标签身份保持不变`;
    window.print();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "标签打印失败"; }
  finally { busy.value = false; }
}

onMounted(async () => {
  if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "资产详情加载失败"; return; }
  await open();
  if (selected.value && route.query.saved) {
    notice.value = `草稿 ${selected.value.assetCode} 已保存`;
    window.history.replaceState({}, "", window.location.pathname);
  }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/assets">返回台账列表</router-link>
    </template>

    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <section v-if="selected" class="fams-panel fams-stack" aria-labelledby="detail-title">
      <div class="fams-panel-head">
        <div>
          <h2 id="detail-title" class="fams-panel-title">{{ selected.name }}</h2>
          <p class="fams-panel-subtitle">{{ selected.assetCode }} · {{ cardStageLabels[selected.cardStage] }} · 版本 {{ selected.version }}</p>
        </div>
      </div>
      <p v-if="selected.physicalDestination" class="fams-panel-subtitle">
        实物去向：{{ selected.physicalDestination.destination }} · {{ destinationLabels[selected.physicalDestination.status] }}；正式保管仍以上方资产事实为准。
      </p>
      <dl class="fams-facts">
        <div><dt>分类</dt><dd>{{ selected.category?.id ?? "—" }}</dd></div>
        <div><dt>型号</dt><dd class="u-break">{{ selected.model ?? "—" }}</dd></div>
        <div><dt>所属组织</dt><dd>{{ selected.organization?.id ?? "—" }}</dd></div>
        <div><dt>当前保管</dt><dd>{{ custodyText }}</dd></div>
      </dl>
      <div class="fams-panel-actions">
        <button v-if="canWrite" class="fams-button" data-kind="secondary" :disabled="locked" type="button" @click="openAction('copy')">复制建卡</button>
        <button v-if="canCorrectNotes"
          class="fams-button" data-kind="secondary" :disabled="locked" type="button" @click="openAction('notes')">修正备注</button>
        <button v-if="canCancelDraft" class="fams-button" data-kind="danger" :disabled="locked"
          type="button" @click="openAction('cancel')">取消草稿</button>
        <button v-if="canPrintTag" class="fams-button" data-kind="secondary" :disabled="locked"
          type="button" @click="printCurrent">打印标签</button>
      </div>
      <div v-if="pending" class="fams-card fams-stack" role="group" :aria-label="confirmLabels[pending]">
        <label class="fams-field"><span class="fams-field-label">{{ fieldLabels[pending] }}</span>
          <input v-if="pendingIsNotes" class="fams-control" v-model="notes" />
          <input v-else-if="pendingIsCopy" class="fams-control" v-model="quantity" type="number" min="1" max="100" />
          <input v-else class="fams-control" v-model="reason" />
        </label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="confirm">{{ confirmLabels[pending] }}</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="pending = null">返回修改</button>
        </div>
      </div>
      <div v-if="tag" class="fams-tag-preview">
        <span>{{ tag.enterpriseName }}</span>
        <strong>{{ tag.assetName }}</strong>
        <code>{{ tag.assetCode }}</code>
        <img v-if="qrImage" :src="qrImage" alt="资产二维码" width="160" height="160" />
        <small>扫码标识仅用于定位，权限由当前会话决定</small>
      </div>
    </section>
  </AppChrome>
</template>
