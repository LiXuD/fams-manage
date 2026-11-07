<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import FamsMediaPreview from "@/components/FamsMediaPreview.vue";
import RepairCollaborationPanel from "./../panels/RepairCollaborationPanel.vue";
import RepairPhysicalPanel from "./../panels/RepairPhysicalPanel.vue";
import RepairReturnPanel from "./../panels/RepairReturnPanel.vue";
import RepairTargetChangePanel from "./../panels/RepairTargetChangePanel.vue";
import RepairReassignmentPanel from "./../panels/RepairReassignmentPanel.vue";
import RepairInventoryPhoto from "./../panels/RepairInventoryPhoto.vue";
import RepairFactoryPanel from "./../panels/RepairFactoryPanel.vue";
import { factoryEventNames, factoryRoundStates, factoryScopeStates } from "@/features/repair/factory-api";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { useObjectUrlPreview } from "@/composables/useObjectUrlPreview";
import { provideBusyRegistry } from "@/features/repair/busyRegistry";
import { RepairApiError, repairPhotoContent, repairProblem, repairRequest, repairStates, type Repair, type RepairMutation, type RepairPhoto } from "@/features/repair/api";
import { ScrapApiError, scrapRequest, type ScrapIntent } from "@/features/scrap/api";
/** 页面在确认底座里持有的意图：维修突变，或带固定来源段的关联报废意图；关联报废沿用 RepairMutation 自带幂等键。 */
type PageMutation = RepairMutation | (Omit<ScrapIntent, "key"> & { repairSourceId: string; key: string });
/** 关联报废写路径的固定前缀；命中它即将请求交给报废域底座。 */
const LINKED_SCRAP_PREFIX = "/from-repair/";
const LINKED_SCRAP_INCOMPLETE_MESSAGE = "请填写关联报废原因、现状和建议处置方式。";
const LINKED_SCRAP_SUBMITTED_NOTICE = "关联报废申请已保存。";
const LINKED_SCRAP_RECEIPT_LABEL = "查看维修关联报废";
const LINKED_SCRAP_SUMMARY = "提交关联报废，原维修处理权和实物事实继续保留。报废批准后由原处理库管执行。";
const DEFAULT_CONFIRMATION_TEXT = "请核对本次资产、实际依据及处理范围。批准不会自动领取，原地关闭须确认已恢复且实物从未离开原保管人。";
const DEFAULT_SAVED_NOTICE = "本次结果已保存。批准、库管受理和实际修复分别留痕。";
const session = useSessionStore(), route = useRoute();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, route.params.id]));
const detail = ref<Repair | null>(null);
const approvalMethod = shallowRef("INTERNAL_NO_COST"), reason = shallowRef("");
const factoryCostPolicy = shallowRef("NO_COST"), factoryCostLimit = shallowRef("");
const moneyPattern = /^(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,2})?$/;
const observedCode = shallowRef(""), evidence = shallowRef(""), confirmedPlace = shallowRef(false), neverLeft = shallowRef(false);
const selectedFile = ref<File | null>(null), preview = useObjectUrlPreview();
const pending = ref<PageMutation | null>(null), confirmation = ref<PageMutation | null>(null);
const scrapReason = shallowRef(""), scrapCondition = shallowRef(""), scrapMethod = shallowRef(""), scrapReceiptId = ref<string | null>(null);
const busy = shallowRef(false), error = shallowRef(""), notice = shallowRef("");
/** 页内互斥：视图占 host 一位（读取、待确认、在途办理），各面板各占一位，互不逐条转发。 */
const registry = provideBusyRegistry(), host = registry.register("host"), factoryBusy = registry.occupied("factory");
const hostOccupied = computed(() => busy.value || !!pending.value || !!confirmation.value);
const locked = computed(() => hostOccupied.value || host.blocked.value);
watch(hostOccupied, value => { host.busy.value = value; }, { immediate: true, flush: "sync" });
const confirmationFactoryScope = computed(() => (confirmation.value?.body as Record<string, unknown> | undefined)?.allowedMethod === "INTERNAL_OR_FACTORY");
const confirmationCostScopeText = computed(() => {
  const body = confirmation.value?.body as Record<string, unknown> | undefined;
  if (body?.factoryCostPolicy === "UNLIMITED") return "不限费用";
  if (body?.factoryCostPolicy === "LIMITED") return `所有轮次累计限额 ${body.factoryCostLimit} 元`;
  return "无费用";
});
const occurredAtText = computed(() => detail.value ? new Date(detail.value.occurredAt).toLocaleString() : "");
const approvalIncludesFactory = computed(() => approvalMethod.value === "INTERNAL_OR_FACTORY");
const factoryCostLimited = computed(() => factoryCostPolicy.value === "LIMITED");
const approveButtonText = computed(() => approvalIncludesFactory.value ? "批准内部或必要时返厂" : "批准内部无费用处理");
const inPlacePanelTitle = computed(() => detail.value?.actions?.completeInPlace ? "核对已恢复并原地关闭" : "现场核实并受理原地维修");
const factoryDispatchedAtText = computed(() => detail.value?.factorySummary?.round?.dispatchedAt ? new Date(detail.value.factorySummary.round.dispatchedAt).toLocaleString() : "");
const factoryReceivedAtText = computed(() => detail.value?.factorySummary?.round?.receivedAt ? new Date(detail.value.factorySummary.round.receivedAt).toLocaleString() : "");
const factoryPanelVisible = computed(() => (session.user?.roles.includes("ASSET_ADMIN") ?? false)
  || ((session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false) && !!detail.value && detail.value.processorAccountId === session.user?.id));
const photoRows = computed(() => (detail.value?.photos ?? []).map((photo) => ({ ...photo, readDisabled: locked.value || photo.scanStatus !== "CLEAN" })));
const historyRows = computed(() => (detail.value?.history ?? []).map((event) => ({ ...event, occurredAtText: new Date(event.occurred_at).toLocaleString() })));
const eventNames: Record<string, string> = { REPAIR_SUBMITTED: "已提交报修", REPAIR_DECIDED: "管理员已决定", REPAIR_CLAIMED: "库管已领取", REPAIR_CONTROLLED_REASSIGNMENT: "管理员已受控转派",
  REPAIR_SCRAP_HANDED_OFF: "已移交关联报废", REPAIR_SCRAP_RETURNED: "报废驳回后继续维修", REPAIR_CLOSED_BY_SCRAP: "已执行报废并结束维修",
  REPAIR_ACCEPTED_IN_PLACE: "已受理原地维修", REPAIR_COMPLETED_IN_PLACE: "已核对原地恢复", REPAIR_CANCELLED: "已取消", REPAIR_FAULT_PHOTO_ADDED: "已补充故障照片", REPAIR_CONFLICT_REJECTED: "旧操作已拒绝",
  ...Object.fromEntries(Object.entries(factoryEventNames).map(([action, label]) => [`REPAIR_${action}`, label])) };
const confirmationSummaryText = computed(() => {
  const operation = confirmation.value;
  return operation && "repairSourceId" in operation ? operation.summary : DEFAULT_CONFIRMATION_TEXT;
});
const showScrapReceipt = computed(() => scrapReceiptId.value !== null);
const scrapReceiptHref = computed(() => `/scrap-requests/${scrapReceiptId.value ?? ""}`);
const liveness = useRequestLiveness(() => [identity.value]);
function reset() {
  liveness.invalidate(); preview.close(); detail.value = null; pending.value = null; confirmation.value = null; selectedFile.value = null;
  scrapReason.value = ""; scrapCondition.value = ""; scrapMethod.value = ""; scrapReceiptId.value = null;
  reason.value = ""; approvalMethod.value = "INTERNAL_NO_COST"; factoryCostPolicy.value = "NO_COST"; factoryCostLimit.value = ""; evidence.value = ""; observedCode.value = ""; neverLeft.value = false; confirmedPlace.value = false;
  busy.value = false;
}
function failure(cause: unknown) {
  if (cause instanceof ScrapApiError) cause = new RepairApiError(cause.status, cause.code, {});
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) reset();
  error.value = repairProblem(cause);
}
async function load() {
  if (busy.value || confirmation.value) return;
  const turn = liveness.begin(), selected = /^[1-9][0-9]{0,18}$/.test(String(route.params.id ?? "")) ? String(route.params.id) : "";
  if (!selected) return;
  busy.value = true; error.value = "";
  try {
    const selectedResult = await repairRequest<Repair>(`/${selected}`);
    if (liveness.isCurrent(turn)) detail.value = selectedResult;
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
function fileChosen(event: Event) { if (!locked.value) selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null; }
function prepare(action: "approve" | "reject" | "cancel" | "claim" | "accept" | "complete" | "photo") {
  if (locked.value) return;
  error.value = ""; const selected = detail.value; if (!selected) return; let path = `/${selected.id}`, body: unknown, file: File | undefined;
  if (["approve", "reject", "cancel"].includes(action)) {
    if (reason.value.trim().length < 3) { error.value = "请填写本次决定的明确原因。"; return; }
    path += action === "cancel" ? "/cancel" : "/decision";
    body = action === "cancel" ? { reason: reason.value.trim() } : { result: action === "approve" ? "APPROVE" : "REJECT", allowedMethod: action === "approve" ? approvalMethod.value : null, reason: reason.value.trim() };
    if (action === "approve" && approvalMethod.value === "INTERNAL_OR_FACTORY") {
      const policy = factoryCostPolicy.value, limit = factoryCostLimit.value.trim();
      if (!["NO_COST", "LIMITED", "UNLIMITED"].includes(policy) || policy === "LIMITED" && (!moneyPattern.test(limit) || !/[1-9]/.test(limit))) { error.value = "请明确返厂费用约束，累计限额须为正金额并最多两位小数。"; return; }
      if (policy !== "NO_COST") body = { ...(body as Record<string, unknown>), factoryCostPolicy: policy, ...(policy === "LIMITED" ? { factoryCostLimit: limit } : {}) };
    }
  } else if (action === "claim") path += "/claim";
  else if (action === "photo") {
    const value = selectedFile.value;
    if (!value || value.size <= 0 || value.size > 10 * 1024 * 1024 || !/\.(jpe?g|png|webp)$/i.test(value.name)) { error.value = "请选择不超过10MB的JPG、PNG或WEBP图片。"; return; }
    path += "/photos"; file = value;
  } else {
    if (!observedCode.value.trim() || !confirmedPlace.value || !selected.source.locationId || !neverLeft.value || evidence.value.trim().length < 10 || selected.lockVersion == null) {
      error.value = "请现场核对编码、原位置和实物从未离开，并填写明确处理依据。"; return;
    }
    path += action === "accept" ? "/accept-in-place" : "/complete-in-place";
    body = { assetVersion: selected.assetVersion, ownershipVersion: selected.ownershipVersion, lockVersion: selected.lockVersion,
      observedCode: observedCode.value.trim(), actualLocationId: selected.source.locationId, neverLeftCustodian: true, evidence: evidence.value.trim() };
  }
  confirmation.value = { path, key: crypto.randomUUID(), version: selected.version, body, file };
}
/** 关联报废只复核维修单据版本；资产/归属/持锁三个版本随体提交。 */
function prepareScrap() {
  const row = detail.value;
  if (locked.value || !row?.actions?.requestScrap || row.lockVersion == null) return;
  if (!scrapReason.value.trim() || !scrapCondition.value.trim() || !scrapMethod.value.trim()) {
    error.value = LINKED_SCRAP_INCOMPLETE_MESSAGE; return;
  }
  error.value = "";
  confirmation.value = Object.freeze({ path: LINKED_SCRAP_PREFIX + row.id, repairSourceId: row.id,
    key: crypto.randomUUID(), version: row.version, summary: LINKED_SCRAP_SUMMARY,
    body: Object.freeze({ assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion,
      reason: scrapReason.value.trim(), observedCondition: scrapCondition.value.trim(), proposedDisposition: scrapMethod.value.trim() }),
  });
}
async function perform() {
  if (busy.value || !pending.value) return;
  const operation = pending.value, turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    const linked = "repairSourceId" in operation;
    const result = linked
      ? await scrapRequest<{ id: string; repairId?: string }>(operation.path, { ...operation, key: operation.key, body: operation.body ?? {} })
      : await repairRequest<Repair & { repairId?: string }>(operation.path, operation);
    const id = linked ? operation.repairSourceId : result.repairId ?? result.id;
    const selected = await repairRequest<Repair>(`/${id}`);
    if (liveness.isCurrent(turn)) { pending.value = null; detail.value = selected;
      selectedFile.value = null; confirmedPlace.value = false; neverLeft.value = false; evidence.value = ""; observedCode.value = "";
      if (linked) { scrapReceiptId.value = result.id; scrapReason.value = ""; scrapCondition.value = ""; scrapMethod.value = ""; }
      notice.value = linked ? LINKED_SCRAP_SUBMITTED_NOTICE : DEFAULT_SAVED_NOTICE; preview.close(); }
  } catch (cause) {
    if (liveness.isCurrent(turn)) { if ((cause instanceof RepairApiError || cause instanceof ScrapApiError) && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); }
  } finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function confirm() { if (!confirmation.value || busy.value) return; pending.value = confirmation.value; confirmation.value = null; await perform(); }
async function readPhoto(photo: RepairPhoto) {
  if (locked.value || !detail.value) return; preview.close();
  const id = detail.value.id, turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    const grant = await repairRequest<{ url: string }>(`/${id}/photos/${photo.objectId}/download-grant`, { path: "", key: crypto.randomUUID(), version: detail.value.version, body: {} });
    if (!liveness.isCurrent(turn)) return;
    if (!grant.url.startsWith(`/api/v1/repairs/${id}/photos/${photo.objectId}/content?grant=`)) throw new Error("Invalid photo location");
    const signal = preview.beginDownload(); const blob = await repairPhotoContent(grant.url, signal);
    if (!liveness.isCurrent(turn)) return;
    if (blob.size !== photo.sizeBytes || blob.size > 10 * 1024 * 1024 || !["image/jpeg", "image/png", "image/webp"].includes(blob.type)) throw new Error("Invalid photo bytes");
    preview.show(blob, photo.filename);
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
watch(identity, () => {
  reset(); error.value = "";
  if (route.query?.saved) { notice.value = DEFAULT_SAVED_NOTICE; window.history.replaceState({}, "", window.location.pathname); }
  else notice.value = "";
  void load();
}, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { reset(); });
</script>
<template>
  <AppChrome>
    <template #actions>
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || !!confirmation || factoryBusy" @click="load">刷新并核对当前结果</button>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/repairs">返回报修列表</router-link>
    </template>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
    <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="perform">重试原报修操作</button>
    <div v-if="confirmation" class="fams-card fams-stack" role="group" aria-label="确认本次报修办理">
      <p>{{ confirmationSummaryText }}</p>
      <p v-if="confirmationFactoryScope">返厂费用范围：{{ confirmationCostScopeText }}</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="confirm">确认保存本次办理</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="confirmation = null">返回修改</button>
      </div>
    </div>
    <section v-if="detail" class="fams-panel fams-stack" aria-label="当前报修详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">{{ detail.source.assetCode }} · {{ detail.source.name }}</h2>
          <p class="fams-panel-subtitle">{{ repairStates[detail.status] }} · {{ detail.fault }} · 实际发生 {{ occurredAtText }}</p>
        </div>
      </div>
      <p v-if="detail.returnOrigin">来自损坏归还 {{ detail.returnOrigin.returnId }}，源后续 {{ detail.returnOrigin.followupId }}；原借用 {{ detail.returnOrigin.closedLoanId || '无' }} 已关闭，实际接修和验收入库后才结束源义务。</p>
      <p>提交时位置：{{ detail.source.locationName || '待核实' }}。原责任与借用作为返回依据保留；实物离开过或原目标失效时，不能直接原地恢复。</p>
      <section class="fams-card fams-stack">
        <div class="fams-panel-head"><h3 class="fams-panel-title">原始故障照片</h3></div>
        <p v-if="!detail.photosAvailable">照片服务暂不可用，请联系管理员。</p>
        <ul class="fams-timeline"><li v-for="photo in photoRows" :key="photo.objectId">{{ photo.filename }}<button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="photo.readDisabled" @click="readPhoto(photo)">读取故障照片 {{ photo.filename }}</button></li></ul>
        <fieldset v-if="detail.actions?.addFaultPhoto" class="fams-card fams-stack" :disabled="locked">
          <label class="fams-file"><span class="fams-field-label">选择故障图片</span><input type="file" accept=".jpg,.jpeg,.png,.webp" @change="fileChosen"></label>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare('photo')">核对上传故障照片</button></div>
          <p>管理员决定后保留批准时的照片依据，不能覆盖原记录。</p>
        </fieldset>
        <FamsMediaPreview v-if="preview.url" @dismiss="preview.close()">
          <figure><img :src="preview.url" :alt="`故障照片：${preview.name}`"><figcaption>{{ preview.name }}</figcaption><button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="preview.close()">关闭故障照片</button></figure>
        </FamsMediaPreview>
      </section>
      <fieldset v-if="detail.actions?.approve || detail.actions?.cancel" class="fams-card fams-stack" :disabled="locked">
        <div class="fams-field-grid">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">本次决定原因</span><textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
          <template v-if="detail.actions.approve">
            <label class="fams-field"><span class="fams-field-label">本次批准处理方式</span><select class="fams-control" v-model="approvalMethod" aria-label="本次批准处理方式"><option value="INTERNAL_NO_COST">仅内部无费用处理</option><option value="INTERNAL_OR_FACTORY">内部或必要时返厂</option></select></label>
            <template v-if="approvalIncludesFactory">
              <label class="fams-field"><span class="fams-field-label">本维修所有返厂轮次费用约束</span><select class="fams-control" v-model="factoryCostPolicy" aria-label="本次返厂费用约束"><option value="NO_COST">无费用</option><option value="LIMITED">所有轮次累计限额</option><option value="UNLIMITED">不限费用</option></select></label>
              <label v-if="factoryCostLimited" class="fams-field"><span class="fams-field-label">所有轮次累计正限额（元）</span><input class="fams-control" v-model="factoryCostLimit" inputmode="decimal" maxlength="15"></label>
            </template>
          </template>
        </div>
        <template v-if="detail.actions.approve">
          <p>一次批准所选范围及明确费用约束。批准不等于已领取、接修或寄出。</p>
          <div class="fams-inline">
            <button class="fams-button" data-kind="primary" type="button" @click="prepare('approve')">{{ approveButtonText }}</button>
            <button class="fams-button" data-kind="secondary" type="button" @click="prepare('reject')">驳回无效报修</button>
          </div>
        </template>
        <button v-if="detail.actions.cancel" class="fams-button" data-kind="secondary" type="button" @click="prepare('cancel')">取消尚未批准的报修</button>
      </fieldset>
      <router-link v-if="showScrapReceipt" class="fams-button" data-kind="link" data-size="sm" :to="scrapReceiptHref">{{ LINKED_SCRAP_RECEIPT_LABEL }}</router-link>
      <fieldset v-if="detail.actions?.requestScrap" class="fams-card fams-stack" :disabled="locked">
        <h3 class="fams-panel-title">从维修发起关联报废</h3>
        <div class="fams-field-grid">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">维修关联报废原因</span><textarea class="fams-control" v-model="scrapReason" maxlength="2000"/></label>
          <label class="fams-field fams-field-wide"><span class="fams-field-label">报废实物现状</span><textarea class="fams-control" v-model="scrapCondition" maxlength="2000"/></label>
          <label class="fams-field"><span class="fams-field-label">建议报废处置方式</span><input class="fams-control" v-model="scrapMethod" maxlength="500"/></label>
        </div>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepareScrap">核对维修关联报废</button>
        </div>
      </fieldset>
      <button v-if="detail.actions?.claim" class="fams-button" data-kind="secondary" type="button" :disabled="locked" @click="prepare('claim')">领取维修处理</button>
      <fieldset v-if="detail.actions?.acceptInPlace || detail.actions?.completeInPlace" class="fams-card fams-stack" :disabled="locked">
        <div class="fams-panel-head"><h3 class="fams-panel-title">{{ inPlacePanelTitle }}</h3></div>
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">现场核对的资产编码</span><input class="fams-control" v-model="observedCode" maxlength="2048"></label>
        </div>
        <div class="fams-selection-group">
          <label class="fams-selection"><input v-model="confirmedPlace" type="checkbox">已现场核对实物仍在 {{ detail.source.locationName || '原位置' }}</label>
          <label class="fams-selection"><input v-model="neverLeft" type="checkbox">实物从未离开原保管人，本次按原地处理核实</label>
        </div>
        <div class="fams-field-grid">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">实际处理及核实依据</span><textarea class="fams-control" v-model="evidence" maxlength="2000" /></label>
        </div>
        <div class="fams-form-actions">
          <button v-if="detail.actions.acceptInPlace" class="fams-button" data-kind="primary" type="button" @click="prepare('accept')">核对受理原地维修</button>
          <button v-if="detail.actions.completeInPlace" class="fams-button" data-kind="primary" type="button" @click="prepare('complete')">确认已恢复并原地关闭</button>
        </div>
      </fieldset>
      <section v-if="detail.inventoryEvidence?.length" class="fams-card fams-stack" aria-label="原始盘点损坏证据">
        <div class="fams-panel-head">
          <div>
            <h3 class="fams-panel-title">原始盘点损坏证据</h3>
            <p class="fams-panel-subtitle">保留原始事件照片；损坏核实和发现时间以盘点复核记录为准。</p>
          </div>
        </div>
        <div v-for="origin in detail.inventoryEvidence" :key="origin.observationId" class="fams-stack">
          <p>盘点 {{ origin.taskId }} · 原始证据 {{ origin.observationId }}</p>
          <RepairInventoryPhoto v-for="photo in origin.photoIds" :key="`${detail.id}:${detail.version}:${origin.observationId}:${photo}`" :repair-id="detail.id" :observation-id="origin.observationId" :object-id="photo" :blocked="locked" @invalidated="reset" />
        </div>
      </section>
      <section v-if="detail.factorySummary" class="fams-card fams-stack" aria-label="返厂进度摘要">
        <div class="fams-panel-head"><h3 class="fams-panel-title">返厂进度</h3></div>
        <p v-if="detail.factorySummary.scope">范围：{{ factoryScopeStates[detail.factorySummary.scope.scopeStatus] }}</p>
        <p>实际寄出 {{ detail.factorySummary.actualDispatchCount }} 次</p>
        <p v-if="detail.factorySummary.round">第 {{ detail.factorySummary.round.roundNumber }} 轮 · {{ factoryRoundStates[detail.factorySummary.round.roundStatus] }} · 预计收回 {{ detail.factorySummary.round.expectedReturnDate }}<span v-if="detail.factorySummary.round.dispatchedAt"> · 实际寄出 {{ factoryDispatchedAtText }}</span><span v-if="detail.factorySummary.round.receivedAt"> · 实际收回 {{ factoryReceivedAtText }}</span></p>
      </section>
      <RepairFactoryPanel v-if="factoryPanelVisible" :repair="detail" @changed="load" @invalidated="reset" />
      <RepairReassignmentPanel :repair="detail" @changed="load" @invalidated="reset" />
      <RepairPhysicalPanel :repair="detail" @changed="load" @invalidated="reset" />
      <RepairReturnPanel :repair="detail" @changed="load" @invalidated="reset" />
      <RepairTargetChangePanel :repair="detail" @changed="load" @invalidated="reset" />
      <RepairCollaborationPanel :repair-id="detail.id" :request-version="detail.version" @changed="load" @invalidated="reset" />
      <div class="fams-panel-head"><h3 class="fams-panel-title">最近100条处理历史 / 共 {{ detail.historyTotal || 0 }} 条</h3></div>
      <ul class="fams-timeline"><li v-for="event in historyRows" :key="event.id">{{ event.actor_username }} · {{ eventNames[event.action] || '历史处理' }}<small>{{ event.occurredAtText }} · {{ event.detail_json.reason || event.detail_json.evidence || event.detail_json.fault || '' }}</small></li></ul>
    </section>
  </AppChrome>
</template>
