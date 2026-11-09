<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { ScrapApiError, scrapOriginal, scrapRequest, scrapCorrectionStates, scrapStates, type ScrapAsset, type ScrapChoice,
  type ScrapAttachment, type ScrapAttachmentCapabilities, type ScrapAttachmentKind,
  type ScrapIntent, type ScrapRequest } from "@/scrap/api";

const route = useRoute(), session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, route.params.id]));
const rows = ref<ScrapRequest[]>([]), detail = ref<ScrapRequest | null>(null), page = ref(1), total = ref(0);
const overdueOnly = ref(false), canListOverdue = ref(false), businessDate = ref("");
const assets = ref<ScrapAsset[]>([]), assetPage = ref(1), assetTotal = ref(0), selectedAsset = ref("");
const reason = ref(""), condition = ref(""), proposed = ref(""), decisionReason = ref(""), approved = ref("");
const observed = ref(""), serial = ref(""), occurred = ref(""), physical = ref(false), evidence = ref(""), due = ref("");
const recipient = ref(""), dispositionEvidence = ref<string[]>([]);
const places = ref<ScrapChoice[]>([]), people = ref<ScrapChoice[]>([]), location = ref(""), custodian = ref("");
const locationPage = ref(0), locationTotal = ref(0), peoplePage = ref(0), peopleTotal = ref(0);
const bins = ref<ScrapChoice[]>([]), binPage = ref(0), binTotal = ref(0);
const correctionLocation = ref(""), correctionReason = ref(""), correctionDecision = ref("");
const busy = ref(false), stale = ref(true), error = ref(""), notice = ref("");
const confirmation = ref<ScrapIntent | null>(null), pending = ref<ScrapIntent | null>(null);
const locked = computed(() => busy.value || !!confirmation.value || !!pending.value);
const attachmentKinds = ["request", "disposition"] as const;
const attachmentLabel = { request: "申请附件", disposition: "处置凭证" };
const capabilities = ref<ScrapAttachmentCapabilities | null>(null);
const attachments = ref<Record<ScrapAttachmentKind, ScrapAttachment[]>>({ request: [], disposition: [] });
const preview = ref<{ url: string; name: string; mime: string } | null>(null);
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
function clearFacts() {
  places.value = []; people.value = []; location.value = ""; custodian.value = "";
  locationPage.value = 0; peoplePage.value = 0; locationTotal.value = 0; peopleTotal.value = 0;
  bins.value = []; binPage.value = 0; binTotal.value = 0;
  correctionLocation.value = ""; correctionReason.value = ""; correctionDecision.value = "";
  decisionReason.value = ""; approved.value = ""; observed.value = ""; serial.value = "";
  occurred.value = ""; physical.value = false; evidence.value = ""; due.value = "";
  recipient.value = ""; dispositionEvidence.value = [];
}
function reset() {
  clearAttachments(); generation++; clearFacts(); rows.value = []; detail.value = null; assets.value = []; selectedAsset.value = "";
  page.value = 1; total.value = 0; assetPage.value = 1; assetTotal.value = 0;
  overdueOnly.value = false; canListOverdue.value = false; businessDate.value = "";
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
  clearAttachments();
  try {
    const listPath = overdueOnly.value ? "/dispositions/overdue" : "";
    const result = await scrapRequest<{ items: ScrapRequest[]; total: number;
      canListOverdueDispositions?: boolean; businessDate?: string }>(listPath + "?page=" + next + "&pageSize=20");
    const selected = /^[1-9][0-9]{0,18}$/.test(id) ? await scrapRequest<ScrapRequest>("/" + id) : null;
    if (!current(epoch, owner)) return;
    if (detail.value?.id !== selected?.id) clearFacts();
    rows.value = result.items; total.value = result.total; page.value = next; detail.value = selected; stale.value = false;
    canListOverdue.value = result.canListOverdueDispositions === true;
    businessDate.value = result.businessDate ?? "";
    if (selected) await loadAttachments(selected, epoch, owner);
  } catch (cause) { if (current(epoch, owner)) { stale.value = true; failure(cause); } }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function toggleOverdue() {
  if (locked.value || !canListOverdue.value) return;
  overdueOnly.value = !overdueOnly.value;
  rows.value = []; total.value = 0; detail.value = null; clearFacts(); stale.value = true;
  void load("", 1);
}
function closePreview() {
  if (preview.value) URL.revokeObjectURL(preview.value.url);
  preview.value = null;
}
function clearAttachments() {
  closePreview(); capabilities.value = null;
  attachments.value = { request: [], disposition: [] };
}
async function loadAttachments(row: ScrapRequest, epoch: number, owner: string) {
  const path = "/" + row.id;
  try {
    const caps = await scrapRequest<ScrapAttachmentCapabilities>(path + "/attachment-capabilities");
    if (!current(epoch, owner)) return;
    const files = caps.canRead ? await Promise.all([
      scrapRequest<ScrapAttachment[]>(path + "/attachments/request"),
      row.disposition ? scrapRequest<ScrapAttachment[]>(path + "/attachments/disposition") : Promise.resolve([]),
    ]) : [[], []];
    if (!current(epoch, owner)) return;
    capabilities.value = caps;
    attachments.value = { request: files[0] ?? [], disposition: files[1] ?? [] };
  } catch (cause) {
    if (!current(epoch, owner)) return;
    if (cause instanceof ScrapApiError && cause.status === 401) { failure(cause); return; }
    if (!(cause instanceof ScrapApiError && cause.status === 404)) error.value = "附件暂不可读取，请刷新报废进度。";
  }
}
function pickAttachment(kind: ScrapAttachmentKind, event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0]; input.value = "";
  const row = detail.value, caps = capabilities.value;
  if (!file || !row || !caps || locked.value || stale.value) return;
  const allowed = kind === "request" ? caps.canUploadRequest : caps.canUploadDisposition;
  const version = kind === "request" ? caps.requestVersion : caps.dispositionVersion;
  if (!allowed || version == null) return;
  if (!["image/png", "image/jpeg", "image/webp", "application/pdf"].includes(file.type)
      || file.size <= 0 || file.size > (file.type === "application/pdf" ? 20 : 10) * 1024 * 1024) {
    error.value = "请选择有效的图片或 PDF。图片最多 10 MB，PDF 最多 20 MB。"; return;
  }
  error.value = "";
  confirmation.value = Object.freeze({ path: "/" + row.id + "/attachments/" + kind,
    key: crypto.randomUUID(), version, body: Object.freeze({}), file,
    summary: "上传" + attachmentLabel[kind] + "「" + file.name + "」。" });
}
async function openAttachment(kind: ScrapAttachmentKind, item: ScrapAttachment) {
  const row = detail.value;
  if (!row || locked.value || stale.value || !capabilities.value?.canRead
      || !attachments.value[kind].includes(item)) return;
  const epoch = ++generation, owner = identity.value;
  closePreview(); busy.value = true; error.value = "";
  try {
    const blob = await scrapOriginal(row.id, kind, item, () => current(epoch, owner));
    if (blob && current(epoch, owner)) preview.value = { url: URL.createObjectURL(blob), name: item.filename, mime: blob.type };
  } catch (cause) {
    if (!current(epoch, owner)) return;
    clearAttachments();
    if (cause instanceof ScrapApiError && cause.status === 401) failure(cause);
    else error.value = "附件读取未完成，请刷新并核对访问权限。";
  } finally { if (current(epoch, owner)) busy.value = false; }
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
async function catalog(kind: "location" | "employee" | "bin") {
  if (locked.value || stale.value || !(detail.value?.actions.execute
      || kind === "employee" && detail.value?.actions.dispose
      || kind === "bin" && detail.value?.actions.initiateCorrection)) return;
  const next = (kind === "location" ? locationPage.value : kind === "bin" ? binPage.value : peoplePage.value) + 1;
  const epoch = ++generation, owner = identity.value; busy.value = true;
  try {
    const result = await scrapRequest<{ items: ScrapChoice[]; total: number }>(
      "/" + detail.value.id + "/execution-catalog/" + kind + "?page=" + next + "&pageSize=100");
    if (!current(epoch, owner)) return;
    if (kind === "location") { places.value.push(...result.items); locationPage.value = next; locationTotal.value = result.total; }
    else if (kind === "bin") { bins.value.push(...result.items); binPage.value = next; binTotal.value = result.total; }
    else { people.value.push(...result.items); peoplePage.value = next; peopleTotal.value = result.total; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function prepare(action: "submit" | "approve" | "reject" | "claim" | "execute" | "executeLoss" | "executeFactory" | "cancel"
    | "requestCorrection" | "approveCorrection" | "rejectCorrection" | "claimCorrection" | "executeCorrection") {
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
  } else if (action === "requestCorrection") {
    if (!row || !row.actions.initiateCorrection) return;
    const place = bins.value.find(item => item.id === correctionLocation.value);
    if (!place || !correctionReason.value.trim()) {
      error.value = "请选择有效纠正仓位并填写纠正原因。"; return;
    }
    path = "/" + row.id + "/corrections"; version = row.version;
    body = { assetVersion: row.assetVersion, lockVersion: row.lockVersion, locationId: place.id,
      locationVersion: place.version, reason: correctionReason.value.trim() };
    summary = "发起受控纠正，建议将 " + row.source.assetCode + " 恢复至仓位 " + place.name + "，等待管理员确认。";
  } else if (action === "approveCorrection" || action === "rejectCorrection"
      || action === "claimCorrection" || action === "executeCorrection") {
    const correction = row?.correction;
    if (!correction) return;
    const capability = action === "approveCorrection" || action === "rejectCorrection"
      ? "decideCorrection" : action;
    if (!row.actions[capability]) return;
    version = correction.version; body = { assetVersion: correction.assetVersion, lockVersion: correction.lockVersion };
    if (capability === "decideCorrection") {
      if (!correctionDecision.value.trim()) { error.value = "请填写本次决定原因。"; return; }
      path = "/corrections/" + correction.id + "/decision";
      body = { ...body, result: action === "approveCorrection" ? "APPROVE" : "REJECT",
        reason: correctionDecision.value.trim() };
      summary = action === "approveCorrection"
        ? "批准纠正至仓位 " + correction.targetLocationName + "，等待库管核验实物。"
        : "驳回本次纠正，资产保持冻结并回到管理员纠正待办。";
    } else if (action === "claimCorrection") {
      path = "/corrections/" + correction.id + "/claim";
      summary = "取得本单纠正唯一处理权，核验实物后恢复在库。";
    } else {
      if (!row.actions.executeCorrection) return;
      const time = new Date(occurred.value);
      if (!physical.value || !observed.value.trim() || row.source.serialNumber && !serial.value.trim()
          || evidence.value.trim().length < 10 || !Number.isFinite(time.getTime())) {
        error.value = "请核对原机编码、序列号、实际时刻及至少10字核验依据。"; return;
      }
      path = "/corrections/" + correction.id + "/execute";
      body = { ...body, ownershipVersion: correction.ownershipVersion, locationVersion: correction.targetLocationVersion,
        observedCode: observed.value.trim(), observedSerial: serial.value.trim(), occurredAt: time.toISOString(),
        physicalIdentityVerified: true, evidence: evidence.value.trim() };
      summary = "执行纠正，将 " + row.source.assetCode + " 核验后恢复至批准仓位 " + correction.targetLocationName + "。";
    }
  } else {
    if (!row || row.lockVersion == null) return;
    const capability = action === "approve" || action === "reject" ? "decide" : action;
    if (!row.actions[capability]) return;
    version = row.version; path = "/" + row.id + "/" + (capability === "decide" ? "decision" : action === "executeLoss" ? "execute-loss" : action === "executeFactory" ? "execute-factory" : action);
    body = { assetVersion: row.assetVersion, lockVersion: row.lockVersion };
    const loss = row.sourceType === "LOSS";
    if (capability === "decide") {
      const method = loss ? "遗失核销" : row.factoryDisposition ?? approved.value.trim();
      if (!decisionReason.value.trim() || action === "approve" && !method) {
        error.value = "请填写本次原因，批准时须明确允许的处置范围。"; return;
      }
      body = { ...body, result: action === "approve" ? "APPROVE" : "REJECT", reason: decisionReason.value.trim(),
        ...(action === "approve" ? { approvedDisposition: method } : {}) };
      summary = action === "approve" ? "批准处置范围 " + method + (row.factoryDisposition ? "，由原库管凭厂家证明执行。" : "，等待库管办理。") : "驳回报废，核对原保管关系后恢复。";
    } else if (action === "cancel") {
      if (!decisionReason.value.trim()) { error.value = "请填写取消原因。"; return; }
      body = { ...body, reason: decisionReason.value.trim() };
      summary = "取消本次报废申请。系统会核对原保管关系，失效时保留冻结并交由管理员处理。";
    } else if (action === "claim") summary = loss
      ? "取得本单唯一处理权，核对遗失证据后执行。" : "取得本单唯一处理权，核验实物后执行。";
    else if (action === "executeFactory") {
      const time = new Date(occurred.value);
      if (!Number.isFinite(time.getTime())) { error.value = "请填写厂家凭证记载的实际处置时间。"; return; }
      body = { ...body, ownershipVersion: row.ownershipVersion, occurredAt: time.toISOString() };
      summary = "依据本轮厂家证明执行 " + row.source.assetCode + " 报废并登记实际处置。";
    } else if (action === "executeLoss") {
      if (evidence.value.trim().length < 10) { error.value = "请填写至少10字的核销复核依据。"; return; }
      body = { ...body, ownershipVersion: row.ownershipVersion, evidence: evidence.value.trim() };
      summary = "核销 " + row.source.assetCode + "，保留原遗失和保管历史。如有原借用，将终止原借用。";
    } else {
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
function prepareDisposition() {
  const row = detail.value;
  if (locked.value || stale.value || !row?.actions.dispose || !capabilities.value?.canRead || row.disposition?.version == null) return;
  const person = people.value.find(item => item.id === custodian.value);
  const date = new Date(occurred.value), method = row.decision?.decision?.approvedDisposition;
  const ids = dispositionEvidence.value.filter(id => attachments.value.disposition.some(item => item.objectId === id));
  if (!person || !method || !recipient.value.trim() || !observed.value.trim()
      || row.source.serialNumber && !serial.value.trim() || !Number.isFinite(date.getTime()) || !ids.length) {
    error.value = "请核对原机、实际处置时间、接收或处置对象、经办人及凭证。"; return;
  }
  const body = Object.freeze({ requestVersion: row.version, ownershipVersion: row.ownershipVersion,
    observedCode: observed.value.trim(), observedSerial: serial.value.trim(), occurredAt: date.toISOString(),
    method, recipient: recipient.value.trim(), operatorEmployeeId: person.id, operatorVersion: person.version,
    evidenceObjectIds: Object.freeze(ids) });
  error.value = "";
  confirmation.value = Object.freeze({ path: "/" + row.id + "/disposition/complete", key: crypto.randomUUID(),
    version: row.disposition.version, body, summary: "确认 " + row.source.assetCode + " 已实际处置。处置对象 "
      + body.recipient + "，经办人 " + person.name + "，凭证 " + ids.length + " 份。" });
}
async function perform() {
  if (!pending.value || busy.value) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value;
  busy.value = true; error.value = ""; let saved = "";
  clearAttachments();
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
    <section aria-label="报废申请列表"><h2>{{ overdueOnly ? '超期待处置' : '申请进度' }}</h2>
      <button v-if="canListOverdue" :disabled="locked" @click="toggleOverdue">{{ overdueOnly ? '返回全部申请' : '查看超期待处置' }}</button>
      <p v-if="overdueOnly && businessDate">业务日期 {{ businessDate }}，已超期 {{ total }} 单</p>
      <p v-if="overdueOnly && !stale && !total">当前没有超期待处置记录。</p>
      <ul><li v-for="row in rows" :key="row.id"><button :disabled="locked" @click="load(row.id)">{{ row.source.assetCode }} · {{ row.sourceType === 'LOSS' && row.status === 'PROCESSING' ? '待遗失核销' : scrapStates[row.status] || row.status }}</button><span v-if="overdueOnly">计划处置 {{ row.disposition?.due_date }}</span></li></ul>
      <button :disabled="locked || page === 1" @click="load('', page - 1)">上一页报废申请</button>
      <button :disabled="locked || page * 20 >= total" @click="load('', page + 1)">下一页报废申请</button>
    </section>
    <section v-if="detail" aria-label="报废详情"><h2>{{ detail.source.assetCode }} · {{ detail.source.name }}</h2>
      <p>{{ detail.sourceType === 'LOSS' && detail.status === 'PROCESSING' ? '待遗失核销' : scrapStates[detail.status] || detail.status }} · 原因 {{ detail.reason }} · 现状 {{ detail.observedCondition }}</p>
      <a v-if="detail.factoryDisposition && detail.sourceBusinessId && (detail.actions.decide || detail.actions.executeFactory)"
        :href="'/repairs/' + detail.sourceBusinessId">前往原维修核对厂家凭证</a>
      <p>建议处置 {{ detail.proposedDisposition }} · 当前处理库管 {{ detail.processorAccountId || '尚未认领' }}</p>
      <section v-if="capabilities" aria-label="报废附件">
        <p v-if="!capabilities.canRead">查看报废附件需要单独授权。</p>
        <template v-for="kind in attachmentKinds" :key="kind">
          <section v-if="kind === 'request' || detail.disposition" :aria-label="attachmentLabel[kind]">
            <h3>{{ attachmentLabel[kind] }}</h3>
            <label v-if="kind === 'request' ? capabilities.canUploadRequest : capabilities.canUploadDisposition">
              选择{{ attachmentLabel[kind] }}
              <input type="file" accept="image/png,image/jpeg,image/webp,application/pdf" :disabled="locked || stale"
                @change="pickAttachment(kind, $event)"/>
            </label>
            <ul v-if="capabilities.canRead"><li v-for="item in attachments[kind]" :key="item.objectId">
              <button :disabled="locked || stale" @click="openAttachment(kind, item)">{{ item.filename }}</button>
            </li></ul>
          </section>
        </template>
        <section v-if="preview" aria-label="报废附件原件">
          <img v-if="preview.mime !== 'application/pdf'" :src="preview.url" :alt="preview.name" class="scrap-preview"/>
          <a :href="preview.url" :download="preview.name">下载原件 {{ preview.name }}</a>
          <button @click="closePreview">关闭报废原件</button>
        </section>
      </section>
      <fieldset v-if="detail.actions.decide" :disabled="locked || stale"><label>本次决定原因<textarea v-model="decisionReason" maxlength="1000"/></label>
        <p v-if="detail.sourceType === 'LOSS'">批准的处置范围 遗失核销</p>
        <p v-else-if="detail.factoryDisposition">批准的处置范围 {{ detail.factoryDisposition === "FACTORY_RETAINED" ? "厂家收旧" : "厂家销毁" }}</p>
        <label v-else>批准的处置范围<input v-model="approved" maxlength="500"/></label><button @click="prepare('approve')">批准报废范围</button><button @click="prepare('reject')">驳回报废申请</button>
      </fieldset>
      <fieldset v-if="detail.actions.cancel" :disabled="locked || stale">
        <label v-if="!detail.actions.decide">取消原因<textarea v-model="decisionReason" maxlength="1000"/></label>
        <button @click="prepare('cancel')">取消报废申请</button>
      </fieldset>
      <p v-if="detail.correction">纠正进度 {{ scrapCorrectionStates[detail.correction.status] || detail.correction.status }} · 目标仓位 {{ detail.correction.targetLocationName }} · 纠正原因 {{ detail.correction.reason }} · 当前纠正办理人 {{ detail.correction.processorAccountId || '尚未认领' }}</p>
      <fieldset v-if="detail.actions.initiateCorrection" :disabled="locked || stale">
        <legend>发起受控纠正</legend>
        <p>原保管关系已失效，资产保持冻结。请选择有效仓位并说明纠正依据，批准后由库管核验实物恢复在库。</p>
        <button :disabled="binPage > 0 && bins.length >= binTotal" @click="catalog('bin')">加载更多有效仓位</button>
        <label>纠正目标仓位<select v-model="correctionLocation"><option value="">请选择</option><option v-for="item in bins" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label>纠正原因<textarea v-model="correctionReason" maxlength="1000"/></label>
        <button @click="prepare('requestCorrection')">核对发起受控纠正</button>
      </fieldset>
      <fieldset v-if="detail.actions.decideCorrection" :disabled="locked || stale">
        <legend>确认受控纠正</legend>
        <p>目标仓位 {{ detail.correction?.targetLocationName }} · 纠正原因 {{ detail.correction?.reason }}</p>
        <label>本次决定原因<textarea v-model="correctionDecision" maxlength="1000"/></label>
        <button @click="prepare('approveCorrection')">批准受控纠正</button>
        <button @click="prepare('rejectCorrection')">驳回受控纠正</button>
      </fieldset>
      <button v-if="detail.actions.claimCorrection" :disabled="locked || stale" @click="prepare('claimCorrection')">认领受控纠正</button>
      <fieldset v-if="detail.actions.executeCorrection" :disabled="locked || stale">
        <legend>核验原机并执行纠正恢复</legend>
        <p>批准目标仓位 {{ detail.correction?.targetLocationName }}</p>
        <label>实际原机编码<input v-model="observed" maxlength="64"/></label><label>实际原机序列号<input v-model="serial" maxlength="128"/></label>
        <label>实际核验时间<input v-model="occurred" type="datetime-local" step="0.001"/></label>
        <label>实际核验依据<textarea v-model="evidence" maxlength="2000"/></label>
        <label><input v-model="physical" type="checkbox"/>已亲见并核对同一原机与批准仓位一致</label>
        <button @click="prepare('executeCorrection')">核对执行纠正恢复</button>
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
      <fieldset v-if="detail.actions.executeFactory" :disabled="locked || stale">
        <legend>按厂家凭证执行报废</legend>
        <label>凭证记载的实际处置时间<input v-model="occurred" type="datetime-local" step="0.001"/></label>
        <button @click="prepare('executeFactory')">核对厂家凭证并执行报废</button>
      </fieldset>
      <fieldset v-if="detail.actions.executeLoss" :disabled="locked || stale">
        <legend>执行遗失核销</legend>
        <p>核对原报失及保管责任。如有原借用，执行后关闭原借用。</p>
        <label>核销复核依据<textarea v-model="evidence" maxlength="2000"/></label>
        <button @click="prepare('executeLoss')">核对执行遗失核销</button>
      </fieldset>
      <p v-if="detail.disposition">处置进度 {{ detail.disposition.disposition_status === 'PENDING_DISPOSITION' ? '待实际处置'
        : detail.disposition.disposition_status === 'DISPOSED' ? '已处置' : detail.disposition.disposition_status === 'LOSS_WRITTEN_OFF' ? '遗失核销'
        : detail.disposition.disposition_status }}<span v-if="detail.disposition.due_date"> · 计划日期 {{ detail.disposition.due_date }}</span></p>
      <p v-if="detail.disposition?.completion">实际处置 {{ detail.disposition.completion.occurredAt }} · {{ detail.disposition.completion.method }}
        <span v-if="detail.disposition.completion.recipient">· 处置对象 {{ detail.disposition.completion.recipient }}</span><span v-if="detail.disposition.completion.operatorName"> · 经办人 {{ detail.disposition.completion.operatorName }}</span></p>
      <fieldset v-if="detail.actions.dispose" :disabled="locked || stale || !capabilities?.canRead">
        <legend>登记实际处置</legend>
        <p>批准的处置方式 {{ detail.decision?.decision?.approvedDisposition }}</p>
        <label>处置原机编码<input v-model="observed" maxlength="64"/></label>
        <label>处置原机序列号<input v-model="serial" maxlength="128"/></label>
        <label>实际处置时间<input v-model="occurred" type="datetime-local" step="0.001"/></label>
        <label>接收或处置对象<input v-model="recipient" maxlength="500"/></label>
        <button :disabled="peoplePage > 0 && people.length >= peopleTotal" @click="catalog('employee')">加载更多实际经办人</button>
        <label>实际经办人<select v-model="custodian"><option value="">请选择</option><option v-for="item in people" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <p v-if="attachments.disposition.length === 0">请先上传处置凭证。</p>
        <label v-for="file in attachments.disposition" :key="file.objectId">
          <input v-model="dispositionEvidence" type="checkbox" :value="file.objectId"/>使用凭证 {{ file.filename }}
        </label>
        <button @click="prepareDisposition">核对实际处置结案</button>
      </fieldset>
    </section>
  </section></AppChrome>
</template>
<style scoped>
.scrap-page { max-width: 1080px; } section { margin: 20px 0; }
fieldset { border: 1px solid #d9e0e8; padding: 16px; } label { display: block; margin: 12px 0; }
.scrap-preview { display: block; max-width: 100%; max-height: 360px; }
textarea { display: block; width: min(100%, 720px); min-height: 70px; } button { margin: 6px; }
</style>
