<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { RepairApiError, repairProblem, repairRequest, repairStates, type Repair } from "@/features/repair/api";
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const rows = ref<Repair[]>([]), total = shallowRef(0), page = shallowRef(1);
const busy = shallowRef(false), error = shallowRef("");
const prevDisabled = computed(() => busy.value || page.value === 1);
const nextDisabled = computed(() => busy.value || page.value * 20 >= total.value);
const liveness = useRequestLiveness(() => [identity.value]);
function reset() { liveness.invalidate(); rows.value = []; total.value = 0; page.value = 1; busy.value = false; }
function failure(cause: unknown) {
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) reset();
  error.value = repairProblem(cause);
}
async function load() {
  if (busy.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await repairRequest<{ items: Repair[]; total: number }>(`?page=${page.value}&pageSize=20`);
    if (liveness.isCurrent(turn)) { rows.value = result.items; total.value = result.total; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function paginate(delta: number) { if (busy.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= total.value) return; page.value += delta; await load(); }
watch(identity, () => { reset(); error.value = ""; void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { reset(); });
</script>
<template>
  <AppChrome>
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="load">刷新并核对当前结果</button>
      <router-link class="fams-button" data-kind="primary" data-size="sm" to="/repairs/new">新建报修</router-link>
    </template>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <section class="fams-panel fams-stack">
      <div class="fams-panel-head"><h2 class="fams-panel-title">报修进度</h2></div>
      <ul class="fams-timeline"><li v-for="row in rows" :key="row.id"><router-link class="fams-button" data-kind="link" data-size="sm" :to="`/repairs/${row.id}`">{{ row.source.assetCode }} · {{ row.fault }} · {{ repairStates[row.status] || '待核实' }}</router-link></li></ul>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="paginate(-1)">上一页报修</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="paginate(1)">下一页报修</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
