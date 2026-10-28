<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { listReceipts, type InboundReceipt } from "@/inbound/api";

const session = useSessionStore();
const warehouseRole = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;
const receipts = ref<InboundReceipt[]>([]);
const error = ref("");

async function load(): Promise<void> {
  try {
    const result = await listReceipts();
    receipts.value = result.items;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "入库单加载失败"; }
}

onMounted(load);
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link v-if="warehouseRole" class="fams-button" data-kind="primary" to="/inbound/new">建立入库单</router-link>
    </template>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <section class="fams-panel fams-stack" aria-label="入库单列表">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">入库单</h2>
        </div>
      </div>
      <div class="fams-table-scroll"><table class="fams-table">
        <thead><tr><th scope="col" data-column-kind="identity">单号</th><th scope="col">业务日期</th><th scope="col">状态</th>
          <th scope="col" data-column-kind="numeric">资产数</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
        <tbody><tr v-for="value in receipts" :key="value.id">
          <th scope="row" data-column-kind="identity">{{ value.receiptNumber }}</th><td>{{ value.businessDate }}</td>
          <td><span class="fams-status">{{ value.status }}</span></td><td data-column-kind="numeric">{{ value.items.length }}</td>
          <td data-column-kind="action"><router-link class="fams-button" data-kind="link" data-size="sm"
            :to="`/inbound/${value.id}`">详情</router-link></td>
        </tr><tr v-if="!receipts.length" class="fams-table-empty"><td colspan="5"><strong>暂无入库单</strong>由库管新建入库草稿后在此查看。</td></tr></tbody>
      </table></div>
    </section>
  </AppChrome>
</template>
