<script setup lang="ts">
import { computed, ref } from "vue";

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

const organization = ref("");
const state = ref<State | "">("");
const query = computed(() => {
  const params = new URLSearchParams();
  if (organization.value) params.set("organizationId", organization.value);
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
    const response = await fetch(`/api/v1/operations-reports/loss?${params}`, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    result.value = await response.json() as Page;
  } catch {
    error.value = "无法读取报失与找回报表。请检查筛选条件和当前权限。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>报失与找回</h3>
    <form class="actions" @submit.prevent="load()">
      <label>使用部门 ID <input v-model.trim="organization" inputmode="numeric" pattern="[1-9][0-9]*"></label>
      <label>状态
        <select v-model="state">
          <option value="">全部</option>
          <option v-for="(label, value) in states" :key="value" :value="value">{{ label }}</option>
        </select>
      </label>
      <button :disabled="busy" type="submit">查询报失与找回</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询报失与找回</p>
    <div v-if="visible">
      <p>
        <span v-for="(label, value) in states" :key="value">
          {{ label }} {{ visible.counts.find(row => row.state === value)?.count ?? 0 }} 单　
        </span>
      </p>
      <a :href="`/api/v1/operations-reports/loss.csv?${applied}`">下载报失与找回 CSV</a>
      <p v-if="visible.items.length === 0">没有符合条件的报失单。</p>
      <table v-else>
        <thead><tr><th scope="col">资产与报失详情</th><th scope="col">使用部门</th><th scope="col">状态</th>
          <th scope="col">报失时间</th><th scope="col">确认遗失时间</th><th scope="col">找回时间</th></tr></thead>
        <tbody>
          <tr v-for="row in visible.items" :key="row.reportId">
            <td><router-link :to="{ name: 'loss-reports', params: { id: row.reportId } }">{{ row.assetCode }}</router-link></td>
            <td>{{ row.organization ?? "—" }}</td><td>{{ states[row.state] }}</td><td>{{ row.reportedAt }}</td>
            <td>{{ row.confirmedLostAt ?? "—" }}</td><td>{{ row.recoveredAt ?? "—" }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="visible.nextBefore" type="button" :disabled="busy" @click="load(visible.nextBefore)">下一页</button>
    </div>
  </section>
</template>
