<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import LossRecoveryPanel from "./LossRecoveryPanel.vue";
import LossProgressPanel from "./LossProgressPanel.vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { LossApiError, lossProblem, lossRequest, lossStates, type LossAsset, type LossIntent, type LossReport } from "@/loss/api";
const session = useSessionStore(), route = useRoute();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, route.params.id]));
const rows = ref<LossReport[]>([]), detail = ref<LossReport | null>(null), total = ref(0), page = ref(1);
const assets = ref<LossAsset[]>([]), assetId = ref(""), assetPage = ref(1), assetTotal = ref(0);
const lastSeenAt = ref(""), lastLocation = ref(""), circumstances = ref(""), reason = ref("");
const custodian = ref(""), searchedScope = ref(""), searchedAt = ref(""), nextReviewDate = ref("");
const file = ref<File | null>(null), pending = ref<LossIntent | null>(null), confirmation = ref<LossIntent | null>(null);
const busy = ref(false), error = ref(""), notice = ref("");
const preview = ref(""), previewName = ref("");
let download: AbortController | null = null;
const locked = computed(() => busy.value || !!pending.value || !!confirmation.value);
let generation = 0, live = true, readOwner = "";
const followUpOnly = ref(false);
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
function reset() {
  generation++; readOwner = ""; followUpOnly.value = false; clearPreview(); rows.value = []; detail.value = null; assets.value = []; assetId.value = ""; file.value = null;
  pending.value = null; confirmation.value = null; busy.value = false; total.value = 0; page.value = 1; assetPage.value = 1; assetTotal.value = 0;
  lastSeenAt.value = ""; lastLocation.value = ""; circumstances.value = ""; reason.value = "";
  custodian.value = ""; searchedScope.value = ""; searchedAt.value = ""; nextReviewDate.value = ""; error.value = ""; notice.value = "";
}
function failure(cause: unknown) { if (cause instanceof LossApiError && [401, 403, 404].includes(cause.status)) reset(); error.value = lossProblem(cause); }
async function load(id = detail.value?.id ?? String(route.params.id ?? ""), delta = 0) {
  if (busy.value || confirmation.value) return;
  const next = page.value + delta; if (next < 1 || delta > 0 && (next - 1) * 20 >= total.value) return;
  clearPreview();
  if (id !== detail.value?.id) {
    file.value = null; reason.value = ""; custodian.value = ""; searchedScope.value = ""; searchedAt.value = ""; nextReviewDate.value = "";
  }
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const result = await lossRequest<{ items: LossReport[]; total: number }>(`${followUpOnly.value ? "/follow-ups" : ""}?page=${next}&pageSize=20`);
    const selected = /^[1-9][0-9]{0,18}$/.test(id) ? await lossRequest<LossReport>(`/${id}`) : null;
    if (current(epoch, owner)) { readOwner = owner; rows.value = result.items; total.value = result.total; page.value = next; detail.value = selected; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function loadAssets(delta = 0) {
  if (locked.value) return; const next = assetPage.value + delta;
  if (next < 1 || delta > 0 && (next - 1) * 20 >= assetTotal.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true;
  try { const result = await lossRequest<{ items: LossAsset[]; total: number }>(`/eligible?page=${next}&pageSize=20`);
    if (current(epoch, owner)) { assets.value = result.items; assetTotal.value = result.total; assetPage.value = next; assetId.value = ""; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function prepareProgress(intent: LossIntent) {
  const row = detail.value;
  if (locked.value || readOwner !== identity.value || !row) return;
  const attempt = row.recoveries?.find(item => intent.path.startsWith(`/recoveries/${item.id}/`));
  if (attempt) {
    const action = ({decision:"decide",cancel:"cancel",claim:"claim",execute:"execute"} as Record<string,string>)[intent.path.slice(intent.path.lastIndexOf("/")+1)];
    if (!action || intent.version !== attempt.version || !attempt.actions[action]) return;
  } else {
    const action = ({[`/${row.id}/confirm-lost`]:"confirmLost",[`/${row.id}/follow-up`]:"followUp",[`/${row.id}/recover-original`]:"recoverOriginal",[`/${row.id}/recovery-requests`]:"requestRecovery"} as Record<string,string>)[intent.path];
    if (!action || intent.version !== row.version || !row.actions?.[action]) return;
  }
  confirmation.value = intent;
}
function switchMode(followups: boolean) { if (locked.value) return; followUpOnly.value = followups; page.value = 1; void load(""); }
function prepare(action: "submit" | "approve" | "reject" | "cancel" | "claim" | "photo" | "search") {
  if (locked.value) return; error.value = "";
  const row = detail.value; let path = "", body: unknown, upload: File | undefined;
  if (action === "submit") {
    const asset = assets.value.find(value => value.id === assetId.value), date = new Date(lastSeenAt.value);
    if (!asset || !Number.isFinite(date.getTime()) || !lastLocation.value.trim() || circumstances.value.trim().length < 10) { error.value = "请选择资产，填写最后见到时间、位置和至少10字情况。"; return; }
    body = { assetId: asset.id, assetVersion: asset.version, lastSeenAt: date.toISOString(), lastLocation: lastLocation.value.trim(), circumstances: circumstances.value.trim() };
  } else {
    if (!row) return; path = `/${row.id}`;
    if (["approve", "reject", "cancel"].includes(action)) {
      if (reason.value.trim().length < 3) { error.value = "请填写至少3字原因。"; return; }
      path += action === "cancel" ? "/cancel" : "/decision";
      body = action === "cancel" ? { reason: reason.value.trim() } : { result: action === "approve" ? "APPROVE" : "REJECT", reason: reason.value.trim() };
    } else if (action === "claim") path += "/claim";
    else if (action === "photo") {
      if (!file.value || !["image/png", "image/jpeg", "image/webp"].includes(file.value.type) || file.value.size > 10 * 1024 * 1024) { error.value = "请选择不超过10MB的PNG、JPEG或WEBP图片。"; return; }
      path += "/photos"; upload = file.value;
    } else {
      const date = new Date(searchedAt.value);
      if (custodian.value.trim().length < 10 || searchedScope.value.trim().length < 10 || !Number.isFinite(date.getTime()) || !/^\d{4}-\d{2}-\d{2}$/.test(nextReviewDate.value)) { error.value = "请完整填写保管人核实、查找范围、实际时间和下次复核日。"; return; }
      path += "/search";
      body = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion,
        custodianVerification: custodian.value.trim(), searchedScope: searchedScope.value.trim(), searchedAt: date.toISOString(), nextReviewDate: nextReviewDate.value };
    }
  }
  confirmation.value = Object.freeze({ path, key: crypto.randomUUID(), version: row?.version ?? 0, body, file: upload });
}
async function send() {
  if (busy.value || !pending.value) return;
  const intent = pending.value, owner = identity.value, epoch = ++generation; busy.value = true; error.value = "";
  let refresh = "";
  try {
    const result = await lossRequest<LossReport>(intent.path, intent);
    if (current(epoch, owner)) { pending.value = null; file.value = null; refresh = result.id; notice.value = "本次操作已记录。"; }
  } catch (cause) {
    if (current(epoch, owner)) {
      if (cause instanceof LossApiError && cause.status < 500) pending.value = null;
      failure(cause);
    }
  } finally { if (current(epoch, owner)) busy.value = false; }
  if (refresh && live && identity.value === owner) await load(refresh);
}
function confirm() { if (confirmation.value) { pending.value = confirmation.value; confirmation.value = null; void send(); } }
function clearPreview() { download?.abort(); download = null; if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ""; previewName.value = ""; }
async function viewPhoto(object: string, name: string) {
  if (locked.value || !detail.value) return;
  clearPreview(); const controller = new AbortController(); download = controller;
  const owner = identity.value, epoch = ++generation, id = detail.value.id; busy.value = true;
  try {
    const grant = await lossRequest<{ url: string }>(`/${id}/photos/${object}/download-grant`, { path: "", key: crypto.randomUUID(), version: detail.value.version });
    if (!current(epoch, owner)) return;
    const expected = `/api/v1/loss-reports/${id}/photos/${object}/content?grant=`;
    if (!grant.url.startsWith(expected) || grant.url.includes("#")) throw new Error("Invalid attachment grant");
    const response = await fetch(grant.url, { credentials: "include", signal: controller.signal });
    if (!response.ok) throw new LossApiError(response.status, "LOSS_PHOTO_READ_FAILED");
    const blob = await response.blob();
    if (!["image/png", "image/jpeg", "image/webp"].includes(blob.type)) throw new Error("Invalid image content");
    if (current(epoch, owner)) { preview.value = URL.createObjectURL(blob); previewName.value = name; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function chosen(event: Event) { if (!locked.value) file.value = (event.target as HTMLInputElement).files?.[0] ?? null; }
watch(identity, () => { reset(); void load(); }, { immediate: true });
onBeforeUnmount(() => { live = false; reset(); });
</script>

<template>
  <AppChrome><main class="loss-page">
    <header><h1>报失与查找</h1><p>报告找不到只冻结流转；批准核实后，由处理库管记录实际查找。原责任和借用继续保留。</p></header>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="confirmation" role="dialog" aria-label="确认报失操作"><p>{{ confirmation.summary ?? '请确认本次资产、情况、版本及证据，提交后保留操作履历。' }}</p><button @click="confirm">确认提交</button><button @click="confirmation = null">返回修改</button></div>
    <div v-if="pending"><p>{{ pending.summary ?? '操作结果待核对，当前输入已冻结。' }}</p><button :disabled="busy" @click="send">使用原请求重试</button><button :disabled="busy" @click="load()">只读核对最新结果</button></div>
    <button :disabled="busy || !!confirmation" @click="load()">刷新报失记录</button>
    <button :disabled="locked" @click="switchMode(false)">全部报失</button><button :disabled="locked" @click="switchMode(true)">遗失跟进清单</button>
    <section v-if="!followUpOnly" aria-label="发起报失"><h2>发起报失</h2><fieldset :disabled="locked">
      <button @click="loadAssets()">查询可报失资产</button><span>第{{ assetPage }}页，共{{ assetTotal }}项</span>
      <button :disabled="assetPage <= 1" @click="loadAssets(-1)">上一页资产</button><button :disabled="assetPage * 20 >= assetTotal" @click="loadAssets(1)">下一页资产</button>
      <label>资产<select v-model="assetId"><option value="">请选择</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option></select></label>
      <label>最后见到时间<input v-model="lastSeenAt" type="datetime-local" step="1" /></label>
      <label>最后位置<input v-model="lastLocation" maxlength="500" /></label>
      <label>找不到的情况<textarea v-model="circumstances" maxlength="2000" /></label><button @click="prepare('submit')">提交报失</button>
    </fieldset></section>
    <section aria-label="报失记录"><h2>{{ followUpOnly ? '遗失跟进清单' : '报失记录' }}（{{ total }}）</h2><table><thead><tr><th>单号</th><th>资产</th><th>状态</th><th v-if="followUpOnly">原责任 / 跟进库管 / 下次跟进日</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td>{{ row.id }}</td><td>{{ row.source.assetCode }} · {{ row.source.name }}</td><td>{{ lossStates[row.status] ?? row.status }}</td><td v-if="followUpOnly">{{ row.source.responsibleEmployeeId ?? row.source.departmentResponsibleEmployeeId ?? '在库保管' }} / {{ row.processorAccountId }} / {{ row.nextReviewDate }}</td><td><button :disabled="locked" @click="load(row.id)">查看</button></td></tr></tbody></table>
      <button :disabled="locked || page <= 1" @click="load('', -1)">上一页记录</button><button :disabled="locked || page * 20 >= total" @click="load('', 1)">下一页记录</button>
    </section>
    <section v-if="detail" aria-label="报失详情"><h2>报失单 {{ detail.id }} · {{ lossStates[detail.status] ?? detail.status }}</h2>
      <p>{{ detail.source.assetCode }} · {{ detail.source.name }}；最后见到 {{ detail.lastSeenAt }}；位置：{{ detail.lastLocation }}</p><p>{{ detail.circumstances }}</p>
      <p>处理库管：{{ detail.processorAccountId ?? '尚未认领' }}；下次复核：{{ detail.nextReviewDate ?? '核实后填写' }}</p>
      <p v-if="detail.status === 'SEARCHING'">已进入查找中，资产继续持锁。本次查找仍在处理中。</p>
      <p v-if="detail.status === 'CONFIRMED_LOST' && !detail.recoveredAt">已确认遗失，本次查找单结案，原责任和借用仍在，持续跟进。确认时间：{{ detail.confirmedLostAt }}；最近跟进：{{ detail.lastFollowUpAt ?? '尚未跟进' }}</p>
      <LossRecoveryPanel v-if="detail.recoveryAsset || detail.recoveredAt || detail.recoveries?.length" :key="'recovery:' + identity + detail.id + ':' + detail.version" :report="detail" :locked="locked" @prepare="prepareProgress" />
      <LossProgressPanel v-if="detail.actions?.confirmLost || detail.actions?.followUp" :key="identity + detail.id + ':' + detail.version" :report="detail" :locked="locked" @prepare="prepareProgress" />
      <fieldset :disabled="locked"><template v-if="detail.actions?.addPhoto"><label>情况图片<input type="file" accept="image/png,image/jpeg,image/webp" @change="chosen" /></label><button @click="prepare('photo')">上传情况图片</button></template>
        <ul><li v-for="photo in detail.photos" :key="photo.objectId">已保存原件：{{ photo.filename }}<button @click="viewPhoto(photo.objectId, photo.filename)">查看原件</button></li></ul>
        <template v-if="detail.actions?.decide || detail.actions?.cancel"><label>本次原因<textarea v-model="reason" maxlength="1000" /></label></template>
        <button v-if="detail.actions?.decide" @click="prepare('approve')">批准核实</button><button v-if="detail.actions?.decide" @click="prepare('reject')">驳回报告</button><button v-if="detail.actions?.cancel" @click="prepare('cancel')">撤回报告</button>
        <button v-if="detail.actions?.claim" @click="prepare('claim')">认领核实任务</button>
        <template v-if="detail.actions?.search"><label>最后保管人核实结果<textarea v-model="custodian" maxlength="2000" /></label><label>已查找范围与线索<textarea v-model="searchedScope" maxlength="2000" /></label><label>实际查找时间<input v-model="searchedAt" type="datetime-local" step="1" /></label><label>下次复核日<input v-model="nextReviewDate" type="date" /></label><button @click="prepare('search')">记录核实并进入查找</button></template>
      </fieldset><div v-if="preview"><p>{{ previewName }}</p><img :src="preview" :alt="previewName" style="max-width:100%;max-height:500px" /><button @click="clearPreview">关闭原件</button></div><h3>处理履历</h3><ol><li v-for="event in detail.history" :key="event.id">{{ event.occurred_at }} · {{ event.actor_username }} · {{ event.action }}</li></ol>
    </section>
  </main></AppChrome>
</template>

<style scoped>
.loss-page{padding:24px;max-width:1200px;margin:auto}header p{color:#64748b}section{background:white;border:1px solid #dbe3ee;border-radius:10px;padding:20px;margin:20px 0}fieldset{border:0;padding:0}label{display:flex;flex-direction:column;gap:6px;margin:12px 0;max-width:640px}input,select,textarea{padding:8px;border:1px solid #cbd5e1;border-radius:5px}button{padding:8px 12px;margin:5px;border:1px solid #cbd5e1;border-radius:5px;background:#f8fafc;cursor:pointer}button:disabled{opacity:.5;cursor:default}table{width:100%;text-align:left;border-collapse:collapse}th,td{padding:10px;border-bottom:1px solid #e2e8f0}[role=alert]{color:#b91c1c}[role=dialog]{padding:20px;border:2px solid #2563eb;background:#eff6ff}
</style>
