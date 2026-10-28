<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { loanMessage, loanRequest } from "@/loan/api";
import type { LoanMutation } from "@/loan/api";
import type { BusinessPage, ReturnAssetChoice } from "../../../../packages/api-types/src/loan";

const router = useRouter();
const returnAssets = ref<ReturnAssetChoice[]>([]);
const assetId = ref(""); const assetVersion = ref(0); const reason = ref("");
const busy = ref(false); const error = ref(""); const retryable = ref(false);
let pending: LoanMutation | null = null;

function chooseReturnAsset() {
  const asset = returnAssets.value.find(row => row.id === assetId.value);
  if (asset) assetVersion.value = asset.version;
}
function proposeReturn() {
  if (!assetId.value.trim() || !reason.value.trim()) { error.value = "请选择借用或填写退库资产及原因。"; return; }
  void send("/asset-returns", 0, { assetId: assetId.value.trim(), assetVersion: assetVersion.value, reason: reason.value.trim() });
}
async function send(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "请先重试上次操作或刷新查询。"; return; }
  pending ??= { path, version, key: crypto.randomUUID(), body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const value = await loanRequest<{ id: string }>(pending.path, pending);
    pending = null; retryable.value = false;
    await router.push(`/asset-returns/${encodeURIComponent(value.id)}?saved=1`);
  } catch (e) { error.value = loanMessage(e); retryable.value = pending !== null; }
  finally { busy.value = false; }
}
function retry() { if (pending) void send(pending.path, pending.version); }
onMounted(async () => {
  busy.value = true;
  try {
    const assetPage = await loanRequest<BusinessPage<ReturnAssetChoice>>("/asset-returns/eligible-assets?page=1&pageSize=20");
    returnAssets.value = assetPage.items;
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/asset-returns">返回退还列表</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section class="fams-panel fams-stack" aria-label="提请退库">
      <div class="fams-panel-head">
        <h2 class="fams-panel-title">在用资产退库 / 停用人员代办</h2>
      </div>
      <label class="fams-field"><span class="fams-field-label">退库资产</span>
        <select class="fams-control" v-model="assetId" :disabled="busy || retryable" @change="chooseReturnAsset">
          <option value="">请选择当前可退库资产</option><option v-for="asset in returnAssets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option>
        </select></label>
      <label class="fams-field">
        <span class="fams-field-label">操作原因 / 验收说明</span>
        <textarea class="fams-control" v-model="reason" :disabled="busy || retryable" maxlength="1000" />
      </label>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || retryable" @click="proposeReturn">提请退库</button>
        <button v-if="retryable" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="retry">重试上次操作</button>
      </div>
    </section>
  </AppChrome>
</template>
