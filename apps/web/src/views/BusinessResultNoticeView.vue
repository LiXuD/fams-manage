<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { loanMessage, loanRequest, type LoanMutation } from "@/loan/api";

interface Notice { id: string; businessType: string; businessId: string; itemId: string;
  content: { result: { assetCode: string }; actualSigner: string; submittedBy: string; approvedBy: string; executedBy: string;
    metadata: { cannotConfirmReason: string; contactRecord: string; actualReceivedAt: string };
    businessSnapshot?: { targetEmployeeId: string; processorAccountId: string } }; createdAt: string; read: boolean; reviewRequested: boolean }
interface Page { items: Notice[]; total: number; unread: number }
const rows = ref<Notice[]>([]); const page = ref(1); const total = ref(0);
const unread = ref(0); const busy = ref(false); const error = ref("");
const reasons = ref<Record<string, string>>({}); const info = ref("");
const pendingObjection = ref<(LoanMutation & { id: string }) | null>(null);
async function load() {
  const result = await loanRequest<Page>(`/business-result-notices?page=${page.value}&pageSize=20`);
  rows.value = result.items; total.value = result.total; unread.value = result.unread;
}
async function refresh(delta = 0) {
  if (busy.value) return;
  busy.value = true; error.value = ""; page.value += delta;
  try { await load(); } catch (e) { error.value = loanMessage(e); }
  finally { busy.value = false; }
}
async function read(id: string) {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try {
    await loanRequest(`/business-result-notices/${id}/read`, {
      path: `/business-result-notices/${id}/read`, key: crypto.randomUUID(), version: 0,
    }); await load();
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function object(id: string) {
  if (busy.value) return;
  if (pendingObjection.value && pendingObjection.value.id !== id) return;
  if (!pendingObjection.value && !reasons.value[id]?.trim()) return;
  pendingObjection.value ??= { id, path: `/business-result-notices/${id}/objection`,
    key: crypto.randomUUID(), version: 0, body: { reason: reasons.value[id] } };
  busy.value = true; error.value = ""; info.value = "";
  try {
    await loanRequest(pendingObjection.value.path, pendingObjection.value);
    pendingObjection.value = null; await load();
    info.value = "已登记管理员核查。核查期间资产责任仍保持，请勿重复办理接收或自行解除责任。";
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
onMounted(() => refresh());
</script>
<template>
  <AppChrome>
    <h1>业务结果通知</h1>
    <p>未读 {{ unread }} 条。代确认执行后资产责任已生效；如有异议，请从业务单据联系管理员核查。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <p v-if="info" role="status">{{ info }}</p>
    <button v-if="pendingObjection" :disabled="busy" @click="object(pendingObjection.id)">原请求重试核查登记</button>
    <button :disabled="busy" @click="refresh()">刷新通知</button>
    <article v-for="notice in rows" :key="notice.id">
      <h2>代确认执行结果 · {{ notice.read ? '已读' : '未读' }}</h2>
      <p>资产 {{ notice.content.result.assetCode }} · 单据 {{ notice.businessId }} · {{ notice.createdAt }}</p>
      <p>签收已完成，责任归属仍为原目标责任人员。</p>
      <router-link v-if="notice.businessType !== 'TRANSFER'" :to="{ path: '/requisitions', query: { id: notice.businessId } }">查看业务单据并核查</router-link>
      <details v-if="notice.businessType === 'TRANSFER'">
        <summary>查看本次调拨签收结果与核查依据</summary>
        <p>原调拨 {{ notice.businessId }} / 明细 {{ notice.itemId }} · 资产 {{ notice.content.result.assetCode }}</p>
        <p>实际签收人 {{ notice.content.actualSigner }} · {{ notice.content.metadata?.actualReceivedAt }}</p>
        <p>原目标责任人员 {{ notice.content.businessSnapshot?.targetEmployeeId }}</p>
        <p>代确认原因 {{ notice.content.metadata?.cannotConfirmReason }}；联系记录 {{ notice.content.metadata?.contactRecord }}</p>
        <p>提交账号 {{ notice.content.submittedBy }} · 批准账号 {{ notice.content.approvedBy }} · 执行账号 {{ notice.content.executedBy }}</p>
      </details>
      <button v-if="!notice.read" :disabled="busy" @click="read(notice.id)">标记已读</button>
      <p v-if="notice.reviewRequested">已登记管理员核查，资产责任保持。</p>
      <form v-else @submit.prevent="object(notice.id)">
        <label>核查说明<textarea v-model="reasons[notice.id]" required maxlength="1000" :disabled="busy || !!pendingObjection" /></label>
        <button :disabled="busy || !!pendingObjection || !reasons[notice.id]?.trim()">通知管理员核查</button>
      </form>
    </article>
    <p v-if="!rows.length">暂无结果通知。</p>
    <button :disabled="busy || page <= 1" @click="refresh(-1)">上一页</button>
    <span>{{ page }} 页 / 共 {{ total }} 条</span>
    <button :disabled="busy || page * 20 >= total" @click="refresh(1)">下一页</button>
  </AppChrome>
</template>
