<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

const kinds = { APPROVAL: "管理员审批", CLAIM: "独占领取", FIRST_EXECUTION: "首次实物验收", REASSIGNMENT: "受控转派" };
const statuses: Record<string, string> = {
  APPROVED: "已批准", REJECTED: "已驳回", CLAIMED: "已领取", ACCEPTED: "已验收", REASSIGNED: "已转派",
};
interface Row {
  factId: string;
  receiptNo: string;
  receiptId: string;
  processorAccountId: string | null;
  status: string;
  startedAt: string | null;
  finishedAt: string;
  elapsedSeconds: number | null;
}
interface Page { items: Row[]; nextBefore: string | null }

const kind = shallowRef<keyof typeof kinds>("APPROVAL");
const receiptId = shallowRef("");
const status = shallowRef("");
const options = computed(() => kind.value === "APPROVAL" ? ["APPROVED", "REJECTED"] : []);
const hasOptions = computed(() => options.value.length > 0);
const applied = shallowRef("");
const page = ref<Page>();
const busy = shallowRef(false);
const error = shallowRef("");
const query = computed(() => {
  const params = new URLSearchParams({ kind: kind.value });
  if (receiptId.value) params.set("receiptId", receiptId.value);
  if (status.value) params.set("status", status.value);
  return params.toString();
});
const visible = computed(() => query.value === applied.value ? page.value : undefined);
const csvHref = computed(() => `/api/v1/operations-reports/inbound-timing.csv?${applied.value}`);
const hasNoRows = computed(() => !visible.value?.items.length);

const liveness = useRequestLiveness();

async function load(before?: string) {
  const turn = liveness.begin();
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  page.value = undefined;
  error.value = "";
  busy.value = true;
  if (before) params.set("before", before);
  try {
    const fetched = await reportRequest<Page>(`/operations-reports/inbound-timing?${params}`);
    if (!liveness.isCurrent(turn)) return;
    page.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取入库时效报表。请检查筛选条件和当前权限。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-panel fams-stack">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">入库审批与执行时效</h3>
    </div>
    <form class="fams-field-row" @submit.prevent="load()">
      <div class="fams-field">
        <label for="timing-kind">入库办理阶段</label>
        <select id="timing-kind" class="fams-control" v-model="kind" @change="status = ''">
          <option v-for="(label, code) in kinds" :key="code" :value="code">{{ label }}</option>
        </select>
      </div>
      <div class="fams-field">
        <label for="timing-receipt">入库单 ID</label>
        <input id="timing-receipt" class="fams-control" v-model.trim="receiptId" inputmode="numeric" pattern="[1-9][0-9]*">
      </div>
      <div v-if="hasOptions" class="fams-field">
        <label for="timing-status">审批结果</label>
        <select id="timing-status" class="fams-control" v-model="status">
          <option value="">全部</option>
          <option v-for="code in options" :key="code" :value="code">{{ statuses[code] }}</option>
        </select>
      </div>
      <button class="fams-button" data-kind="secondary" type="submit" :disabled="busy">查询入库时效</button>
    </form>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询</p>
    <div v-if="visible">
      <a class="fams-button" data-kind="link" :href="csvHref">下载入库时效 CSV</a>
      <p v-if="hasNoRows">没有符合条件的记录。</p>
      <div v-else class="fams-table-scroll">
        <table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">入库单</th><th scope="col">经办账号 ID</th><th scope="col">结果</th>
          <th scope="col">开始时间</th><th scope="col">完成时间</th><th scope="col">耗时（秒）</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.factId">
            <th scope="row" data-column-kind="identity"><router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'inbound', query: { receiptId: row.receiptId } }">{{ row.receiptNo }}</router-link></th>
            <td>{{ row.processorAccountId ?? "—" }}</td><td>{{ statuses[row.status] ?? row.status }}</td>
            <td>{{ row.startedAt ?? "—" }}</td><td>{{ row.finishedAt }}</td><td>{{ row.elapsedSeconds ?? "—" }}</td>
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
