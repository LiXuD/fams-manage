<script setup lang="ts">
import { computed, ref } from "vue";

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

const processor = ref("");
const state = ref<State | "">("");
const query = computed(() => {
  const params = new URLSearchParams();
  if (processor.value) params.set("processorId", processor.value);
  if (state.value) params.set("state", state.value);
  return params.toString();
});
const applied = ref("");
const result = ref<Page>();
const visible = computed(() => applied.value === query.value ? result.value : undefined);
const busy = ref(false);
const error = ref("");

async function load(before?: string) {
  if (busy.value) return;
  const params = new URLSearchParams(query.value);
  applied.value = params.toString();
  result.value = undefined;
  busy.value = true;
  error.value = "";
  if (before) params.set("before", before);
  try {
    const response = await fetch(`/api/v1/operations-reports/scrap-dispositions?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Page;
  } catch {
    error.value = "无法读取报废处置与超期报表。请检查筛选条件和当前权限。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>报废处置与超期</h3>
    <form class="actions" @submit.prevent="load()">
      <label>处置经办账号 ID <input v-model.trim="processor" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>状态
        <select v-model="state">
          <option value="">全部</option>
          <option v-for="(label, value) in states" :key="value" :value="value">{{ label }}</option>
        </select>
      </label>
      <button :disabled="busy" type="submit">查询报废处置与超期</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询报废处置与超期</p>
    <div v-if="visible">
      <p>企业业务日期 {{ visible.businessDate }}</p>
      <p>
        <span v-for="(label, value) in states" :key="value">
          {{ label }} {{ visible.counts.find(row => row.state === value)?.count ?? 0 }} 单　
        </span>
      </p>
      <a :href="`/api/v1/operations-reports/scrap-dispositions.csv?${applied}`">下载报废处置与超期 CSV</a>
      <p v-if="visible.items.length === 0">没有符合条件的处置记录。</p>
      <table v-else>
        <thead><tr><th scope="col">资产与报废详情</th><th scope="col">处置经办人</th><th scope="col">状态</th>
          <th scope="col">处置截止日</th><th scope="col">处置登记时间</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.requestId">
            <td><router-link :to="{ name: 'scrap-requests', params: { id: row.requestId } }">{{ row.assetCode }}</router-link></td>
            <td>{{ row.processor ?? "—" }}</td><td>{{ states[row.state] }}</td><td>{{ row.dueDate ?? "—" }}</td>
            <td>{{ row.dispositionRecordedAt ?? "—" }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
    </div>
  </section>
</template>
