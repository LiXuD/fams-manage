<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { BusinessApiError, businessRequest } from "@/api/business";
import { useRequestLiveness } from "@/composables/useRequestLiveness";

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
const code = shallowRef("");
const result = ref<Result>();
const busy = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);

async function query(page: number) {
  const assetCode = page === 1 ? code.value.trim() : result.value?.asset.assetCode;
  if (!assetCode || busy.value) return;
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  result.value = undefined;
  try {
    const params = new URLSearchParams({ assetCode, page: String(page) });
    const data = await businessRequest<Result>(`/asset-lookup?${params}`);
    if (liveness.isCurrent(turn)) result.value = data;
  } catch (cause) {
    if (liveness.isCurrent(turn)) {
      error.value = cause instanceof BusinessApiError && cause.status === 404
        ? "未找到当前账号可查询的资产。请核对资产编码。"
        : "查询未完成，请核对当前权限后重试。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

const timelineRows = computed(() => (result.value?.timeline ?? []).map((entry) => ({
  id: entry.id, datetime: entry.occurredAt,
  timeText: new Date(entry.occurredAt).toLocaleString(),
  summaryText: entry.summary || entry.eventType,
  businessText: entry.businessId ? `${entry.businessType} ${entry.businessId}` : "",
})));
const prevVisible = computed(() => (result.value?.page ?? 1) > 1);

watch(identity, () => { liveness.invalidate(); result.value = undefined; busy.value = false; error.value = ""; code.value = ""; });
</script>

<template>
  <AppChrome>
    <form class="fams-field-row" @submit.prevent="query(1)">
      <label class="fams-field"><span class="fams-field-label">资产编码</span>
        <input class="fams-control" v-model="code" required maxlength="128" :disabled="busy" @input="result = undefined"></label>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询资产</button>
    </form>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <section v-if="result" class="fams-panel fams-stack" aria-label="资产状态与履历">
      <h2 class="fams-panel-title">{{ result.asset.assetCode }} · {{ result.asset.name }}</h2>
      <p>当前状态 {{ lifecycle[result.asset.lifecycle] ?? result.asset.lifecycle }}
        · {{ exceptions[result.asset.exception] ?? result.asset.exception }}</p>
      <h3>时间线履历</h3>
      <p v-if="!timelineRows.length">当前页没有可见履历。</p>
      <ol class="fams-timeline">
        <li v-for="row in timelineRows" :key="row.id">
          <time :datetime="row.datetime">{{ row.timeText }}</time>
          · {{ row.summaryText }}
          <span v-if="row.businessText"> · {{ row.businessText }}</span>
        </li>
      </ol>
      <div class="fams-pagination-actions">
        <button v-if="prevVisible" class="fams-button" data-kind="secondary" data-size="sm" type="button"
          @click="query(result.page - 1)">上一页履历</button>
        <button v-if="result.more" class="fams-button" data-kind="secondary" data-size="sm" type="button"
          @click="query(result.page + 1)">下一页履历</button>
      </div>
    </section>
  </AppChrome>
</template>
