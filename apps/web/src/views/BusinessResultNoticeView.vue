<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { loanMessage, loanRequest } from "@/loan/api";

interface Notice { id: number; businessType: string; businessId: number; itemId: number;
  content: { result: { assetCode: string }; actualSigner: string }; createdAt: string; read: boolean }
interface Page { items: Notice[]; total: number; unread: number }
const rows = ref<Notice[]>([]); const page = ref(1); const total = ref(0);
const unread = ref(0); const busy = ref(false); const error = ref("");
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
async function read(id: number) {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try {
    await loanRequest(`/business-result-notices/${id}/read`, {
      path: `/business-result-notices/${id}/read`, key: crypto.randomUUID(), version: 0,
    }); await load();
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
onMounted(() => refresh());
</script>
<template>
  <AppChrome>
    <h1>业务结果通知</h1>
    <p>未读 {{ unread }} 条。代确认执行后资产责任已生效；如有异议，请从业务单据联系管理员核查。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <button :disabled="busy" @click="refresh()">刷新通知</button>
    <article v-for="notice in rows" :key="notice.id">
      <h2>代确认执行结果 · {{ notice.read ? '已读' : '未读' }}</h2>
      <p>资产 {{ notice.content.result.assetCode }} · 单据 {{ notice.businessId }} · {{ notice.createdAt }}</p>
      <p>签收已完成，责任归属仍为原目标责任人员。</p>
      <router-link :to="{ path: '/requisitions', query: { id: notice.businessId } }">查看业务单据并核查</router-link>
      <button v-if="!notice.read" :disabled="busy" @click="read(notice.id)">标记已读</button>
    </article>
    <p v-if="!rows.length">暂无结果通知。</p>
    <button :disabled="busy || page <= 1" @click="refresh(-1)">上一页</button>
    <span>{{ page }} 页 / 共 {{ total }} 条</span>
    <button :disabled="busy || page * 20 >= total" @click="refresh(1)">下一页</button>
  </AppChrome>
</template>
