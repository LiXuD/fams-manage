<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { requisitionLabels as labels, requisitionMessage, requisitionRequest } from "@/requisition/api";
import type { RequisitionPage, RequisitionSummary } from "../../../../packages/api-types/src/requisition";

const rows = ref<RequisitionSummary[]>([]);
const busy = ref(false); const error = ref(""); const page = ref(1); const total = ref(0);
const status = ref("");

async function load() {
  const result = await requisitionRequest<RequisitionPage>(`?page=${page.value}&pageSize=20${status.value ? "&status=" + status.value : ""}`);
  rows.value = result.items; total.value = result.total;
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await load(); }
  catch (e) { error.value = requisitionMessage(e); } finally { busy.value = false; }
}
onMounted(refresh);
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="primary" to="/requisitions/new">新建申请</router-link>
    </template>

    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section class="fams-panel fams-stack" aria-label="申请列表">
      <div class="fams-field-row">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="refresh">刷新结果</button>
        <label class="fams-field">
          <span class="fams-field-label">状态筛选</span>
          <select class="fams-control" v-model="status" :disabled="busy" @change="page = 1; refresh()">
            <option value="">全部</option><option v-for="(label, code) in labels" :key="code" :value="code">{{ label }}</option></select>
        </label>
      </div>

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">申请编号</th><th scope="col">发起人</th><th scope="col">状态</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.requestNo }}</th>
              <td>{{ row.creatorUsernameSnapshot }}</td>
              <td><span class="fams-status">{{ labels[row.requestStatus] }}</span></td>
              <td data-column-kind="action"><router-link class="fams-button" data-kind="link" data-size="sm"
                :to="`/requisitions/${row.id}`">查看申请</router-link></td>
            </tr>
            <tr v-if="!rows.length && !busy" class="fams-table-empty">
              <td colspan="4"><strong>当前筛选没有申请</strong>换个状态条件，或从右上角新建一份领用、借用申请。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || page === 1"
            @click="page--; refresh()">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || page * 20 >= total"
            @click="page++; refresh()">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
