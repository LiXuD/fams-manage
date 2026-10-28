<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { loanLabels as labels, loanMessage, loanRequest } from "@/loan/api";
import type { AssetReturn, BusinessPage } from "../../../../packages/api-types/src/loan";

const returns = ref<AssetReturn[]>([]);
const busy = ref(false); const error = ref(""); const page = ref(1); const total = ref(0);

async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try {
    const result = await loanRequest<BusinessPage<AssetReturn>>(`/asset-returns?page=${page.value}&pageSize=20`);
    returns.value = result.items; total.value = result.total;
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
onMounted(refresh);
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="primary" to="/asset-returns/new">提请退库</router-link>
    </template>

    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section class="fams-panel fams-stack" aria-label="退还与退库">
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新</button>
      </div>

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">退还单</th><th scope="col">状态</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in returns" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.id }}</th>
              <td><span class="fams-status">{{ labels[row.returnStatus] }}</span></td>
              <td data-column-kind="action"><router-link class="fams-button" data-kind="link" data-size="sm"
                :to="`/asset-returns/${row.id}`">查看 / 审批 / 验收</router-link></td>
            </tr>
            <tr v-if="!returns.length" class="fams-table-empty"><td colspan="3"><strong>当前没有退还单</strong>借用归还或在用资产退库提交后会出现在这里。</td></tr>
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
