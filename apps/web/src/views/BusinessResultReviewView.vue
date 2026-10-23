<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { AuthApiError } from "@/auth/api";
import { formatProxyTime } from "@/proxy/time";
import AppChrome from "@/components/AppChrome.vue";
import { LoanApiError, loanMessage, loanRequest } from "@/loan/api";

interface Review { noticeId: string; executionId: string; businessId: string; businessType: string;
  recipient: string; reason: string; createdAt: string }
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
function clear() { generation++; rows.value = []; page.value = 1; total.value = 0; busy.value = false; }
const rows = ref<Review[]>([]); const busy = ref(false); const error = ref("");
const page = ref(1); const total = ref(0);
async function refresh(delta = 0) {
  if (busy.value) return;
  const epoch = ++generation, owner = identity.value;
  busy.value = true; error.value = ""; page.value += delta;
  try {
    const result = await loanRequest<{ items: Review[]; total: number }>(`/business-result-notices/review-requests?page=${page.value}&pageSize=20`);
    if (current(epoch, owner)) { rows.value = result.items; total.value = result.total; }
  } catch (e) { if (current(epoch, owner)) {
    if ((e instanceof LoanApiError || e instanceof AuthApiError) && [401, 403, 404].includes(e.status)) clear();
    error.value = loanMessage(e);
  } } finally { if (current(epoch, owner)) busy.value = false; }
}
watch(identity, () => { clear(); error.value = ""; if (session.user?.roles.includes("ASSET_ADMIN")) void refresh(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; clear(); });
</script>
<template>
  <AppChrome>
    <h1>代确认结果核查</h1>
    <p>资产管理员共享查看，不因查看保留审批权。请核验原签收凭证与业务事实，再按现有业务流程处理；核查请求不自动解除责任。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <button :disabled="busy" @click="refresh()">刷新核查列表</button>
    <article v-for="review in rows" :key="review.noticeId">
      <h2>通知 {{ review.noticeId }} · {{ review.recipient }}</h2>
      <p>{{ review.reason }} · {{ formatProxyTime(review.createdAt) }}</p>
      <router-link :to="{ path: '/proxy-confirmations', query: { id: review.executionId } }">查看代确认与签收凭证</router-link>
      <router-link :to="review.businessType === 'REPAIR' ? { path: '/repairs/' + review.businessId } : { path: review.businessType === 'TRANSFER' ? '/transfers' : '/requisitions', query: { id: review.businessId } }">查看业务单据</router-link>
    </article>
    <p v-if="!rows.length">暂无待核查请求。</p>
    <button :disabled="busy || page <= 1" @click="refresh(-1)">上一页</button>
    <span>{{ page }} 页 / 共 {{ total }} 条</span>
    <button :disabled="busy || page * 20 >= total" @click="refresh(1)">下一页</button>
  </AppChrome>
</template>
