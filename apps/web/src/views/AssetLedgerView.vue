<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { cardStageLabels, custodyLabel, lifecycleLabels, listAssets, type AssetCard } from "@/asset/api";

const session = useSessionStore();
const rows = ref<AssetCard[]>([]);
const total = ref(0);
const page = ref(1);
const code = ref("");
const nameFilter = ref("");
const error = ref("");

const canWrite = session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false;

async function load(): Promise<void> {
  error.value = "";
  try {
    const result = await listAssets(page.value, code.value, nameFilter.value);
    rows.value = result.items;
    total.value = result.total;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "台账加载失败"; }
}

onMounted(load);
</script>

<template>
  <AppChrome>
    <template v-if="canWrite" #actions>
      <router-link class="fams-button" data-kind="primary" data-size="sm" to="/assets/new">单个建卡</router-link>
    </template>

    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section class="fams-panel fams-stack" aria-label="台账筛选与列表">
      <form class="fams-field-row" @submit.prevent="page = 1; load()">
        <div class="fams-field">
          <label for="ledger-code">资产编码</label>
          <input id="ledger-code" class="fams-control" v-model="code" placeholder="编码包含" />
        </div>
        <div class="fams-field">
          <label for="ledger-name">资产名称</label>
          <input id="ledger-name" class="fams-control" v-model="nameFilter" placeholder="名称包含" />
        </div>
        <button class="fams-button" data-kind="secondary" type="submit">查询</button>
      </form>

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead>
            <tr>
              <th scope="col" data-column-kind="identity">资产编码</th>
              <th scope="col">名称</th>
              <th scope="col">型号</th>
              <th scope="col">生命周期</th>
              <th scope="col">建卡阶段</th>
              <th scope="col">正式保管</th>
              <th scope="col">实物去向</th>
              <th scope="col" data-column-kind="action">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="asset in rows" :key="asset.id">
              <th scope="row" data-column-kind="identity">{{ asset.assetCode }}</th>
              <td>{{ asset.name }}</td>
              <td class="u-secondary">{{ asset.model ?? "—" }}</td>
              <td><span class="fams-status">{{ lifecycleLabels[asset.lifecycle] }}</span></td>
              <td><span class="fams-status">{{ cardStageLabels[asset.cardStage] }}</span></td>
              <td>{{ custodyLabel(asset) }}</td>
              <td>{{ asset.physicalDestination?.destination ?? "—" }}</td>
              <td data-column-kind="action">
                <router-link class="fams-button" data-kind="link" data-size="sm" :to="`/assets/${asset.id}`">详情</router-link>
              </td>
            </tr>
            <tr v-if="rows.length === 0" class="fams-table-empty">
              <td colspan="8"><strong>暂无符合条件的资产</strong>调整资产编码或名称条件后重新查询。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 项</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="page <= 1"
            @click="page--; load()">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="page * 20 >= total"
            @click="page++; load()">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
