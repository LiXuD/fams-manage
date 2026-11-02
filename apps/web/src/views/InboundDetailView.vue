<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import InboundReassignment from "@/components/InboundReassignment.vue";
import AttachmentPanel from "@/components/AttachmentPanel.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { getAsset } from "@/asset/api";
import {
  acceptItem, cancelReceipt, claimReceipt, decideReceipt, getReceipt, submitReceipt,
  inboundDecisionLabels, inboundItemLabels, inboundStatusLabels,
  type InboundItem, type InboundReceipt,
} from "@/inbound/api";

type Decision = "cancel" | "reject";
const props = defineProps<{ id: string }>();
const session = useSessionStore();
const selected = ref<InboundReceipt | null>(null);
const error = shallowRef("");
const notice = shallowRef("");
const busy = shallowRef(false);
const decision = ref<Decision | null>(null);
const reason = shallowRef("");
const accepting = ref<InboundItem | null>(null);
const observed = shallowRef("");
const warehouseRole = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;
const adminRole = session.user?.roles.includes("ASSET_ADMIN") ?? false;
const locked = computed(() => busy.value || decision.value !== null || accepting.value !== null);
const decisionLabels: Record<Decision, string> = { cancel: "确认取消", reject: "确认驳回" };
const reasonLabels: Record<Decision, string> = { cancel: "取消原因", reject: "驳回原因" };

async function open(): Promise<void> {
  if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "详情加载失败"; return; }
  try { selected.value = await getReceipt(props.id); error.value = ""; }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "详情加载失败"; }
}

async function act(action: "submit" | "approve" | "claim"): Promise<void> {
  if (!selected.value || locked.value) return;
  const current = selected.value;
  await execute(async () => {
    if (action === "submit") selected.value = await submitReceipt(current);
    if (action === "approve") selected.value = await decideReceipt(current, true);
    if (action === "claim") selected.value = await claimReceipt(current);
    notice.value = "入库单状态已更新";
  });
}

function openDecision(action: Decision): void {
  if (!selected.value || locked.value) return;
  reason.value = "";
  decision.value = action;
}

async function confirmDecision(): Promise<void> {
  if (!selected.value || busy.value || !decision.value) return;
  const trimmed = reason.value.trim();
  if (!trimmed) return;
  const current = selected.value;
  const action = decision.value;
  await execute(async () => {
    if (action === "cancel") selected.value = await cancelReceipt(current, trimmed);
    else selected.value = await decideReceipt(current, false, trimmed);
    decision.value = null;
    notice.value = "入库单状态已更新";
  });
}

function openAccept(item: InboundItem): void {
  if (!selected.value || locked.value) return;
  observed.value = "";
  accepting.value = item;
}

async function confirmAccept(): Promise<void> {
  if (!selected.value || busy.value || !accepting.value) return;
  const value = observed.value.trim();
  if (!value) return;
  const item = accepting.value;
  await execute(async () => {
    const asset = await getAsset(item.asset.id);
    const result = await acceptItem(selected.value!, item, asset.version, value);
    selected.value = result.receipt;
    accepting.value = null;
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
          <h2 class="fams-panel-title">{{ selected.receiptNumber }} · {{ inboundStatusLabels[selected.status] }}</h2>
        </div>
      </div>
      <p v-if="selected.currentProcessor">当前处理人：{{ selected.currentProcessor.processor.username }}</p>
      <p v-if="selected.decision">管理员决定：{{ inboundDecisionLabels[selected.decision.result] }}</p>
      <div class="fams-panel-actions">
        <router-link v-if="warehouseRole && selected.status === 'DRAFT' && selected.createdBy.id === session.user?.id" class="fams-button" data-kind="secondary" :to="`/inbound/${selected.id}/edit`">修订草稿</router-link>
        <button v-if="warehouseRole && selected.status === 'DRAFT'" class="fams-button" data-kind="primary" type="button" :disabled="locked" @click="act('submit')">提交审批</button>
        <button v-if="warehouseRole && ['DRAFT', 'PENDING_ADMIN_DECISION'].includes(selected.status)" class="fams-button" data-kind="secondary" type="button" :disabled="locked" @click="openDecision('cancel')">取消</button>
        <button v-if="adminRole && selected.status === 'PENDING_ADMIN_DECISION'" class="fams-button" data-kind="primary" type="button" :disabled="locked" @click="act('approve')">批准</button>
        <button v-if="adminRole && selected.status === 'PENDING_ADMIN_DECISION'" class="fams-button" data-kind="secondary" type="button" :disabled="locked" @click="openDecision('reject')">驳回</button>
        <button v-if="warehouseRole && selected.status === 'WAITING_WAREHOUSE_CLAIM'" class="fams-button" data-kind="primary" type="button" :disabled="locked" @click="act('claim')">认领整单</button>
      </div>
      <div v-if="decision" class="fams-card fams-stack" role="group" :aria-label="decisionLabels[decision]">
        <label class="fams-field"><span class="fams-field-label">{{ reasonLabels[decision] }}</span>
          <input class="fams-control" v-model="reason" />
        </label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="confirmDecision">{{ decisionLabels[decision] }}</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="decision = null">返回修改</button>
        </div>
      </div>
      <div v-if="accepting" class="fams-card fams-stack" role="group" aria-label="确认验收入库">
        <label class="fams-field"><span class="fams-field-label">核对实物编码：{{ accepting.assetSnapshot.assetCode }}</span>
          <input class="fams-control" v-model="observed" />
        </label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="confirmAccept">确认验收入库</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="accepting = null">返回修改</button>
        </div>
      </div>
      <div class="fams-table-scroll"><table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">资产编码</th><th scope="col">资产名称</th><th scope="col">执行状态</th>
          <th scope="col" data-column-kind="action">操作</th></tr></thead>
        <tbody><tr v-for="item in selected.items" :key="item.id">
          <th scope="row" data-column-kind="identity">{{ item.assetSnapshot.assetCode }}</th><td>{{ item.assetSnapshot.assetName }}</td>
          <td><span class="fams-status">{{ inboundItemLabels[item.executionStatus] }}</span></td>
          <td data-column-kind="action"><button v-if="warehouseRole && selected.status === 'WAREHOUSE_PROCESSING' && item.executionStatus === 'PENDING' && selected.currentProcessor?.processor.id === session.user?.id" class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="locked" @click="openAccept(item)">验收入库</button></td>
        </tr></tbody>
      </table></div>
      <InboundReassignment v-if="adminRole && selected.status === 'WAREHOUSE_PROCESSING'"
        :id="selected.id" :version="selected.version" @saved="open()" />
      <AttachmentPanel :key="selected.id" :receipt="selected" :disabled="busy"
        @busy="busy = $event" @uploaded="selected.version = $event" />
    </section>
  </AppChrome>
</template>
