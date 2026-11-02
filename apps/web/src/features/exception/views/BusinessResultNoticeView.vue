<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { AuthApiError } from "@/auth/api";
import { formatProxyTime } from "@/features/exception/time";
import AppChrome from "@/components/AppChrome.vue";
import { useRequestLiveness, type LivenessToken } from "@/composables/useRequestLiveness";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { BusinessApiError, businessMessage, businessRequest, type BusinessMutation } from "@/api/business";

interface Notice { id: string; businessType: string; businessId: string; itemId: string;
  content: { result: { assetCode: string }; actualSigner: string; submittedBy: string; approvedBy: string; executedBy: string;
    metadata: { cannotConfirmReason: string; contactRecord: string; actualReceivedAt: string };
    businessSnapshot?: { targetEmployeeId: string; processorAccountId: string } }; createdAt: string; read: boolean; reviewRequested: boolean }
interface Page { items: Notice[]; total: number; unread: number }
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const rows = ref<Notice[]>([]); const page = shallowRef(1); const total = shallowRef(0);
const unread = shallowRef(0); const busy = shallowRef(false); const error = shallowRef("");
const reasons = ref<Record<string, string>>({}); const info = shallowRef("");
/** 确认式幂等提交：已读与核查两条在途意图槽及幂等键簿记归底座，4xx 确定性失败才清键；busy、URL、body 与文案留在本视图。 */
const deterministic = (cause: unknown): boolean => (cause instanceof BusinessApiError || cause instanceof AuthApiError)
  && cause.status >= 400 && cause.status < 500;
const { pending: pendingObjection, prepare: draftObjection, confirm: adoptObjection, settle: settleObjection, fail: failObjection, reset: resetObjection } =
  useConfirmedSubmit<Omit<BusinessMutation, "key"> & { id: string }>({ failure: deterministic });
const { pending: pendingRead, prepare: draftRead, confirm: adoptRead, settle: settleRead, fail: failRead, reset: resetRead } =
  useConfirmedSubmit<Omit<BusinessMutation, "key"> & { id: string }>({ failure: deterministic });
interface NoticeRow extends Notice { readText: string; createdAtText: string; receivedAtText: string;
  isRequisition: boolean; isRepair: boolean; isTransfer: boolean; objectionDisabled: boolean }
const noticeRows = computed<NoticeRow[]>(() => rows.value.map((notice) => ({ ...notice,
  readText: notice.read ? "已读" : "未读",
  createdAtText: formatProxyTime(notice.createdAt),
  receivedAtText: formatProxyTime(notice.content.metadata?.actualReceivedAt ?? ""),
  isRequisition: notice.businessType === "REQUISITION",
  isRepair: notice.businessType === "REPAIR",
  isTransfer: notice.businessType === "TRANSFER",
  objectionDisabled: busy.value || !!pendingObjection.value || !!pendingRead.value || !reasons.value[notice.id]?.trim() })));
const prevDisabled = computed(() => busy.value || page.value <= 1);
const nextDisabled = computed(() => busy.value || page.value * 20 >= total.value);
const liveness = useRequestLiveness(() => [identity.value]);
function clear() { liveness.invalidate(); rows.value = []; reasons.value = {}; resetObjection(); resetRead();
  page.value = 1; total.value = 0; unread.value = 0; info.value = ""; busy.value = false; }
function failure(cause: unknown, mutation = false) {
  if (cause instanceof BusinessApiError || cause instanceof AuthApiError) {
    if ([401, 403, 404].includes(cause.status)) clear();
    else if (mutation) { failObjection(cause); failRead(cause); }
  }
  error.value = businessMessage(cause);
}
async function load(turn: LivenessToken) {
  const result = await businessRequest<Page>(`/business-result-notices?page=${page.value}&pageSize=20`);
  if (liveness.isCurrent(turn)) { rows.value = result.items; total.value = result.total; unread.value = result.unread; }
}
async function refresh(delta = 0) {
  if (busy.value || page.value + delta < 1 || delta && (pendingObjection.value || pendingRead.value)) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; page.value += delta;
  try { await load(turn); } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function read(id: string) {
  if (busy.value || pendingObjection.value || pendingRead.value && pendingRead.value.payload.id !== id) return;
  let operation = pendingRead.value;
  if (!operation) { draftRead(() => ({ id, path: `/business-result-notices/${id}/read`, version: 0 })); operation = adoptRead(); }
  if (!operation) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    await businessRequest(operation.payload.path, { ...operation.payload, key: operation.key }); if (!liveness.isCurrent(turn)) return;
    settleRead(); await load(turn);
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause, true); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function object(id: string) {
  if (busy.value || pendingRead.value) return;
  let operation = pendingObjection.value;
  if (operation && operation.payload.id !== id) return;
  if (!operation && !reasons.value[id]?.trim()) return;
  if (!operation) { draftObjection(() => ({ id, path: `/business-result-notices/${id}/objection`, version: 0,
    body: { reason: reasons.value[id].trim() } })); operation = adoptObjection(); }
  if (!operation) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; info.value = "";
  try {
    await businessRequest(operation.payload.path, { ...operation.payload, key: operation.key }); if (!liveness.isCurrent(turn)) return;
    settleObjection(); await load(turn);
    if (liveness.isCurrent(turn)) info.value = "已登记管理员核查。核查期间资产责任仍保持，请勿重复办理接收或自行解除责任。";
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause, true); } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(identity, () => { clear(); error.value = ""; if (session.user) void refresh(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { clear(); });
</script>
<template>
  <AppChrome>
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="refresh()">刷新通知</button>
    </template>
    <section class="fams-panel fams-stack" aria-label="业务结果通知">
      <p role="status">未读 {{ unread }} 条</p>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="info" class="fams-notice" data-state="success" role="status"><strong>{{ info }}</strong></p>
      <button v-if="pendingRead" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="read(pendingRead.payload.id)">原请求重试已读登记</button>
      <button v-if="pendingObjection" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="object(pendingObjection.payload.id)">原请求重试核查登记</button>
      <article v-for="notice in noticeRows" :key="notice.id" class="fams-card fams-stack">
        <div class="fams-panel-head">
          <div>
            <h2 class="fams-panel-title">代确认执行结果 · {{ notice.readText }}</h2>
            <p class="fams-panel-subtitle">资产 {{ notice.content.result.assetCode }} · 单据 {{ notice.businessId }} · {{ notice.createdAtText }}</p>
          </div>
        </div>
        <p>签收已完成，责任归属为本次有效目标责任人员。</p>
        <router-link v-if="notice.isRequisition" :to="`/requisitions/${notice.businessId}`">查看业务单据并核查</router-link>
        <router-link v-if="notice.isRepair" :to="{ path: '/repairs/' + notice.businessId }">查看维修单据并核查</router-link>
        <details v-if="notice.isTransfer" class="fams-card fams-stack">
          <summary class="fams-panel-title">查看本次调拨签收结果与核查依据</summary>
          <p>原调拨 {{ notice.businessId }} / 明细 {{ notice.itemId }} · 资产 {{ notice.content.result.assetCode }}</p>
          <p>实际签收人 {{ notice.content.actualSigner }} · {{ notice.receivedAtText }}</p>
          <p>原目标责任人员 {{ notice.content.businessSnapshot?.targetEmployeeId }}</p>
          <p>代确认原因 {{ notice.content.metadata?.cannotConfirmReason }}；联系记录 {{ notice.content.metadata?.contactRecord }}</p>
          <p>提交账号 {{ notice.content.submittedBy }} · 批准账号 {{ notice.content.approvedBy }} · 执行账号 {{ notice.content.executedBy }}</p>
        </details>
        <button v-if="!notice.read" class="fams-button" data-kind="secondary" type="button" :disabled="busy || !!pendingObjection || !!pendingRead" @click="read(notice.id)">标记已读</button>
        <p v-if="notice.reviewRequested">已登记管理员核查，资产责任保持。</p>
        <form v-else class="fams-stack" @submit.prevent="object(notice.id)">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">核查说明</span><textarea class="fams-control" v-model="reasons[notice.id]" required maxlength="1000" :disabled="busy || !!pendingObjection || !!pendingRead" /></label>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit" :disabled="notice.objectionDisabled">通知管理员核查</button></div>
        </form>
      </article>
      <p v-if="!rows.length">暂无结果通知。</p>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">{{ page }} 页 / 共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="refresh(-1)">上一页</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="refresh(1)">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
