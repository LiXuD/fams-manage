<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { AuthApiError } from "@/auth/api";
import { formatProxyTime } from "@/features/exception/time";
import AppChrome from "@/components/AppChrome.vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { BusinessApiError, businessMessage, businessRequest } from "@/api/business";

interface Review { noticeId: string; executionId: string; businessId: string; businessType: string;
  recipient: string; reason: string; createdAt: string }
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const liveness = useRequestLiveness(() => [identity.value]);
function clear() { liveness.invalidate(); rows.value = []; page.value = 1; total.value = 0; busy.value = false; }
const rows = ref<Review[]>([]); const busy = shallowRef(false); const error = shallowRef("");
const page = shallowRef(1); const total = shallowRef(0);
interface ReviewRow extends Review { createdAtText: string; businessPath: string }
const reviewRows = computed<ReviewRow[]>(() => rows.value.map((review) => ({ ...review,
  createdAtText: formatProxyTime(review.createdAt),
  businessPath: (review.businessType === "TRANSFER" ? "/transfers/" : review.businessType === "REPAIR" ? "/repairs/" : "/requisitions/") + review.businessId })));
const prevDisabled = computed(() => busy.value || page.value <= 1);
const nextDisabled = computed(() => busy.value || page.value * 20 >= total.value);
async function refresh(delta = 0) {
  if (busy.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; page.value += delta;
  try {
    const result = await businessRequest<{ items: Review[]; total: number }>(`/business-result-notices/review-requests?page=${page.value}&pageSize=20`);
    if (liveness.isCurrent(turn)) { rows.value = result.items; total.value = result.total; }
  } catch (e) { if (liveness.isCurrent(turn)) {
    if ((e instanceof BusinessApiError || e instanceof AuthApiError) && [401, 403, 404].includes(e.status)) clear();
    error.value = businessMessage(e);
  } } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(identity, () => { clear(); error.value = ""; if (session.user?.roles.includes("ASSET_ADMIN")) void refresh(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { clear(); });
</script>
<template>
  <AppChrome>
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="refresh()">刷新核查列表</button>
    </template>
    <section class="fams-panel fams-stack" aria-label="代确认结果核查">
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <article v-for="review in reviewRows" :key="review.noticeId" class="fams-card fams-stack">
        <div class="fams-panel-head">
          <div>
            <h2 class="fams-panel-title">通知 {{ review.noticeId }} · {{ review.recipient }}</h2>
            <p class="fams-panel-subtitle">{{ review.reason }} · {{ review.createdAtText }}</p>
          </div>
        </div>
        <router-link :to="{ path: '/proxy-confirmations', query: { id: review.executionId } }">查看代确认与签收凭证</router-link>
        <router-link :to="{ path: review.businessPath }">查看业务单据</router-link>
      </article>
      <p v-if="!rows.length">暂无待核查请求。</p>
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
