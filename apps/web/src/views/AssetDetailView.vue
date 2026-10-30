<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import QRCode from "qrcode";
import { useSessionStore } from "@/auth/sessionStore";
import {
  cancelAsset, copyAsset, custodyLabel, getAsset, getTag, reprintTag, updateAsset,
  type AssetCard, type AssetTag,
} from "@/asset/api";

const props = defineProps<{ id: string }>();
const route = useRoute();
const session = useSessionStore();
const selected = ref<AssetCard | null>(null);
const tag = ref<AssetTag | null>(null);
const qrImage = ref("");
const error = ref("");
const notice = ref("");
const busy = ref(false);

const canWrite = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;

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

async function correctNotes(): Promise<void> {
  if (!selected.value) return;
  const notes = window.prompt("备注修正", "");
  if (notes === null) return;
  busy.value = true;
  try {
    selected.value = await updateAsset(selected.value.id, selected.value.version, { notes });
    notice.value = "备注已更新";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "更新失败"; }
  finally { busy.value = false; }
}

async function copyCurrent(): Promise<void> {
  if (!selected.value) return;
  const quantity = Number(window.prompt("复制建卡数量（1–100）", "1"));
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 100) return;
  busy.value = true;
  try {
    const result = await copyAsset(selected.value.id, selected.value.version, quantity);
    notice.value = `已创建 ${result.assets.length} 张独立草稿`;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "复制失败"; }
  finally { busy.value = false; }
}

async function cancelCurrent(): Promise<void> {
  if (!selected.value) return;
  const reason = window.prompt("取消原因");
  if (!reason?.trim()) return;
  busy.value = true;
  try {
    selected.value = await cancelAsset(selected.value.id, selected.value.version, reason.trim());
    notice.value = "草稿已取消，编码永久保留";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "取消失败"; }
  finally { busy.value = false; }
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
          <p class="fams-panel-subtitle">{{ selected.assetCode }} · {{ selected.cardStage }} · 版本 {{ selected.version }}</p>
        </div>
      </div>
      <p v-if="selected.physicalDestination" class="fams-panel-subtitle">
        实物去向：{{ selected.physicalDestination.destination }} · {{ selected.physicalDestination.status }}；正式保管仍以上方资产事实为准。
      </p>
      <dl class="fams-facts">
        <div><dt>分类</dt><dd>{{ selected.category?.id ?? "—" }}</dd></div>
        <div><dt>型号</dt><dd class="u-break">{{ selected.model ?? "—" }}</dd></div>
        <div><dt>所属组织</dt><dd>{{ selected.organization?.id ?? "—" }}</dd></div>
        <div><dt>当前保管</dt><dd>{{ custodyLabel(selected) }}</dd></div>
      </dl>
      <div class="fams-panel-actions">
        <button v-if="canWrite" class="fams-button" data-kind="secondary" :disabled="busy" type="button" @click="copyCurrent">复制建卡</button>
        <button v-if="canWrite && selected.cardStage !== 'PENDING_INBOUND' && selected.cardStage !== 'CANCELLED'"
          class="fams-button" data-kind="secondary" :disabled="busy" type="button" @click="correctNotes">修正备注</button>
        <button v-if="canWrite && selected.cardStage === 'DRAFT'" class="fams-button" data-kind="danger" :disabled="busy"
          type="button" @click="cancelCurrent">取消草稿</button>
        <button v-if="canWrite && selected.cardStage === 'ACTIVE'" class="fams-button" data-kind="secondary" :disabled="busy"
          type="button" @click="printCurrent">打印标签</button>
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
