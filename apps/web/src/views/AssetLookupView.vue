<script setup lang="ts">
import { computed, ref, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";

interface Result {
  asset: { id: string; assetCode: string; name: string; lifecycle: string; exception: string };
  page: number;
  more: boolean;
  timeline: { id: string; eventType: string; occurredAt: string; businessType?: string;
    businessId?: string; summary?: string }[];
}
const lifecycle: Record<string, string> = { IN_STOCK: "在库", IN_USE: "在用", BORROWED: "借出",
  UNDER_REPAIR: "维修中", FACTORY_REPAIR: "返厂维修", PENDING_SCRAP: "待报废", SCRAPPED: "已报废" };
const exceptions: Record<string, string> = { NORMAL: "正常", SEARCHING: "查找中", LOST: "已遗失" };
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.permissions?.authorizationVersion]));
const code = ref("");
const result = ref<Result>();
const busy = ref(false);
const error = ref("");
let generation = 0;

async function query(page: number) {
  const assetCode = page === 1 ? code.value.trim() : result.value?.asset.assetCode;
  if (!assetCode || busy.value) return;
  const request = ++generation;
  busy.value = true;
  error.value = "";
  result.value = undefined;
  try {
    const params = new URLSearchParams({ assetCode, page: String(page) });
    const response = await fetch(`/api/v1/asset-lookup?${params}`, { credentials: "include", cache: "no-store" });
    if (request !== generation) return;
    if (response.status === 404) {
      error.value = "未找到当前账号可查询的资产。请核对资产编码。";
      return;
    }
    if (!response.ok) throw new Error(String(response.status));
    const data = await response.json() as Result;
    if (request === generation) result.value = data;
  } catch {
    if (request === generation) error.value = "查询未完成，请核对当前权限后重试。";
  } finally {
    if (request === generation) busy.value = false;
  }
}

watch(identity, () => { generation++; result.value = undefined; busy.value = false; error.value = ""; code.value = ""; });
</script>

<template>
  <AppChrome>
    <h1>资产查询</h1>
    <form class="actions" @submit.prevent="query(1)">
      <label>资产编码 <input v-model="code" required maxlength="128" :disabled="busy" @input="result = undefined"></label>
      <button type="submit" :disabled="busy">查询资产</button>
    </form>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <section v-if="result" aria-label="资产状态与履历">
      <h2>{{ result.asset.assetCode }} · {{ result.asset.name }}</h2>
      <p>当前状态 {{ lifecycle[result.asset.lifecycle] ?? result.asset.lifecycle }}
        · {{ exceptions[result.asset.exception] ?? result.asset.exception }}</p>
      <h3>时间线履历</h3>
      <p v-if="!result.timeline.length">当前页没有可见履历。</p>
      <ol>
        <li v-for="entry in result.timeline" :key="entry.id">
          <time :datetime="entry.occurredAt">{{ new Date(entry.occurredAt).toLocaleString() }}</time>
          · {{ entry.summary || entry.eventType }}
          <span v-if="entry.businessId"> · {{ entry.businessType }} {{ entry.businessId }}</span>
        </li>
      </ol>
      <div class="actions">
        <button v-if="result.page > 1" type="button" @click="query(result.page - 1)">上一页履历</button>
        <button v-if="result.more" type="button" @click="query(result.page + 1)">下一页履历</button>
      </div>
    </section>
  </AppChrome>
</template>
