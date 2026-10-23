<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { AuthApiError } from "@/auth/api";
import { formatProxyTime } from "@/proxy/time";
import AppChrome from "@/components/AppChrome.vue";
import { LoanApiError, loanMessage, loanRequest, type LoanMutation } from "@/loan/api";

interface Notice { id: string; businessType: string; businessId: string; itemId: string;
  content: { result: { assetCode: string }; actualSigner: string; submittedBy: string; approvedBy: string; executedBy: string;
    metadata: { cannotConfirmReason: string; contactRecord: string; actualReceivedAt: string };
    businessSnapshot?: { targetEmployeeId: string; processorAccountId: string } }; createdAt: string; read: boolean; reviewRequested: boolean }
interface Page { items: Notice[]; total: number; unread: number }
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const rows = ref<Notice[]>([]); const page = ref(1); const total = ref(0);
const unread = ref(0); const busy = ref(false); const error = ref("");
const reasons = ref<Record<string, string>>({}); const info = ref("");
const pendingObjection = ref<(LoanMutation & { id: string }) | null>(null);
const pendingRead = ref<(LoanMutation & { id: string }) | null>(null);
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
function clear() { generation++; rows.value = []; reasons.value = {}; pendingObjection.value = null; pendingRead.value = null;
  page.value = 1; total.value = 0; unread.value = 0; info.value = ""; busy.value = false; }
function failure(cause: unknown, mutation = false) {
  if (cause instanceof LoanApiError || cause instanceof AuthApiError) {
    if ([401, 403, 404].includes(cause.status)) clear();
    else if (mutation && cause.status >= 400 && cause.status < 500) { pendingObjection.value = null; pendingRead.value = null; }
  }
  error.value = loanMessage(cause);
}
async function load(epoch: number, owner: string) {
  const result = await loanRequest<Page>(`/business-result-notices?page=${page.value}&pageSize=20`);
  if (current(epoch, owner)) { rows.value = result.items; total.value = result.total; unread.value = result.unread; }
}
async function refresh(delta = 0) {
  if (busy.value || page.value + delta < 1 || delta && (pendingObjection.value || pendingRead.value)) return;
  const epoch = ++generation, owner = identity.value;
  busy.value = true; error.value = ""; page.value += delta;
  try { await load(epoch, owner); } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function read(id: string) {
  if (busy.value || pendingObjection.value || pendingRead.value && pendingRead.value.id !== id) return;
  pendingRead.value ??= { id, path: `/business-result-notices/${id}/read`, key: crypto.randomUUID(), version: 0 };
  const operation = pendingRead.value, epoch = ++generation, owner = identity.value;
  busy.value = true; error.value = "";
  try {
    await loanRequest(operation.path, operation); if (!current(epoch, owner)) return;
    pendingRead.value = null; await load(epoch, owner);
  } catch (cause) { if (current(epoch, owner)) failure(cause, true); } finally { if (current(epoch, owner)) busy.value = false; }
}
async function object(id: string) {
  if (busy.value || pendingRead.value) return;
  if (pendingObjection.value && pendingObjection.value.id !== id) return;
  if (!pendingObjection.value && !reasons.value[id]?.trim()) return;
  pendingObjection.value ??= { id, path: `/business-result-notices/${id}/objection`,
    key: crypto.randomUUID(), version: 0, body: { reason: reasons.value[id].trim() } };
  const operation = pendingObjection.value, epoch = ++generation, owner = identity.value;
  busy.value = true; error.value = ""; info.value = "";
  try {
    await loanRequest(operation.path, operation); if (!current(epoch, owner)) return;
    pendingObjection.value = null; await load(epoch, owner);
    if (current(epoch, owner)) info.value = "已登记管理员核查。核查期间资产责任仍保持，请勿重复办理接收或自行解除责任。";
  } catch (cause) { if (current(epoch, owner)) failure(cause, true); } finally { if (current(epoch, owner)) busy.value = false; }
}
watch(identity, () => { clear(); error.value = ""; if (session.user) void refresh(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; clear(); });
</script>
<template>
  <AppChrome>
    <h1>业务结果通知</h1>
    <p>未读 {{ unread }} 条。代确认执行后资产责任已生效；如有异议，请从业务单据联系管理员核查。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <p v-if="info" role="status">{{ info }}</p>
    <button v-if="pendingRead" :disabled="busy" @click="read(pendingRead.id)">原请求重试已读登记</button>
    <button v-if="pendingObjection" :disabled="busy" @click="object(pendingObjection.id)">原请求重试核查登记</button>
    <button :disabled="busy" @click="refresh()">刷新通知</button>
    <article v-for="notice in rows" :key="notice.id">
      <h2>代确认执行结果 · {{ notice.read ? '已读' : '未读' }}</h2>
      <p>资产 {{ notice.content.result.assetCode }} · 单据 {{ notice.businessId }} · {{ formatProxyTime(notice.createdAt) }}</p>
      <p>签收已完成，责任归属为本次有效目标责任人员。</p>
      <router-link v-if="notice.businessType === 'REQUISITION'" :to="{ path: '/requisitions', query: { id: notice.businessId } }">查看业务单据并核查</router-link>
      <router-link v-if="notice.businessType === 'REPAIR'" :to="{ path: '/repairs/' + notice.businessId }">查看维修单据并核查</router-link>
      <details v-if="notice.businessType === 'TRANSFER'">
        <summary>查看本次调拨签收结果与核查依据</summary>
        <p>原调拨 {{ notice.businessId }} / 明细 {{ notice.itemId }} · 资产 {{ notice.content.result.assetCode }}</p>
        <p>实际签收人 {{ notice.content.actualSigner }} · {{ formatProxyTime(notice.content.metadata?.actualReceivedAt) }}</p>
        <p>原目标责任人员 {{ notice.content.businessSnapshot?.targetEmployeeId }}</p>
        <p>代确认原因 {{ notice.content.metadata?.cannotConfirmReason }}；联系记录 {{ notice.content.metadata?.contactRecord }}</p>
        <p>提交账号 {{ notice.content.submittedBy }} · 批准账号 {{ notice.content.approvedBy }} · 执行账号 {{ notice.content.executedBy }}</p>
      </details>
      <button v-if="!notice.read" :disabled="busy || !!pendingObjection || !!pendingRead" @click="read(notice.id)">标记已读</button>
      <p v-if="notice.reviewRequested">已登记管理员核查，资产责任保持。</p>
      <form v-else @submit.prevent="object(notice.id)">
        <label>核查说明<textarea v-model="reasons[notice.id]" required maxlength="1000" :disabled="busy || !!pendingObjection || !!pendingRead" /></label>
        <button :disabled="busy || !!pendingObjection || !!pendingRead || !reasons[notice.id]?.trim()">通知管理员核查</button>
      </form>
    </article>
    <p v-if="!rows.length">暂无结果通知。</p>
    <button :disabled="busy || page <= 1" @click="refresh(-1)">上一页</button>
    <span>{{ page }} 页 / 共 {{ total }} 条</span>
    <button :disabled="busy || page * 20 >= total" @click="refresh(1)">下一页</button>
  </AppChrome>
</template>
