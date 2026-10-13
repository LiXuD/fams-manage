<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loanMessage, loanRequest, type LoanMutation } from "@/loan/api";
import { downloadAttachment, type Attachment } from "@/attachment/api";
import { uploadProxyEvidence, type ProxyConfirmation, type ProxyPage } from "@/proxy/api";

const route = useRoute(); const router = useRouter(); const session = useSessionStore();
const rows = ref<ProxyConfirmation[]>([]); const selected = ref<ProxyConfirmation | null>(null);
const attachments = ref<Attachment[]>([]); const snapshot = ref<Record<string, unknown> | null>(null);
const page = ref(1); const total = ref(0); const busy = ref(false); const error = ref("");
const pending = ref<LoanMutation | null>(null);
const pendingUpload = ref<{ id: string; version: number; file: File; key: string } | null>(null);
const file = ref<File | null>(null);
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const locked = computed(() => busy.value || !!pending.value || !!pendingUpload.value);
const form = ref({ observedAssetCode: "", actualSigner: "", actualSignerEmployeeId: "",
  actualReceivedAt: "", cannotConfirmReason: "", contactRecord: "", delegatedReceipt: false,
  delegationRelationship: "", delegationBasis: "", evidenceKind: "SIGNED_RECEIPT",
  collectionMethod: "CODE_CONFIRMED", manualReason: "", targetOrAuthorizedProxyReceived: false });
const decision = ref({ reason: "", physicalEvidenceChecked: false, noDisputeChecked: false });
const labels: Record<string, string> = { DRAFT: "凭证草稿", SUBMITTED: "待管理员批准",
  APPROVED: "已批准，待库管执行", REJECTED: "已驳回", EXECUTED: "已执行", WITHDRAWN: "已撤回" };

async function list() {
  const result = await loanRequest<ProxyPage>(`/proxy-confirmations?page=${page.value}&pageSize=20`);
  rows.value = result.items; total.value = result.total;
}
async function detail(id: string) {
  selected.value = await loanRequest<ProxyConfirmation>(`/proxy-confirmations/${id}`);
  attachments.value = (await loanRequest<{ items: Attachment[] }>(`/proxy-confirmations/${id}/attachments`)).items;
  await router.replace({ query: { id: String(id) } });
}
async function open(id: string) {
  if (locked.value) return;
  busy.value = true; error.value = "";
  try { await detail(id); snapshot.value = null; decision.value = { reason: "", physicalEvidenceChecked: false, noDisputeChecked: false }; }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function refresh() {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try {
    await list(); if (selected.value) await detail(selected.value.id);
    // Refresh queries cannot prove whether an uncertain upload or command committed.
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function send(path: string, body?: unknown, version = selected.value?.version ?? 0) {
  if (busy.value || pendingUpload.value) return;
  if (pending.value && pending.value.path !== path) return;
  pending.value ??= { path, key: crypto.randomUUID(), version,
    body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const result = await loanRequest<ProxyConfirmation>(pending.value.path, pending.value);
    pending.value = null; snapshot.value = null;
    await detail(result.id); await list();
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
function create() {
  if (!snapshot.value || !form.value.actualReceivedAt) return;
  void send("/proxy-confirmations", { ...form.value, snapshot: snapshot.value,
    businessId: snapshot.value.businessId, itemId: snapshot.value.itemId,
    actualSignerEmployeeId: form.value.delegatedReceipt ? null : form.value.actualSignerEmployeeId,
    actualReceivedAt: new Date(form.value.actualReceivedAt).toISOString() }, 0);
}
async function upload() {
  if (busy.value || pending.value || !selected.value) return;
  if (!pendingUpload.value && !file.value) return;
  pendingUpload.value ??= { id: selected.value.id, version: selected.value.version,
    file: file.value!, key: crypto.randomUUID() };
  busy.value = true; error.value = "";
  try {
    const request = pendingUpload.value;
    await uploadProxyEvidence(request.id, request.version, request.file, request.key);
    pendingUpload.value = null; file.value = null; await detail(request.id); await list();
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function download(item: Attachment) {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try { await downloadAttachment(item); }
  catch (e) { error.value = e instanceof Error ? e.message : "下载失败"; }
  finally { busy.value = false; }
}
async function paginate(delta: number) {
  if (locked.value) return; page.value += delta; await refresh();
}
onMounted(async () => {
  busy.value = true;
  try {
    await list();
    if (route.query.id && /^\d+$/.test(String(route.query.id))) await detail(String(route.query.id));
    else if (warehouse.value && route.query.businessId && route.query.itemId) {
      snapshot.value = await loanRequest<Record<string, unknown>>(`/proxy-confirmations/context?businessId=${encodeURIComponent(String(route.query.businessId))}&itemId=${encodeURIComponent(String(route.query.itemId))}`);
      form.value.actualSignerEmployeeId = String(snapshot.value.targetEmployeeId);
      form.value.observedAssetCode = String(snapshot.value.assetCode);
    }
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <h1>领用与借用异常代确认</h1>
    <p>仅用于已经实际交付、原接收人无法操作确认的资产。管理员批准后，当前库管还需明确执行。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <button :disabled="busy" @click="refresh">刷新当前记录</button>
    <button v-if="pending" :disabled="busy" @click="send(pending.path)">重试上次操作</button>
    <button v-if="pendingUpload" :disabled="busy" @click="upload">重试上次凭证上传</button>
    <p v-if="pending || pendingUpload">上次操作结果尚未确认，请保持本页并使用原请求重试。</p>
    <form v-if="snapshot" @submit.prevent="create">
      <h2>申请代确认</h2>
      <p>实物 {{ snapshot.assetCode }} · 责任人员 {{ snapshot.targetEmployeeId }} · 单据 {{ snapshot.businessId }}</p>
      <fieldset :disabled="locked">
        <label>核对实物编码<input v-model="form.observedAssetCode" required maxlength="128"></label>
        <label>实际签收人<input v-model="form.actualSigner" required maxlength="200"></label>
        <label>实际签收时间<input v-model="form.actualReceivedAt" type="datetime-local" required></label>
        <label>不能在线确认的原因<textarea v-model="form.cannotConfirmReason" required maxlength="1000" /></label>
        <label>联系记录<textarea v-model="form.contactRecord" required maxlength="1000" /></label>
        <label><input v-model="form.delegatedReceipt" type="checkbox">授权代理人签收</label>
        <template v-if="form.delegatedReceipt">
          <label>代理关系<textarea v-model="form.delegationRelationship" required maxlength="1000" /></label>
          <label>授权依据<textarea v-model="form.delegationBasis" required maxlength="1000" /></label>
        </template>
        <label>凭证类别<select v-model="form.evidenceKind"><option value="SIGNED_RECEIPT">签收单</option><option value="LOGISTICS_RECEIPT">物流签收</option><option value="AUTHORIZED_PROXY">授权代收</option></select></label>
        <label>编码采集方式<select v-model="form.collectionMethod"><option value="CODE_CONFIRMED">核对编码</option><option value="SCAN_CONFIRMED">扫描核对</option><option value="MANUAL_RECORD">手工记录</option></select></label>
        <label v-if="form.collectionMethod === 'MANUAL_RECORD'">手工记录原因<textarea v-model="form.manualReason" required maxlength="1000" /></label>
        <label><input v-model="form.targetOrAuthorizedProxyReceived" type="checkbox" required>已确认本人或其授权代理人实际收到，且无实物或责任争议</label>
        <button type="submit">保存凭证草稿</button>
      </fieldset>
    </form>
    <h2>代确认记录</h2>
    <ul><li v-for="row in rows" :key="row.id"><button :disabled="locked" @click="open(row.id)">代确认 {{ row.id }} · {{ labels[row.confirmationStatus] }}</button></li></ul>
    <button :disabled="locked || page <= 1" @click="paginate(-1)">上一页</button>
    <span>{{ page }} 页 / 共 {{ total }} 条</span>
    <button :disabled="locked || page * 20 >= total" @click="paginate(1)">下一页</button>
    <section v-if="selected">
      <h2>代确认 {{ selected.id }} · {{ labels[selected.confirmationStatus] }}</h2>
      <p>单据 {{ selected.businessId }} / 分配项 {{ selected.itemId }} · 实物 {{ selected.businessSnapshotJson.assetCode }} · 责任人员 {{ selected.businessSnapshotJson.targetEmployeeId }}</p>
      <p>实际签收人：{{ selected.actualSigner }} · 时间：{{ selected.actualReceivedAt }}</p>
      <p>无法确认原因：{{ selected.cannotConfirmReason }}；联系记录：{{ selected.contactRecord }}</p>
      <p v-if="selected.delegatedReceipt">代理关系：{{ selected.delegationRelationship }}；授权依据：{{ selected.delegationBasis }}</p>
      <p>凭证 {{ selected.evidenceKind }} · 采集 {{ selected.collectionMethod }} {{ selected.manualReason }}</p>
      <p>提交账号 {{ selected.creatorAccountId }} · 批准账号 {{ selected.approverAccountId ?? '待批准' }} · 执行账号 {{ selected.executorAccountId ?? '待执行' }}</p>
      <p v-if="selected.decisionReason">决定说明：{{ selected.decisionReason }}</p>
      <router-link :to="{ path: '/requisitions', query: { id: selected.businessId } }">查看业务单据与核查入口</router-link>
      <ul><li v-for="attachment in attachments" :key="attachment.objectId">{{ attachment.filename }} · {{ attachment.scanStatus }}
        <button :disabled="busy || attachment.scanStatus !== 'CLEAN'" @click="download(attachment)">下载凭证</button></li></ul>
      <fieldset v-if="warehouse && selected.confirmationStatus === 'DRAFT'" :disabled="locked">
        <label>签收凭证<input type="file" accept="application/pdf,image/jpeg,image/png" @change="file = ($event.target as HTMLInputElement).files?.[0] ?? null"></label>
        <button :disabled="!file" @click="upload">上传凭证</button>
        <button :disabled="!attachments.some(a => a.scanStatus === 'CLEAN')" @click="send(`/proxy-confirmations/${selected.id}/submit`)">冻结凭证并提交批准</button>
      </fieldset>
      <fieldset v-if="admin && selected.confirmationStatus === 'SUBMITTED'" :disabled="locked">
        <label>审批说明<textarea v-model="decision.reason" required maxlength="1000" /></label>
        <label><input v-model="decision.physicalEvidenceChecked" type="checkbox">已下载核验实际签收凭证及授权依据</label>
        <label><input v-model="decision.noDisputeChecked" type="checkbox">已确认资产、人员与签收事实无争议</label>
        <button :disabled="!decision.reason.trim() || !decision.physicalEvidenceChecked || !decision.noDisputeChecked" @click="send(`/proxy-confirmations/${selected.id}/decision`, { ...decision, approved: true })">批准本次代确认</button>
        <button :disabled="!decision.reason.trim()" @click="send(`/proxy-confirmations/${selected.id}/decision`, { ...decision, approved: false })">驳回</button>
      </fieldset>
      <button v-if="warehouse && selected.confirmationStatus === 'APPROVED'" :disabled="locked" @click="send(`/proxy-confirmations/${selected.id}/execute`)">执行已批准代确认并通知责任人员</button>
      <p v-if="selected.confirmationStatus === 'EXECUTED'">代确认已执行。责任人员收到结果通知；未读通知不会撤销资产责任。</p>
    </section>
  </AppChrome>
</template>
