<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

const states = { SEARCHING: "查找中", CONFIRMED_LOST: "已确认遗失", RECOVERED: "已找回" } as const;
type State = keyof typeof states;
interface Row {
  reportId: string;
  assetCode: string;
  organization: string | null;
  state: State;
  reportedAt: string;
  confirmedLostAt: string | null;
  recoveredAt: string | null;
}
interface Page { counts: { state: State; count: number }[]; items: Row[]; nextBefore: string | null }
interface CountRow { value: string; label: string; countText: string }
interface LossRow extends Row { organizationText: string; stateText: string; confirmedLostText: string; recoveredText: string }

const organization = shallowRef("");
const state = shallowRef<State | "">("");
const query = computed(() => {
  const params = new URLSearchParams();
  if (organization.value) params.set("organizationId", organization.value);
  if (state.value) params.set("state", state.value);
  return params.toString();
});
const applied = shallowRef("");
const result = ref<Page>();
const visible = computed(() => applied.value === query.value ? result.value : undefined);
const busy = shallowRef(false);
const error = shallowRef("");
const csvHref = computed(() => `/api/v1/operations-reports/loss.csv?${applied.value}`);
const hasNoRows = computed(() => visible.value?.items.length === 0);
const countRows = computed<CountRow[]>(() => Object.entries(states).map(([value, label]) => ({
  value, label,
  countText: `${visible.value?.counts.find((row) => row.state === value)?.count ?? 0} 单\u3000`,
})));
const lossRows = computed<LossRow[]>(() => (visible.value?.items ?? []).map((row) => ({
  ...row,
  organizationText: row.organization ?? "—",
  stateText: states[row.state],
  confirmedLostText: row.confirmedLostAt ?? "—",
  recoveredText: row.recoveredAt ?? "—",
})));

const liveness = useRequestLiveness();

async function load(before?: string) {
  const turn = liveness.begin();
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  result.value = undefined;
  busy.value = true;
  error.value = "";
  if (before) params.set("before", before);
  try {
    const fetched = await reportRequest<Page>(`/operations-reports/loss?${params}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取报失与找回报表。请检查筛选条件和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">报失与找回</h3>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="loss-organization">使用部门 ID</label>
        <input id="loss-organization" class="fams-control" v-model.trim="organization" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="loss-state">状态</label>
        <select id="loss-state" class="fams-control" v-model="state">
          <option value="">全部</option>
          <option v-for="(label, value) in states" :key="value" :value="value">{{ label }}</option>
        </select>
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询报失与找回</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询报失与找回</p>
    <div v-if="visible">
      <p>
        <span v-for="row in countRows" :key="row.value">
          {{ row.label }} {{ row.countText }}
        </span>
      </p>
      <a class="fams-button" data-kind="link" :href="csvHref">下载报失与找回 CSV</a>
      <p v-if="hasNoRows">没有符合条件的报失单。</p>
      <div v-else class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">资产与报失详情</th><th scope="col">使用部门</th><th scope="col">状态</th>
          <th scope="col">报失时间</th><th scope="col">确认遗失时间</th><th scope="col">找回时间</th></tr></thead>
        <tbody>
          <tr v-for="row in lossRows" :key="row.reportId">
            <td><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'loss-reports', params: { id: row.reportId } }">{{ row.assetCode }}</router-link></td>
            <td>{{ row.organizationText }}</td><td><span class="fams-status">{{ row.stateText }}</span></td><td>{{ row.reportedAt }}</td>
            <td>{{ row.confirmedLostText }}</td><td>{{ row.recoveredText }}</td>
          </tr>
        </tbody>
      </table>
      </div>
      <div class="fams-pagination-actions">
        <button v-if="visible.nextBefore" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
      </div>
    </div>
  </section>
</template>
