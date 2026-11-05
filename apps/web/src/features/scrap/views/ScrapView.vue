<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { ScrapApiError, scrapRequest, scrapStates, type ScrapAsset, type ScrapChoice,
  type ScrapIntent, type ScrapRequest } from "@/features/scrap/api";

const route = useRoute(), session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, route.params.id]));
const rows = ref<ScrapRequest[]>([]), detail = ref<ScrapRequest | null>(null), page = ref(1), total = ref(0);
const assets = ref<ScrapAsset[]>([]), assetPage = ref(1), assetTotal = ref(0), selectedAsset = ref("");
const reason = ref(""), condition = ref(""), proposed = ref(""), decisionReason = ref(""), approved = ref("");
const observed = ref(""), serial = ref(""), occurred = ref(""), physical = ref(false), evidence = ref(""), due = ref("");
const places = ref<ScrapChoice[]>([]), people = ref<ScrapChoice[]>([]), location = ref(""), custodian = ref("");
const locationPage = ref(0), locationTotal = ref(0), peoplePage = ref(0), peopleTotal = ref(0);
const busy = ref(false), stale = ref(true), error = ref(""), notice = ref("");
const confirmation = ref<ScrapIntent | null>(null), pending = ref<ScrapIntent | null>(null);
const locked = computed(() => busy.value || !!confirmation.value || !!pending.value);
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
function clearFacts() {
  places.value = []; people.value = []; location.value = ""; custodian.value = "";
  locationPage.value = 0; peoplePage.value = 0; locationTotal.value = 0; peopleTotal.value = 0;
  decisionReason.value = ""; approved.value = ""; observed.value = ""; serial.value = "";
  occurred.value = ""; physical.value = false; evidence.value = ""; due.value = "";
}
function reset() {
  generation++; clearFacts(); rows.value = []; detail.value = null; assets.value = []; selectedAsset.value = "";
  page.value = 1; total.value = 0; assetPage.value = 1; assetTotal.value = 0;
  reason.value = ""; condition.value = ""; proposed.value = "";
  pending.value = null; confirmation.value = null; busy.value = false; stale.value = true; error.value = ""; notice.value = "";
}
function failure(cause: unknown) {
  if (cause instanceof ScrapApiError && [401, 403, 404].includes(cause.status)) reset();
  error.value = cause instanceof ScrapApiError && cause.status === 409
    ? "单据、处理权或实物资料已变化，请刷新核对后重新办理。"
    : "办理未完成，请核对当前身份、实际资料和单据进度。";
}
async function load(id = detail.value?.id ?? String(route.params.id ?? ""), next = page.value) {
  if (busy.value || confirmation.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const result = await scrapRequest<{ items: ScrapRequest[]; total: number }>("?page=" + next + "&pageSize=20");
    const selected = /^[1-9][0-9]{0,18}$/.test(id) ? await scrapRequest<ScrapRequest>("/" + id) : null;
    if (!current(epoch, owner)) return;
    if (detail.value?.id !== selected?.id) clearFacts();
    rows.value = result.items; total.value = result.total; page.value = next; detail.value = selected; stale.value = false;
  } catch (cause) { if (current(epoch, owner)) { stale.value = true; failure(cause); } }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function loadAssets(next = 1) {
  if (locked.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true;
  try {
    const result = await scrapRequest<{ items: ScrapAsset[]; total: number }>("/eligible?page=" + next + "&pageSize=20");
    if (current(epoch, owner)) { assets.value = result.items; assetTotal.value = result.total; assetPage.value = next; selectedAsset.value = ""; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function catalog(kind: "location" | "employee") {
  if (locked.value || stale.value || !detail.value?.actions.execute) return;
  const next = (kind === "location" ? locationPage.value : peoplePage.value) + 1;
  const epoch = ++generation, owner = identity.value; busy.value = true;
  try {
    const result = await scrapRequest<{ items: ScrapChoice[]; total: number }>(
      "/" + detail.value.id + "/execution-catalog/" + kind + "?page=" + next + "&pageSize=100");
    if (!current(epoch, owner)) return;
    if (kind === "location") { places.value.push(...result.items); locationPage.value = next; locationTotal.value = result.total; }
    else { people.value.push(...result.items); peoplePage.value = next; peopleTotal.value = result.total; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function prepare(action: "submit" | "approve" | "reject" | "claim" | "execute") {
  if (locked.value || stale.value) return;
  const row = detail.value;
  let path = "", body: Record<string, unknown>, version: number | undefined, summary: string;
  error.value = "";
  if (action === "submit") {
    const asset = assets.value.find(item => item.id === selectedAsset.value);
    if (!asset || !reason.value.trim() || !condition.value.trim() || !proposed.value.trim()) {
      error.value = "请选择有权资产，填写原因、现状和建议处置方式。"; return;
    }
    body = { assetId: asset.id, assetVersion: asset.version, reason: reason.value.trim(),
      observedCondition: condition.value.trim(), proposedDisposition: proposed.value.trim() };
    summary = "提交 " + asset.assetCode + " 报废申请，等待管理员确认。";
  } else {
    if (!row || row.lockVersion == null) return;
    const capability = action === "approve" || action === "reject" ? "decide" : action;
    if (!row.actions[capability]) return;
    version = row.version; path = "/" + row.id + "/" + (capability === "decide" ? "decision" : action);
    body = { assetVersion: row.assetVersion, lockVersion: row.lockVersion };
    if (capability === "decide") {
      if (!decisionReason.value.trim() || action === "approve" && !approved.value.trim()) {
        error.value = "请填写本次原因，批准时须明确允许的处置范围。"; return;
      }
      body = { ...body, result: action === "approve" ? "APPROVE" : "REJECT", reason: decisionReason.value.trim(),
        ...(action === "approve" ? { approvedDisposition: approved.value.trim() } : {}) };
      summary = action === "approve" ? "批准处置范围 " + approved.value.trim() + "，等待库管认领核实。" : "驳回报废，核对原保管关系后恢复。";
    } else if (action === "claim") summary = "取得本单唯一处理权，核验实物后执行。";
    else {
      const place = places.value.find(item => item.id === location.value), person = people.value.find(item => item.id === custodian.value);
      const time = new Date(occurred.value), method = row.decision?.decision?.approvedDisposition;
      if (!place || !person || !physical.value || !method || !Number.isFinite(time.getTime())
          || !observed.value.trim() || row.source.serialNumber && !serial.value.trim() || evidence.value.trim().length < 10) {
        error.value = "请核对原机编码、序列号、实际时刻、位置、处置保管人及至少10字依据。"; return;
      }
      body = { ...body, ownershipVersion: row.ownershipVersion, observedCode: observed.value.trim(),
        observedSerial: serial.value.trim(), locationId: place.id, locationVersion: place.version,
        custodianEmployeeId: person.id, custodianVersion: person.version, occurredAt: time.toISOString(),
        physicalIdentityVerified: true, evidence: evidence.value.trim(), dispositionMethod: method,
        ...(due.value ? { plannedDisposalDate: due.value } : {}) };
      summary = "执行 " + row.source.assetCode + " 报废，记录 " + place.name + " 的待处置实物，由 " + person.name + " 保管。";
    }
  }
  confirmation.value = Object.freeze({ path, key: crypto.randomUUID(), version, body: Object.freeze(body), summary });
}
async function perform() {
  if (!pending.value || busy.value) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value;
  busy.value = true; error.value = ""; let saved = "";
  try {
    const result = await scrapRequest<{ id: string }>(operation.path, operation);
    if (!current(epoch, owner)) return;
    saved = result.id; pending.value = null; stale.value = true; assets.value = []; selectedAsset.value = "";
    clearFacts(); notice.value = "本次办理已保存，请核对当前进度。";
  } catch (cause) {
    if (current(epoch, owner)) {
      if (cause instanceof ScrapApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
      stale.value = true; failure(cause);
    }
  } finally { if (current(epoch, owner)) busy.value = false; }
  if (saved && current(epoch, owner)) await load(saved);
}
async function confirm() {
  if (!confirmation.value || pending.value || busy.value) return;
  pending.value = confirmation.value; confirmation.value = null; await perform();
}
watch(identity, () => { reset(); void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; reset(); });
</script>
<template>
  <AppChrome><section aria-label="报废与处置" class="scrap-page">
    <h1>报废与处置</h1><p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <button :disabled="busy || !!confirmation" @click="load()">刷新报废进度</button>
    <button v-if="pending" :disabled="busy" @click="perform">重试原报废操作</button>
    <section v-if="confirmation" role="dialog" aria-label="确认报废操作"><p>{{ confirmation.summary }}</p>
      <button :disabled="busy" @click="confirm">确认提交本次办理</button><button :disabled="busy" @click="confirmation = null">返回核对</button>
    </section>
    <section aria-label="提交报废申请"><h2>提交报废申请</h2><button :disabled="locked" @click="loadAssets()">查询可报废资产</button>
      <fieldset :disabled="locked || stale">
        <label>资产<select v-model="selectedAsset"><option value="">请选择</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option></select></label>
        <button :disabled="assetPage === 1" @click="loadAssets(assetPage - 1)">上一页可报废资产</button>
        <button :disabled="assetPage * 20 >= assetTotal" @click="loadAssets(assetPage + 1)">下一页可报废资产</button>
        <label>报废原因<textarea v-model="reason" maxlength="2000"/></label><label>资产现状<textarea v-model="condition" maxlength="2000"/></label>
        <label>建议处置方式<input v-model="proposed" maxlength="500"/></label><button @click="prepare('submit')">核对提交报废申请</button>
      </fieldset>
    </section>
    <section aria-label="报废申请列表"><h2>申请进度</h2>
      <ul><li v-for="row in rows" :key="row.id"><button :disabled="locked" @click="load(row.id)">{{ row.source.assetCode }} · {{ scrapStates[row.status] || row.status }}</button></li></ul>
      <button :disabled="locked || page === 1" @click="load('', page - 1)">上一页报废申请</button>
      <button :disabled="locked || page * 20 >= total" @click="load('', page + 1)">下一页报废申请</button>
    </section>
    <section v-if="detail" aria-label="报废详情"><h2>{{ detail.source.assetCode }} · {{ detail.source.name }}</h2>
      <p>{{ scrapStates[detail.status] || detail.status }} · 原因 {{ detail.reason }} · 现状 {{ detail.observedCondition }}</p>
      <p>建议处置 {{ detail.proposedDisposition }} · 当前处理库管 {{ detail.processorAccountId || '尚未认领' }}</p>
      <fieldset v-if="detail.actions.decide" :disabled="locked || stale"><label>本次决定原因<textarea v-model="decisionReason" maxlength="1000"/></label>
        <label>批准的处置范围<input v-model="approved" maxlength="500"/></label><button @click="prepare('approve')">批准报废范围</button><button @click="prepare('reject')">驳回报废申请</button>
      </fieldset>
      <button v-if="detail.actions.claim" :disabled="locked || stale" @click="prepare('claim')">认领报废处理</button>
      <fieldset v-if="detail.actions.execute" :disabled="locked || stale"><h3>核验原机并执行报废</h3>
        <p>批准范围 {{ detail.decision?.decision?.approvedDisposition }}</p>
        <label>实际原机编码<input v-model="observed" maxlength="64"/></label><label>实际原机序列号<input v-model="serial" maxlength="128"/></label>
        <label>实际核验时间<input v-model="occurred" type="datetime-local" step="0.001"/></label>
        <button :disabled="locationPage > 0 && places.length >= locationTotal" @click="catalog('location')">加载更多实际位置</button>
        <label>待处置位置<select v-model="location"><option value="">请选择</option><option v-for="item in places" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <button :disabled="peoplePage > 0 && people.length >= peopleTotal" @click="catalog('employee')">加载更多处置保管人</button>
        <label>处置保管人<select v-model="custodian"><option value="">请选择</option><option v-for="item in people" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label>计划处置日期<input v-model="due" type="date"/></label><p>留空默认执行日后30个自然日。</p>
        <label>实际核验依据<textarea v-model="evidence" maxlength="2000"/></label>
        <label><input v-model="physical" type="checkbox"/>已亲见并核对同一原机和处置保管安排</label>
        <button @click="prepare('execute')">核对执行报废</button>
      </fieldset>
      <p v-if="detail.disposition">处置进度 {{ detail.disposition.disposition_status === 'PENDING_DISPOSITION' ? '待实际处置' : detail.disposition.disposition_status }} · 计划日期 {{ detail.disposition.due_date }}</p>
    </section>
  </section></AppChrome>
</template>
<style scoped>
.scrap-page { max-width: 1080px; } section { margin: 20px 0; }
fieldset { border: 1px solid #d9e0e8; padding: 16px; } label { display: block; margin: 12px 0; }
textarea { display: block; width: min(100%, 720px); min-height: 70px; } button { margin: 6px; }
</style>
