<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { formatProxyTime } from "@/proxy/time";
import { dutyRequest } from "./api";

interface Anomaly {
  id: string;
  type: string;
  status: "OPEN" | "RESOLVED";
  detectedAt: string;
  resolvedAt?: string;
  relatedTodos: { id: string; kind: string; parameterVersionId: string; deadline: string }[];
}

const labels: Record<string, string> = {
  NO_ASSET_ADMIN: "没有有效资产管理员",
  NO_WAREHOUSE_OPERATOR: "没有有效库管员",
  SMS_CHANNEL_MISSING: "短信通道未配置",
  WECHAT_TEMPLATE_MISSING: "微信提醒模板未配置",
};
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const page = ref(1);
const rows = ref<Anomaly[]>([]);
const total = ref(0);
const busy = ref(false);
const error = ref("");
let generation = 0;

async function load(target = 1) {
  const current = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const result = await dutyRequest<{ items: Anomaly[]; total: number }>(
      `/configuration-anomalies?page=${target}&pageSize=20`);
    if (current !== generation) return;
    page.value = target;
    rows.value = result.items;
    total.value = result.total;
  } catch {
    if (current !== generation) return;
    rows.value = [];
    total.value = 0;
    error.value = "配置异常暂时无法读取，请刷新重试。";
  } finally {
    if (current === generation) busy.value = false;
  }
}

watch(identity, () => {
  generation++;
  rows.value = [];
  total.value = 0;
  if (session.user?.roles.includes("SYSTEM_ADMIN")) void load();
}, { immediate: true, flush: "sync" });
</script>

<template>
  <section>
    <h2>配置异常</h2>
    <button :disabled="busy" @click="load(page)">刷新配置异常</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!busy && rows.length === 0">暂无配置异常。</p>
    <article v-for="row in rows" :key="row.id">
      <h3>{{ labels[row.type] }} · {{ row.status === "OPEN" ? "待恢复" : "已恢复" }}</h3>
      <p>发现于 {{ formatProxyTime(row.detectedAt) }}
        <template v-if="row.resolvedAt"> · 恢复于 {{ formatProxyTime(row.resolvedAt) }}</template>
      </p>
      <ul v-if="row.relatedTodos.length">
        <li v-for="todo in row.relatedTodos" :key="todo.id">
          待办 {{ todo.id }} · 参数版本 {{ todo.parameterVersionId }} · 原截止 {{ formatProxyTime(todo.deadline) }}
        </li>
      </ul>
    </article>
    <div v-if="total > 20" class="actions">
      <button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</button>
      <span>第 {{ page }} 页</span>
      <button :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</button>
    </div>
  </section>
</template>
