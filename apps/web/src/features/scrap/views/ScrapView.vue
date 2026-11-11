<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import FamsMediaPreview from "@/components/FamsMediaPreview.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useObjectUrlPreview } from "@/composables/useObjectUrlPreview";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { SCRAP_ATTACHMENT_MIME, ScrapApiError, scrapAttachmentSizeLimit, scrapCorrectionStates, scrapOriginal,
  scrapRecoveryStates, scrapRequest, scrapRequestState, scrapReversalStates, type ScrapAsset,
  type ScrapAttachment, type ScrapAttachmentCapabilities,
  type ScrapAttachmentKind, type ScrapChoice, type ScrapIntent, type ScrapListResult,
  type ScrapRequest } from "@/features/scrap/api";

const PAGE_SIZE = 20;
/** 执行目录单次读取的页大小；加载更多按已读页数顺延。 */
const CATALOG_PAGE_SIZE = 100;
/** 报废单号是纯数字主键；路由参数只有在合形时才值得单独发一次详情读取。 */
const REQUEST_ID = /^[1-9][0-9]{0,18}$/;
/** 两类附件的渲染顺序与段落标题同源，段落装配集中在 attachmentSections 一处。 */
const ATTACHMENT_KINDS: ScrapAttachmentKind[] = ["request", "disposition"];
const ATTACHMENT_LABEL: Record<ScrapAttachmentKind, string> = { request: "申请附件", disposition: "处置凭证" };
/** 报失来源的批准范围与后端约定同源，不由前端改写；故报失单在处理中的读法是「待遗失核销」。 */
const LOSS_DISPOSITION_METHOD = "遗失核销";
const LOSS_PENDING_WRITE_OFF_TEXT = "待遗失核销";
/** 厂家去向码在批准范围里的读法；与报失固定值同为「批准的处置范围」的只读来源。 */
const FACTORY_DISPOSITION_SCOPE: Record<"FACTORY_RETAINED" | "FACTORY_DESTROYED", string> = {
  FACTORY_RETAINED: "厂家收旧", FACTORY_DESTROYED: "厂家销毁",
};
const DISPOSITION_STATE_LABEL: Record<string, string> = { PENDING_DISPOSITION: "待实际处置", DISPOSED: "已处置", LOSS_WRITTEN_OFF: "遗失核销", REVERSED: "已因冲正撤销" };
const APPROVE_SCOPE_REQUIRED_MESSAGE = "请填写本次原因，批准时须明确允许的处置范围。";
const CANCEL_REASON_REQUIRED_MESSAGE = "请填写取消原因。";
const FACTORY_OCCURRED_REQUIRED_MESSAGE = "请填写厂家凭证记载的实际处置时间。";
const LOSS_EVIDENCE_REQUIRED_MESSAGE = "请填写至少10字的核销复核依据。";
/** 纠正、冲正与找回三段的必填校验文案，逐字取自功能线；三段共用的实物核验文案只声明一次。 */
const SUBFLOW_DECISION_REQUIRED_MESSAGE = "请填写本次决定原因。";
const SUBFLOW_IDENTITY_REQUIRED_MESSAGE = "请核对原机编码、序列号、实际时刻及至少10字核验依据。";
const CORRECTION_TARGET_REQUIRED_MESSAGE = "请选择有效纠正仓位并填写纠正原因。";
const REVERSAL_TARGET_REQUIRED_MESSAGE = "请选择有效入库仓位并填写冲正原因。";
const RECOVERY_FACTS_REQUIRED_MESSAGE = "请选择临时保管位置与经办人、拟恢复仓位并填写找回事实和原因。";
/** 未登记该段时统一返回空串，模板只按文本非空渲染，进度读法不再落在视图模板里。 */
function flowStateText(labels: Record<string, string>, status: string | undefined): string {
  return status === undefined ? "" : labels[status] ?? status;
}

interface AttachmentSection {
  kind: ScrapAttachmentKind; label: string; uploadLabel: string; canUpload: boolean; items: ScrapAttachment[];
}

const route = useRoute(), session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, route.params.id]));
const rows = ref<ScrapRequest[]>([]), detail = ref<ScrapRequest | null>(null);
const assets = ref<ScrapAsset[]>([]), places = ref<ScrapChoice[]>([]), people = ref<ScrapChoice[]>([]);
const capabilities = ref<ScrapAttachmentCapabilities | null>(null);
const attachments = ref<Record<ScrapAttachmentKind, ScrapAttachment[]>>({ request: [], disposition: [] });
const page = shallowRef(1), total = shallowRef(0);
const overdueOnly = shallowRef(false), canListOverdue = shallowRef(false), businessDate = shallowRef("");
const assetPage = shallowRef(1), assetTotal = shallowRef(0), selectedAsset = shallowRef("");
const reason = shallowRef(""), condition = shallowRef(""), proposed = shallowRef(""), decisionReason = shallowRef(""), approved = shallowRef("");
const observed = shallowRef(""), serial = shallowRef(""), occurred = shallowRef(""), physical = shallowRef(false), evidence = shallowRef(""), due = shallowRef("");
const recipient = shallowRef(""), dispositionEvidence = ref<string[]>([]);
const location = shallowRef(""), custodian = shallowRef("");
const locationPage = shallowRef(0), locationTotal = shallowRef(0), peoplePage = shallowRef(0), peopleTotal = shallowRef(0);
/** 有效仓位目录服务于纠正、冲正与找回三段的「拟恢复仓位」，与执行报废的「待处置位置」不是同一读法。 */
const bins = ref<ScrapChoice[]>([]), binPage = shallowRef(0), binTotal = shallowRef(0);
const correctionLocation = shallowRef(""), correctionReason = shallowRef(""), correctionDecision = shallowRef("");
const reversalLocation = shallowRef(""), reversalReason = shallowRef(""), reversalDecision = shallowRef("");
const recoveryFound = shallowRef(""), recoveryPlace = shallowRef(""), recoveryCustodian = shallowRef("");
const recoveryLocation = shallowRef(""), recoveryReason = shallowRef(""), recoveryDecision = shallowRef("");
const busy = shallowRef(false), stale = shallowRef(true), error = shallowRef(""), notice = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
const preview = useObjectUrlPreview();
/** 确认式幂等提交：草稿/在途两槽与幂等键归底座；本域确定性失败判据（4xx 结果已定）留在视图声明。 */
const { prepared, pending, prepare: draft, confirm: adopt, settle, fail, cancel, reset } = useConfirmedSubmit<ScrapIntent>({
  identity: () => identity.value,
  failure: (cause) => cause instanceof ScrapApiError && cause.status >= 400 && cause.status < 500,
});

/** 报失来源单在处理中没有实物可验，读法改为「待遗失核销」；其余状态沿用域内映射。 */
function rowStateText(row: Pick<ScrapRequest, "sourceType" | "status">): string {
  return row.sourceType === "LOSS" && row.status === "PROCESSING" ? LOSS_PENDING_WRITE_OFF_TEXT : scrapRequestState(row);
}
/** 批准范围的只读来源：报失固定遗失核销，厂家换机取本单去向码，其余仍是管理员填写。 */
function approvedScopeMethod(row: ScrapRequest): string {
  return row.sourceType === "LOSS" ? LOSS_DISPOSITION_METHOD : row.factoryDisposition ? FACTORY_DISPOSITION_SCOPE[row.factoryDisposition] : "";
}

const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
const refreshDisabled = computed(() => busy.value || !!prepared.value);
const requestRows = computed(() => rows.value.map((row) => ({
  ...row, stateText: rowStateText(row), dueText: `计划处置 ${row.disposition?.due_date ?? ""}`,
})));
const detailStateText = computed(() => detail.value ? rowStateText(detail.value) : "");
const detailTitle = computed(() => detail.value ? `${detail.value.source.assetCode} · ${detail.value.source.name}` : "");
const detailFactsText = computed(() => detail.value
  ? `${detailStateText.value} · 原因 ${detail.value.reason} · 现状 ${detail.value.observedCondition}` : "");
const detailPlanText = computed(() => detail.value
  ? `建议处置 ${detail.value.proposedDisposition} · 当前处理库管 ${detail.value.processorAccountId || "尚未认领"}` : "");
const approvedDispositionText = computed(() => detail.value?.decision?.decision?.approvedDisposition ?? "");
/** 只读批准范围：报失与厂家来源无输入框，读数与 prepare 的口径同用 approvedScopeMethod。 */
const approvedScopeText = computed(() => {
  const row = detail.value;
  if (!row) return "";
  const method = approvedScopeMethod(row) || row.factoryDisposition || "";
  return method ? `批准的处置范围 ${method}` : "";
});
const dispositionText = computed(() => {
  const row = detail.value?.disposition;
  if (!row) return "";
  const state = DISPOSITION_STATE_LABEL[row.disposition_status] ?? row.disposition_status;
  return `处置进度 ${state}${row.due_date ? ` · 计划日期 ${row.due_date}` : ""}`;
});
const completionText = computed(() => {
  const done = detail.value?.disposition?.completion;
  if (!done) return "";
  return `实际处置 ${done.occurredAt} · ${done.method}`
    + (done.recipient ? ` · 处置对象 ${done.recipient}` : "")
    + (done.operatorName ? ` · 经办人 ${done.operatorName}` : "");
});
const assetPrevDisabled = computed(() => assetPage.value === 1);
const assetNextDisabled = computed(() => assetPage.value * PAGE_SIZE >= assetTotal.value);
const recordsPrevDisabled = computed(() => locked.value || page.value === 1);
const recordsNextDisabled = computed(() => locked.value || page.value * PAGE_SIZE >= total.value);
const locationLoadDisabled = computed(() => locationPage.value > 0 && places.value.length >= locationTotal.value);
const peopleLoadDisabled = computed(() => peoplePage.value > 0 && people.value.length >= peopleTotal.value);
const binLoadDisabled = computed(() => binPage.value > 0 && bins.value.length >= binTotal.value);
/** 期初承接批次只在期初报废申请上出现；批次与原行号是后端事实，前端不改写。 */
const initialSourceText = computed(() => {
  const handover = detail.value?.initialSource;
  if (!handover) return "";
  return `期初承接批次 ${handover.batchId} 第 ${handover.sheetRow} 行：报废事实来自上线前既有记录，批准仍需管理员另行决定。`;
});
/** 三段的进度行与确认/执行段的只读摘要：文案与功能线逐字一致，只在 computed 里拼装一次。 */
const correctionProgressText = computed(() => {
  const row = detail.value?.correction;
  if (!row) return "";
  return `纠正进度 ${flowStateText(scrapCorrectionStates, row.status)} · 目标仓位 ${row.targetLocationName}`
    + ` · 纠正原因 ${row.reason} · 当前纠正办理人 ${row.processorAccountId || "尚未认领"}`;
});
const correctionScopeText = computed(() => {
  const row = detail.value?.correction;
  return row ? `目标仓位 ${row.targetLocationName} · 纠正原因 ${row.reason}` : "";
});
const reversalProgressText = computed(() => {
  const row = detail.value?.reversal;
  if (!row) return "";
  return `冲正进度 ${flowStateText(scrapReversalStates, row.status)} · 目标仓位 ${row.targetLocationName}`
    + ` · 冲正原因 ${row.reason} · 当前冲正办理人 ${row.processorAccountId || "尚未认领"}`;
});
const reversalScopeText = computed(() => {
  const row = detail.value?.reversal;
  return row ? `目标仓位 ${row.targetLocationName} · 冲正原因 ${row.reason}` : "";
});
const recoveryProgressText = computed(() => {
  const row = detail.value?.recovery;
  if (!row) return "";
  return `找回进度 ${flowStateText(scrapRecoveryStates, row.status)}`
    + ` · 临时保管 ${row.temporaryCustodianName} @ ${row.temporaryLocationName}`
    + ` · 拟恢复仓位 ${row.targetLocationName} · 找回事实 ${row.reason}`
    + ` · 当前找回办理人 ${row.processorAccountId || "尚未认领"}`;
});
const recoveryScopeText = computed(() => {
  const row = detail.value?.recovery;
  if (!row) return "";
  return `临时保管 ${row.temporaryCustodianName} @ ${row.temporaryLocationName}`
    + ` · 拟恢复仓位 ${row.targetLocationName} · 找回事实 ${row.reason}`;
});
const correctionTargetText = computed(() => {
  const row = detail.value?.correction;
  return row ? `批准目标仓位 ${row.targetLocationName}` : "";
});
const reversalTargetText = computed(() => {
  const row = detail.value?.reversal;
  return row ? `批准目标仓位 ${row.targetLocationName}` : "";
});
const recoveryTargetText = computed(() => {
  const row = detail.value?.recovery;
  return row ? `批准目标仓位 ${row.targetLocationName}` : "";
});
const preparedSummary = computed(() => prepared.value?.payload.summary ?? "");
const requestsTitle = computed(() => overdueOnly.value ? "超期待处置" : "申请进度");
const overdueToggleLabel = computed(() => overdueOnly.value ? "返回全部申请" : "查看超期待处置");
const overdueSummaryText = computed(() => overdueOnly.value && businessDate.value
  ? `业务日期 ${businessDate.value}，已超期 ${total.value} 单` : "");
const showOverdueEmptyNote = computed(() => overdueOnly.value && !stale.value && total.value === 0);
const attachmentSections = computed<AttachmentSection[]>(() => {
  const caps = capabilities.value, row = detail.value;
  if (!caps) return [];
  return ATTACHMENT_KINDS
    .filter((kind) => kind === "request" || row?.disposition)
    .map((kind) => ({
      kind,
      label: ATTACHMENT_LABEL[kind],
      uploadLabel: `选择${ATTACHMENT_LABEL[kind]}`,
      canUpload: kind === "request" ? caps.canUploadRequest : caps.canUploadDisposition,
      items: attachments.value[kind],
    }));
});
const dispositionFiles = computed(() => attachments.value.disposition);
const showDispositionEmptyNote = computed(() => dispositionFiles.value.length === 0);
const dispositionDisabled = computed(() => locked.value || stale.value || capabilities.value?.canRead !== true);
const previewImageShown = computed(() => preview.url !== "" && preview.mime !== "application/pdf");
/** 厂家来源单只有决定权或厂家执行权时，才值得引导回原维修核对凭证；其余身份不显示。 */
const showFactoryProofLink = computed(() => {
  const row = detail.value;
  return !!row?.factoryDisposition && !!row.sourceBusinessId
    && (row.actions.decide || row.actions.executeFactory === true);
});
const factoryProofHref = computed(() => `/repairs/${detail.value?.sourceBusinessId ?? ""}`);
const showApprovedScopeNote = computed(() => approvedScopeText.value !== "");
const showApprovedScopeField = computed(() => !showApprovedScopeNote.value);
/** 取消段与批准段共用 decisionReason 一个输入位；有决定权时该位已在批准段出现，不重复渲染。 */
const showCancelReasonField = computed(() => detail.value !== null && !detail.value.actions.decide);

function clearFacts() {
  places.value = []; people.value = []; location.value = ""; custodian.value = "";
  locationPage.value = 0; peoplePage.value = 0; locationTotal.value = 0; peopleTotal.value = 0;
  bins.value = []; binPage.value = 0; binTotal.value = 0;
  correctionLocation.value = ""; correctionReason.value = ""; correctionDecision.value = "";
  reversalLocation.value = ""; reversalReason.value = ""; reversalDecision.value = "";
  recoveryFound.value = ""; recoveryPlace.value = ""; recoveryCustodian.value = "";
  recoveryLocation.value = ""; recoveryReason.value = ""; recoveryDecision.value = "";
  decisionReason.value = ""; approved.value = ""; observed.value = ""; serial.value = "";
  occurred.value = ""; physical.value = false; evidence.value = ""; due.value = "";
  recipient.value = ""; dispositionEvidence.value = [];
}
/** 附件簿记整体作废：预览回收 object URL，能力位与两份列表一并清空。 */
function clearAttachments() {
  preview.close(); capabilities.value = null;
  attachments.value = { request: [], disposition: [] };
}
function resetView() {
  liveness.invalidate(); clearAttachments(); clearFacts();
  rows.value = []; detail.value = null; assets.value = []; selectedAsset.value = "";
  page.value = 1; total.value = 0; assetPage.value = 1; assetTotal.value = 0;
  overdueOnly.value = false; canListOverdue.value = false; businessDate.value = "";
  reason.value = ""; condition.value = ""; proposed.value = "";
  busy.value = false; stale.value = true; error.value = ""; notice.value = "";
}
/** 身份、权限或记录失效时整页作废；其余失败只落文案，保留输入供修改。 */
function failure(cause: unknown) {
  if (cause instanceof ScrapApiError && [401, 403, 404].includes(cause.status)) { reset(); resetView(); }
  error.value = cause instanceof ScrapApiError && cause.status === 409
    ? "单据、处理权或实物资料已变化，请刷新核对后重新办理。"
    : "办理未完成，请核对当前身份、实际资料和单据进度。";
}

async function load(id = detail.value?.id ?? String(route.params.id ?? ""), next = page.value) {
  if (busy.value || prepared.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = "";
  clearAttachments();
  try {
    const scope = overdueOnly.value ? "/dispositions/overdue" : "";
    const result = await scrapRequest<ScrapListResult>(`${scope}?page=${next}&pageSize=${PAGE_SIZE}`);
    const selected = REQUEST_ID.test(id) ? await scrapRequest<ScrapRequest>(`/${id}`) : null;
    if (!liveness.isCurrent(turn)) return;
    if (detail.value?.id !== selected?.id) clearFacts();
    rows.value = result.items; total.value = result.total; page.value = next; detail.value = selected; stale.value = false;
    canListOverdue.value = result.canListOverdueDispositions === true;
    businessDate.value = result.businessDate ?? "";
    if (selected) await loadAttachments(selected, turn);
  } catch (cause) { if (liveness.isCurrent(turn)) { stale.value = true; failure(cause); } }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}

function toggleOverdue() {
  if (locked.value || !canListOverdue.value) return;
  overdueOnly.value = !overdueOnly.value;
  rows.value = []; total.value = 0; detail.value = null; clearFacts(); stale.value = true;
  void load("", 1);
}

/** 能力位与两份列表在同一次读取里成对写回；附件读不到只落本段文案，不影响单据主体。 */
async function loadAttachments(row: ScrapRequest, turn: ReturnType<typeof liveness.begin>) {
  const path = `/${row.id}`;
  try {
    const caps = await scrapRequest<ScrapAttachmentCapabilities>(`${path}/attachment-capabilities`);
    if (!liveness.isCurrent(turn)) return;
    const files = caps.canRead ? await Promise.all([
      scrapRequest<ScrapAttachment[]>(`${path}/attachments/request`),
      row.disposition ? scrapRequest<ScrapAttachment[]>(`${path}/attachments/disposition`) : Promise.resolve([]),
    ]) : [[], []];
    if (!liveness.isCurrent(turn)) return;
    capabilities.value = caps;
    attachments.value = { request: files[0] ?? [], disposition: files[1] ?? [] };
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
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
  if (!SCRAP_ATTACHMENT_MIME.includes(file.type) || file.size <= 0
      || file.size > scrapAttachmentSizeLimit(file.type)) {
    error.value = "请选择有效的图片或 PDF。图片最多 10 MB，PDF 最多 20 MB。"; return;
  }
  const name = file.name;
  error.value = "";
  draft(() => Object.freeze({ path: `/${row.id}/attachments/${kind}`, version,
    body: Object.freeze({}), file, summary: `上传${ATTACHMENT_LABEL[kind]}「${name}」。` }));
}

async function openAttachment(kind: ScrapAttachmentKind, item: ScrapAttachment) {
  const row = detail.value;
  if (!row || locked.value || stale.value || !capabilities.value?.canRead
      || !attachments.value[kind].includes(item)) return;
  const turn = liveness.begin();
  preview.close(); busy.value = true; error.value = "";
  try {
    const blob = await scrapOriginal(row.id, kind, item, () => liveness.isCurrent(turn));
    if (blob && liveness.isCurrent(turn)) preview.show(blob, item.filename);
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    clearAttachments();
    if (cause instanceof ScrapApiError && cause.status === 401) failure(cause);
    else error.value = "附件读取未完成，请刷新并核对访问权限。";
  } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}

async function loadAssets(next = 1) {
  if (locked.value) return;
  const turn = liveness.begin(); busy.value = true;
  try {
    const result = await scrapRequest<{ items: ScrapAsset[]; total: number }>(`/eligible?page=${next}&pageSize=${PAGE_SIZE}`);
    if (liveness.isCurrent(turn)) { assets.value = result.items; assetTotal.value = result.total; assetPage.value = next; selectedAsset.value = ""; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}

async function catalog(kind: "location" | "employee" | "bin") {
  const row = detail.value;
  if (locked.value || stale.value || !row || !(row.actions.execute
      || kind === "employee" && (row.actions.dispose || row.actions.initiateRecovery)
      || kind === "location" && (row.actions.execute || row.actions.initiateRecovery)
      || kind === "bin" && (row.actions.initiateCorrection || row.actions.initiateReversal
          || row.actions.initiateRecovery))) return;
  const next = (kind === "location" ? locationPage.value : kind === "bin" ? binPage.value : peoplePage.value) + 1;
  const turn = liveness.begin(); busy.value = true;
  try {
    const result = await scrapRequest<{ items: ScrapChoice[]; total: number }>(
      `/${row.id}/execution-catalog/${kind}?page=${next}&pageSize=${CATALOG_PAGE_SIZE}`);
    if (!liveness.isCurrent(turn)) return;
    if (kind === "location") { places.value.push(...result.items); locationPage.value = next; locationTotal.value = result.total; }
    else if (kind === "bin") { bins.value.push(...result.items); binPage.value = next; binTotal.value = result.total; }
    else { people.value.push(...result.items); peoplePage.value = next; peopleTotal.value = result.total; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}

function prepare(action: "submit" | "approve" | "reject" | "claim" | "execute" | "executeLoss" | "executeFactory" | "cancel"
    | "requestCorrection" | "approveCorrection" | "rejectCorrection" | "claimCorrection" | "executeCorrection"
    | "requestReversal" | "approveReversal" | "rejectReversal" | "claimReversal" | "executeReversal"
    | "requestRecovery" | "approveRecovery" | "rejectRecovery" | "claimRecovery" | "executeRecovery") {
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
    if (!place || !correctionReason.value.trim()) { error.value = CORRECTION_TARGET_REQUIRED_MESSAGE; return; }
    path = "/" + row.id + "/corrections"; version = row.version;
    body = { assetVersion: row.assetVersion, lockVersion: row.lockVersion, locationId: place.id,
      locationVersion: place.version, reason: correctionReason.value.trim() };
    summary = "发起受控纠正，建议将 " + row.source.assetCode + " 恢复至仓位 " + place.name + "，等待管理员确认。";
  } else if (action === "approveCorrection" || action === "rejectCorrection"
      || action === "claimCorrection" || action === "executeCorrection") {
    const correction = row?.correction;
    if (!row || !correction) return;
    const capability = action === "approveCorrection" || action === "rejectCorrection"
      ? "decideCorrection" : action;
    if (!row.actions[capability]) return;
    version = correction.version; body = { assetVersion: correction.assetVersion, lockVersion: correction.lockVersion };
    if (capability === "decideCorrection") {
      if (!correctionDecision.value.trim()) { error.value = SUBFLOW_DECISION_REQUIRED_MESSAGE; return; }
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
        error.value = SUBFLOW_IDENTITY_REQUIRED_MESSAGE; return;
      }
      path = "/corrections/" + correction.id + "/execute";
      body = { ...body, ownershipVersion: correction.ownershipVersion, locationVersion: correction.targetLocationVersion,
        observedCode: observed.value.trim(), observedSerial: serial.value.trim(), occurredAt: time.toISOString(),
        physicalIdentityVerified: true, evidence: evidence.value.trim() };
      summary = "执行纠正，将 " + row.source.assetCode + " 核验后恢复至批准仓位 " + correction.targetLocationName + "。";
    }
  } else if (action === "requestReversal") {
    if (!row || !row.actions.initiateReversal) return;
    const place = bins.value.find(item => item.id === reversalLocation.value);
    if (!place || !reversalReason.value.trim()) { error.value = REVERSAL_TARGET_REQUIRED_MESSAGE; return; }
    path = "/" + row.id + "/reversals"; version = row.version;
    body = { assetVersion: row.assetVersion, locationId: place.id,
      locationVersion: place.version, reason: reversalReason.value.trim() };
    summary = "发起误报废冲正，建议将 " + row.source.assetCode + " 重新验收入至仓位 " + place.name + "，等待管理员确认。";
  } else if (action === "approveReversal" || action === "rejectReversal"
      || action === "claimReversal" || action === "executeReversal") {
    const reversal = row?.reversal;
    if (!row || !reversal) return;
    const capability = action === "approveReversal" || action === "rejectReversal"
      ? "decideReversal" : action;
    if (!row.actions[capability]) return;
    version = reversal.version; body = { assetVersion: reversal.assetVersion };
    if (capability === "decideReversal") {
      if (!reversalDecision.value.trim()) { error.value = SUBFLOW_DECISION_REQUIRED_MESSAGE; return; }
      path = "/reversals/" + reversal.id + "/decision";
      body = { ...body, result: action === "approveReversal" ? "APPROVE" : "REJECT",
        reason: reversalDecision.value.trim() };
      summary = action === "approveReversal"
        ? "批准冲正至仓位 " + reversal.targetLocationName + "，等待库管核验实物。"
        : "驳回本次冲正，资产保持已报废并回到库管待处置。";
    } else if (action === "claimReversal") {
      path = "/reversals/" + reversal.id + "/claim";
      summary = "取得本单冲正唯一处理权，核验实物后重新验收入库。";
    } else {
      const time = new Date(occurred.value);
      if (!physical.value || !observed.value.trim() || row.source.serialNumber && !serial.value.trim()
          || evidence.value.trim().length < 10 || !Number.isFinite(time.getTime())) {
        error.value = SUBFLOW_IDENTITY_REQUIRED_MESSAGE; return;
      }
      path = "/reversals/" + reversal.id + "/execute";
      body = { ...body, ownershipVersion: reversal.ownershipVersion, locationVersion: reversal.targetLocationVersion,
        observedCode: observed.value.trim(), observedSerial: serial.value.trim(), occurredAt: time.toISOString(),
        physicalIdentityVerified: true, evidence: evidence.value.trim() };
      summary = "执行冲正，将 " + row.source.assetCode + " 核验后重新验收入批准仓位 " + reversal.targetLocationName + "。";
    }
  } else if (action === "requestRecovery") {
    if (!row || !row.actions.initiateRecovery) return;
    const place = places.value.find(item => item.id === recoveryPlace.value);
    const person = people.value.find(item => item.id === recoveryCustodian.value);
    const target = bins.value.find(item => item.id === recoveryLocation.value);
    const found = new Date(recoveryFound.value);
    if (!place || !person || !target || !recoveryReason.value.trim() || !Number.isFinite(found.getTime())) {
      error.value = RECOVERY_FACTS_REQUIRED_MESSAGE; return;
    }
    path = "/" + row.id + "/recoveries"; version = row.version;
    body = { assetVersion: row.assetVersion, foundAt: found.toISOString(), temporaryLocationId: place.id,
      temporaryLocationVersion: place.version, temporaryCustodianEmployeeId: person.id,
      locationId: target.id, locationVersion: target.version, reason: recoveryReason.value.trim() };
    summary = "登记 " + row.source.assetCode + " 报废后找回：实物暂由 " + person.name + " 在 " + place.name
      + " 保管，建议验收入仓位 " + target.name + "，等待管理员确认纳管。";
  } else if (action === "approveRecovery" || action === "rejectRecovery"
      || action === "claimRecovery" || action === "executeRecovery") {
    const recovery = row?.recovery;
    if (!row || !recovery) return;
    const capability = action === "approveRecovery" || action === "rejectRecovery"
      ? "decideRecovery" : action;
    if (!row.actions[capability]) return;
    version = recovery.version; body = { assetVersion: recovery.assetVersion };
    if (capability === "decideRecovery") {
      if (!recoveryDecision.value.trim()) { error.value = SUBFLOW_DECISION_REQUIRED_MESSAGE; return; }
      path = "/recoveries/" + recovery.id + "/decision";
      body = { ...body, result: action === "approveRecovery" ? "APPROVE" : "REJECT",
        reason: recoveryDecision.value.trim() };
      summary = action === "approveRecovery"
        ? "批准找回纳管至仓位 " + recovery.targetLocationName + "，等待库管实物验收。"
        : "驳回本次找回纳管，资产保持已报废并保留临时保管事实。";
    } else if (action === "claimRecovery") {
      path = "/recoveries/" + recovery.id + "/claim";
      summary = "取得本单找回唯一处理权，核验原机后重新纳管入库。";
    } else {
      const time = new Date(occurred.value);
      if (!physical.value || !observed.value.trim() || row.source.serialNumber && !serial.value.trim()
          || evidence.value.trim().length < 10 || !Number.isFinite(time.getTime())) {
        error.value = SUBFLOW_IDENTITY_REQUIRED_MESSAGE; return;
      }
      path = "/recoveries/" + recovery.id + "/execute";
      body = { ...body, ownershipVersion: recovery.ownershipVersion, locationVersion: recovery.targetLocationVersion,
        observedCode: observed.value.trim(), observedSerial: serial.value.trim(), occurredAt: time.toISOString(),
        physicalIdentityVerified: true, evidence: evidence.value.trim() };
      summary = "执行找回纳管，将原卡 " + row.source.assetCode + " 核验后验收入批准仓位 "
        + recovery.targetLocationName + "，原遗失核销与借用终止保持不改。";
    }
  } else {
    if (!row || row.lockVersion == null) return;
    const capability = action === "approve" || action === "reject" ? "decide" : action;
    if (!row.actions[capability]) return;
    const segment = action === "executeLoss" ? "execute-loss" : action === "executeFactory" ? "execute-factory" : action;
    version = row.version; path = "/" + row.id + "/" + (capability === "decide" ? "decision" : segment);
    body = { assetVersion: row.assetVersion, lockVersion: row.lockVersion };
    if (capability === "decide") {
      const method = approvedScopeMethod(row) || approved.value.trim();
      if (!decisionReason.value.trim() || action === "approve" && !method) {
        error.value = APPROVE_SCOPE_REQUIRED_MESSAGE; return;
      }
      body = { ...body, result: action === "approve" ? "APPROVE" : "REJECT", reason: decisionReason.value.trim(),
        ...(action === "approve" ? { approvedDisposition: method } : {}) };
      summary = action === "approve"
        ? "批准处置范围 " + method + (row.factoryDisposition ? "，由原库管凭厂家证明执行。" : "，等待库管办理。")
        : "驳回报废，核对原保管关系后恢复。";
    } else if (action === "cancel") {
      if (!decisionReason.value.trim()) { error.value = CANCEL_REASON_REQUIRED_MESSAGE; return; }
      body = { ...body, reason: decisionReason.value.trim() };
      summary = "取消本次报废申请。系统会核对原保管关系，失效时保留冻结并交由管理员处理。";
    } else if (action === "claim") summary = row.sourceType === "LOSS"
      ? "取得本单唯一处理权，核对遗失证据后执行。" : "取得本单唯一处理权，核验实物后执行。";
    else if (action === "executeFactory") {
      const time = new Date(occurred.value);
      if (!Number.isFinite(time.getTime())) { error.value = FACTORY_OCCURRED_REQUIRED_MESSAGE; return; }
      body = { ...body, ownershipVersion: row.ownershipVersion, occurredAt: time.toISOString() };
      summary = "依据本轮厂家证明执行 " + row.source.assetCode + " 报废并登记实际处置。";
    } else if (action === "executeLoss") {
      if (evidence.value.trim().length < 10) { error.value = LOSS_EVIDENCE_REQUIRED_MESSAGE; return; }
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
  draft(() => Object.freeze({ path, version, body: Object.freeze(body), summary }));
}

/** 实际处置结案：冻结单据/归属/处置行三个独立版本，凭证只能取本次已核对到的处置附件。 */
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
  const path = "/" + row.id + "/disposition/complete";
  const version = row.disposition.version;
  const assetCode = row.source.assetCode, operatorName = person.name, evidenceCount = ids.length;
  error.value = "";
  draft(() => Object.freeze({ path, version, body,
    summary: "确认 " + assetCode + " 已实际处置。处置对象 " + body.recipient
      + "，经办人 " + operatorName + "，凭证 " + evidenceCount + " 份。" }));
}

async function send() {
  const operation = pending.value;
  if (!operation || busy.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = ""; let saved = "";
  clearAttachments();
  try {
    const result = await scrapRequest<{ id: string }>(operation.payload.path, { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    saved = result.id; settle(); stale.value = true; assets.value = []; selectedAsset.value = "";
    clearFacts(); notice.value = "本次办理已保存，请核对当前进度。";
  } catch (cause) {
    if (liveness.isCurrent(turn)) {
      fail(cause);
      stale.value = true; failure(cause);
    }
  } finally { if (liveness.isCurrent(turn)) busy.value = false; }
  if (saved && liveness.isCurrent(turn)) await load(saved);
}

function submitPrepared() { if (adopt()) void send(); }

watch(identity, () => { reset(); resetView(); void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(resetView);
</script>
<template>
  <AppChrome>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>

    <div v-if="prepared" class="fams-panel fams-stack" role="dialog" aria-label="确认报废操作">
      <p>{{ preparedSummary }}</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="submitPrepared">确认提交本次办理</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="cancel">返回核对</button>
      </div>
    </div>

    <div class="fams-panel-actions">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="refreshDisabled" @click="load()">刷新报废进度</button>
      <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="send">重试原报废操作</button>
    </div>

    <section class="fams-panel fams-stack" aria-label="提交报废申请">
      <div class="fams-panel-head"><h2 class="fams-panel-title">提交报废申请</h2></div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="loadAssets()">查询可报废资产</button>
      </div>
      <fieldset class="fams-card fams-stack" :disabled="locked || stale">
        <label class="fams-field"><span class="fams-field-label">资产</span><select class="fams-control" v-model="selectedAsset"><option value="">请选择</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option></select></label>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="assetPrevDisabled" @click="loadAssets(assetPage - 1)">上一页可报废资产</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="assetNextDisabled" @click="loadAssets(assetPage + 1)">下一页可报废资产</button>
        </div>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">报废原因</span><textarea class="fams-control" v-model="reason" maxlength="2000"/></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">资产现状</span><textarea class="fams-control" v-model="condition" maxlength="2000"/></label>
        <label class="fams-field"><span class="fams-field-label">建议处置方式</span><input class="fams-control" v-model="proposed" maxlength="500"/></label>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare('submit')">核对提交报废申请</button></div>
      </fieldset>
    </section>

    <section class="fams-panel fams-stack" aria-label="报废申请列表">
      <div class="fams-panel-head"><h2 class="fams-panel-title">{{ requestsTitle }}</h2></div>
      <div class="fams-panel-actions">
        <button v-if="canListOverdue" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="toggleOverdue">{{ overdueToggleLabel }}</button>
      </div>
      <p v-if="overdueSummaryText">{{ overdueSummaryText }}</p>
      <p v-if="showOverdueEmptyNote">当前没有超期待处置记录。</p>
      <ul class="fams-timeline">
        <li v-for="row in requestRows" :key="row.id"><button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="load(row.id)">{{ row.source.assetCode }} · {{ row.stateText }}</button><span v-if="overdueOnly">{{ row.dueText }}</span></li>
      </ul>
      <div class="fams-pagination">
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="recordsPrevDisabled" @click="load('', page - 1)">上一页报废申请</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="recordsNextDisabled" @click="load('', page + 1)">下一页报废申请</button>
        </div>
      </div>
    </section>

    <section v-if="detail" class="fams-panel fams-stack" aria-label="报废详情">
      <div class="fams-panel-head"><h2 class="fams-panel-title">{{ detailTitle }}</h2></div>
      <p>{{ detailFactsText }}</p>
      <p v-if="initialSourceText">{{ initialSourceText }}</p>
      <a v-if="showFactoryProofLink" :href="factoryProofHref">前往原维修核对厂家凭证</a>
      <p>{{ detailPlanText }}</p>
      <section v-if="capabilities" class="fams-card fams-stack" aria-label="报废附件">
        <p v-if="!capabilities.canRead">查看报废附件需要单独授权。</p>
        <section v-for="section in attachmentSections" :key="section.kind" class="fams-stack" :aria-label="section.label">
          <h3 class="fams-panel-title">{{ section.label }}</h3>
          <label v-if="section.canUpload" class="fams-file"><span class="fams-field-label">{{ section.uploadLabel }}</span><input type="file" accept="image/png,image/jpeg,image/webp,application/pdf" :disabled="locked || stale" @change="pickAttachment(section.kind, $event)"/></label>
          <ul v-if="capabilities.canRead" class="fams-timeline"><li v-for="item in section.items" :key="item.objectId"><button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || stale" @click="openAttachment(section.kind, item)">{{ item.filename }}</button></li></ul>
        </section>
      </section>
      <fieldset v-if="detail.actions.decide" class="fams-card fams-stack" :disabled="locked || stale">
        <label class="fams-field fams-field-wide"><span class="fams-field-label">本次决定原因</span><textarea class="fams-control" v-model="decisionReason" maxlength="1000"/></label>
        <p v-if="showApprovedScopeNote">{{ approvedScopeText }}</p>
        <label v-if="showApprovedScopeField" class="fams-field"><span class="fams-field-label">批准的处置范围</span><input class="fams-control" v-model="approved" maxlength="500"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('approve')">批准报废范围</button>
          <button class="fams-button" data-kind="danger" type="button" @click="prepare('reject')">驳回报废申请</button>
        </div>
      </fieldset>
      <fieldset v-if="detail.actions.cancel" class="fams-card fams-stack" :disabled="locked || stale">
        <label v-if="showCancelReasonField" class="fams-field fams-field-wide"><span class="fams-field-label">取消原因</span><textarea class="fams-control" v-model="decisionReason" maxlength="1000"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="danger" type="button" @click="prepare('cancel')">取消报废申请</button>
        </div>
      </fieldset>
      <p v-if="correctionProgressText">{{ correctionProgressText }}</p>
      <fieldset v-if="detail.actions.initiateCorrection" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>发起受控纠正</legend>
        <p>原保管关系已失效，资产保持冻结。请选择有效仓位并说明纠正依据，批准后由库管核验实物恢复在库。</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="binLoadDisabled" @click="catalog('bin')">加载更多有效仓位</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">纠正目标仓位</span><select class="fams-control" v-model="correctionLocation"><option value="">请选择</option><option v-for="item in bins" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">纠正原因</span><textarea class="fams-control" v-model="correctionReason" maxlength="1000"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('requestCorrection')">核对发起受控纠正</button>
        </div>
      </fieldset>
      <fieldset v-if="detail.actions.decideCorrection" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>确认受控纠正</legend>
        <p>{{ correctionScopeText }}</p>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">本次决定原因</span><textarea class="fams-control" v-model="correctionDecision" maxlength="1000"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('approveCorrection')">批准受控纠正</button>
          <button class="fams-button" data-kind="danger" type="button" @click="prepare('rejectCorrection')">驳回受控纠正</button>
        </div>
      </fieldset>
      <div v-if="detail.actions.claimCorrection" class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="locked || stale" @click="prepare('claimCorrection')">认领受控纠正</button>
      </div>
      <fieldset v-if="detail.actions.executeCorrection" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>核验原机并执行纠正恢复</legend>
        <p>{{ correctionTargetText }}</p>
        <label class="fams-field"><span class="fams-field-label">实际原机编码</span><input class="fams-control" v-model="observed" maxlength="64"/></label>
        <label class="fams-field"><span class="fams-field-label">实际原机序列号</span><input class="fams-control" v-model="serial" maxlength="128"/></label>
        <label class="fams-field"><span class="fams-field-label">实际核验时间</span><input class="fams-control" v-model="occurred" type="datetime-local" step="0.001"/></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">实际核验依据</span><textarea class="fams-control" v-model="evidence" maxlength="2000"/></label>
        <label class="fams-selection"><input v-model="physical" type="checkbox"/>已亲见并核对同一原机与批准仓位一致</label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('executeCorrection')">核对执行纠正恢复</button>
        </div>
      </fieldset>
      <p v-if="reversalProgressText">{{ reversalProgressText }}</p>
      <fieldset v-if="detail.actions.initiateReversal" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>发起误报废冲正</legend>
        <p>本单已执行报废但尚未实际处置。请选择重新验收入的仓位并说明错误原因，批准后由库管核验实物恢复在库。</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="binLoadDisabled" @click="catalog('bin')">加载更多有效仓位</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">冲正目标仓位</span><select class="fams-control" v-model="reversalLocation"><option value="">请选择</option><option v-for="item in bins" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">冲正原因</span><textarea class="fams-control" v-model="reversalReason" maxlength="1000"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('requestReversal')">核对发起误报废冲正</button>
        </div>
      </fieldset>
      <fieldset v-if="detail.actions.decideReversal" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>确认误报废冲正</legend>
        <p>{{ reversalScopeText }}</p>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">本次决定原因</span><textarea class="fams-control" v-model="reversalDecision" maxlength="1000"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('approveReversal')">批准误报废冲正</button>
          <button class="fams-button" data-kind="danger" type="button" @click="prepare('rejectReversal')">驳回误报废冲正</button>
        </div>
      </fieldset>
      <div v-if="detail.actions.claimReversal" class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="locked || stale" @click="prepare('claimReversal')">认领误报废冲正</button>
      </div>
      <fieldset v-if="detail.actions.executeReversal" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>核验原机并执行冲正入库</legend>
        <p>{{ reversalTargetText }}</p>
        <label class="fams-field"><span class="fams-field-label">实际原机编码</span><input class="fams-control" v-model="observed" maxlength="64"/></label>
        <label class="fams-field"><span class="fams-field-label">实际原机序列号</span><input class="fams-control" v-model="serial" maxlength="128"/></label>
        <label class="fams-field"><span class="fams-field-label">实际核验时间</span><input class="fams-control" v-model="occurred" type="datetime-local" step="0.001"/></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">实际核验依据</span><textarea class="fams-control" v-model="evidence" maxlength="2000"/></label>
        <label class="fams-selection"><input v-model="physical" type="checkbox"/>已亲见并核对同一原机与批准仓位一致</label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('executeReversal')">核对执行冲正入库</button>
        </div>
      </fieldset>
      <p v-if="recoveryProgressText">{{ recoveryProgressText }}</p>
      <fieldset v-if="detail.actions.initiateRecovery" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>登记报废后找回</legend>
        <p>本单已按遗失核销结束。实物后来找回时先登记找回事实和临时保管，资产保持已报废；批准后由库管核验原机并重新纳管入库，不重复建卡、不重开原借用。</p>
        <label class="fams-field"><span class="fams-field-label">实际找回时刻</span><input class="fams-control" v-model="recoveryFound" type="datetime-local" step="0.001"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locationLoadDisabled" @click="catalog('location')">加载更多临时保管位置</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">临时保管位置</span><select class="fams-control" v-model="recoveryPlace"><option value="">请选择</option><option v-for="item in places" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="peopleLoadDisabled" @click="catalog('employee')">加载更多临时保管经办人</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">临时保管经办人</span><select class="fams-control" v-model="recoveryCustodian"><option value="">请选择</option><option v-for="item in people" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="binLoadDisabled" @click="catalog('bin')">加载更多有效仓位</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">拟恢复仓位</span><select class="fams-control" v-model="recoveryLocation"><option value="">请选择</option><option v-for="item in bins" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">找回事实与原因</span><textarea class="fams-control" v-model="recoveryReason" maxlength="1000"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('requestRecovery')">核对登记报废后找回</button>
        </div>
      </fieldset>
      <fieldset v-if="detail.actions.decideRecovery" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>确认找回重新纳管</legend>
        <p>{{ recoveryScopeText }}</p>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">本次决定原因</span><textarea class="fams-control" v-model="recoveryDecision" maxlength="1000"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('approveRecovery')">批准找回纳管</button>
          <button class="fams-button" data-kind="danger" type="button" @click="prepare('rejectRecovery')">驳回找回纳管</button>
        </div>
      </fieldset>
      <div v-if="detail.actions.claimRecovery" class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="locked || stale" @click="prepare('claimRecovery')">认领找回纳管</button>
      </div>
      <fieldset v-if="detail.actions.executeRecovery" class="fams-card fams-stack" :disabled="locked || stale">
        <legend>核验原机并执行找回纳管</legend>
        <p>{{ recoveryTargetText }}</p>
        <label class="fams-field"><span class="fams-field-label">实际原机编码</span><input class="fams-control" v-model="observed" maxlength="64"/></label>
        <label class="fams-field"><span class="fams-field-label">实际原机序列号</span><input class="fams-control" v-model="serial" maxlength="128"/></label>
        <label class="fams-field"><span class="fams-field-label">实际验收时间</span><input class="fams-control" v-model="occurred" type="datetime-local" step="0.001"/></label>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">实际验收依据</span><textarea class="fams-control" v-model="evidence" maxlength="2000"/></label>
        <label class="fams-selection"><input v-model="physical" type="checkbox"/>已亲见并核对同一原机与批准仓位一致</label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('executeRecovery')">核对执行找回纳管</button>
        </div>
      </fieldset>
      <div v-if="detail.actions.claim" class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="locked || stale" @click="prepare('claim')">认领报废处理</button>
      </div>
      <fieldset v-if="detail.actions.execute" class="fams-card fams-stack" :disabled="locked || stale">
        <h3 class="fams-panel-title">核验原机并执行报废</h3>
        <p>批准范围 {{ approvedDispositionText }}</p>
        <label class="fams-field"><span class="fams-field-label">实际原机编码</span><input class="fams-control" v-model="observed" maxlength="64"/></label>
        <label class="fams-field"><span class="fams-field-label">实际原机序列号</span><input class="fams-control" v-model="serial" maxlength="128"/></label>
        <label class="fams-field"><span class="fams-field-label">实际核验时间</span><input class="fams-control" v-model="occurred" type="datetime-local" step="0.001"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locationLoadDisabled" @click="catalog('location')">加载更多实际位置</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">待处置位置</span><select class="fams-control" v-model="location"><option value="">请选择</option><option v-for="item in places" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="peopleLoadDisabled" @click="catalog('employee')">加载更多处置保管人</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">处置保管人</span><select class="fams-control" v-model="custodian"><option value="">请选择</option><option v-for="item in people" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">计划处置日期</span><input class="fams-control" v-model="due" type="date"/></label>
        <p>留空默认执行日后30个自然日。</p>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">实际核验依据</span><textarea class="fams-control" v-model="evidence" maxlength="2000"/></label>
        <label class="fams-selection"><input v-model="physical" type="checkbox"/>已亲见并核对同一原机和处置保管安排</label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('execute')">核对执行报废</button>
        </div>
      </fieldset>
      <fieldset v-if="detail.actions.executeFactory" class="fams-card fams-stack" :disabled="locked || stale">
        <h3 class="fams-panel-title">按厂家凭证执行报废</h3>
        <label class="fams-field"><span class="fams-field-label">凭证记载的实际处置时间</span><input class="fams-control" v-model="occurred" type="datetime-local" step="0.001"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('executeFactory')">核对厂家凭证并执行报废</button>
        </div>
      </fieldset>
      <fieldset v-if="detail.actions.executeLoss" class="fams-card fams-stack" :disabled="locked || stale">
        <h3 class="fams-panel-title">执行遗失核销</h3>
        <p>核对原报失及保管责任。如有原借用，执行后关闭原借用。</p>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">核销复核依据</span><textarea class="fams-control" v-model="evidence" maxlength="2000"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('executeLoss')">核对执行遗失核销</button>
        </div>
      </fieldset>
      <p v-if="detail.disposition">{{ dispositionText }}</p>
      <p v-if="completionText">{{ completionText }}</p>
      <fieldset v-if="detail.actions.dispose" class="fams-card fams-stack" :disabled="dispositionDisabled">
        <legend>登记实际处置</legend>
        <p>批准的处置方式 {{ approvedDispositionText }}</p>
        <label class="fams-field"><span class="fams-field-label">处置原机编码</span><input class="fams-control" v-model="observed" maxlength="64"/></label>
        <label class="fams-field"><span class="fams-field-label">处置原机序列号</span><input class="fams-control" v-model="serial" maxlength="128"/></label>
        <label class="fams-field"><span class="fams-field-label">实际处置时间</span><input class="fams-control" v-model="occurred" type="datetime-local" step="0.001"/></label>
        <label class="fams-field"><span class="fams-field-label">接收或处置对象</span><input class="fams-control" v-model="recipient" maxlength="500"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="peopleLoadDisabled" @click="catalog('employee')">加载更多实际经办人</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">实际经办人</span><select class="fams-control" v-model="custodian"><option value="">请选择</option><option v-for="item in people" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <p v-if="showDispositionEmptyNote">请先上传处置凭证。</p>
        <label v-for="file in dispositionFiles" :key="file.objectId" class="fams-selection"><input v-model="dispositionEvidence" type="checkbox" :value="file.objectId"/>使用凭证 {{ file.filename }}</label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepareDisposition">核对实际处置结案</button>
        </div>
      </fieldset>
    </section>

    <FamsMediaPreview v-if="preview.url" @dismiss="preview.close()">
      <figure>
        <img v-if="previewImageShown" :src="preview.url" :alt="preview.name">
        <figcaption>{{ preview.name }}</figcaption>
        <div class="fams-inline">
          <a :href="preview.url" :download="preview.name">下载原件 {{ preview.name }}</a>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="preview.close()">关闭报废原件</button>
        </div>
      </figure>
    </FamsMediaPreview>
  </AppChrome>
</template>
