<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { AuthApiError } from "@/auth/api";
import { LoanApiError, loanMessage, loanRequest, type LoanMutation } from "@/loan/api";
import { downloadAttachment, type Attachment } from "@/attachment/api";
import { formatProxyTime } from "@/proxy/time";
import { uploadProxyEvidence, type ProxyConfirmation, type ProxyPage } from "@/proxy/api";
const route = useRoute(), router = useRouter(), session = useSessionStore();
const rows = ref<ProxyConfirmation[]>([]), selected = ref<ProxyConfirmation | null>(null);
const attachments = ref<Attachment[]>([]), snapshot = ref<Record<string, unknown> | null>(null);
const page = ref(1), total = ref(0), busy = ref(false), error = ref("");
const pending = ref<LoanMutation | null>(null), pendingUpload = ref<{ id: string; version: number; file: File; key: string } | null>(null);
const file = ref<File | null>(null);
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN")), warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const locked = computed(() => busy.value || !!pending.value || !!pendingUpload.value);
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const emptyForm = () => ({ observedAssetCode: "", actualSigner: "", actualSignerEmployeeId: "", actualReceivedAt: "", cannotConfirmReason: "", contactRecord: "", delegatedReceipt: false,
  delegationRelationship: "", delegationBasis: "", evidenceKind: "SIGNED_RECEIPT", collectionMethod: "CODE_CONFIRMED", manualReason: "", targetOrAuthorizedProxyReceived: false });
const form = ref(emptyForm()), decision = ref({ reason: "", physicalEvidenceChecked: false, noDisputeChecked: false });
const labels: Record<string, string> = { DRAFT: "凭证草稿", SUBMITTED: "待管理员批准", APPROVED: "已批准，待库管执行", REJECTED: "已驳回", EXECUTED: "已执行", WITHDRAWN: "已撤回" };
const businessLabels: Record<string, string> = { REQUISITION: "领用/借用", TRANSFER: "调拨", REPAIR: "维修返还" };
const businessLink = (type: string, id: string) => type === "TRANSFER" ? { path: "/transfers", query: { id } }
  : { path: (type === "REPAIR" ? "/repairs/" : "/requisitions/") + id };
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
function clear() { generation++; rows.value = []; selected.value = null; attachments.value = []; snapshot.value = null; pending.value = null; pendingUpload.value = null;
  file.value = null; form.value = emptyForm(); decision.value = { reason: "", physicalEvidenceChecked: false, noDisputeChecked: false }; page.value = 1; total.value = 0; busy.value = false; }
function failure(cause: unknown, mutation = false) {
  if (cause instanceof LoanApiError || cause instanceof AuthApiError) {
    if ([401, 403, 404].includes(cause.status)) clear();
    else if (mutation && cause.status >= 400 && cause.status < 500) { pending.value = null; pendingUpload.value = null; }
  }
  error.value = loanMessage(cause);
}
async function list(epoch: number, owner: string) {
  const result = await loanRequest<ProxyPage>(`/proxy-confirmations?page=${page.value}&pageSize=20`);
  if (current(epoch, owner)) { rows.value = result.items; total.value = result.total; }
}
async function detail(id: string, epoch: number, owner: string) {
  const record = await loanRequest<ProxyConfirmation>(`/proxy-confirmations/${id}`);
  if (!current(epoch, owner)) return;
  const files = await loanRequest<{ items: Attachment[] }>(`/proxy-confirmations/${id}/attachments`);
  if (!current(epoch, owner)) return;
  selected.value = record; attachments.value = files.items; await router.replace({ query: { id } });
}
async function open(id: string) {
  if (locked.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await detail(id, epoch, owner); if (current(epoch, owner)) { snapshot.value = null; decision.value = { reason: "", physicalEvidenceChecked: false, noDisputeChecked: false }; } }
  catch (cause) { if (current(epoch, owner)) failure(cause); } finally { if (current(epoch, owner)) busy.value = false; }
}
async function refresh() {
  if (busy.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await list(epoch, owner); if (current(epoch, owner) && selected.value) await detail(selected.value.id, epoch, owner); }
  catch (cause) { if (current(epoch, owner)) failure(cause); } finally { if (current(epoch, owner)) busy.value = false; }
}
async function send(path: string, body?: unknown, version = selected.value?.version ?? 0) {
  if (busy.value || pendingUpload.value || pending.value && pending.value.path !== path) return;
  pending.value ??= { path, key: crypto.randomUUID(), version, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { const result = await loanRequest<ProxyConfirmation>(operation.path, operation);
    if (!current(epoch, owner)) return; pending.value = null; snapshot.value = null;
    await detail(result.id, epoch, owner); if (current(epoch, owner)) await list(epoch, owner);
  } catch (cause) { if (current(epoch, owner)) failure(cause, true); } finally { if (current(epoch, owner)) busy.value = false; }
}
function create() {
  if (!warehouse.value || !snapshot.value || !form.value.actualReceivedAt) return;
  const at = new Date(form.value.actualReceivedAt); if (!Number.isFinite(at.getTime())) { error.value = "请填写有效的实际签收时间。"; return; }
  void send("/proxy-confirmations", { ...form.value, snapshot: snapshot.value, businessId: snapshot.value.businessId, itemId: snapshot.value.itemId,
    actualSignerEmployeeId: form.value.delegatedReceipt ? null : form.value.actualSignerEmployeeId, actualReceivedAt: at.toISOString() }, 0);
}
async function upload() {
  if (busy.value || pending.value || !selected.value || !pendingUpload.value && !file.value) return;
  pendingUpload.value ??= { id: selected.value.id, version: selected.value.version, file: file.value!, key: crypto.randomUUID() };
  const operation = pendingUpload.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await uploadProxyEvidence(operation.id, operation.version, operation.file, operation.key);
    if (!current(epoch, owner)) return; pendingUpload.value = null; file.value = null;
    await detail(operation.id, epoch, owner); if (current(epoch, owner)) await list(epoch, owner);
  } catch (cause) { if (current(epoch, owner)) failure(cause, true); } finally { if (current(epoch, owner)) busy.value = false; }
}
async function download(item: Attachment) {
  if (busy.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await downloadAttachment(item, () => current(epoch, owner)); }
  catch (cause) { if (current(epoch, owner)) failure(cause); } finally { if (current(epoch, owner)) busy.value = false; }
}
async function paginate(delta: number) {
  if (locked.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= total.value) return;
  page.value += delta; await refresh();
}
async function initialize() {
  clear(); error.value = ""; const epoch = generation, owner = identity.value; busy.value = true;
  try { await list(epoch, owner); if (!current(epoch, owner)) return;
    if (route.query.id && /^[1-9][0-9]*$/.test(String(route.query.id))) await detail(String(route.query.id), epoch, owner);
    else if (warehouse.value && route.query.businessId && route.query.itemId) {
      const result = await loanRequest<Record<string, unknown>>(`/proxy-confirmations/context?businessType=${encodeURIComponent(String(route.query.businessType ?? "REQUISITION"))}&businessId=${encodeURIComponent(String(route.query.businessId))}&itemId=${encodeURIComponent(String(route.query.itemId))}`);
      if (current(epoch, owner)) { snapshot.value = result; form.value.actualSignerEmployeeId = String(result.targetEmployeeId); form.value.observedAssetCode = String(result.assetCode); }
    }
  } catch (cause) { if (current(epoch, owner)) failure(cause); } finally { if (current(epoch, owner)) busy.value = false; }
}
watch(identity, () => { if (session.user) void initialize(); else { clear(); error.value = ""; } }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; clear(); });
</script>

<template>
  <AppChrome>
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="refresh">刷新当前记录</button>
    </template>
    <section class="fams-panel fams-stack" aria-label="异常代确认">
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="send(pending.path)">重试上次操作</button>
      <button v-if="pendingUpload" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="upload">重试上次凭证上传</button>
      <p v-if="pending || pendingUpload">上次操作结果尚未确认，请保持本页并使用原请求重试。</p>
      <form v-if="snapshot" class="fams-card fams-stack" @submit.prevent="create">
        <div class="fams-panel-head"><h2 class="fams-panel-title">申请代确认</h2></div>
        <p>实物 {{ snapshot.assetCode }} · 责任人员 {{ snapshot.targetEmployeeId }} · 单据 {{ snapshot.businessId }}</p>
        <fieldset class="fams-stack" :disabled="locked">
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">核对实物编码</span><input class="fams-control" v-model="form.observedAssetCode" required maxlength="128"></label>
            <label class="fams-field"><span class="fams-field-label">实际签收人</span><input class="fams-control" v-model="form.actualSigner" required maxlength="200"></label>
            <label class="fams-field"><span class="fams-field-label">实际签收时间</span><input class="fams-control" v-model="form.actualReceivedAt" type="datetime-local" step="0.001" required></label>
          </div>
          <label class="fams-field fams-field-wide"><span class="fams-field-label">不能在线确认的原因</span><textarea class="fams-control" v-model="form.cannotConfirmReason" required maxlength="1000" /></label>
          <label class="fams-field fams-field-wide"><span class="fams-field-label">联系记录</span><textarea class="fams-control" v-model="form.contactRecord" required maxlength="1000" /></label>
          <label class="fams-selection"><input v-model="form.delegatedReceipt" type="checkbox">授权代理人签收</label>
          <template v-if="form.delegatedReceipt">
            <label class="fams-field fams-field-wide"><span class="fams-field-label">代理关系</span><textarea class="fams-control" v-model="form.delegationRelationship" required maxlength="1000" /></label>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">授权依据</span><textarea class="fams-control" v-model="form.delegationBasis" required maxlength="1000" /></label>
          </template>
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">凭证类别</span><select class="fams-control" v-model="form.evidenceKind"><option value="SIGNED_RECEIPT">签收单</option><option value="LOGISTICS_RECEIPT">物流签收</option><option value="AUTHORIZED_PROXY">授权代收</option></select></label>
            <label class="fams-field"><span class="fams-field-label">编码采集方式</span><select class="fams-control" v-model="form.collectionMethod"><option value="CODE_CONFIRMED">核对编码</option><option value="SCAN_CONFIRMED">扫描核对</option><option value="MANUAL_RECORD">手工记录</option></select></label>
          </div>
          <label v-if="form.collectionMethod === 'MANUAL_RECORD'" class="fams-field fams-field-wide"><span class="fams-field-label">手工记录原因</span><textarea class="fams-control" v-model="form.manualReason" required maxlength="1000" /></label>
          <label class="fams-selection"><input v-model="form.targetOrAuthorizedProxyReceived" type="checkbox" required>已确认本人或其授权代理人实际收到，且无实物或责任争议</label>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit">保存凭证草稿</button></div>
        </fieldset>
      </form>
      <div class="fams-panel-head"><h2 class="fams-panel-title">代确认记录</h2></div>
      <ul class="fams-timeline">
        <li v-for="row in rows" :key="row.id"><button class="fams-button" data-size="sm" type="button" :disabled="locked" @click="open(row.id)">代确认 {{ row.id }} · {{ labels[row.confirmationStatus] }}</button></li>
      </ul>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">{{ page }} 页 / 共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page <= 1" @click="paginate(-1)">上一页</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page * 20 >= total" @click="paginate(1)">下一页</button>
        </div>
      </div>
      <section v-if="selected" class="fams-card fams-stack">
        <div class="fams-panel-head">
          <div>
            <h2 class="fams-panel-title">代确认 {{ selected.id }} · {{ labels[selected.confirmationStatus] }}</h2>
            <p class="fams-panel-subtitle">{{ businessLabels[selected.businessType] || '业务' }}单据 {{ selected.businessId }} / 明细 {{ selected.itemId }} · 实物 {{ selected.businessSnapshotJson.assetCode }} · 责任人员 {{ selected.businessSnapshotJson.targetEmployeeId }}</p>
          </div>
        </div>
        <p>实际签收人：{{ selected.actualSigner }} · 时间：{{ formatProxyTime(selected.actualReceivedAt) }}</p>
        <p>无法确认原因：{{ selected.cannotConfirmReason }}；联系记录：{{ selected.contactRecord }}</p>
        <p v-if="selected.delegatedReceipt">代理关系：{{ selected.delegationRelationship }}；授权依据：{{ selected.delegationBasis }}</p>
        <p>凭证 {{ selected.evidenceKind }} · 采集 {{ selected.collectionMethod }} {{ selected.manualReason }}</p>
        <p>提交账号 {{ selected.creatorAccountId }} · 批准账号 {{ selected.approverAccountId ?? '待批准' }} · 执行账号 {{ selected.executorAccountId ?? '待执行' }}</p>
        <p v-if="selected.decisionReason">决定说明：{{ selected.decisionReason }}</p>
        <router-link :to="businessLink(selected.businessType, selected.businessId)">查看业务单据与核查入口</router-link>
        <ul class="fams-timeline">
          <li v-for="attachment in attachments" :key="attachment.objectId">{{ attachment.filename }}<small>{{ attachment.scanStatus }}</small><button class="fams-button" data-size="sm" type="button" :disabled="busy || attachment.scanStatus !== 'CLEAN'" @click="download(attachment)">下载凭证</button></li>
        </ul>
        <fieldset v-if="warehouse && selected.confirmationStatus === 'DRAFT'" class="fams-stack" :disabled="locked">
          <label class="fams-file"><span class="fams-field-label">签收凭证</span><input type="file" accept="application/pdf,image/jpeg,image/png,image/webp" @change="file = ($event.target as HTMLInputElement).files?.[0] ?? null"></label>
          <div class="fams-inline">
            <button class="fams-button" data-kind="secondary" type="button" :disabled="!file" @click="upload">上传凭证</button>
            <button class="fams-button" data-kind="primary" type="button" :disabled="!attachments.some(a => a.scanStatus === 'CLEAN')" @click="send(`/proxy-confirmations/${selected.id}/submit`)">冻结凭证并提交批准</button>
          </div>
        </fieldset>
        <fieldset v-if="admin && selected.confirmationStatus === 'SUBMITTED'" class="fams-stack" :disabled="locked">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">审批说明</span><textarea class="fams-control" v-model="decision.reason" required maxlength="1000" /></label>
          <div class="fams-selection-group">
            <label class="fams-selection"><input v-model="decision.physicalEvidenceChecked" type="checkbox">已下载核验实际签收凭证及授权依据</label>
            <label class="fams-selection"><input v-model="decision.noDisputeChecked" type="checkbox">已确认资产、人员与签收事实无争议</label>
          </div>
          <div class="fams-inline">
            <button class="fams-button" data-kind="primary" type="button" :disabled="!decision.reason.trim() || !decision.physicalEvidenceChecked || !decision.noDisputeChecked" @click="send(`/proxy-confirmations/${selected.id}/decision`, { ...decision, approved: true })">批准本次代确认</button>
            <button class="fams-button" data-kind="secondary" type="button" :disabled="!decision.reason.trim()" @click="send(`/proxy-confirmations/${selected.id}/decision`, { ...decision, approved: false })">驳回</button>
          </div>
        </fieldset>
        <button v-if="warehouse && selected.confirmationStatus === 'APPROVED'" class="fams-button" data-kind="primary" type="button" :disabled="locked" @click="send(`/proxy-confirmations/${selected.id}/execute`)">执行已批准代确认并通知责任人员</button>
        <p v-if="selected.confirmationStatus === 'EXECUTED'">代确认已执行。责任人员收到结果通知；未读通知不会撤销资产责任。</p>
      </section>
    </section>
  </AppChrome>
</template>
