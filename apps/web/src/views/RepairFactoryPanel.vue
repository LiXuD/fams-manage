<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import RepairFactoryPartsPanel from "./RepairFactoryPartsPanel.vue";
import { RepairApiError, repairRequest, type Repair } from "@/repair/api";
import { factoryEventNames, factoryProblem, factoryRequest, factoryRoundStates, factoryScopeStates, type FactoryMutation } from "@/repair/factory-api";
import type { FactoryDetail, FactoryFile, FactoryHistory, FactoryPage, FactoryReply, FactoryRound, FactoryRoundDetail } from "@/repair/factory-types";
const props = defineProps<{ repair: Repair; blocked: boolean }>();
const emit = defineEmits<{ "busy-change": [boolean]; changed: []; invalidated: [] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id]));
const canRead = computed(() => !!session.user?.roles.includes("ASSET_ADMIN") || !!session.user?.roles.includes("WAREHOUSE_OPERATOR") && String(session.user.id) === props.repair.processorAccountId);
const opened = ref(false), busy = ref(false), stale = ref(true), error = ref(""), notice = ref("");
const partsBusy = ref(false);
const data = ref<FactoryDetail | null>(null), rows = ref<FactoryRound[]>([]), selected = ref<FactoryRoundDetail | null>(null), selectedId = ref("");
const history = ref<FactoryHistory[]>([]), total = ref(0), page = ref(1), historyTotal = ref(0), historyPage = ref(1);
const confirmation = ref<FactoryMutation | null>(null), pending = ref<FactoryMutation | null>(null);
const reason = ref(""), provider = ref(""), contact = ref(""), phone = ref(""), due = ref(""), fault = ref("");
const observedCode = ref(""), serial = ref(""), actualDate = ref(""), actualTime = ref(""), evidence = ref("");
const costPolicy = ref("NO_COST"), costLimit = ref(""), moneyAmount = ref(""), moneyReason = ref(""), moneyChecked = ref(false), moneyEvidence = ref<string[]>([]), costUnassessed = ref(false);
const moneyPattern = /^(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,2})?$/;
const costText = (policy?: string, limit?: string | null) => policy === "UNLIMITED" ? "费用不限" : policy === "LIMITED" ? `所有轮次累计限额 ${limit ?? "待核对"} 元` : "无费用";
const originalChecked = ref(false), noCostChecked = ref(false), condition = ref("RESTORED");
const locationId = ref(""), locations = ref<{ id: string; name: string; version: number }[]>([]), locationPage = ref(1), locationTotal = ref(0);
const file = ref<File | null>(null), purpose = ref("FACTORY_ACK"), selectedEvidence = ref<string[]>([]);
const preview = ref(""), previewName = ref(""), previewMime = ref("");
let generation = 0, live = true, download: AbortController | null = null;
const current = (epoch: number, owner: string) => live && epoch === generation && identity.value === owner;
const locked = computed(() => props.blocked || busy.value || partsBusy.value || !!pending.value || !!confirmation.value);
const activeSelected = computed(() => !!selected.value && selected.value.id === data.value?.activeRound?.id);
const evidencePurpose = computed(() => data.value?.activeRound?.roundStatus === "DISPATCHED" ? "RECEIPT" : "FACTORY_ACK");
const eligibleEvidence = computed(() => selected.value?.evidence.filter(item => item.purpose === evidencePurpose.value) ?? []);
const financialEvidence = computed(() => selected.value?.evidence.filter(item => ["QUOTE", "COST_RECEIPT"].includes(item.purpose) && selected.value?.files.some(file => file.objectId === item.object_id && file.scanStatus === "CLEAN")) ?? []);
function clearMoney() { moneyAmount.value = ""; moneyReason.value = ""; moneyChecked.value = false; moneyEvidence.value = []; costUnassessed.value = false; }
watch([busy, pending, confirmation, partsBusy], () => emit("busy-change", busy.value || !!pending.value || !!confirmation.value || partsBusy.value), { flush: "sync" });
function clearPreview() { download?.abort(); download = null; if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ""; previewName.value = ""; previewMime.value = ""; }
function clearPhysical() { clearMoney(); observedCode.value = ""; serial.value = ""; actualDate.value = ""; actualTime.value = ""; evidence.value = ""; originalChecked.value = false; noCostChecked.value = false; selectedEvidence.value = []; locationId.value = ""; }
function reset() {
  partsBusy.value = false;
  generation++; clearPreview(); opened.value = false; busy.value = false; stale.value = true; data.value = null; rows.value = []; history.value = []; selected.value = null; selectedId.value = "";
  confirmation.value = null; pending.value = null; file.value = null; locations.value = []; total.value = 0; historyTotal.value = 0; page.value = 1; historyPage.value = 1;
  costPolicy.value = "NO_COST"; costLimit.value = ""; reason.value = ""; provider.value = ""; contact.value = ""; phone.value = ""; due.value = ""; fault.value = ""; purpose.value = "FACTORY_ACK"; clearPhysical();
}
function failure(cause: unknown) {
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) { reset(); emit("invalidated"); }
  if (cause instanceof RepairApiError && [400, 409, 413, 422].includes(cause.status)) stale.value = true;
  error.value = factoryProblem(cause);
}
async function fetchState(epoch: number, owner: string) {
  const id = props.repair.id;
  const detail = await factoryRequest<FactoryDetail>(id);
  const list = await factoryRequest<FactoryPage<FactoryRound>>(id, `/rounds?page=${page.value}&pageSize=20`);
  const events = await factoryRequest<FactoryPage<FactoryHistory>>(id, `/history?page=${historyPage.value}&pageSize=20`);
  if (!current(epoch, owner)) return;
  const nextId = selectedId.value || detail.activeRound?.id || list.items[0]?.id || "";
  const next = nextId ? await factoryRequest<FactoryRoundDetail>(id, `/rounds/${nextId}`) : null;
  if (!current(epoch, owner)) return;
  data.value = detail; rows.value = list.items; total.value = list.total; history.value = events.items; historyTotal.value = events.total;
  selected.value = next; selectedId.value = nextId; stale.value = false;
}
async function load() {
  if (busy.value || props.blocked || confirmation.value || !canRead.value) return;
  opened.value = true; const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await fetchState(epoch, owner); }
  catch (cause) { if (current(epoch, owner)) { stale.value = true; failure(cause); } }
  finally { if (current(epoch, owner)) busy.value = false; }
  if (current(epoch, owner) && data.value?.version !== props.repair.version) emit("changed");
}
async function selectRound(row: FactoryRound) {
  if (locked.value) return; selectedId.value = row.id; clearPhysical(); clearPreview(); await load();
}
async function paginate(delta: number, events = false) {
  if (locked.value) return; const n = (events ? historyPage.value : page.value) + delta, count = events ? historyTotal.value : total.value;
  if (n < 1 || (n - 1) * 20 >= count) return;
  if (events) historyPage.value = n; else { page.value = n; selectedId.value = ""; clearPhysical(); clearPreview(); }
  await load();
}
async function loadLocations(delta = 0) {
  if (locked.value) return; const next = locationPage.value + delta; if (next < 1 || delta > 0 && (next - 1) * 20 >= locationTotal.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const result = await repairRequest<FactoryPage<{ id: string; name: string; version: number }>>(`/physical-catalog/location?page=${next}&pageSize=20`);
    if (current(epoch, owner)) { locations.value = result.items; locationTotal.value = result.total; locationPage.value = next; locationId.value = ""; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
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
  let path = "", body: unknown, chosen: File | undefined, form: Record<string, string> | undefined, label = "";
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
  confirmation.value = { repairId: props.repair.id, path, key: crypto.randomUUID(), version: state.version, body, file: chosen, form, label };
}
async function perform() {
  if (!pending.value || busy.value || props.blocked) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    await factoryRequest<FactoryReply>(operation.repairId, operation.path, operation);
    if (!current(epoch, owner)) return;
    pending.value = null; stale.value = true; file.value = null; clearPhysical(); clearPreview(); reason.value = "";
    if (operation.path === "/rounds") { selectedId.value = ""; purpose.value = "FACTORY_ACK"; }
    if (operation.path.endsWith("/dispatch")) purpose.value = "RECEIPT";
    notice.value = "本次办理已保存。轮次收回不等于整单维修完成，仍需核实后续实际去向。";
    await fetchState(epoch, owner);
  } catch (cause) {
    if (current(epoch, owner)) { if (cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); }
  } finally { if (current(epoch, owner)) busy.value = false; }
  if (current(epoch, owner) && !pending.value) emit("changed");
}
async function confirm() { if (busy.value || props.blocked || pending.value || !confirmation.value) return; pending.value = confirmation.value; confirmation.value = null; await perform(); }
function fileChosen(event: Event) {
  if (locked.value) return;
  const input = event.target as HTMLInputElement;
  file.value = input.files?.[0] ?? null;
  input.value = "";
}
async function readFile(item: FactoryFile) {
  if (locked.value || !selected.value || item.scanStatus !== "CLEAN") return; clearPreview();
  const repair = props.repair.id, round = selected.value.id, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const grant = await factoryRequest<{ token: string; expiresAtEpochSecond: number }>(repair, `/rounds/${round}/evidence/${item.objectId}/grant`);
    if (!current(epoch, owner)) return;
    download = new AbortController();
    const url = `/api/v1/repairs/${encodeURIComponent(repair)}/factory/rounds/${encodeURIComponent(round)}/evidence/${encodeURIComponent(item.objectId)}/content?token=${encodeURIComponent(grant.token)}`;
    const response = await fetch(url, { credentials: "include", cache: "no-store", signal: download.signal });
    if (!response.ok) throw new RepairApiError(response.status, "REPAIR_FACTORY_EVIDENCE_READ_FAILED", {});
    const blob = await response.blob(); if (!current(epoch, owner)) return;
    if (blob.size !== item.sizeBytes || blob.size > 20 * 1024 * 1024 || blob.type !== item.contentType || !["image/jpeg", "image/png", "image/webp", "application/pdf"].includes(blob.type)) throw new Error("Invalid evidence bytes");
    preview.value = URL.createObjectURL(blob); previewName.value = item.filename; previewMime.value = blob.type;
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
watch(identity, () => { reset(); error.value = ""; notice.value = ""; opened.value = !!props.repair.factorySummary; if (opened.value && canRead.value) void load(); }, { immediate: true, flush: "sync" });
watch(() => props.blocked, value => { if (!value && opened.value && canRead.value && !data.value && !pending.value && !confirmation.value) void load(); });
watch(() => props.repair.version, () => { if (opened.value && !pending.value && !confirmation.value && data.value?.version !== props.repair.version) void load(); });
onBeforeUnmount(() => { live = false; reset(); emit("busy-change", false); });
</script>
<template>
  <section v-if="canRead" aria-label="返厂轮次与费用" class="factory-panel"><h3>返厂范围与实物轮次</h3>
    <p>按本维修已批准范围办理返厂。实际收回与费用核实分别记录；收回本身不结束整单维修，更换整机须单独建卡。</p>
    <button :disabled="props.blocked || busy || !!confirmation" @click="load">核对返厂范围与轮次</button>
    <template v-if="opened"><p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <button v-if="pending" :disabled="busy || props.blocked" @click="perform">重试原返厂办理</button>
      <section v-if="confirmation" aria-label="确认本次返厂办理"><p>{{ confirmation.label }}。请核对本次已填写的实物、厂家及实际依据；登记后保留原记录。</p><button :disabled="busy || props.blocked" @click="confirm">确认保存本次返厂办理</button><button :disabled="busy || props.blocked" @click="confirmation = null">返回修改返厂办理</button></section>
      <template v-if="data"><p v-if="data.pendingScope">{{ factoryScopeStates[data.pendingScope.scopeStatus] }} · {{ data.pendingScope.requestReason }} · 申请费用范围：{{ costText(data.pendingScope.costPolicy, data.pendingScope.costLimit) }}（尚未批准）</p><p v-if="data.approvedScope">{{ factoryScopeStates[data.approvedScope.scopeStatus] }} · {{ costText(data.approvedScope.costPolicy, data.approvedScope.costLimit) }} · {{ data.approvedScope.decisionReason }}</p>
        <p v-if="data.costSummary">本维修已核实累计费用 {{ data.costSummary.confirmedTotal }} 元；已收回但费用待核实 {{ data.costSummary.unassessedCount }} 轮。<strong v-if="data.costSummary.overrun">累计费用超出现批准范围，普通结束继续保留，请申请明确新范围。</strong></p>
        <p v-if="data.summary">已实际寄出 {{ data.summary.actualDispatchCount }} 次；{{ data.summary.followupOpen ? '返厂事项仍在办理' : data.status === 'COMPLETED' ? '维修已实际结束，轮次原记录保留' : '本轮事项已记录，维修尚需核实实际结束' }}。</p>
        <fieldset v-if="data.actions.requestScope || data.actions.requestCostScope || data.actions.decideScope || data.actions.withdrawScope || data.actions.cancelRound" :disabled="locked || stale"><label>本次范围或取消原因<textarea v-model="reason" maxlength="1000" /></label>
          <template v-if="data.actions.requestCostScope"><label>本维修所有返厂轮次费用约束<select v-model="costPolicy"><option value="NO_COST">无费用</option><option value="LIMITED">累计限额</option><option value="UNLIMITED">不限费用</option></select></label><label v-if="costPolicy === 'LIMITED'">所有轮次累计限额（元）<input v-model="costLimit" inputmode="decimal" maxlength="15"></label><button @click="prepare('requestCostScope')">申请返厂费用范围变更</button></template>
          <button v-if="data.actions.requestScope && !data.actions.requestCostScope" @click="prepare('requestScope')">申请必要的无费用返厂</button><button v-if="data.actions.decideScope" @click="prepare('approveScope')">批准所申请返厂范围</button><button v-if="data.actions.decideScope" @click="prepare('rejectScope')">驳回返厂范围变更</button><button v-if="data.actions.withdrawScope" @click="prepare('withdrawScope')">撤回尚未决定的范围申请</button><button v-if="data.actions.cancelRound && activeSelected" @click="prepare('cancelRound')">取消尚未寄出的本轮</button>
        </fieldset>
        <fieldset v-if="data.actions.createRound" :disabled="locked || stale"><h4>新建一轮待寄出记录</h4><label>厂家或服务商<input v-model="provider" maxlength="200"></label><label>服务联系人<input v-model="contact" maxlength="100"></label><label>服务电话<input v-model="phone" maxlength="64"></label><label>预计收回日期<input v-model="due" type="date"></label><label>本轮实际故障<textarea v-model="fault" maxlength="2000" /></label><button @click="prepare('createRound')">核对新建待寄出轮次</button></fieldset>
        <p v-if="!data.actions.requestScope && !data.approvedScope && !data.pendingScope">当前处理库管须先完成实际接修，再提出必要返厂的范围变更。</p>
        <h4>独立轮次 / 共 {{ total }} 轮</h4><ul><li v-for="row in rows" :key="row.id"><button :disabled="locked" @click="selectRound(row)">第 {{ row.roundNumber }} 轮 · {{ row.providerName }} · {{ factoryRoundStates[row.roundStatus] }}</button></li></ul><button :disabled="locked || page === 1" @click="paginate(-1)">上一页返厂轮次</button>第 {{ page }} 页<button :disabled="locked || page * 20 >= total" @click="paginate(1)">下一页返厂轮次</button>
        <section v-if="selected" aria-label="所选返厂轮次"><h4>第 {{ selected.roundNumber }} 轮 · {{ factoryRoundStates[selected.roundStatus] }}</h4><p>{{ selected.providerName }} · {{ selected.serviceContact }} · {{ selected.servicePhone }}；预计收回 {{ selected.expectedReturnDate }}。</p><p>{{ selected.fault }}</p><p>实际寄出 {{ selected.dispatchedAt || '尚未记录' }}；实际原机收回 {{ selected.receivedAt || '尚未记录' }}。</p><p v-if="selected.receivedAt">实际状况：{{ selected.conditionResult === 'RESTORED' ? '已恢复' : '仍有故障，继续维修' }}；{{ selected.treatmentResult }}；本轮实际费用 {{ selected.actualCost === null ? '待核实' : selected.actualCost + ' 元' }}。</p><p v-if="selected.cancelReason">取消原因：{{ selected.cancelReason }}。本轮未计作实际返厂次数。</p>
          <h4>本轮原始凭证</h4><p v-if="!data.photosAvailable">原件服务暂不可用。</p><ul><li v-for="item in selected.files" :key="item.objectId">{{ item.filename }}<button :disabled="locked || item.scanStatus !== 'CLEAN'" @click="readFile(item)">读取厂家凭证 {{ item.filename }}</button></li></ul>
          <fieldset v-if="activeSelected && data.actions.uploadEvidence || selected.actions?.uploadFinancialEvidence" :disabled="locked || stale"><label>本次凭证用途<select v-model="purpose"><option value="FAULT" :disabled="!activeSelected">实际故障</option><option value="FACTORY_ACK" :disabled="!activeSelected">厂家接收依据</option><option value="RECEIPT" :disabled="selected.roundStatus !== 'DISPATCHED'">原机实际收回依据</option><option value="QUOTE" :disabled="!selected.actions?.uploadFinancialEvidence">本轮报价原件</option><option value="COST_RECEIPT" :disabled="!selected.actions?.uploadFinancialEvidence">本轮实际费用凭证</option></select></label><label>选择图片或PDF原件<input type="file" accept=".jpg,.jpeg,.png,.webp,.pdf" @change="fileChosen"></label><p v-if="file">已选原件：{{ file.name }}</p><button @click="prepare('upload')">核对追加本轮凭证</button></fieldset>
          <p v-for="(quote, index) in selected.quotes || []" :key="index">本轮报价 {{ quote.amount }} 元（不计作实际费用）</p>
          <p v-if="selected.actualCost !== null">本轮已确认最终费用 {{ selected.actualCost }} 元。</p>
          <fieldset v-if="selected.actions?.recordQuote || selected.actions?.recordCost" :disabled="locked || stale"><h4>本轮报价与最终费用</h4><p>先追加本轮对应用途原件，再核对金额。最终费用确认后保留原记录。</p><label>本次金额（元，最多两位小数）<input v-model="moneyAmount" inputmode="decimal" maxlength="15"></label><label>本次费用核对依据<textarea v-model="moneyReason" maxlength="1000" /></label>
            <label v-for="item in financialEvidence" :key="item.object_id"><input v-model="moneyEvidence" type="checkbox" :value="item.object_id">{{ item.purpose === 'QUOTE' ? '报价原件：' : '费用原件：' }}{{ selected.files.find(file => file.objectId === item.object_id)?.filename }}</label>
            <label><input v-model="moneyChecked" type="checkbox">已亲自核对本次金额及所选本轮原件</label><button v-if="selected.actions?.recordQuote" @click="prepare('quote')">核对登记本轮报价</button><button v-if="selected.actions?.recordCost" @click="prepare('cost')">核对确认本轮最终费用</button>
          </fieldset>
          <fieldset v-if="activeSelected && (data.actions.dispatch || data.actions.receive)" :disabled="locked || stale"><h4>{{ data.actions.receive ? '核实原实物实际收回' : '登记实物实际寄出' }}</h4><p>本轮原机 {{ selected.originalIdentity.assetCode }}{{ selected.originalIdentity.serialNumber ? ` · 序列号 ${selected.originalIdentity.serialNumber}` : '' }}。</p><label>现场核对的资产编码<input v-model="observedCode" maxlength="2048"></label><label v-if="selected.originalIdentity.serialNumber">现场核对的原机序列号<input v-model="serial" maxlength="128"></label><label>实际办理日期<input v-model="actualDate" type="date"></label><label>实际办理时刻（含秒，本机时区）<input v-model="actualTime" type="time" step="1"></label><label><input v-model="originalChecked" type="checkbox">已核实是本轮原实物，编码与身份一致</label>
            <label v-for="item in eligibleEvidence" :key="item.object_id"><input v-model="selectedEvidence" type="checkbox" :value="item.object_id">选用本轮{{ evidencePurpose === 'RECEIPT' ? '原机收回' : '厂家接收' }}原件 {{ selected.files.find(file => file.objectId === item.object_id)?.filename || '待核对原件' }}</label><p v-if="!eligibleEvidence.length">请先上传本轮对应用途的厂家原件，再明确选用。</p>
            <template v-if="data.actions.receive"><label>原机实际状况<select v-model="condition"><option value="RESTORED">已恢复</option><option value="STILL_FAULTY">仍有故障，继续维修</option></select></label><button @click="loadLocations()">查询有效收回地点</button><label v-for="location in locations" :key="location.id"><input v-model="locationId" type="radio" :value="location.id">{{ location.name }}</label><button :disabled="locationPage === 1" @click="loadLocations(-1)">上一页收回地点</button><button :disabled="locationPage * 20 >= locationTotal" @click="loadLocations(1)">下一页收回地点</button><label><input v-model="noCostChecked" type="checkbox">{{ selected.actualCost === null ? '已核实本轮实际没有费用' : '已核对本轮已记录最终费用 ' + selected.actualCost + ' 元' }}</label><label v-if="selected.actualCost === null"><input v-model="costUnassessed" type="checkbox">费用尚待核实，先记录原机实际收回</label></template>
            <label>{{ data.actions.receive ? '实际收回状况与处理结果' : '实物寄出与厂家接收依据' }}<textarea v-model="evidence" maxlength="2000" /></label><button v-if="data.actions.dispatch" @click="prepare('dispatch')">核对实际寄出原实物</button><button v-if="data.actions.receive" @click="prepare('receive')">核对原实物已实际收回</button>
          </fieldset>
          <RepairFactoryPartsPanel v-if="selected.roundStatus === 'RECEIVED'" :key="`${identity}:${selected.id}`" :repair="props.repair" :round="selected" :blocked="props.blocked || busy || !!pending || !!confirmation || stale" @busy-change="partsBusy = $event" @changed="load" @invalidated="reset();emit('invalidated')" @read-file="readFile" />
          <figure v-if="preview"><img v-if="previewMime !== 'application/pdf'" :src="preview" :alt="`厂家凭证：${previewName}`"><p v-else>PDF原件已读取，可保存查看。</p><figcaption>{{ previewName }}</figcaption><a :href="preview" :download="previewName">保存这份厂家原件</a><button @click="clearPreview">关闭厂家凭证</button></figure>
        </section>
        <h4>独立办理历史 / 共 {{ historyTotal }} 条</h4><ul><li v-for="item in history" :key="item.id">{{ item.actor_username }} · {{ new Date(item.recorded_at).toLocaleString() }} · {{ factoryEventNames[item.action] || '历史返厂办理' }}</li></ul><button :disabled="locked || historyPage === 1" @click="paginate(-1,true)">上一页返厂历史</button>第 {{ historyPage }} 页<button :disabled="locked || historyPage * 20 >= historyTotal" @click="paginate(1,true)">下一页返厂历史</button>
      </template>
    </template>
  </section>
</template>
<style scoped>
.factory-panel { border-top: 1px solid #d9e0e8; padding-top: 16px; } fieldset { border: 1px solid #d9e0e8; padding: 16px; margin: 16px 0; } label { display: block; margin: 12px 0; } textarea { display: block; width: min(100%, 720px); min-height: 70px; } button { margin: 6px; } img { max-width: min(100%, 800px); max-height: 560px; }
</style>
