<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { formatProxyTime } from "@/features/exception/time";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { dutyRequest } from "@/features/reminder/api";

interface AnomalyTodo {
  id: string;
  kind: string;
  businessType?: string;
  businessId?: string;
  parameterVersionId?: string;
  deadline?: string;
}

interface Anomaly {
  id: string;
  type: string;
  status: "OPEN" | "RESOLVED";
  detectedAt: string;
  resolvedAt?: string;
  relatedTodos: AnomalyTodo[];
  relatedBusinessTodos?: AnomalyTodo[];
}
interface AnomalyRow { id: string; title: string; detectedText: string; resolvedText: string; todoRows: { id: string; text: string }[] }

const labels: Record<string, string> = {
  NO_ASSET_ADMIN: "没有有效资产管理员",
  NO_WAREHOUSE_OPERATOR: "没有有效库管员",
  SMS_CHANNEL_MISSING: "短信通道未配置",
  WECHAT_TEMPLATE_MISSING: "微信提醒模板未配置",
};
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const page = shallowRef(1);
const rows = ref<Anomaly[]>([]);
const total = shallowRef(0);
const busy = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
const hasNoRows = computed(() => !busy.value && rows.value.length === 0);
const showPagination = computed(() => total.value > 20);
const prevDisabled = computed(() => busy.value || page.value <= 1);
const nextDisabled = computed(() => busy.value || page.value * 20 >= total.value);
const anomalyRows = computed<AnomalyRow[]>(() => rows.value.map((row) => ({
  id: row.id,
  title: `${labels[row.type]} · ${row.status === "OPEN" ? "待恢复" : "已恢复"}`,
  detectedText: `发现于 ${formatProxyTime(row.detectedAt)}`,
  resolvedText: row.resolvedAt ? ` · 恢复于 ${formatProxyTime(row.resolvedAt)}` : "",
  todoRows: [...row.relatedTodos, ...(row.relatedBusinessTodos ?? [])].map((todo) => {
    const parts = [`待办 ${todo.id}`, todo.kind];
    if (todo.businessType && todo.businessId) parts.push(`${todo.businessType} ${todo.businessId}`);
    if (todo.parameterVersionId) parts.push(`参数版本 ${todo.parameterVersionId}`);
    if (todo.deadline) parts.push(`原截止 ${formatProxyTime(todo.deadline)}`);
    return { id: todo.id, text: parts.filter(Boolean).join(" · ") };
  }),
})));

async function load(target = 1) {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  try {
    const result = await dutyRequest<{ items: Anomaly[]; total: number }>(
      `/configuration-anomalies?page=${target}&pageSize=20`);
    if (!liveness.isCurrent(turn)) return;
    page.value = target;
    rows.value = result.items;
    total.value = result.total;
  } catch {
    if (!liveness.isCurrent(turn)) return;
    rows.value = [];
    total.value = 0;
    error.value = "配置异常暂时无法读取，请刷新重试。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

watch(identity, () => {
  liveness.invalidate();
  rows.value = [];
  total.value = 0;
  if (session.user?.roles.includes("SYSTEM_ADMIN")) void load();
}, { immediate: true, flush: "sync" });
</script>

<template>
  <section class="fams-panel fams-stack" aria-label="配置异常">
    <div class="fams-panel-head">
      <h2 class="fams-panel-title">配置异常</h2>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(page)">刷新配置异常</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-else-if="hasNoRows">暂无配置异常。</p>
    <article v-for="row in anomalyRows" :key="row.id" class="fams-card fams-stack">
      <h3 class="fams-panel-title">{{ row.title }}</h3>
      <p>{{ row.detectedText }}<template v-if="row.resolvedText">{{ row.resolvedText }}</template></p>
      <ul v-if="row.todoRows.length" class="fams-timeline">
        <li v-for="todo in row.todoRows" :key="todo.id">{{ todo.text }}</li>
      </ul>
    </article>
    <div v-if="showPagination" class="fams-pagination-actions">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="load(page - 1)">上一页</button>
      <span class="fams-pagination-page">第 {{ page }} 页</span>
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="load(page + 1)">下一页</button>
    </div>
  </section>
</template>
