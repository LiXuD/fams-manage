<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { lifecycleLabels } from "@/features/asset/api";
import { businessMessage, businessRequest } from "@/api/business";
import { transferLabels as labels } from "@/features/transfer/api";
import type { BusinessMutation } from "@/api/business";
import type { Transfer, TransferAsset, TransferChoice, TransferReceipt } from "@fams/api-types/transfer";

const session = useSessionStore();
const rows = ref<Transfer[]>([]);
const receipts = ref<TransferReceipt[]>([]), receipt = ref<TransferReceipt | null>(null);
const reason = shallowRef(""), code = shallowRef("");
const personId = shallowRef(""), personAssets = ref<TransferAsset[]>([]), personNotice = shallowRef("");
const personnelChoices = ref<TransferChoice[]>([]);
const personTasks = shallowRef("");
const busy = shallowRef(false), error = shallowRef(""), notice = shallowRef(""), retryable = shallowRef(false), page = shallowRef(1), total = shallowRef(0), checked = shallowRef(false);
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const prevDisabled = computed(() => busy.value || retryable.value || page.value === 1);
const nextDisabled = computed(() => busy.value || retryable.value || page.value * 20 >= total.value);
let pending: BusinessMutation | null = null, grant: string | null = null;
const liveness = useRequestLiveness(() => [page.value]);

async function catalog(kind: string, org?: string) {
  const result: TransferChoice[] = []; let p = 1, part: TransferChoice[];
  do { part = (await businessRequest<{ items: TransferChoice[] }>(`/transfers/catalog/${kind}?page=${p++}&pageSize=200${org ? `&organizationId=${org}` : ""}`)).items; result.push(...part); }
  while (part.length === 200);
  return result;
}
async function load() {
  const turn = liveness.begin();
  const [list, waiting] = await Promise.all([
    businessRequest<{ items: Transfer[]; total: number }>(`/transfers?page=${page.value}&pageSize=20`),
    businessRequest<{ items: TransferReceipt[] }>("/transfers/receipts/pending")]);
  if (liveness.isCurrent(turn)) { rows.value = list.items; total.value = list.total; receipts.value = waiting.items; }
}
async function refresh() {
  if (busy.value) return; busy.value = true; error.value = "";
  try { await load(); pending = null; grant = null; receipt.value = null; retryable.value = false; }
  catch (e) { error.value = businessMessage(e); } finally { busy.value = false; }
}
async function mutate(path: string, version: number, body?: unknown) {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "请先重试上次操作或刷新核对。"; return; }
  pending ??= { path, version, key: crypto.randomUUID(), body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    await businessRequest<{ id: string }>(pending.path, pending);
    const action = pending.path; pending = null; retryable.value = false; notice.value = "操作已保存。";
    if (action.includes("/receipts/")) { receipt.value = null; grant = null; }
    await load();
  } catch (e) { error.value = businessMessage(e); retryable.value = pending !== null; }
  finally { busy.value = false; }
}
function retry() { const operation = pending; if (operation) void mutate(operation.path, operation.version); }
async function openReceipt(row: TransferReceipt) {
  if (busy.value || pending) return; busy.value = true; error.value = ""; grant = null;
  const turn = liveness.begin();
  try { const credential = await businessRequest<{ grant: string; requestVersion: number }>(`/transfers/receipts/${row.id}/grant`, { path: "", version: 0, key: crypto.randomUUID() });
    if (liveness.isCurrent(turn)) {
      grant = credential.grant; receipt.value = { ...row, requestVersion: credential.requestVersion }; code.value = ""; checked.value = false;
    }
  } catch (e) { error.value = businessMessage(e); } finally { busy.value = false; }
}
function receive(refuse: boolean) {
  if (!receipt.value || !grant || !checked.value || !code.value.trim() || refuse && !reason.value.trim()) { error.value = "请核对实物、编码及不符原因。"; return; }
  void mutate(`/transfers/receipts/${receipt.value.id}/${refuse ? "refuse" : "receive"}`, receipt.value.requestVersion,
    { grant, observedCode: code.value.trim(), physicalChecked: true, reason: reason.value.trim() });
}
async function loadPersonnelChoices() {
  if (!admin.value || busy.value) return;
  busy.value = true;
  const turn = liveness.begin();
  try { const result = await catalog("personnel"); if (liveness.isCurrent(turn)) personnelChoices.value = result; }
  catch (e) { error.value = businessMessage(e); } finally { busy.value = false; }
}
async function personnel() {
  if (!personId.value || busy.value) return; busy.value = true; error.value = "";
  const turn = liveness.begin();
  try { const value = await businessRequest<{ employee: { name: string; status: string }; assets: TransferAsset[]; pendingReceipts: unknown[]; processingTransfers: unknown[]; openLoans: unknown[]; openReturns: unknown[]; openRequisitions: unknown[] }>(`/transfers/personnel-handover?employeeId=${personId.value}`);
    if (liveness.isCurrent(turn)) {
      personAssets.value = value.assets; personNotice.value = `${value.employee.name} · ${value.employee.status === "ACTIVE" ? "有效" : "已停用"}；未交接前保留原责任。`;
      personTasks.value = `待接收调拨 ${value.pendingReceipts.length}，处理中调拨 ${value.processingTransfers.length}，未关闭借用 ${value.openLoans.length}，待归还 ${value.openReturns.length}，领用/借用待办 ${value.openRequisitions.length}。请通过对应业务完成交接；停用不会自动关闭。`;
    }
  } catch (e) { error.value = businessMessage(e); } finally { busy.value = false; }
}
onMounted(refresh);
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="primary" data-size="sm" to="/transfers/new">新建调拨</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>
    <section v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></section>

    <section class="fams-panel fams-stack" aria-labelledby="request-title">
      <div class="fams-panel-head">
        <div>
          <h2 id="request-title" class="fams-panel-title">申请与执行</h2>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="refresh">刷新</button>
          <button v-if="retryable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="retry">重试上次操作</button>
        </div>
      </div>
      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">调拨单</th><th scope="col">状态</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.id }}</th>
              <td><span class="fams-status">{{ labels[row.requestStatus] }}</span></td>
              <td data-column-kind="action"><router-link class="fams-button" data-kind="link" data-size="sm"
                :to="`/transfers/${row.id}`">查看 / 执行</router-link></td>
            </tr>
            <tr v-if="!rows.length" class="fams-table-empty"><td colspan="3"><strong>当前没有调拨申请</strong>从上方新建调拨开始。</td></tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled"
            @click="page--; refresh()">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled"
            @click="page++; refresh()">下一页</button>
        </div>
      </div>
    </section>

    <section class="fams-panel fams-stack" aria-labelledby="receipt-title">
      <div class="fams-panel-head">
        <h2 id="receipt-title" class="fams-panel-title">本人待接收</h2>
        <span class="fams-status" data-tone="info">{{ receipts.length }} 项</span>
      </div>
      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">资产</th><th scope="col">名称</th><th scope="col">实际去向</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in receipts" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.assetCode }}</th>
              <td>{{ row.name }}</td><td>{{ row.destination }}</td>
              <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button"
                :disabled="busy || retryable" @click="openReceipt(row)">核对并接收</button></td>
            </tr>
            <tr v-if="!receipts.length" class="fams-table-empty"><td colspan="4"><strong>没有待接收的调拨</strong>库管记录实物交出后会出现在这里。</td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="receipt" class="fams-card fams-stack">
        <h3>{{ receipt.assetCode }} · {{ receipt.name }}</h3>
        <p>请核对实际收到的实物。</p>
        <label class="fams-field"><span class="fams-field-label">实物编码</span>
          <input class="fams-control" v-model="code" :disabled="busy || retryable" /></label>
        <label class="fams-field">
          <span class="fams-field-label">申请 / 决定原因</span>
          <textarea class="fams-control" v-model="reason" maxlength="1000" :disabled="busy || retryable" />
        </label>
        <label class="fams-selection"><input v-model="checked" type="checkbox" :disabled="busy || retryable" />已实际收到并核对</label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || retryable" @click="receive(false)">确认调拨接收</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="busy || retryable" @click="receive(true)">反馈实物不符</button>
        </div>
      </div>
    </section>

    <details v-if="admin" class="fams-panel fams-stack">
      <summary class="fams-panel-title">人员责任交接清单</summary>
      <div class="fams-stack">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="loadPersonnelChoices">加载原责任人员</button>
        <label class="fams-field"><span class="fams-field-label">原责任人员</span>
          <select class="fams-control" v-model="personId" :disabled="busy"><option value="">请选择（含已停用人员）</option><option v-for="person in personnelChoices" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="personnel">查询未交接责任</button>
        </div>
        <p>{{ personNotice }}</p>
        <p class="fams-panel-subtitle">{{ personTasks }}</p>
        <p v-for="asset in personAssets" :key="asset.id">{{ asset.assetCode }} · {{ asset.name }} · {{ lifecycleLabels[asset.lifecycleStatus] }}；在用资产通过上方调拨交接，借出资产通过归还关闭责任。</p>
      </div>
    </details>
  </AppChrome>
</template>
