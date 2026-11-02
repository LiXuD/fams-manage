<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import RepairReturnSourcePanel from "./RepairReturnSourcePanel.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, repairProblem, repairRequest, type Repair, type RepairAsset, type RepairMutation } from "@/repair/api";
const session = useSessionStore(), router = useRouter();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const assets = ref<RepairAsset[]>([]), assetTotal = shallowRef(0), assetPage = shallowRef(1), assetId = shallowRef("");
const fault = shallowRef(""), impact = ref("MEDIUM"), occurredAt = shallowRef("");
const pending = ref<RepairMutation | null>(null), confirmation = ref<RepairMutation | null>(null);
const busy = shallowRef(false), error = shallowRef("");
const sourceLocked = shallowRef(false);
const locked = computed(() => busy.value || !!pending.value || !!confirmation.value || sourceLocked.value);
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && generation === epoch && identity.value === owner;
function reset() {
  generation++; assets.value = []; assetTotal.value = 0; assetPage.value = 1; assetId.value = ""; fault.value = ""; occurredAt.value = "";
  pending.value = null; confirmation.value = null; busy.value = false;
}
function failure(cause: unknown) {
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) reset();
  error.value = repairProblem(cause);
}
async function loadAssets(delta = 0) {
  if (locked.value) return; const next = assetPage.value + delta;
  if (next < 1 || delta > 0 && (next - 1) * 20 >= assetTotal.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { const result = await repairRequest<{ items: RepairAsset[]; total: number }>(`/eligible?page=${next}&pageSize=20`);
    if (current(epoch, owner)) { assets.value = result.items; assetTotal.value = result.total; assetPage.value = next; assetId.value = ""; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function prepare() {
  if (locked.value) return;
  error.value = "";
  const asset = assets.value.find(value => value.id === assetId.value), date = new Date(occurredAt.value);
  if (!asset || fault.value.trim().length < 3 || !Number.isFinite(date.getTime())) { error.value = "请选择资产并填写实际故障和发生时间。"; return; }
  confirmation.value = { path: "", key: crypto.randomUUID(), version: 0,
    body: { assetId: asset.id, assetVersion: asset.version, fault: fault.value.trim(), impact: impact.value, occurredAt: date.toISOString() } };
}
async function perform() {
  if (busy.value || !pending.value) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const result = await repairRequest<Repair & { repairId?: string }>(operation.path, operation);
    const id = result.repairId ?? result.id;
    if (current(epoch, owner)) { pending.value = null; await router.push(`/repairs/${encodeURIComponent(id)}?saved=1`); }
  } catch (cause) {
    if (current(epoch, owner)) { if (cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); }
  } finally { if (current(epoch, owner)) busy.value = false; }
}
async function confirm() { if (!confirmation.value || busy.value) return; pending.value = confirmation.value; confirmation.value = null; await perform(); }
watch(identity, () => { reset(); error.value = ""; }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; reset(); });
</script>
<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/repairs">返回报修列表</router-link>
    </template>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="perform">重试原报修操作</button>
    <div v-if="confirmation" class="fams-card fams-stack" role="group" aria-label="确认本次报修办理">
      <p>请核对本次资产、实际依据及处理范围。批准不会自动领取，原地关闭须确认已恢复且实物从未离开原保管人。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="confirm">确认保存本次办理</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="confirmation = null">返回修改</button>
      </div>
    </div>
    <RepairReturnSourcePanel v-if="session.user?.roles.includes('ASSET_ADMIN')" :blocked="busy || !!pending || !!confirmation" @created="router.push(`/repairs/${encodeURIComponent($event.id)}?saved=1`)" @busy-change="sourceLocked=$event" />
    <section class="fams-panel fams-stack">
      <div class="fams-panel-head"><h2 class="fams-panel-title">提交报修</h2></div>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="locked" @click="loadAssets()">查找可报修资产</button>
      <div class="fams-selection-group">
        <label class="fams-selection" v-for="asset in assets" :key="asset.id"><input v-model="assetId" type="radio" :value="asset.id" :disabled="locked">{{ asset.assetCode }} · {{ asset.name }} · {{ asset.locationName || '位置待核实' }}</label>
      </div>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ assetTotal }} 项</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || assetPage === 1" @click="loadAssets(-1)">上一页可报修资产</button>
          <span class="fams-pagination-page">第 {{ assetPage }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || assetPage * 20 >= assetTotal" @click="loadAssets(1)">下一页可报修资产</button>
        </div>
      </div>
      <fieldset class="fams-card fams-stack" :disabled="locked">
        <div class="fams-field-grid">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">实际故障</span><textarea class="fams-control" v-model="fault" maxlength="2000" /></label>
          <label class="fams-field"><span class="fams-field-label">影响程度</span><select class="fams-control" v-model="impact"><option value="LOW">轻微影响</option><option value="MEDIUM">部分影响使用</option><option value="HIGH">无法正常使用</option></select></label>
          <label class="fams-field"><span class="fams-field-label">实际发生时间</span><input class="fams-control" v-model="occurredAt" type="datetime-local"></label>
        </div>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare()">核对提交报修</button></div>
      </fieldset>
    </section>
  </AppChrome>
</template>
