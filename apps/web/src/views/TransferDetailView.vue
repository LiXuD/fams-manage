<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import TransferReassignment from "@/components/TransferReassignment.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loanMessage, loanRequest, transferLabels as labels } from "@/loan/api";
import type { LoanMutation } from "@/loan/api";
import type { Transfer, TransferChoice, TransferItem } from "@fams/api-types/transfer";

const props = defineProps<{ id: string }>();
const session = useSessionStore(), route = useRoute();
const selected = ref<Transfer | null>(null);
const organizations = ref<TransferChoice[]>([]), locations = ref<TransferChoice[]>([]);
const peopleNames = ref<Record<string, string>>({});
const reason = shallowRef(""), code = shallowRef(""), destination = shallowRef(""), evidence = shallowRef(""), signer = shallowRef(""), receivedAt = shallowRef("");
const busy = shallowRef(false), error = shallowRef(""), notice = shallowRef(""), retryable = shallowRef(false), checked = shallowRef(false);
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const operator = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
let pending: LoanMutation | null = null;

async function catalog(kind: string, org?: string) {
  const result: TransferChoice[] = []; let p = 1, part: TransferChoice[];
  do { part = (await loanRequest<{ items: TransferChoice[] }>(`/transfers/catalog/${kind}?page=${p++}&pageSize=200${org ? `&organizationId=${org}` : ""}`)).items; result.push(...part); }
  while (part.length === 200);
  return result;
}
async function open() {
  selected.value = await loanRequest<Transfer>("/transfers/" + props.id);
  await loadPeople(selected.value);
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await open(); pending = null; retryable.value = false; }
  catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "请先重试上次操作或刷新核对。"; return; }
  pending ??= { path, version, key: crypto.randomUUID(), body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const result = await loanRequest<{ id: string }>(pending.path, pending);
    pending = null; retryable.value = false; notice.value = "操作已保存。";
    selected.value = await loanRequest<Transfer>("/transfers/" + result.id); await loadPeople(selected.value);
  } catch (e) { error.value = loanMessage(e); retryable.value = pending !== null; }
  finally { busy.value = false; }
}
function retry() { const operation = pending; if (operation) void mutate(operation.path, operation.version); }
function place(id: unknown) { return locations.value.find(row => row.id === String(id))?.name ?? "原登记位置"; }
function organization(id: unknown) { return organizations.value.find(row => row.id === String(id))?.name ?? "未指定组织"; }
function custodyName(value: unknown) { return value === "PERSONAL" ? "个人" : value === "SHARED" ? "部门公用" : "仓库保管"; }
function personName(id: unknown) { return id ? peopleNames.value[String(id)] ?? "原登记责任人员" : "仓库保管"; }
async function loadPeople(value: Transfer) {
  const ids = new Set(value.items.flatMap(item => [item.targetSnapshot.usingOrganizationId, item.sourceSnapshot.using_organization_id]).filter(Boolean).map(String));
  const choices = await Promise.all([...ids].map(id => catalog("employee", id)));
  peopleNames.value = Object.fromEntries(choices.flat().map(person => [person.id, person.name]));
}
function decide(approved: boolean) { if (selected.value && reason.value.trim()) void mutate(`/transfers/${selected.value.id}/decision`, selected.value.version, { approved, reason: reason.value.trim() }); }
function physical(item: TransferItem, action: string) {
  if (!selected.value || !code.value.trim() || !destination.value.trim() || !evidence.value.trim()) { error.value = "请填写实物编码、实际去向和交接/收回依据。"; return; }
  const body = { itemVersion: item.version, observedCode: code.value.trim(), destination: destination.value.trim(), evidence: evidence.value.trim(),
    actualReceiver: signer.value.trim() || null, receivedAt: receivedAt.value ? new Date(receivedAt.value).toISOString() : null, physicalReturned: action === "recover" ? checked.value : null };
  void mutate(`/transfers/${selected.value.id}/items/${item.id}/${action}`, selected.value.version, body);
}
onMounted(async () => {
  busy.value = true;
  try {
    if (!/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "没有当前记录或操作的权限。"; return; }
    [organizations.value, locations.value] = await Promise.all([catalog("organization"), catalog("location")]);
    await open();
    if (route.query.saved) { notice.value = "操作已保存。"; window.history.replaceState({}, "", window.location.pathname); }
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/transfers">返回调拨列表</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <section v-if="selected" class="fams-panel fams-stack" aria-label="调拨详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">调拨 {{ selected.id }} · {{ labels[selected.requestStatus] }}</h2>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新</button>
          <button v-if="retryable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="retry">重试上次操作</button>
        </div>
      </div>

      <p>{{ selected.reason }}</p>
      <dl class="fams-facts">
        <div><dt>总项数</dt><dd>{{ selected.totalQuantity }}</dd></div>
        <div><dt>完成</dt><dd>{{ selected.completedQuantity }}</dd></div>
        <div><dt>取消</dt><dd>{{ selected.cancelledQuantity }}</dd></div>
        <div><dt>待处理</dt><dd>{{ selected.pendingQuantity }}</dd></div>
        <div><dt>来源分组</dt><dd>{{ selected.sourceGroups }}</dd></div>
        <div><dt>目标分组</dt><dd>{{ selected.targetGroups }}</dd></div>
      </dl>
      <p class="fams-panel-subtitle">整单由一名库管记录全部执行；如需多人负责，批准前建议按分组分别提交。</p>
      <label class="fams-field">
        <span class="fams-field-label">申请 / 决定原因</span>
        <textarea class="fams-control" v-model="reason" maxlength="1000" :disabled="busy || retryable" />
      </label>
      <TransferReassignment v-if="admin && ['PROCESSING','WAITING_HANDOVER'].includes(selected.requestStatus)" :key="selected.id" :id="selected.id" :version="selected.version" @saved="refresh" />
      <div class="fams-panel-actions">
        <button v-if="selected.creatorAccountId === session.user?.id && selected.requestStatus === 'DRAFT'" class="fams-button" data-kind="primary" type="button"
          :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/submit`, selected.version)">提交调拨</button>
        <button v-if="selected.creatorAccountId === session.user?.id && ['DRAFT', 'PENDING_APPROVAL'].includes(selected.requestStatus)" class="fams-button" data-kind="danger" type="button"
          :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/cancel`, selected.version)">取消申请</button>
        <template v-if="admin && selected.requestStatus === 'PENDING_APPROVAL'">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="decide(true)">批准调拨</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="decide(false)">驳回调拨</button>
        </template>
        <button v-if="operator && selected.requestStatus === 'WAITING_WAREHOUSE_CLAIM'" class="fams-button" data-kind="primary" type="button"
          :disabled="busy || retryable" @click="mutate(`/transfers/${selected.id}/claim`, selected.version)">领取整单调拨</button>
      </div>

      <div v-if="operator && selected.processorAccountId === session.user?.id && ['PROCESSING','WAITING_HANDOVER'].includes(selected.requestStatus)" class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">实物编码 / 当前二维码</span>
          <input class="fams-control" v-model="code" :disabled="busy || retryable" /></label>
        <label class="fams-field"><span class="fams-field-label">实际去向</span>
          <input class="fams-control" v-model="destination" :disabled="busy || retryable" maxlength="500" /></label>
        <label class="fams-field"><span class="fams-field-label">目标现场实际签收人</span>
          <input class="fams-control" v-model="signer" :disabled="busy || retryable" maxlength="200" /></label>
        <label class="fams-field"><span class="fams-field-label">现场收妥时间</span>
          <input class="fams-control" v-model="receivedAt" type="datetime-local" :disabled="busy || retryable" /></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">现场交接 / 收回依据</span>
          <textarea class="fams-control" v-model="evidence" :disabled="busy || retryable" maxlength="1000" /></label>
        <label class="fams-selection fams-field-wide"><input v-model="checked" type="checkbox" :disabled="busy || retryable" />已实际收回并核对实物</label>
      </div>

      <article v-for="item in selected.items" :key="item.id" class="fams-card fams-stack">
        <h3>{{ item.sourceSnapshot.asset_code }} · {{ labels[item.itemStatus] }}</h3>
        <p>来源：{{ custodyName(item.sourceSnapshot.custody_type) }} / {{ organization(item.sourceSnapshot.using_organization_id) }} / {{ personName(item.sourceSnapshot.responsible_employee_id ?? item.sourceSnapshot.department_responsible_employee_id) }} / {{ place(item.sourceSnapshot.current_location_id) }}</p>
        <p>目标：{{ custodyName(item.targetSnapshot.custodyType) }} / {{ organization(item.targetSnapshot.usingOrganizationId) }} / {{ personName(item.targetSnapshot.employeeId) }} / {{ place(item.targetSnapshot.locationId) }}</p>
        <p v-if="item.destination" class="u-secondary">实际去向 {{ item.destination }} · {{ item.deliveryEvidence }}</p>
        <div v-if="operator && selected.processorAccountId === session.user?.id" class="fams-panel-actions">
          <template v-if="item.itemStatus === 'READY'">
            <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable"
              @click="physical(item, item.receiptRequired ? 'deliver' : 'verify')">{{ item.receiptRequired ? '记录交出，等待目标人接收' : '记录现场收妥并完成' }}</button>
            <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="physical(item, 'cancel')">未交出取消</button>
          </template>
          <button v-if="['DELIVERED','REFUSED'].includes(item.itemStatus)" class="fams-button" data-kind="secondary" type="button"
            :disabled="busy || retryable" @click="physical(item, 'recovery')">开始实物收回</button>
          <button v-if="item.itemStatus === 'RECOVERY_PENDING'" class="fams-button" data-kind="primary" type="button"
            :disabled="busy || retryable" @click="physical(item, 'recover')">确认实物收回后取消</button>
          <router-link v-if="item.itemStatus === 'DELIVERED' && item.receiptRequired" class="fams-button" data-kind="link" data-size="sm"
            :to="{ path: '/proxy-confirmations', query: { businessType: 'TRANSFER', businessId: selected.id, itemId: item.id } }">申请异常代确认</router-link>
          <router-link v-if="['READY', 'DELIVERED', 'REFUSED'].includes(item.itemStatus)" class="fams-button" data-kind="link" data-size="sm"
            :to="{ path: '/transfer-target-changes', query: { businessId: selected.id, itemId: item.id } }">申请目标变更</router-link>
        </div>
        <p v-if="item.itemStatus === 'COMPLETED'" class="fams-panel-subtitle">正式目标已更新，原责任和交接证据保留。</p>
      </article>
    </section>
  </AppChrome>
</template>
