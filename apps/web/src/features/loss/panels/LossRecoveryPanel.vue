<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { lossRequest, type LossIntent, type LossRecovery, type LossRecoveryAsset, type LossReport } from "@/features/loss/api";
import { businessRequest } from "@/api/business";
import { buildTransferTarget, loadCatalogPages, resolveReference } from "@/features/transfer/references";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import type { TransferChoice, TransferTarget } from "@fams/api-types/transfer";

const props = defineProps<{ report: LossReport; locked: boolean }>();
/** 只起草，不提交：幂等键与在途簿记由父视图的确认式提交底座持有。 */
const emit = defineEmits<{ prepare: [intent: LossIntent] }>();

type Action = "recoverOriginal" | "recoverDamaged" | "recoverReturn" | "recoverTransfer" | "recoverFactory" | "requestRecovery" | "requestDamaged" | "requestReturn" | "requestTransfer" | "requestFactoryRecovery"
  | "approve" | "reject" | "cancel" | "claim" | "execute" | "executeDamaged" | "executeReturn" | "executeTransfer" | "executeFactory";

/** 报失单级动作需要的能力位；找回三族都按各自的直接动作复核。 */
const reportCapabilities: Record<string, string> = { recoverOriginal: "recoverOriginal", recoverDamaged: "recoverOriginal", recoverReturn: "recoverOriginal", recoverTransfer: "recoverOriginal", recoverFactory: "recoverFactory", requestRecovery: "requestRecovery", requestDamaged: "requestRecovery", requestReturn: "requestRecovery", requestTransfer: "requestRecovery", requestFactoryRecovery: "requestFactoryRecovery" };
const attemptCapabilities: Record<string, string> = { approve: "decide", reject: "decide", cancel: "cancel", claim: "claim", execute: "execute", executeDamaged: "execute", executeReturn: "execute", executeTransfer: "execute", executeFactory: "execute" };
const attemptPaths: Record<string, string> = { approve: "decision", reject: "decision", cancel: "cancel", claim: "claim", execute: "execute", executeDamaged: "execute-to-repair", executeReturn: "execute-to-return", executeTransfer: "execute-to-transfer", executeFactory: "execute-to-factory" };
const statusLabels: Record<string, string> = { PENDING_ADMIN_DECISION: "待管理员批准找回", WAITING_WAREHOUSE_CLAIM: "待库管重新认领", VERIFYING: "找回实物核实中", COMPLETED: "已完成找回", REJECTED: "找回申请已驳回", CANCELLED: "找回申请已撤回" };
const impacts: Record<string, string> = { LOW: "较低", MEDIUM: "中等", HIGH: "较高" };

const observedCode = shallowRef(""), serial = shallowRef(""), foundAt = shallowRef(""), evidence = shallowRef(""), reason = shallowRef(""), checked = shallowRef(false);
const returnChecked = shallowRef(false), locations = ref<{ id: string; name: string; version: number }[]>([]), locationIndex = shallowRef(""), locationPage = shallowRef(0), locationTotal = shallowRef(0), loadingLocations = shallowRef(false);
const damagedChecked = shallowRef(false), fault = shallowRef(""), impact = shallowRef("MEDIUM");
const transferChecked = shallowRef(false), sameTransferCustodian = shallowRef(false), transferReason = shallowRef(""), targetCustody = shallowRef("PERSONAL");
const factoryChecked = shallowRef(false), factoryCondition = shallowRef("INTACT");
const targetOwner = shallowRef(""), targetOrganization = shallowRef(""), targetPerson = shallowRef(""), targetLocation = shallowRef(""), targetWarehouse = shallowRef("");
/** 目标目录整份替换，与本面板既有的 locations 保持同形。 */
const targetOrganizations = ref<TransferChoice[]>([]), targetPeople = ref<TransferChoice[]>([]);
const targetLocations = ref<TransferChoice[]>([]), targetWarehouses = ref<TransferChoice[]>([]), loadingTarget = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness(() => [props.report.id, props.report.version, props.locked]);

const source = computed(() => props.report.recoveryAsset ?? null);
const showFactoryReturnNote = computed(() => !!props.report.repairId && !!props.report.source?.factorySource);
const showRepairNote = computed(() => !!props.report.repairId && !props.report.source?.factorySource);
const showDamagedSection = computed(() => !!props.report.actions?.recoverOriginal || !!props.report.actions?.requestRecovery || (props.report.recoveries ?? []).some((item) => item.actions.execute && item.request?.facts?.condition === "DAMAGED" && item.request?.destination !== "FACTORY_REPAIR"));
const showReturnSection = computed(() => !!props.report.actions?.recoverOriginal || !!props.report.actions?.requestRecovery || (props.report.recoveries ?? []).some((item) => item.actions.execute && item.request?.destination === "ASSET_RETURN"));
const locationsLoaded = computed(() => locationPage.value > 0);
const locationsSummary = computed(() => `已加载${locations.value.length}/${locationTotal.value}个位置`);
const loadLocationsLabel = computed(() => locationsLoaded.value ? "继续加载位置" : "加载实际位置");
const loadTransferLocationsLabel = computed(() => locationsLoaded.value ? "继续加载实际位置" : "加载实物实际位置");
const eligibleTargetPeople = computed(() => targetPeople.value.filter(
  person => targetCustody.value !== "SHARED" || person.sharedEligible));
const stockTransfer = computed(() => props.report.source?.lifecycle === "IN_STOCK");
const canTransfer = computed(() => stockTransfer.value || props.report.source?.lifecycle === "IN_USE");
const eligibleTargetLocations = computed(() => targetLocations.value.filter(place => stockTransfer.value
  ? place.locationType === "BIN" && place.warehouseId === targetWarehouse.value : place.locationType !== "BIN"));
const transferAttempt = computed(() => props.report.recoveries?.find(
  item => item.actions.execute && item.request?.destination === "TRANSFER"));
const factoryAttempt = computed(() => props.report.recoveries?.find(
  item => item.actions.execute && item.request?.destination === "FACTORY_REPAIR"));
const showFactorySection = computed(() => !!props.report.actions?.recoverFactory || !!props.report.actions?.requestFactoryRecovery || !!factoryAttempt.value);
const showTransferTarget = computed(() => !!props.report.actions?.recoverOriginal || !!transferAttempt.value);
const showTransferSection = computed(() => canTransfer.value && (showTransferTarget.value || !!props.report.actions?.requestRecovery));
/** 申请行显示模型：状态、去向与三段核实文案在此拼装，按钮可见性按申请单自身事实判定，模板只读现成字段。 */
const attemptRows = computed(() => (props.report.recoveries ?? []).map((attempt) => {
  const facts = attempt.request?.facts ?? {};
  const damaged = facts.condition === "DAMAGED";
  const returned = attempt.request?.destination === "ASSET_RETURN";
  const transferred = attempt.request?.destination === "TRANSFER";
  const factored = attempt.request?.destination === "FACTORY_REPAIR";
  return { ...attempt,
    statusText: statusLabels[attempt.status] ?? attempt.status,
    processorText: attempt.processorAccountId ?? "尚未认领",
    foundAtText: show(facts.foundAt),
    observedCodeText: show(facts.observedAssetCode),
    observedSerialText: showOr(facts.observedSerialNumber, "未登记"),
    locationIdText: show(facts.locationId),
    destinationText: returned ? "关联归还/退库，原责任待实物验收后移交" : transferred ? "关联正式调拨" : factored ? "交回原返厂维修" : "原责任恢复或维修",
    conditionText: facts.condition === "INTACT" ? "完好" : damaged ? "损坏，批准后转正式维修" : "需关联处理",
    custodianText: facts.stillOriginalCustodian === true ? "仍由原责任人保管" : "责任需核对",
    identityText: facts.physicalIdentityVerified === true ? "已对照原机身份" : "身份待核对",
    evidenceText: show(facts.evidence),
    showReason: attempt.actions.decide || attempt.actions.cancel,
    executeOriginalShown: attempt.actions.execute && !damaged && !returned && !transferred && !factored,
    executeDamagedShown: attempt.actions.execute && damaged && !factored,
    executeReturnShown: attempt.actions.execute && returned };
}));

function show(value: unknown): string { return value === undefined || value === null ? "" : String(value); }
function showOr(value: unknown, fallback: string): string { return value ? String(value) : fallback; }

async function loadLocations() {
  if (props.locked || loadingLocations.value) return;
  const turn = liveness.begin();
  loadingLocations.value = true;
  try {
    const result = await lossRequest<{ items: { id: string; name: string; version: number }[]; total: number }>(`/recovery-locations?page=${locationPage.value + 1}&pageSize=100`);
    if (!liveness.isCurrent(turn)) return;
    locations.value.push(...result.items); locationTotal.value = result.total; locationPage.value++;
  } catch { error.value = "位置未加载，请重试。"; }
  finally { loadingLocations.value = false; }
}

/** 实物实际所在的那一条位置记录：locations 由分页 push 追加，读取必须每次现算，不能缓存。 */
function selectedPlace() { return locations.value.find((item) => item.id === locationIndex.value) ?? null; }

/** 完好找回后转正式调拨：目标目录沿用调拨域的分页扇出与目标装配，请求客户端仍是跨域写内核。 */
function transferCatalog(kind: string, organization?: string): Promise<TransferChoice[]> {
  return loadCatalogPages<TransferChoice>(businessRequest, "/transfers/catalog", kind, organization);
}
async function loadTransferTarget() {
  if (props.locked || loadingTarget.value) return;
  loadingTarget.value = true;
  error.value = "";
  try {
    const [organizations, places, warehouses] = await Promise.all([
      transferCatalog("organization"), transferCatalog("location"),
      stockTransfer.value ? transferCatalog("warehouse") : Promise.resolve([]),
    ]);
    targetOrganizations.value = organizations;
    targetLocations.value = places;
    targetWarehouses.value = warehouses;
  } catch { error.value = "调拨目标目录未加载，请重试。"; }
  finally { loadingTarget.value = false; }
}
async function loadTransferPeople() {
  targetPerson.value = "";
  targetPeople.value = [];
  const organization = targetOrganization.value;
  if (!organization) return;
  loadingTarget.value = true;
  error.value = "";
  try {
    const people = await transferCatalog("employee", organization);
    // 目标槽位与上面的目录读取互不相干，迟到判据只能按部门值现算，不能共用一代序号互相作废写回。
    if (targetOrganization.value === organization) targetPeople.value = people;
  } catch { error.value = "目标责任人员未加载，请重新选择部门。"; }
  finally { loadingTarget.value = false; }
}
function transferTarget(): TransferTarget {
  const owner = resolveReference(targetOrganizations.value, targetOwner.value);
  const place = resolveReference(eligibleTargetLocations.value, targetLocation.value);
  if (!place || (targetOwner.value && !owner) || !transferReason.value.trim()) {
    throw new Error("请选择有效的目标位置，并填写调拨原因。");
  }
  if (stockTransfer.value) {
    const warehouse = resolveReference(targetWarehouses.value, targetWarehouse.value);
    if (!warehouse) throw new Error("请选择目标仓库和仓位。");
    return buildTransferTarget({ stock: true, custodyType: targetCustody.value, location: place, owner, warehouse });
  }
  const usingOrganization = resolveReference(targetOrganizations.value, targetOrganization.value);
  const employee = resolveReference(eligibleTargetPeople.value, targetPerson.value);
  if (!usingOrganization || !employee) throw new Error("请选择有效的调拨目标部门和责任人。");
  return buildTransferTarget({ stock: false, custodyType: targetCustody.value, location: place, owner, usingOrganization, employee });
}

/** 完好原机但保管或位置已变化：核实事实按实际位置登记，后续走正式归还/退库。 */
function returnFacts() {
  const place = selectedPlace(), date = new Date(foundAt.value);
  if (!source.value || !returnChecked.value || !place || !Number.isFinite(date.getTime()) || !observedCode.value.trim() || source.value.serialNumber && !serial.value.trim() || evidence.value.trim().length < 10) {
    throw new Error("请核对完好原机、实际位置、找回时间及至少10字变更保管证据，并确认关联归还/退库。");
  }
  return { foundAt: date.toISOString(), observedAssetCode: observedCode.value.trim(), observedSerialNumber: serial.value.trim(), locationId: place.id, locationVersion: place.version, physicalIdentityVerified: true, condition: "INTACT", stillOriginalCustodian: false, evidence: evidence.value.trim() };
}

/** 完好找回并转正式调拨：事实按实物实际位置登记，保管是否仍归原责任人由现场勾选决定。 */
function transferFacts() {
  const place = selectedPlace(), date = new Date(foundAt.value);
  if (!canTransfer.value || !source.value || !transferChecked.value || !place || !Number.isFinite(date.getTime()) || !observedCode.value.trim() || source.value.serialNumber && !serial.value.trim() || evidence.value.trim().length < 10) {
    throw new Error("请核对完好原机、实际位置、找回时间和至少10字证据，并确认实际保管或位置变化。");
  }
  return { foundAt: date.toISOString(), observedAssetCode: observedCode.value.trim(), observedSerialNumber: serial.value.trim(), locationId: place.id, locationVersion: place.version, physicalIdentityVerified: true, condition: "INTACT", stillOriginalCustodian: sameTransferCustodian.value, evidence: evidence.value.trim() };
}

/** 返厂原机找回：实物按现场位置登记，锁交回原返厂维修，实际收回仍在原轮次办理。 */
function factoryFacts() {
  const place = selectedPlace(), date = new Date(foundAt.value);
  if (!source.value || !factoryChecked.value || !place || !Number.isFinite(date.getTime()) || !observedCode.value.trim() || source.value.serialNumber && !serial.value.trim() || evidence.value.trim().length < 10) {
    throw new Error("请核对找回原机、实际位置、时间及至少10字证据。");
  }
  return { foundAt: date.toISOString(), observedAssetCode: observedCode.value.trim(), observedSerialNumber: serial.value.trim(), locationId: place.id, locationVersion: place.version, physicalIdentityVerified: true, condition: factoryCondition.value, stillOriginalCustodian: false, evidence: evidence.value.trim() };
}

/** 原机在原登记位置发现损坏：连同故障与使用影响一起转入正式维修。 */
function damagedFacts() {
  const date = new Date(foundAt.value);
  if (!source.value || !damagedChecked.value || !Number.isFinite(date.getTime()) || !observedCode.value.trim() || source.value.serialNumber && !serial.value.trim() || evidence.value.trim().length < 10 || !source.value.locationId || source.value.locationVersion === null) {
    throw new Error("请核对原机身份、实际找回时间、至少10字证据及故障，并确认原责任原地损坏实物。");
  }
  return { foundAt: date.toISOString(), observedAssetCode: observedCode.value.trim(), observedSerialNumber: serial.value.trim(), locationId: source.value.locationId, locationVersion: source.value.locationVersion, physicalIdentityVerified: true, condition: "DAMAGED", stillOriginalCustodian: true, evidence: evidence.value.trim() };
}

/** 完好原机仍由原责任人在登记位置保管：核实后可直接结束查找或转入新找回申请。 */
function intactFacts() {
  const date = new Date(foundAt.value);
  if (!source.value || !checked.value || !Number.isFinite(date.getTime()) || !observedCode.value.trim() || evidence.value.trim().length < 10 || !source.value.locationId || source.value.locationVersion === null || source.value.serialNumber && !serial.value.trim()) {
    throw new Error("请对照原机编码/序列号，填写实际找回时间及至少10字证据，并确认完好、原保管与登记位置。");
  }
  return { foundAt: date.toISOString(), observedAssetCode: observedCode.value.trim(), observedSerialNumber: serial.value.trim(), locationId: source.value.locationId, locationVersion: source.value.locationVersion, physicalIdentityVerified: true, condition: "INTACT", stillOriginalCustodian: true, evidence: evidence.value.trim() };
}

/** 新建申请只带资产版本；直接办理与在办申请执行时带齐三个乐观版本。 */
function recoveryBody(facts: unknown, requestOnly: boolean, row: LossReport, attempt?: LossRecovery, asset?: LossRecoveryAsset | null) {
  return requestOnly ? { assetVersion: asset?.version, facts }
    : { assetVersion: attempt?.assetVersion ?? row.assetVersion, ownershipVersion: attempt?.ownershipVersion ?? row.ownershipVersion, lockVersion: row.lockVersion, facts };
}

function draftOf(action: Action, attempt?: LossRecovery): { path: string; body: unknown; summary: string; version: number } {
  const row = props.report;
  if (action === "recoverFactory" || action === "requestFactoryRecovery" || action === "executeFactory") {
    const facts = factoryFacts(), requestOnly = action === "requestFactoryRecovery";
    return { path: requestOnly ? `/${row.id}/factory-recovery-requests` : attempt ? `/recoveries/${attempt.id}/execute-to-factory` : `/${row.id}/recover-to-factory`,
      body: recoveryBody(facts, requestOnly, row, attempt, source.value),
      summary: requestOnly ? "提交原维修续办的找回申请，待批准和重新认领。" : "找回并把锁交回原维修。实际收回仍须在原返厂轮次办理。",
      version: attempt?.version ?? row.version };
  }
  if (action === "recoverTransfer" || action === "requestTransfer" || action === "executeTransfer") {
    const facts = transferFacts(), requestOnly = action === "requestTransfer";
    if (requestOnly) {
      return { path: `/${row.id}/transfer-recovery-requests`, body: { assetVersion: source.value?.version, facts },
        summary: `申请找回后调拨 ${facts.observedAssetCode}，须重新批准并认领找回。`, version: row.version };
    }
    const target = transferTarget();
    const person = resolveReference(targetPeople.value, target.employeeId);
    const destination = resolveReference(targetLocations.value, target.locationId);
    const warehouse = resolveReference(targetWarehouses.value, target.warehouseId);
    return { path: attempt ? `/recoveries/${attempt.id}/execute-to-transfer` : `/${row.id}/recover-to-transfer`,
      body: { recovery: recoveryBody(facts, false, row, attempt), target, reason: transferReason.value.trim() },
      summary: `找回并提交调拨 ${facts.observedAssetCode}，实际位置 ${selectedPlace()?.name}，目标 ${warehouse?.name ?? person?.name} / ${destination?.name}。原责任和位置保留至实物履行。`,
      version: attempt?.version ?? row.version };
  }
  if (action === "recoverReturn" || action === "requestReturn" || action === "executeReturn") {
    const facts = returnFacts(), requestOnly = action === "requestReturn";
    return { path: requestOnly ? `/${row.id}/return-recovery-requests` : attempt ? `/recoveries/${attempt.id}/execute-to-return` : `/${row.id}/recover-to-return`,
      body: recoveryBody(facts, requestOnly, row, attempt, source.value),
      summary: `${requestOnly ? "新建归还/退库找回申请，须新批准并认领" : "找回并建立正式归还/退库"}：${facts.observedAssetCode}；实际位置${selectedPlace()?.name}；${facts.evidence}。原责任和借用保留至退库审批及实物验收，当前锁继续保留，尚未成为可用库存。`,
      version: attempt?.version ?? row.version };
  }
  if (action === "recoverDamaged" || action === "requestDamaged" || action === "executeDamaged") {
    const facts = damagedFacts();
    if (action !== "requestDamaged" && fault.value.trim().length < 10) throw new Error("请核对原机身份、实际找回时间、至少10字证据及故障，并确认原责任原地损坏实物。");
    if (action === "requestDamaged") {
      return { path: `/${row.id}/repair-recovery-requests`, body: { assetVersion: source.value?.version, facts },
        summary: `新建损坏找回申请：${observedCode.value.trim()}；${evidence.value.trim()}。须管理员批准并重新认领核实后才能转正式维修，当前不恢复正常，原遗失事实和责任保留。`,
        version: row.version };
    }
    return { path: attempt ? `/recoveries/${attempt.id}/execute-to-repair` : `/${row.id}/recover-to-repair`,
      body: { recovery: recoveryBody(facts, false, row, attempt), fault: fault.value.trim(), impact: impact.value },
      summary: `原机损坏找回并建立正式维修：${observedCode.value.trim()}，故障：${fault.value.trim()}；资产继续由维修单锁定，须管理员批准及库管实际处理，原责任和借用日期保留。`,
      version: attempt?.version ?? row.version };
  }
  if (action === "recoverOriginal" || action === "requestRecovery" || action === "execute") {
    const facts = intactFacts(), requestOnly = action === "requestRecovery";
    return { path: requestOnly ? `/${row.id}/recovery-requests` : attempt ? `/recoveries/${attempt.id}/execute` : `/${row.id}/recover-original`,
      body: recoveryBody(facts, requestOnly, row, attempt, source.value),
      summary: `${requestOnly ? "新建找回申请，批准认领后才能恢复" : "核实后恢复原责任资产"}：${facts.observedAssetCode}，序列号${facts.observedSerialNumber || "未登记"}，找回时间${facts.foundAt}，登记位置${source.value?.locationName}；${facts.evidence}。原借用不改期，历史遗失事实保留。`,
      version: attempt?.version ?? row.version };
  }
  // 在办找回申请的审批、撤回与认领。
  if (action !== "claim" && reason.value.trim().length < 3) throw new Error("请填写至少3字本次原因。");
  const body = action === "claim" ? undefined : action === "cancel" ? { reason: reason.value.trim() } : { result: action === "approve" ? "APPROVE" : "REJECT", reason: reason.value.trim() };
  const summary = action === "claim" ? "取得新的唯一找回处理权，尚未恢复资产。"
    : `${action === "approve" ? "批准新找回申请，仍须库管核实执行" : action === "reject" ? "驳回找回申请，保留原遗失与责任跟进" : "撤回新找回申请，保留原遗失与责任跟进"}；${reason.value.trim()}`;
  return { path: `/recoveries/${attempt?.id}/${attemptPaths[action]}`, body, summary, version: attempt?.version ?? row.version };
}

/** 执行按钮必须与申请单自身事实同族：归还退库、损坏转维修与原机恢复互斥。 */
function matchesAttempt(action: Action, attempt: LossRecovery | undefined): boolean {
  if (!attempt) return true;
  if (action === "execute" || action === "executeDamaged" || action === "executeReturn" || action === "executeTransfer" || action === "executeFactory") {
    if ((attempt.request?.destination === "ASSET_RETURN") !== (action === "executeReturn")) return false;
    if ((attempt.request?.destination === "TRANSFER") !== (action === "executeTransfer")) return false;
    if ((attempt.request?.destination === "FACTORY_REPAIR") !== (action === "executeFactory")) return false;
  }
  if (action === "execute" || action === "executeDamaged") {
    if ((attempt.request?.facts?.condition === "DAMAGED") !== (action === "executeDamaged")) return false;
  }
  return true;
}

function prepare(action: Action, attempt?: LossRecovery) {
  if (props.locked) return;
  const row = props.report;
  const capable = attempt ? attempt.actions[attemptCapabilities[action]] : row.actions?.[reportCapabilities[action]];
  if (!capable || !matchesAttempt(action, attempt)) return;
  error.value = "";
  try {
    const draft = draftOf(action, attempt);
    emit("prepare", Object.freeze({ path: draft.path, version: draft.version, body: draft.body, summary: draft.summary }));
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "请核实当前原机与责任。"; }
}
</script>
<template>
  <section class="fams-panel fams-stack" aria-label="原责任完好找回">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">找回核实与实际去向</h3>
        <p class="fams-panel-subtitle">查找中沿原核实授权；已确认遗失须新建申请并重新批准认领。责任或位置变化须关联后续；损坏找回通过对应申请与核实转入正式维修。</p>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="report.recoveredAt">原实物已找回：{{ report.recoveredAt }}。原遗失及后续履历继续保留。</p>
    <p v-if="showFactoryReturnNote">已交回原返厂维修 {{ report.repairId }}，请在原轮次登记实际收回。</p>
    <p v-if="showRepairNote">已关联正式维修 {{ report.repairId }}，请前往维修管理查看审批和实际办理进度。</p>
    <fieldset class="fams-card fams-stack" :disabled="locked">
      <template v-if="report.recoveryAsset">
        <p>登记位置：{{ report.recoveryAsset.locationName ?? '缺少登记位置' }}；登记序列号：{{ report.recoveryAsset.serialNumber ?? '未登记' }}</p>
        <label class="fams-field"><span class="fams-field-label">实际原机编码</span><input class="fams-control" v-model="observedCode" maxlength="64" /></label>
        <label class="fams-field"><span class="fams-field-label">实际原机序列号（登记有值时必填）</span><input class="fams-control" v-model="serial" maxlength="128" /></label>
        <label class="fams-field"><span class="fams-field-label">实际找回时间</span><input class="fams-control" v-model="foundAt" type="datetime-local" step="1" /></label>
        <label class="fams-field"><span class="fams-field-label">找回实物及原保管核实证据</span><textarea class="fams-control" v-model="evidence" maxlength="2000" /></label>
        <label class="fams-selection"><input v-model="checked" type="checkbox">已对照原机身份，亲见实物完好且仍由原责任人在登记位置保管</label>
      </template>
      <div class="fams-panel-actions">
        <button v-if="report.actions?.recoverOriginal" class="fams-button" data-kind="primary" type="button" @click="prepare('recoverOriginal')">核实原机并结束查找</button>
        <button v-if="report.actions?.requestRecovery" class="fams-button" data-kind="secondary" type="button" @click="prepare('requestRecovery')">新建找回申请</button>
      </div>
      <section v-if="showDamagedSection" class="fams-card fams-stack" aria-label="损坏找回转维修">
        <h4 class="fams-panel-title">原责任原地损坏找回</h4>
        <p>填写上方原机身份、实际找回时间及证据，另核实以下损坏事实。</p>
        <label class="fams-field"><span class="fams-field-label">发现的故障</span><textarea class="fams-control" v-model="fault" maxlength="2000" /></label>
        <label class="fams-field"><span class="fams-field-label">使用影响</span><select class="fams-control" v-model="impact"><option v-for="(label, key) in impacts" :key="key" :value="key">{{ label }}</option></select></label>
        <label class="fams-selection"><input v-model="damagedChecked" type="checkbox">已亲见损坏原机仍由原责任人在登记位置保管，申请正式维修</label>
        <div class="fams-panel-actions">
          <button v-if="report.actions?.recoverOriginal" class="fams-button" data-kind="primary" type="button" @click="prepare('recoverDamaged')">损坏找回并申请维修</button>
          <button v-if="report.actions?.requestRecovery" class="fams-button" data-kind="secondary" type="button" @click="prepare('requestDamaged')">新建损坏找回申请</button>
        </div>
      </section>
      <section v-if="showReturnSection" class="fams-card fams-stack" aria-label="找回关联归还退库">
        <h4 class="fams-panel-title">保管或位置变化，关联归还/退库</h4>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="loadingLocations || locked" @click="loadLocations">{{ loadLocationsLabel }}</button>
        </div>
        <p v-if="locationsLoaded">{{ locationsSummary }}</p>
        <label class="fams-field"><span class="fams-field-label">实物实际位置</span><select class="fams-control" v-model="locationIndex"><option value="">请选择实际位置</option><option v-for="place in locations" :key="place.id" :value="place.id">{{ place.name }}</option></select></label>
        <label class="fams-selection"><input v-model="returnChecked" type="checkbox">已亲见完好原机，保管已变化；按上述实际位置和证据关联归还/退库，原责任待实物验收后移交</label>
        <div class="fams-panel-actions">
          <button v-if="report.actions?.recoverOriginal" class="fams-button" data-kind="primary" type="button" @click="prepare('recoverReturn')">找回并申请归还退库</button>
          <button v-if="report.actions?.requestRecovery" class="fams-button" data-kind="secondary" type="button" @click="prepare('requestReturn')">新建归还退库找回申请</button>
        </div>
      </section>
      <section v-if="showTransferSection" class="fams-card fams-stack" aria-label="找回关联调拨">
        <h4 class="fams-panel-title">完好找回后调拨</h4>
        <p>沿用上方填写的原机身份、实际找回时间、实际位置和证据。调拨单须批准，实际收件后才更新责任。</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="loadingLocations || locked" @click="loadLocations">{{ loadTransferLocationsLabel }}</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">找回实物位置</span><select class="fams-control" v-model="locationIndex"><option value="">请选择实际位置</option><option v-for="place in locations" :key="place.id" :value="place.id">{{ place.name }}</option></select></label>
        <label class="fams-selection"><input v-model="transferChecked" type="checkbox">已核实完好原机，现场保管或位置已变化，需要正式调拨</label>
        <label class="fams-selection"><input v-model="sameTransferCustodian" type="checkbox">现场仍由原责任人保管，仅位置变化</label>
        <template v-if="showTransferTarget">
          <div class="fams-panel-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="loadingTarget" @click="loadTransferTarget">加载调拨目标目录</button>
          </div>
          <fieldset :disabled="loadingTarget">
            <label class="fams-field"><span class="fams-field-label">目标所属组织</span><select class="fams-control" v-model="targetOwner"><option value="">未指定</option><option v-for="org in targetOrganizations" :key="org.id" :value="org.id">{{ org.name }}</option></select></label>
            <label v-if="stockTransfer" class="fams-field"><span class="fams-field-label">目标仓库</span><select class="fams-control" v-model="targetWarehouse" @change="targetLocation = ''"><option value="">请选择仓库</option><option v-for="warehouse in targetWarehouses" :key="warehouse.id" :value="warehouse.id">{{ warehouse.name }}</option></select></label>
            <template v-else>
              <label class="fams-field"><span class="fams-field-label">目标使用部门</span><select class="fams-control" v-model="targetOrganization" @change="loadTransferPeople"><option value="">请选择部门</option><option v-for="org in targetOrganizations" :key="org.id" :value="org.id">{{ org.name }}</option></select></label>
              <label class="fams-field"><span class="fams-field-label">目标保管方式</span><select class="fams-control" v-model="targetCustody" @change="targetPerson = ''"><option value="PERSONAL">个人</option><option value="SHARED">部门公用</option></select></label>
              <label class="fams-field"><span class="fams-field-label">目标责任人</span><select class="fams-control" v-model="targetPerson"><option value="">请选择责任人</option><option v-for="person in eligibleTargetPeople" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
            </template>
            <label class="fams-field"><span class="fams-field-label">目标位置</span><select class="fams-control" v-model="targetLocation"><option value="">请选择目标位置</option><option v-for="place in eligibleTargetLocations" :key="place.id" :value="place.id">{{ place.name }}</option></select></label>
            <label class="fams-field"><span class="fams-field-label">调拨原因</span><textarea class="fams-control" v-model="transferReason" maxlength="1000" /></label>
            <div class="fams-panel-actions">
              <button v-if="report.actions?.recoverOriginal" class="fams-button" data-kind="primary" type="button" @click="prepare('recoverTransfer')">找回并提交调拨</button>
              <button v-if="transferAttempt" class="fams-button" data-kind="primary" type="button" @click="prepare('executeTransfer', transferAttempt)">核实找回并提交调拨</button>
            </div>
          </fieldset>
        </template>
        <div class="fams-panel-actions">
          <button v-if="report.actions?.requestRecovery" class="fams-button" data-kind="secondary" type="button" @click="prepare('requestTransfer')">新建调拨找回申请</button>
        </div>
      </section>
      <section v-if="showFactorySection" class="fams-card fams-stack" aria-label="返厂原机找回续修">
        <h4 class="fams-panel-title">返厂原机找回续修</h4>
        <p>填写上方原机编码、序列号、找回时间及证据。原维修继续办理实际收回。</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="loadingLocations || locked" @click="loadLocations">加载找回实物位置</button>
        </div>
        <label class="fams-field"><span class="fams-field-label">原机找回位置</span><select class="fams-control" v-model="locationIndex"><option value="">请选择实际位置</option><option v-for="place in locations" :key="place.id" :value="place.id">{{ place.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">找回时原机状况</span><select class="fams-control" v-model="factoryCondition"><option value="INTACT">完好</option><option value="DAMAGED">损坏</option></select></label>
        <label class="fams-selection"><input v-model="factoryChecked" type="checkbox">已亲见并对照返厂原机</label>
        <div class="fams-panel-actions">
          <button v-if="report.actions?.recoverFactory" class="fams-button" data-kind="primary" type="button" @click="prepare('recoverFactory')">找回并交回原维修</button>
          <button v-if="report.actions?.requestFactoryRecovery" class="fams-button" data-kind="secondary" type="button" @click="prepare('requestFactoryRecovery')">新建返厂原机找回申请</button>
          <button v-if="factoryAttempt" class="fams-button" data-kind="primary" type="button" @click="prepare('executeFactory', factoryAttempt)">核实找回并交回原维修</button>
        </div>
      </section>
      <p v-if="report.transferId">已关联正式调拨 {{ report.transferId }}，请在调拨管理继续审批和实物交接。</p>
      <p v-if="report.returnId">已关联正式归还/退库 {{ report.returnId }}，请在借用与退库管理继续审批及实物验收。</p>
      <p>近期找回申请：{{ report.recoveriesTotal ?? 0 }}项（展示最近20项及当前在办项）</p>
      <article v-for="attempt in attemptRows" :key="attempt.id" class="fams-card fams-stack">
        <h4 class="fams-panel-title">找回申请 {{ attempt.id }} · {{ attempt.statusText }}</h4>
        <p>新处理库管：{{ attempt.processorText }}；发现时间：{{ attempt.foundAtText }}</p>
        <dl class="fams-facts" aria-label="本次找回申请证据">
          <div><dt>申请核实的原机编码</dt><dd>{{ attempt.observedCodeText }}</dd></div>
          <div><dt>申请核实的原机序列号</dt><dd>{{ attempt.observedSerialText }}</dd></div>
          <div><dt>申请登记位置编号</dt><dd>{{ attempt.locationIdText }}</dd></div>
          <div><dt>本次申请去向</dt><dd>{{ attempt.destinationText }}</dd></div>
          <div><dt>申请核实状况</dt><dd>{{ attempt.conditionText }}；{{ attempt.custodianText }}；{{ attempt.identityText }}</dd></div>
          <div><dt>申请核实证据</dt><dd>{{ attempt.evidenceText }}</dd></div>
        </dl>
        <label v-if="attempt.showReason" class="fams-field"><span class="fams-field-label">本次找回审批/撤回原因</span><textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
        <div class="fams-panel-actions">
          <button v-if="attempt.actions.decide" class="fams-button" data-kind="primary" type="button" @click="prepare('approve', attempt)">批准找回申请</button>
          <button v-if="attempt.actions.decide" class="fams-button" data-kind="danger" type="button" @click="prepare('reject', attempt)">驳回找回申请</button>
          <button v-if="attempt.actions.cancel" class="fams-button" data-kind="secondary" type="button" @click="prepare('cancel', attempt)">撤回找回申请</button>
          <button v-if="attempt.actions.claim" class="fams-button" data-kind="secondary" type="button" @click="prepare('claim', attempt)">重新认领找回任务</button>
          <button v-if="attempt.executeOriginalShown" class="fams-button" data-kind="primary" type="button" @click="prepare('execute', attempt)">核实原机并完成找回</button>
          <button v-if="attempt.executeDamagedShown" class="fams-button" data-kind="primary" type="button" @click="prepare('executeDamaged', attempt)">核实损坏原机并转正式维修</button>
          <button v-if="attempt.executeReturnShown" class="fams-button" data-kind="primary" type="button" @click="prepare('executeReturn', attempt)">核实完好原机并关联归还退库</button>
        </div>
      </article>
    </fieldset>
  </section>
</template>
