<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

const states = { PENDING_DISPOSITION: "待处置", OVERDUE: "已超期", DISPOSED: "已处置",
  LOSS_WRITTEN_OFF: "遗失核销", REVERSED: "冲正撤销" } as const;
type State = keyof typeof states;
interface Row {
  requestId: string;
  assetCode: string;
  processor: string;
  state: State;
  dueDate: string | null;
  dispositionRecordedAt: string | null;
}
interface Page { businessDate: string; counts: { state: State; count: number }[]; items: Row[]; nextBefore: string | null }
interface CountRow { value: string; label: string; countText: string }
interface DispositionRow extends Row { processorText: string; stateText: string; dueDateText: string; recordedAtText: string }

const processor = shallowRef("");
const state = shallowRef<State | "">("");
const query = computed(() => {
  const params = new URLSearchParams();
  if (processor.value) params.set("processorId", processor.value);
  if (state.value) params.set("state", state.value);
  return params.toString();
});
const applied = shallowRef("");
const result = ref<Page>();
const visible = computed(() => applied.value === query.value ? result.value : undefined);
const busy = shallowRef(false);
const error = shallowRef("");
const csvHref = computed(() => `/api/v1/operations-reports/scrap-dispositions.csv?${applied.value}`);
const hasNoRows = computed(() => visible.value?.items.length === 0);
const businessDateText = computed(() => `企业业务日期 ${visible.value?.businessDate ?? ""}`);
const countRows = computed<CountRow[]>(() => Object.entries(states).map(([value, label]) => ({
  value, label,
  countText: `${visible.value?.counts.find((row) => row.state === value)?.count ?? 0} 单\u3000`,
})));
const dispositionRows = computed<DispositionRow[]>(() => (visible.value?.items ?? []).map((row) => ({
  ...row,
  processorText: row.processor ?? "—",
  stateText: states[row.state],
  dueDateText: row.dueDate ?? "—",
  recordedAtText: row.dispositionRecordedAt ?? "—",
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
    const fetched = await reportRequest<Page>(`/operations-reports/scrap-dispositions?${params}`);
    if (!liveness.isCurrent(turn)) return;
    result.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取报废处置与超期报表。请检查筛选条件和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">报废处置与超期</h3>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="disposition-processor">处置经办账号 ID</label>
        <input id="disposition-processor" class="fams-control" v-model.trim="processor" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div class="fams-field">
        <label for="disposition-state">状态</label>
        <select id="disposition-state" class="fams-control" v-model="state">
          <option value="">全部</option>
          <option v-for="(label, value) in states" :key="value" :value="value">{{ label }}</option>
        </select>
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询报废处置与超期</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询报废处置与超期</p>
    <div v-if="visible">
      <p>{{ businessDateText }}</p>
      <p>
        <span v-for="row in countRows" :key="row.value">
          {{ row.label }} {{ row.countText }}
        </span>
      </p>
      <a class="fams-button" data-kind="link" :href="csvHref">下载报废处置与超期 CSV</a>
      <p v-if="hasNoRows">没有符合条件的处置记录。</p>
      <div v-else class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">资产与报废详情</th><th scope="col">处置经办人</th><th scope="col">状态</th>
          <th scope="col">处置截止日</th><th scope="col">处置登记时间</th></tr></thead>
        <tbody>
          <tr v-for="row in dispositionRows" :key="row.requestId">
            <td><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'scrap-requests', params: { id: row.requestId } }">{{ row.assetCode }}</router-link></td>
            <td>{{ row.processorText }}</td><td><span class="fams-status">{{ row.stateText }}</span></td><td>{{ row.dueDateText }}</td>
            <td>{{ row.recordedAtText }}</td>
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
