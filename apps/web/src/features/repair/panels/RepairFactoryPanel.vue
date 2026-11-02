<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness, type LivenessToken } from "@/composables/useRequestLiveness";
import { useObjectUrlPreview } from "@/composables/useObjectUrlPreview";
import RepairFactoryPartsPanel from "./RepairFactoryPartsPanel.vue";
import RepairFactoryReplacementPanel from "./RepairFactoryReplacementPanel.vue";
import FamsMediaPreview from "@/components/FamsMediaPreview.vue";
import { RepairApiError, repairRequest, type Repair } from "@/features/repair/api";
import { factoryEventNames, factoryEvidenceContent, factoryProblem, factoryRequest, factoryRoundStates, factoryScopeStates, type FactoryMutation } from "@/features/repair/factory-api";
import type { FactoryDetail, FactoryFile, FactoryHistory, FactoryPage, FactoryReply, FactoryRound, FactoryRoundDetail } from "@/features/repair/factory-types";
import { useBusy } from "@/features/repair/busyRegistry";
const props = defineProps<{ repair: Repair }>();
const emit = defineEmits<{ changed: []; invalidated: [] }>();
/** 页内互斥：claimed 写本面板（含部件子面板）的占用位，blocked 读「任一其它面板在忙」。 */
const { busy: claimed, blocked } = useBusy("factory");
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id]));
const canRead = computed(() => !!session.user?.roles.includes("ASSET_ADMIN") || !!session.user?.roles.includes("WAREHOUSE_OPERATOR") && String(session.user.id) === props.repair.processorAccountId);
const opened = shallowRef(false), busy = shallowRef(false), stale = shallowRef(true), error = shallowRef(""), notice = shallowRef("");
const partsBusy = shallowRef(false);
const replacementBusy = shallowRef(false);
const data = ref<FactoryDetail | null>(null), roundRows = ref<FactoryRound[]>([]), selected = ref<FactoryRoundDetail | null>(null), selectedId = shallowRef("");
const history = ref<FactoryHistory[]>([]), total = shallowRef(0), page = shallowRef(1), historyTotal = shallowRef(0), historyPage = shallowRef(1);
/** 确认式幂等提交：草稿/在途两槽与幂等键簿记归底座；busy/locked、URL、文件与文案留在本面板。 */
const { prepared: confirmation, pending, prepare: draft, confirm: adopt, settle, fail, cancel, reset: resetSubmit } = useConfirmedSubmit<Omit<FactoryMutation, "key">>({ failure: (cause) => cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500 });
const reason = shallowRef(""), provider = shallowRef(""), contact = shallowRef(""), phone = shallowRef(""), due = shallowRef(""), fault = shallowRef("");
const observedCode = shallowRef(""), serial = shallowRef(""), actualDate = shallowRef(""), actualTime = shallowRef(""), evidence = shallowRef("");
const costPolicy = shallowRef("NO_COST"), costLimit = shallowRef(""), moneyAmount = shallowRef(""), moneyReason = shallowRef(""), moneyChecked = shallowRef(false), moneyEvidence = ref<string[]>([]), costUnassessed = shallowRef(false);
const moneyPattern = /^(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,2})?$/;
const costText = (policy?: string, limit?: string | null) => policy === "UNLIMITED" ? "费用不限" : policy === "LIMITED" ? `所有轮次累计限额 ${limit ?? "待核对"} 元` : "无费用";
const originalChecked = shallowRef(false), noCostChecked = shallowRef(false), condition = ref("RESTORED");
const locationId = shallowRef(""), locations = ref<{ id: string; name: string; version: number }[]>([]), locationPage = shallowRef(1), locationTotal = shallowRef(0);
const file = ref<File | null>(null), purpose = ref("FACTORY_ACK"), selectedEvidence = ref<string[]>([]);
const preview = useObjectUrlPreview();
const liveness = useRequestLiveness(() => [identity.value]);
const locked = computed(() => blocked.value || busy.value || partsBusy.value || replacementBusy.value
  || !!pending.value || !!confirmation.value);
const activeSelected = computed(() => !!selected.value && selected.value.id === data.value?.activeRound?.id);
const evidencePurpose = computed(() => data.value?.activeRound?.roundStatus === "DISPATCHED" ? "RECEIPT" : "FACTORY_ACK");
const eligibleEvidence = computed(() => selected.value?.evidence.filter(item => item.purpose === evidencePurpose.value) ?? []);
const financialEvidence = computed(() => selected.value?.evidence.filter(item => ["QUOTE", "COST_RECEIPT"].includes(item.purpose) && selected.value?.files.some(entry => entry.objectId === item.object_id && entry.scanStatus === "CLEAN")) ?? []);
const headerRefreshDisabled = computed(() => blocked.value || busy.value || !!confirmation.value);
const retryDisabled = computed(() => busy.value || blocked.value);
const confirmDisabled = computed(() => busy.value || blocked.value);
const pendingScopeCostText = computed(() => { const scope = data.value?.pendingScope; return scope ? costText(scope.costPolicy, scope.costLimit) : ""; });
const approvedScopeCostText = computed(() => { const scope = data.value?.approvedScope; return scope ? costText(scope.costPolicy, scope.costLimit) : ""; });
const overrunNotice = computed(() => data.value?.costSummary?.overrun ? "累计费用超出现批准范围，普通结束继续保留，请申请明确新范围。" : "");
const factorySummaryText = computed(() => { const s = data.value?.summary; return s ? `已实际寄出 ${s.actualDispatchCount} 次；${s.followupOpen ? "返厂事项仍在办理" : data.value?.status === "COMPLETED" ? "维修已实际结束，轮次原记录保留" : "本轮事项已记录，维修尚需核实实际结束"}。` : ""; });
const scopeActionsShown = computed(() => !!data.value && (data.value.actions.requestScope || data.value.actions.requestCostScope || data.value.actions.decideScope || data.value.actions.withdrawScope || data.value.actions.cancelRound));
const noRouteHint = computed(() => !!data.value && !data.value.actions.requestScope && !data.value.approvedScope && !data.value.pendingScope);
const roundPrevDisabled = computed(() => locked.value || page.value === 1);
const roundNextDisabled = computed(() => locked.value || page.value * 20 >= total.value);
const historyPrevDisabled = computed(() => locked.value || historyPage.value === 1);
const historyNextDisabled = computed(() => locked.value || historyPage.value * 20 >= historyTotal.value);
const locationPrevDisabled = computed(() => locationPage.value === 1);
const locationNextDisabled = computed(() => locationPage.value * 20 >= locationTotal.value);
const dispatchText = computed(() => selected.value?.dispatchedAt || "尚未记录");
const receivedText = computed(() => selected.value?.receivedAt || "尚未记录");
const conditionText = computed(() => selected.value?.conditionResult === "RESTORED" ? "已恢复" : "仍有故障，继续维修");
const actualCostText = computed(() => { const cost = selected.value?.actualCost; return cost === null || cost === undefined ? "待核实" : `${cost} 元`; });
const receivedText2 = computed(() => { const row = selected.value; return row ? `实际状况：${conditionText.value}；${row.treatmentResult ?? ""}；本轮实际费用 ${actualCostText.value}。` : ""; });
const fileNames = computed<Record<string, string>>(() => Object.fromEntries((selected.value?.files ?? []).map(item => [item.objectId, item.filename])));
const uploadSectionShown = computed(() => !!(activeSelected.value && data.value?.actions.uploadEvidence || selected.value?.actions?.uploadFinancialEvidence));
const receiptOptionDisabled = computed(() => selected.value?.roundStatus !== "DISPATCHED");
const quoteOptionDisabled = computed(() => !selected.value?.actions?.uploadFinancialEvidence);
const quoteLines = computed(() => (selected.value?.quotes ?? []).map((quote, index) => ({ key: `q${index}:${quote.amount}:${quote.evidenceIds.join(",")}`, amount: quote.amount })));
const costListShown = computed(() => !!selected.value && (!!selected.value.quotes?.length || selected.value.actualCost !== null));
const financialRows = computed(() => financialEvidence.value.map(item => ({ ...item, label: item.purpose === "QUOTE" ? "报价原件：" : "费用原件：", filename: fileNames.value[item.object_id] })));
const receiveFieldsetShown = computed(() => !!(activeSelected.value && (data.value?.actions.dispatch || data.value?.actions.receive)));
const receiveFieldsetTitle = computed(() => data.value?.actions.receive ? "核实原实物实际收回" : "登记实物实际寄出");
const originalIdentityText = computed(() => { const id = selected.value?.originalIdentity; return id ? `本轮原机 ${id.assetCode}${id.serialNumber ? ` · 序列号 ${id.serialNumber}` : ""}。` : ""; });
const serialFieldShown = computed(() => !!selected.value?.originalIdentity.serialNumber);
const evidenceRows = computed(() => eligibleEvidence.value.map(item => ({ ...item, label: `选用本轮${evidencePurpose.value === "RECEIPT" ? "原机收回" : "厂家接收"}原件 ${fileNames.value[item.object_id] || "待核对原件"}` })));
const noEligibleEvidence = computed(() => evidenceRows.value.length === 0);
const noCostLabel = computed(() => { const cost = selected.value?.actualCost; return cost === null || cost === undefined ? "已核实本轮实际没有费用" : `已核对本轮已记录最终费用 ${cost} 元`; });
const costUnassessedShown = computed(() => selected.value?.actualCost === null);
const receiveFieldLabel = computed(() => data.value?.actions.receive ? "实际收回状况与处理结果" : "实物寄出与厂家接收依据");
const previewImageShown = computed(() => preview.mime !== "application/pdf");
const historyRows = computed(() => history.value.map(item => ({ ...item, recordedText: new Date(item.recorded_at).toLocaleString() })));
const costLimitFieldShown = computed(() => costPolicy.value === "LIMITED");
const fileRows = computed(() => (selected.value?.files ?? []).map(item => ({ ...item, readable: item.scanStatus === "CLEAN" })));
const finalCostShown = computed(() => selected.value?.actualCost !== null);
const partsPanelShown = computed(() => selected.value?.roundStatus === "RECEIVED");
/** 整机换新在已寄出与已收回两种轮次都可能办理，去向选项本身由轮次状态限制。 */
const replacementPanelShown = computed(() => selected.value?.roundStatus === "DISPATCHED"
  || selected.value?.roundStatus === "RECEIVED");
function clearMoney() { moneyAmount.value = ""; moneyReason.value = ""; moneyChecked.value = false; moneyEvidence.value = []; costUnassessed.value = false; }
watch([busy, pending, confirmation, partsBusy, replacementBusy], () => {
  claimed.value = busy.value || !!pending.value || !!confirmation.value || partsBusy.value || replacementBusy.value;
}, { flush: "sync" });
function clearPhysical() { clearMoney(); observedCode.value = ""; serial.value = ""; actualDate.value = ""; actualTime.value = ""; evidence.value = ""; originalChecked.value = false; noCostChecked.value = false; selectedEvidence.value = []; locationId.value = ""; }
function reset() {
  partsBusy.value = false;
  replacementBusy.value = false;
  liveness.invalidate(); preview.close(); opened.value = false; busy.value = false; stale.value = true; data.value = null; roundRows.value = []; history.value = []; selected.value = null; selectedId.value = "";
  resetSubmit(); file.value = null; locations.value = []; total.value = 0; historyTotal.value = 0; page.value = 1; historyPage.value = 1;
  costPolicy.value = "NO_COST"; costLimit.value = ""; reason.value = ""; provider.value = ""; contact.value = ""; phone.value = ""; due.value = ""; fault.value = ""; purpose.value = "FACTORY_ACK"; clearPhysical();
}
function failure(cause: unknown) {
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) { reset(); emit("invalidated"); }
  if (cause instanceof RepairApiError && [400, 409, 413, 422].includes(cause.status)) stale.value = true;
  error.value = factoryProblem(cause);
}
async function fetchState(turn: LivenessToken) {
  const id = props.repair.id;
  const detail = await factoryRequest<FactoryDetail>(id);
  const list = await factoryRequest<FactoryPage<FactoryRound>>(id, `/rounds?page=${page.value}&pageSize=20`);
  const events = await factoryRequest<FactoryPage<FactoryHistory>>(id, `/history?page=${historyPage.value}&pageSize=20`);
  if (!liveness.isCurrent(turn)) return;
  const nextId = selectedId.value || detail.activeRound?.id || list.items[0]?.id || "";
  const next = nextId ? await factoryRequest<FactoryRoundDetail>(id, `/rounds/${nextId}`) : null;
  if (!liveness.isCurrent(turn)) return;
  data.value = detail; roundRows.value = list.items; total.value = list.total; history.value = events.items; historyTotal.value = events.total;
  selected.value = next; selectedId.value = nextId; stale.value = false;
}
async function load() {
  if (busy.value || blocked.value || confirmation.value || !canRead.value) return;
  opened.value = true; const turn = liveness.begin(); busy.value = true; error.value = "";
  try { await fetchState(turn); }
  catch (cause) { if (liveness.isCurrent(turn)) { stale.value = true; failure(cause); } }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
  if (liveness.isCurrent(turn) && data.value?.version !== props.repair.version) emit("changed");
}
async function selectRound(row: FactoryRound) {
  if (locked.value) return; selectedId.value = row.id; clearPhysical(); preview.close(); await load();
}
async function paginate(delta: number, events = false) {
  if (locked.value) return; const n = (events ? historyPage.value : page.value) + delta, count = events ? historyTotal.value : total.value;
  if (n < 1 || (n - 1) * 20 >= count) return;
  if (events) historyPage.value = n; else { page.value = n; selectedId.value = ""; clearPhysical(); preview.close(); }
  await load();
}
async function loadLocations(delta = 0) {
  if (locked.value) return; const next = locationPage.value + delta; if (next < 1 || delta > 0 && (next - 1) * 20 >= locationTotal.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    const result = await repairRequest<FactoryPage<{ id: string; name: string; version: number }>>(`/physical-catalog/location?page=${next}&pageSize=20`);
    if (liveness.isCurrent(turn)) { locations.value = result.items; locationTotal.value = result.total; locationPage.value = next; locationId.value = ""; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
function physicalTime() {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(actualDate.value) || !/^\d{2}:\d{2}(?::\d{2})?$/.test(actualTime.value)) throw new Error("physical time required");
  const clock = actualTime.value.length === 5 ? actualTime.value + ":00" : actualTime.value;
  const date = new Date(`${actualDate.value}T${clock}`), parts = actualDate.value.split("-").map(Number), time = clock.split(":").map(Number);
  if (!Number.isFinite(date.getTime()) || date.getFullYear() !== parts[0] || date.getMonth() + 1 !== parts[1] || date.getDate() !== parts[2] || date.getHours() !== time[0] || date.getMinutes() !== time[1] || date.getSeconds() !== time[2]) throw new Error("physical time invalid");
  return date.toISOString();
}
type Action = "requestCostScope" | "quote" | "cost" | "requestScope" | "approveScope" | "rejectScope" | "withdrawScope" | "createRound" | "cancelRound" | "dispatch" | "receive" | "upload";
function prepare(action: Action) {
  if (locked.value || stale.value || !data.value) return; error.value = "";
  const state = data.value, active = state.activeRound, row = selected.value;
  const permission = action === "approveScope" || action === "rejectScope" ? "decideScope" : action === "upload" ? "uploadEvidence" : action;
  const financialPurpose = ["QUOTE", "COST_RECEIPT"].includes(purpose.value);
  const financial = action === "quote" || action === "cost" || action === "upload" && financialPurpose;
  const permitted = action === "quote" ? row?.actions?.recordQuote : action === "cost" ? row?.actions?.recordCost
    : action === "upload" && financialPurpose ? row?.actions?.uploadFinancialEvidence : state.actions[permission as keyof typeof state.actions];
  if (!permitted) return;
  const guards = { assetVersion: state.assetVersion, ownershipVersion: state.ownershipVersion, lockVersion: state.lockVersion };
  let path: string, body: unknown, chosen: File | undefined, form: Record<string, string> | undefined, label: string;
  if (["requestScope", "requestCostScope", "approveScope", "rejectScope", "withdrawScope", "cancelRound"].includes(action) && reason.value.trim().length < 3) { error.value = "请填写本次办理的明确原因。"; return; }
  if (action === "requestCostScope") {
    const limit = costLimit.value.trim();
    if (!["NO_COST", "LIMITED", "UNLIMITED"].includes(costPolicy.value) || costPolicy.value === "LIMITED" && (!moneyPattern.test(limit) || !/[1-9]/.test(limit))) { error.value = "累计限额须为正金额，最多两位小数。"; return; }
    path = "/scopes"; body = { ...guards, reason: reason.value.trim(), costPolicy: costPolicy.value, costLimit: costPolicy.value === "LIMITED" ? limit : costPolicy.value === "NO_COST" ? "0.00" : null };
    label = `申请本维修返厂费用范围：${costText(costPolicy.value, limit)}`;
  } else if (action === "requestScope") { path = "/scopes"; body = { ...guards, reason: reason.value.trim() }; label = "申请无费用返厂范围变更"; }
  else if (["approveScope", "rejectScope", "withdrawScope"].includes(action)) {
    const scope = state.pendingScope; if (!scope) return;
    path = action === "withdrawScope" ? "/scope-withdrawal" : "/scope-decision";
    body = { scopeId: scope.id, scopeVersion: scope.version, result: action === "approveScope" ? "APPROVE" : action === "rejectScope" ? "REJECT" : "WITHDRAW", reason: reason.value.trim() };
    label = action === "approveScope" ? `批准所申请返厂范围：${costText(scope.costPolicy, scope.costLimit)}` : action === "rejectScope" ? "驳回返厂范围变更" : "撤回尚未决定的范围申请";
  } else if (action === "createRound") {
    if (!state.approvedScope || !provider.value.trim() || !contact.value.trim() || phone.value.trim().length < 3 || !/^\d{4}-\d{2}-\d{2}$/.test(due.value) || fault.value.trim().length < 3) { error.value = "请填写本轮厂家、联系人、电话、预计收回日期和实际故障。"; return; }
    path = "/rounds"; body = { ...guards, scopeId: state.approvedScope.id, scopeVersion: state.approvedScope.version, providerName: provider.value.trim(), serviceContact: contact.value.trim(), servicePhone: phone.value.trim(), expectedReturnDate: due.value, fault: fault.value.trim() }; label = "新建待寄出轮次";
  } else {
    if (!row || !financial && (!active || row.id !== active.id || row.version !== active.version) || state.lockVersion == null) { error.value = "请刷新并选中当前活动轮次，不能沿用另一轮的办理依据。"; return; }
    path = `/rounds/${row.id}`;
    if (action === "quote" || action === "cost") {
      const amount = moneyAmount.value.trim(), selected = [...moneyEvidence.value], expected = action === "quote" ? "QUOTE" : "COST_RECEIPT";
      const allowed = new Set(financialEvidence.value.filter(item => item.purpose === expected).map(item => item.object_id));
      if (!moneyPattern.test(amount) || !moneyChecked.value || moneyReason.value.trim().length < 3 || !selected.every(id => allowed.has(id)) || (action === "quote" || /[1-9]/.test(amount)) && !selected.length) { error.value = "请核对精确金额、原因及本轮对应用途原件，并亲自确认已核对。"; return; }
      path += action === "quote" ? "/quotes" : "/cost"; body = { ...guards, roundVersion: row.version, amount, evidenceIds: selected, reason: moneyReason.value.trim(), finalAmountChecked: true };
      label = `${action === "quote" ? "登记本轮报价" : "确认本轮最终费用"} ${amount} 元；${moneyReason.value.trim()}`;
    } else if (action === "cancelRound") { path += "/cancel"; body = { ...guards, roundVersion: row.version, reason: reason.value.trim() }; label = "取消尚未寄出的轮次"; }
    else if (action === "upload") {
      const value = file.value, pdf = value && /\.pdf$/i.test(value.name);
      if (!value || value.size <= 0 || value.name.length > 255 || value.size > (pdf ? 20 : 10) * 1024 * 1024 || !/\.(pdf|png|jpe?g|webp)$/i.test(value.name)) { error.value = "请选择不超过10MB的JPG/PNG/WEBP图片或不超过20MB的PDF凭证。"; return; }
      path += "/evidence"; chosen = value; form = { roundVersion: String(row.version), assetVersion: String(state.assetVersion), ownershipVersion: String(state.ownershipVersion), lockVersion: String(state.lockVersion), purpose: purpose.value }; label = `追加本轮${purpose.value === "QUOTE" ? "报价" : purpose.value === "COST_RECEIPT" ? "实际费用" : purpose.value === "FACTORY_ACK" ? "厂家接收" : purpose.value === "RECEIPT" ? "原机收回" : "故障"}凭证：${value.name}`;
    } else {
      let time: string; try { time = physicalTime(); } catch { error.value = "请填写真实办理日期和时刻（含秒），不使用故障发现时间代填。"; return; }
      const eligible = new Set(eligibleEvidence.value.map(item => item.object_id));
      if (!observedCode.value.trim() || row.originalIdentity.serialNumber && !serial.value.trim() || !originalChecked.value || evidence.value.trim().length < 10 || !selectedEvidence.value.length || !selectedEvidence.value.every(id => eligible.has(id))) { error.value = "请现场核对原机编码与序列号，选择本轮对应原件并填写实际办理依据。"; return; }
      const physical = { ...guards, roundVersion: row.version, observedCode: observedCode.value.trim(), observedSerial: serial.value.trim() || null, occurredAt: time, evidenceIds: [...selectedEvidence.value] };
      if (action === "dispatch") { path += "/dispatch"; body = { ...physical, evidence: evidence.value.trim() }; label = "登记原实物已实际寄出"; }
      else {
        const location = locations.value.find(item => item.id === locationId.value);
        if (!location || !noCostChecked.value && !costUnassessed.value || noCostChecked.value && costUnassessed.value || costUnassessed.value && row.actualCost != null) { error.value = "请选择实际地点，并核对已记录费用、实际无费用或费用待核实；整机换新不能按原机收回。"; return; }
        path += "/receive-original"; body = { ...physical, originalPhysicalIdentityChecked: true, actualLocationId: location.id, locationVersion: location.version, conditionResult: condition.value, actualCost: costUnassessed.value ? null : row.actualCost ?? "0.00", ...(costUnassessed.value ? { costUnassessed: true } : {}), treatmentResult: evidence.value.trim() }; label = "登记原实物已核实收回，维修继续持锁";
      }
    }
  }
  draft(() => ({ repairId: props.repair.id, path, version: state.version, body, file: chosen, form, label }));
}
async function perform() {
  if (!pending.value || busy.value || blocked.value) return;
  const operation = pending.value, turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    await factoryRequest<FactoryReply>(operation.payload.repairId, operation.payload.path, { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settle(); stale.value = true; file.value = null; clearPhysical(); preview.close(); reason.value = "";
    if (operation.payload.path === "/rounds") { selectedId.value = ""; purpose.value = "FACTORY_ACK"; }
    if (operation.payload.path.endsWith("/dispatch")) purpose.value = "RECEIPT";
    notice.value = "本次办理已保存。轮次收回不等于整单维修完成，仍需核实后续实际去向。";
    await fetchState(turn);
  } catch (cause) {
    if (liveness.isCurrent(turn)) { fail(cause); failure(cause); }
  } finally { if (liveness.isCurrent(turn)) busy.value = false; }
  if (liveness.isCurrent(turn) && !pending.value) emit("changed");
}
async function confirm() { if (busy.value || blocked.value || pending.value || !adopt()) return; await perform(); }
function fileChosen(event: Event) {
  if (locked.value) return;
  const input = event.target as HTMLInputElement;
  file.value = input.files?.[0] ?? null;
  input.value = "";
}
async function readFile(item: FactoryFile) {
  if (locked.value || !selected.value || item.scanStatus !== "CLEAN") return; preview.close();
  const repair = props.repair.id, round = selected.value.id, turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    const grant = await factoryRequest<{ token: string; expiresAtEpochSecond: number }>(repair, `/rounds/${round}/evidence/${item.objectId}/grant`);
    if (!liveness.isCurrent(turn)) return;
    const signal = preview.beginDownload();
    const blob = await factoryEvidenceContent(repair, round, item.objectId, grant.token, signal); if (!liveness.isCurrent(turn)) return;
    if (blob.size !== item.sizeBytes || blob.size > 20 * 1024 * 1024 || blob.type !== item.contentType || !["image/jpeg", "image/png", "image/webp", "application/pdf"].includes(blob.type)) throw new Error("Invalid evidence bytes");
    preview.show(blob, item.filename);
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(identity, () => { reset(); error.value = ""; notice.value = ""; opened.value = !!props.repair.factorySummary; if (opened.value && canRead.value) void load(); }, { immediate: true, flush: "sync" });
watch(blocked, value => { if (!value && opened.value && canRead.value && !data.value && !pending.value && !confirmation.value) void load(); });
watch(() => props.repair.version, () => { if (opened.value && !pending.value && !confirmation.value && data.value?.version !== props.repair.version) void load(); });
onBeforeUnmount(() => { reset(); });
</script>
<template>
  <section v-if="canRead" class="fams-card fams-stack" aria-label="返厂轮次与费用">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">返厂范围与实物轮次</h3>
        <p class="fams-panel-subtitle">按本维修已批准范围办理返厂。实际收回与费用核实分别记录；收回本身不结束整单维修，更换整机须单独建卡。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="headerRefreshDisabled" @click="load">核对返厂范围与轮次</button>
      </div>
    </div>
    <template v-if="opened">
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="retryDisabled" @click="perform">重试原返厂办理</button>
      <div v-if="confirmation" class="fams-card fams-stack" role="group" aria-label="确认本次返厂办理">
        <p>{{ confirmation.payload.label }}。请核对本次已填写的实物、厂家及实际依据；登记后保留原记录。</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="confirmDisabled" @click="confirm">确认保存本次返厂办理</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="confirmDisabled" @click="cancel()">返回修改返厂办理</button>
        </div>
      </div>
      <template v-if="data">
        <p v-if="data.pendingScope"><span class="fams-status">{{ factoryScopeStates[data.pendingScope.scopeStatus] }}</span> · {{ data.pendingScope.requestReason }} · 申请费用范围：{{ pendingScopeCostText }}（尚未批准）</p>
        <p v-if="data.approvedScope"><span class="fams-status">{{ factoryScopeStates[data.approvedScope.scopeStatus] }}</span> · {{ approvedScopeCostText }} · {{ data.approvedScope.decisionReason }}</p>
        <p v-if="data.costSummary">本维修已核实累计费用 {{ data.costSummary.confirmedTotal }} 元；已收回但费用待核实 {{ data.costSummary.unassessedCount }} 轮。<strong v-if="overrunNotice">{{ overrunNotice }}</strong></p>
        <p v-if="data.summary">{{ factorySummaryText }}</p>
        <fieldset v-if="scopeActionsShown" class="fams-card fams-stack" :disabled="locked || stale">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">本次范围或取消原因</span><textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
          <template v-if="data.actions.requestCostScope">
            <div class="fams-field-grid">
              <label class="fams-field"><span class="fams-field-label">本维修所有返厂轮次费用约束</span>
                <select class="fams-control" v-model="costPolicy"><option value="NO_COST">无费用</option><option value="LIMITED">累计限额</option><option value="UNLIMITED">不限费用</option></select></label>
              <label v-if="costLimitFieldShown" class="fams-field"><span class="fams-field-label">所有轮次累计限额（元）</span>
                <input class="fams-control" v-model="costLimit" inputmode="decimal" maxlength="15"></label>
            </div>
            <button class="fams-button" data-kind="primary" type="button" @click="prepare('requestCostScope')">申请返厂费用范围变更</button>
          </template>
          <div class="fams-inline">
            <button v-if="data.actions.requestScope && !data.actions.requestCostScope" class="fams-button" data-kind="primary" type="button" @click="prepare('requestScope')">申请必要的无费用返厂</button>
            <button v-if="data.actions.decideScope" class="fams-button" data-kind="primary" type="button" @click="prepare('approveScope')">批准所申请返厂范围</button>
            <button v-if="data.actions.decideScope" class="fams-button" data-kind="secondary" type="button" @click="prepare('rejectScope')">驳回返厂范围变更</button>
            <button v-if="data.actions.withdrawScope" class="fams-button" data-kind="secondary" type="button" @click="prepare('withdrawScope')">撤回尚未决定的范围申请</button>
            <button v-if="data.actions.cancelRound && activeSelected" class="fams-button" data-kind="danger" type="button" @click="prepare('cancelRound')">取消尚未寄出的本轮</button>
          </div>
        </fieldset>
        <fieldset v-if="data.actions.createRound" class="fams-card fams-stack" :disabled="locked || stale">
          <h4 class="fams-panel-title">新建一轮待寄出记录</h4>
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">厂家或服务商</span><input class="fams-control" v-model="provider" maxlength="200"></label>
            <label class="fams-field"><span class="fams-field-label">服务联系人</span><input class="fams-control" v-model="contact" maxlength="100"></label>
            <label class="fams-field"><span class="fams-field-label">服务电话</span><input class="fams-control" v-model="phone" maxlength="64"></label>
            <label class="fams-field"><span class="fams-field-label">预计收回日期</span><input class="fams-control" v-model="due" type="date"></label>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">本轮实际故障</span><textarea class="fams-control" v-model="fault" maxlength="2000" /></label>
          </div>
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('createRound')">核对新建待寄出轮次</button>
        </fieldset>
        <p v-if="noRouteHint">当前处理库管须先完成实际接修，再提出必要返厂的范围变更。</p>
        <h4 class="fams-panel-title">独立轮次 / 共 {{ total }} 轮</h4>
        <ul class="fams-timeline">
          <li v-for="row in roundRows" :key="row.id"><button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="selectRound(row)">第 {{ row.roundNumber }} 轮 · {{ row.providerName }} · {{ factoryRoundStates[row.roundStatus] }}</button></li>
        </ul>
        <div class="fams-pagination">
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="roundPrevDisabled" @click="paginate(-1)">上一页返厂轮次</button>
            <span class="fams-pagination-page">第 {{ page }} 页</span>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="roundNextDisabled" @click="paginate(1)">下一页返厂轮次</button>
          </div>
        </div>
        <section v-if="selected" class="fams-card fams-stack" aria-label="所选返厂轮次">
          <h4 class="fams-panel-title">第 {{ selected.roundNumber }} 轮 · <span class="fams-status">{{ factoryRoundStates[selected.roundStatus] }}</span></h4>
          <p>{{ selected.providerName }} · {{ selected.serviceContact }} · {{ selected.servicePhone }}；预计收回 {{ selected.expectedReturnDate }}。</p>
          <p>{{ selected.fault }}</p>
          <p>实际寄出 {{ dispatchText }}；实际原机收回 {{ receivedText }}。</p>
          <p v-if="selected.receivedAt">{{ receivedText2 }}</p>
          <p v-if="selected.cancelReason">取消原因：{{ selected.cancelReason }}。本轮未计作实际返厂次数。</p>
          <h4 class="fams-panel-title">本轮原始凭证</h4>
          <p v-if="!data.photosAvailable">原件服务暂不可用。</p>
          <ul class="fams-timeline">
            <li v-for="item in fileRows" :key="item.objectId">{{ item.filename }}<button class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="locked || !item.readable" @click="readFile(item)">读取厂家凭证 {{ item.filename }}</button></li>
          </ul>
          <fieldset v-if="uploadSectionShown" class="fams-card fams-stack" :disabled="locked || stale">
            <label class="fams-field"><span class="fams-field-label">本次凭证用途</span>
              <select class="fams-control" v-model="purpose"><option value="FAULT" :disabled="!activeSelected">实际故障</option><option value="FACTORY_ACK" :disabled="!activeSelected">厂家接收依据</option><option value="RECEIPT" :disabled="receiptOptionDisabled">原机实际收回依据</option><option value="QUOTE" :disabled="quoteOptionDisabled">本轮报价原件</option><option value="COST_RECEIPT" :disabled="quoteOptionDisabled">本轮实际费用凭证</option></select></label>
            <label class="fams-file"><span class="fams-field-label">选择图片或PDF原件</span><input type="file" accept=".jpg,.jpeg,.png,.webp,.pdf" @change="fileChosen"></label>
            <p v-if="file">已选原件：{{ file.name }}</p>
            <button class="fams-button" data-kind="primary" type="button" @click="prepare('upload')">核对追加本轮凭证</button>
          </fieldset>
          <ul v-if="costListShown" class="fams-timeline">
            <li v-for="line in quoteLines" :key="line.key">本轮报价 {{ line.amount }} 元（不计作实际费用）</li>
            <li v-if="finalCostShown">本轮已确认最终费用 {{ selected.actualCost }} 元。</li>
          </ul>
          <fieldset v-if="selected.actions?.recordQuote || selected.actions?.recordCost" class="fams-card fams-stack" :disabled="locked || stale">
            <h4 class="fams-panel-title">本轮报价与最终费用</h4>
            <p>先追加本轮对应用途原件，再核对金额。最终费用确认后保留原记录。</p>
            <div class="fams-field-grid">
              <label class="fams-field"><span class="fams-field-label">本次金额（元，最多两位小数）</span><input class="fams-control" v-model="moneyAmount" inputmode="decimal" maxlength="15"></label>
              <label class="fams-field fams-field-wide"><span class="fams-field-label">本次费用核对依据</span><textarea class="fams-control" v-model="moneyReason" maxlength="1000" /></label>
            </div>
            <div class="fams-selection-group">
              <label v-for="item in financialRows" :key="item.object_id" class="fams-selection"><input v-model="moneyEvidence" type="checkbox" :value="item.object_id">{{ item.label }}{{ item.filename }}</label>
              <label class="fams-selection"><input v-model="moneyChecked" type="checkbox">已亲自核对本次金额及所选本轮原件</label>
            </div>
            <div class="fams-inline">
              <button v-if="selected.actions?.recordQuote" class="fams-button" data-kind="secondary" type="button" @click="prepare('quote')">核对登记本轮报价</button>
              <button v-if="selected.actions?.recordCost" class="fams-button" data-kind="primary" type="button" @click="prepare('cost')">核对确认本轮最终费用</button>
            </div>
          </fieldset>
          <fieldset v-if="receiveFieldsetShown" class="fams-card fams-stack" :disabled="locked || stale">
            <h4 class="fams-panel-title">{{ receiveFieldsetTitle }}</h4>
            <p>{{ originalIdentityText }}</p>
            <div class="fams-field-grid">
              <label class="fams-field"><span class="fams-field-label">现场核对的资产编码</span><input class="fams-control" v-model="observedCode" maxlength="2048"></label>
              <label v-if="serialFieldShown" class="fams-field"><span class="fams-field-label">现场核对的原机序列号</span><input class="fams-control" v-model="serial" maxlength="128"></label>
              <label class="fams-field"><span class="fams-field-label">实际办理日期</span><input class="fams-control" v-model="actualDate" type="date"></label>
              <label class="fams-field"><span class="fams-field-label">实际办理时刻（含秒，本机时区）</span><input class="fams-control" v-model="actualTime" type="time" step="1"></label>
            </div>
            <label class="fams-selection"><input v-model="originalChecked" type="checkbox">已核实是本轮原实物，编码与身份一致</label>
            <div class="fams-selection-group">
              <label v-for="item in evidenceRows" :key="item.object_id" class="fams-selection"><input v-model="selectedEvidence" type="checkbox" :value="item.object_id">{{ item.label }}</label>
            </div>
            <p v-if="noEligibleEvidence">请先上传本轮对应用途的厂家原件，再明确选用。</p>
            <template v-if="data.actions.receive">
              <div class="fams-field-grid">
                <label class="fams-field"><span class="fams-field-label">原机实际状况</span><select class="fams-control" v-model="condition"><option value="RESTORED">已恢复</option><option value="STILL_FAULTY">仍有故障，继续维修</option></select></label>
              </div>
              <button class="fams-button" data-kind="secondary" type="button" @click="loadLocations()">查询有效收回地点</button>
              <div class="fams-selection-group">
                <label v-for="location in locations" :key="location.id" class="fams-selection"><input v-model="locationId" type="radio" :value="location.id">{{ location.name }}</label>
              </div>
              <div class="fams-pagination">
                <div class="fams-pagination-actions">
                  <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locationPrevDisabled" @click="loadLocations(-1)">上一页收回地点</button>
                  <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locationNextDisabled" @click="loadLocations(1)">下一页收回地点</button>
                </div>
              </div>
              <label class="fams-selection"><input v-model="noCostChecked" type="checkbox">{{ noCostLabel }}</label>
              <label v-if="costUnassessedShown" class="fams-selection"><input v-model="costUnassessed" type="checkbox">费用尚待核实，先记录原机实际收回</label>
            </template>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">{{ receiveFieldLabel }}</span><textarea class="fams-control" v-model="evidence" maxlength="2000" /></label>
            <div class="fams-inline">
              <button v-if="data.actions.dispatch" class="fams-button" data-kind="primary" type="button" @click="prepare('dispatch')">核对实际寄出原实物</button>
              <button v-if="data.actions.receive" class="fams-button" data-kind="primary" type="button" @click="prepare('receive')">核对原实物已实际收回</button>
            </div>
          </fieldset>
          <RepairFactoryPartsPanel v-if="partsPanelShown" :key="`${identity}:${selected.id}`" :repair="props.repair" :round="selected" :blocked="blocked || busy || replacementBusy || !!pending || !!confirmation || stale" @busy-change="partsBusy = $event" @changed="load" @invalidated="reset();emit('invalidated')" @read-file="readFile" />
          <RepairFactoryReplacementPanel v-if="replacementPanelShown" :key="`replacement:${identity}:${selected.id}`" :repair="props.repair" :round="selected" :blocked="blocked || busy || partsBusy || !!pending || !!confirmation || stale" @busy-change="replacementBusy = $event" @changed="load" @invalidated="reset();emit('invalidated')" @read-file="readFile" />
          <FamsMediaPreview v-if="preview.url" @dismiss="preview.close()">
            <figure>
            <img v-if="previewImageShown" :src="preview.url" :alt="`厂家凭证：${preview.name}`">
            <p v-else>PDF原件已读取，可保存查看。</p>
            <figcaption>{{ preview.name }}</figcaption>
            <div class="fams-inline">
              <a :href="preview.url" :download="preview.name">保存这份厂家原件</a>
              <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="preview.close()">关闭厂家凭证</button>
            </div>
            </figure>
          </FamsMediaPreview>
        </section>
        <h4 class="fams-panel-title">独立办理历史 / 共 {{ historyTotal }} 条</h4>
        <ul class="fams-timeline">
          <li v-for="item in historyRows" :key="item.id">{{ item.actor_username }} · {{ item.recordedText }} · {{ factoryEventNames[item.action] || '历史返厂办理' }}</li>
        </ul>
        <div class="fams-pagination">
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="historyPrevDisabled" @click="paginate(-1,true)">上一页返厂历史</button>
            <span class="fams-pagination-page">第 {{ historyPage }} 页</span>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="historyNextDisabled" @click="paginate(1,true)">下一页返厂历史</button>
          </div>
        </div>
      </template>
    </template>
  </section>
</template>
