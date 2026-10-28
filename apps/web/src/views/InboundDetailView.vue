<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import InboundReassignment from "@/components/InboundReassignment.vue";
import AttachmentPanel from "@/components/AttachmentPanel.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { getAsset } from "@/asset/api";
import {
  acceptItem, cancelReceipt, claimReceipt, decideReceipt, getReceipt, submitReceipt,
  type InboundItem, type InboundReceipt,
} from "@/inbound/api";

const props = defineProps<{ id: string }>();
const session = useSessionStore();
const selected = ref<InboundReceipt | null>(null);
const error = ref("");
const notice = ref("");
const busy = ref(false);
const warehouseRole = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;
const adminRole = session.user?.roles.includes("ASSET_ADMIN") ?? false;

async function open(): Promise<void> {
  if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "详情加载失败"; return; }
  try { selected.value = await getReceipt(props.id); error.value = ""; }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "详情加载失败"; }
}

async function act(action: "submit" | "cancel" | "approve" | "reject" | "claim"): Promise<void> {
  if (!selected.value) return;
  const current = selected.value;
  let reason: string | undefined;
  if (action === "cancel" || action === "reject") {
    reason = window.prompt(action === "cancel" ? "取消原因" : "驳回原因")?.trim();
    if (!reason) return;
  }
  await execute(async () => {
    if (action === "submit") selected.value = await submitReceipt(current);
    if (action === "cancel") selected.value = await cancelReceipt(current, reason!);
    if (action === "approve") selected.value = await decideReceipt(current, true);
    if (action === "reject") selected.value = await decideReceipt(current, false, reason);
    if (action === "claim") selected.value = await claimReceipt(current);
    notice.value = "入库单状态已更新";
  });
}

async function accept(item: InboundItem): Promise<void> {
  if (!selected.value) return;
  const observed = window.prompt(`核对实物编码：${item.assetSnapshot.assetCode}`)?.trim();
  if (!observed) return;
  await execute(async () => {
    const asset = await getAsset(item.asset.id);
    const result = await acceptItem(selected.value!, item, asset.version, observed);
    selected.value = result.receipt;
    notice.value = `${item.assetSnapshot.assetCode} 已验收入库`;
  });
}

async function execute(operation: () => Promise<void>): Promise<void> {
  busy.value = true; error.value = ""; notice.value = "";
  try { await operation(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "操作失败"; }
  finally { busy.value = false; }
}

onMounted(async () => {
  await open();
  if (selected.value && new URLSearchParams(window.location.search).get("saved")) {
    notice.value = `入库草稿 ${selected.value.receiptNumber} 已保存`;
    window.history.replaceState(null, "", window.location.pathname);
  }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/inbound">返回入库单列表</router-link>
    </template>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
    <section v-if="selected" class="fams-panel fams-stack" aria-label="入库单详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">{{ selected.receiptNumber }} · {{ selected.status }}</h2>
        </div>
      </div>
      <p v-if="selected.currentProcessor">当前处理人：{{ selected.currentProcessor.processor.username }}</p>
      <p v-if="selected.decision">管理员决定：{{ selected.decision.result }}</p>
      <div class="fams-panel-actions">
        <router-link v-if="warehouseRole && selected.status === 'DRAFT' && selected.createdBy.id === session.user?.id" class="fams-button" data-kind="secondary" :to="`/inbound/${selected.id}/edit`">修订草稿</router-link>
        <button v-if="warehouseRole && selected.status === 'DRAFT'" class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="act('submit')">提交审批</button>
        <button v-if="warehouseRole && ['DRAFT', 'PENDING_ADMIN_DECISION'].includes(selected.status)" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="act('cancel')">取消</button>
        <button v-if="adminRole && selected.status === 'PENDING_ADMIN_DECISION'" class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="act('approve')">批准</button>
        <button v-if="adminRole && selected.status === 'PENDING_ADMIN_DECISION'" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="act('reject')">驳回</button>
        <button v-if="warehouseRole && selected.status === 'WAITING_WAREHOUSE_CLAIM'" class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="act('claim')">认领整单</button>
      </div>
      <div class="fams-table-scroll"><table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">资产编码</th><th scope="col">资产名称</th><th scope="col">执行状态</th>
          <th scope="col" data-column-kind="action">操作</th></tr></thead>
        <tbody><tr v-for="item in selected.items" :key="item.id">
          <th scope="row" data-column-kind="identity">{{ item.assetSnapshot.assetCode }}</th><td>{{ item.assetSnapshot.assetName }}</td>
          <td><span class="fams-status">{{ item.executionStatus }}</span></td>
          <td data-column-kind="action"><button v-if="warehouseRole && selected.status === 'WAREHOUSE_PROCESSING' && item.executionStatus === 'PENDING' && selected.currentProcessor?.processor.id === session.user?.id" class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="busy" @click="accept(item)">验收入库</button></td>
        </tr></tbody>
      </table></div>
      <InboundReassignment v-if="adminRole && selected.status === 'WAREHOUSE_PROCESSING'"
        :id="selected.id" :version="selected.version" @saved="open()" />
      <AttachmentPanel :key="selected.id" :receipt="selected" :disabled="busy"
        @busy="busy = $event" @uploaded="selected.version = $event" />
    </section>
  </AppChrome>
</template>
