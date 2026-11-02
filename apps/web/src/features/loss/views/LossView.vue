<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import FamsMediaPreview from "@/components/FamsMediaPreview.vue";
import LossRecoveryPanel from "../panels/LossRecoveryPanel.vue";
import LossProgressPanel from "../panels/LossProgressPanel.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { requestBlob } from "@/api/http";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useObjectUrlPreview } from "@/composables/useObjectUrlPreview";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { LossApiError, lossProblem, lossRequest, lossReportState, type LossAsset, type LossIntent, type LossReport, type LossSubmission } from "@/features/loss/api";

const PAGE_SIZE = 20;
/** 报失单号是纯数字主键；路由参数只有在合形时才值得单独发一次详情读取。 */
const REPORT_ID = /^[1-9][0-9]{0,18}$/;
const IMAGE_TYPES = ["image/png", "image/jpeg", "image/webp"];
/** 面板起草的路径末段 → 详情页需要复核的能力位；与后端动作名同源。 */
const ATTEMPT_ACTIONS: Record<string, string> = { decision: "decide", cancel: "cancel", claim: "claim", execute: "execute", "execute-to-repair": "execute", "execute-to-return": "execute" };
/** 详情级动作路径 → 报失单上的能力位；找回类动作按原机恢复能力复核。 */
function capabilityFor(row: LossReport, path: string): string | undefined {
  return { [`/${row.id}/confirm-lost`]: "confirmLost", [`/${row.id}/follow-up`]: "followUp",
    [`/${row.id}/recover-original`]: "recoverOriginal", [`/${row.id}/recover-to-repair`]: "recoverOriginal",
    [`/${row.id}/recover-to-return`]: "recoverOriginal", [`/${row.id}/recovery-requests`]: "requestRecovery",
    [`/${row.id}/repair-recovery-requests`]: "requestRecovery", [`/${row.id}/return-recovery-requests`]: "requestRecovery" }[path];
}

const session = useSessionStore(), route = useRoute();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, route.params.id]));
const rows = ref<LossReport[]>([]), detail = ref<LossReport | null>(null);
const total = shallowRef(0), page = shallowRef(1), followUpOnly = shallowRef(false);
const assets = ref<LossAsset[]>([]), assetId = shallowRef(""), assetPage = shallowRef(1), assetTotal = shallowRef(0);
const lastSeenAt = shallowRef(""), lastLocation = shallowRef(""), circumstances = shallowRef(""), reason = shallowRef("");
const custodian = shallowRef(""), searchedScope = shallowRef(""), searchedAt = shallowRef(""), nextReviewDate = shallowRef("");
const file = shallowRef<File | null>(null);
const busy = shallowRef(false), error = shallowRef(""), notice = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
const preview = useObjectUrlPreview();
/** 确认式幂等提交：草稿/在途两槽与幂等键归底座；本域确定性失败判据（4xx 结果已定）留在视图声明。 */
const { prepared, pending, prepare: draft, confirm: adopt, settle, fail, cancel, reset } = useConfirmedSubmit<Omit<LossSubmission, "key">>({
  identity: () => identity.value,
  failure: (cause) => cause instanceof LossApiError && cause.status < 500,
});

const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
const refreshDisabled = computed(() => busy.value || !!prepared.value);
const reportRows = computed(() => rows.value.map((row) => ({ ...row, stateText: lossReportState(row) })));
const detailStateText = computed(() => detail.value ? lossReportState(detail.value) : "");
const recordsTitle = computed(() => followUpOnly.value ? "遗失跟进清单" : "报失记录");
const showCreatePanel = computed(() => !followUpOnly.value);
const showRecoveryPanel = computed(() => !!detail.value && (!!detail.value.recoveryAsset || !!detail.value.recoveredAt || !!detail.value.recoveries?.length));
const showProgressPanel = computed(() => !!detail.value?.actions?.confirmLost || !!detail.value?.actions?.followUp);
const showSearchForm = computed(() => !!detail.value?.actions?.search);
const showReasonField = computed(() => !!detail.value?.actions?.decide || !!detail.value?.actions?.cancel);
const showDecisionButtons = computed(() => !!detail.value?.actions?.decide);
const showCancelButton = computed(() => !!detail.value?.actions?.cancel);
const showClaimButton = computed(() => !!detail.value?.actions?.claim);
const showPhotoForm = computed(() => !!detail.value?.actions?.addPhoto);
const showSearchingNote = computed(() => detail.value?.status === "SEARCHING");
const showConfirmedLostNote = computed(() => detail.value?.status === "CONFIRMED_LOST" && !detail.value?.recoveredAt);
const assetPrevDisabled = computed(() => locked.value || assetPage.value <= 1);
const assetNextDisabled = computed(() => locked.value || assetPage.value * PAGE_SIZE >= assetTotal.value);
const recordsPrevDisabled = computed(() => locked.value || page.value <= 1);
const recordsNextDisabled = computed(() => locked.value || page.value * PAGE_SIZE >= total.value);
const preparedSummary = computed(() => prepared.value?.payload.summary ?? "请确认本次资产、情况、版本及证据，提交后保留操作履历。");
const pendingSummary = computed(() => pending.value?.payload.summary ?? "操作结果待核对，当前输入已冻结。");
const recoveryPanelKey = computed(() => detail.value ? `recovery:${identity.value}:${detail.value.id}:${detail.value.version}` : "");
const progressPanelKey = computed(() => detail.value ? `${identity.value}:${detail.value.id}:${detail.value.version}` : "");

function resetView() {
  liveness.invalidate();
  followUpOnly.value = false; preview.close();
  rows.value = []; detail.value = null; assets.value = []; assetId.value = "";
  busy.value = false; total.value = 0; page.value = 1; assetPage.value = 1; assetTotal.value = 0;
  lastSeenAt.value = ""; lastLocation.value = ""; circumstances.value = ""; reason.value = "";
  custodian.value = ""; searchedScope.value = ""; searchedAt.value = ""; nextReviewDate.value = "";
  file.value = null; error.value = ""; notice.value = "";
}
/** 身份、权限或记录失效时整页作废；其余失败只落文案，保留输入供修改。 */
function reportFailure(cause: unknown) {
  if (cause instanceof LossApiError && [401, 403, 404].includes(cause.status)) { reset(); resetView(); }
  error.value = lossProblem(cause);
}

async function load(id = detail.value?.id ?? String(route.params.id ?? ""), delta = 0) {
  if (busy.value || prepared.value) return;
  const next = page.value + delta;
  if (next < 1 || delta > 0 && (next - 1) * PAGE_SIZE >= total.value) return;
  preview.close();
  if (id !== detail.value?.id) {
    file.value = null; reason.value = ""; custodian.value = ""; searchedScope.value = ""; searchedAt.value = ""; nextReviewDate.value = "";
  }
  const turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    const result = await lossRequest<{ items: LossReport[]; total: number }>(`${followUpOnly.value ? "/follow-ups" : ""}?page=${next}&pageSize=${PAGE_SIZE}`);
    const selected = REPORT_ID.test(id) ? await lossRequest<LossReport>(`/${id}`) : null;
    if (!liveness.isCurrent(turn)) return;
    rows.value = result.items; total.value = result.total; page.value = next; detail.value = selected;
  } catch (cause) { if (liveness.isCurrent(turn)) reportFailure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}

async function loadAssets(delta = 0) {
  if (locked.value) return;
  const next = assetPage.value + delta;
  if (next < 1 || delta > 0 && (next - 1) * PAGE_SIZE >= assetTotal.value) return;
  const turn = liveness.begin(); busy.value = true;
  try {
    const result = await lossRequest<{ items: LossAsset[]; total: number }>(`/eligible?page=${next}&pageSize=${PAGE_SIZE}`);
    if (!liveness.isCurrent(turn)) return;
    assets.value = result.items; assetTotal.value = result.total; assetPage.value = next; assetId.value = "";
  } catch (cause) { if (liveness.isCurrent(turn)) reportFailure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}

/** 面板只起草，是否可提交由本视图按当前能力位与乐观版本复核后交给底座。 */
function prepareProgress(intent: LossIntent) {
  const row = detail.value;
  if (locked.value || !row) return;
  const attempt = row.recoveries?.find((item) => intent.path.startsWith(`/recoveries/${item.id}/`));
  if (attempt) {
    const action = ATTEMPT_ACTIONS[intent.path.slice(intent.path.lastIndexOf("/") + 1)];
    if (!action || intent.version !== attempt.version || !attempt.actions[action]) return;
  } else {
    const action = capabilityFor(row, intent.path);
    if (!action || intent.version !== row.version || !row.actions?.[action]) return;
  }
  draft(() => Object.freeze(intent));
}

function switchMode(followups: boolean) {
  if (locked.value) return;
  followUpOnly.value = followups; page.value = 1; void load("");
}

function prepare(action: "submit" | "approve" | "reject" | "cancel" | "claim" | "photo" | "search") {
  if (locked.value) return;
  error.value = "";
  const row = detail.value;
  let path = "", body: unknown, upload: File | undefined;
  if (action === "submit") {
    const asset = assets.value.find((value) => value.id === assetId.value), date = new Date(lastSeenAt.value);
    if (!asset || !Number.isFinite(date.getTime()) || !lastLocation.value.trim() || circumstances.value.trim().length < 10) { error.value = "请选择资产，填写最后见到时间、位置和至少10字情况。"; return; }
    body = { assetId: asset.id, assetVersion: asset.version, lastSeenAt: date.toISOString(), lastLocation: lastLocation.value.trim(), circumstances: circumstances.value.trim() };
  } else {
    if (!row) return;
    path = `/${row.id}`;
    if (action === "approve" || action === "reject" || action === "cancel") {
      if (reason.value.trim().length < 3) { error.value = "请填写至少3字原因。"; return; }
      path += action === "cancel" ? "/cancel" : "/decision";
      body = action === "cancel" ? { reason: reason.value.trim() } : { result: action === "approve" ? "APPROVE" : "REJECT", reason: reason.value.trim() };
    } else if (action === "claim") {
      path += "/claim";
    } else if (action === "photo") {
      const chosen = file.value;
      if (!chosen || !IMAGE_TYPES.includes(chosen.type) || chosen.size > 10 * 1024 * 1024) { error.value = "请选择不超过10MB的PNG、JPEG或WEBP图片。"; return; }
      path += "/photos"; upload = chosen;
    } else {
      const date = new Date(searchedAt.value);
      if (custodian.value.trim().length < 10 || searchedScope.value.trim().length < 10 || !Number.isFinite(date.getTime()) || !/^\d{4}-\d{2}-\d{2}$/.test(nextReviewDate.value)) { error.value = "请完整填写保管人核实、查找范围、实际时间和下次复核日。"; return; }
      path += "/search";
      body = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion,
        custodianVerification: custodian.value.trim(), searchedScope: searchedScope.value.trim(), searchedAt: date.toISOString(), nextReviewDate: nextReviewDate.value };
    }
  }
  draft(() => Object.freeze({ path, version: row?.version ?? 0, body, file: upload }));
}

async function send() {
  const operation = pending.value;
  if (!operation || busy.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = "";
  let refresh = "";
  try {
    const result = await lossRequest<LossReport>(operation.payload.path, { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settle(); file.value = null; refresh = result.id; notice.value = "本次操作已记录。";
  } catch (cause) {
    if (liveness.isCurrent(turn)) { fail(cause); reportFailure(cause); }
  } finally { if (liveness.isCurrent(turn)) busy.value = false; }
  if (refresh && liveness.isCurrent(turn)) await load(refresh);
}

function submitPrepared() { if (adopt()) void send(); }

async function viewPhoto(object: string, name: string) {
  const row = detail.value;
  if (locked.value || !row) return;
  preview.close();
  const signal = preview.beginDownload();
  const turn = liveness.begin(), id = row.id; busy.value = true;
  try {
    // 授权令牌换取是一次性读取，不参与确认式提交，因此就地取键而非占用底座槽位。
    const grant = await lossRequest<{ url: string }>(`/${id}/photos/${object}/download-grant`, { path: "", version: row.version, key: crypto.randomUUID() });
    if (!liveness.isCurrent(turn)) return;
    const expected = `/api/v1/loss-reports/${id}/photos/${object}/content?grant=`;
    if (!grant.url.startsWith(expected) || grant.url.includes("#")) throw new Error("Invalid attachment grant");
    const blob = await requestBlob(grant.url, (status) => new LossApiError(status, "LOSS_PHOTO_READ_FAILED"), signal);
    if (!IMAGE_TYPES.includes(blob.type)) throw new Error("Invalid image content");
    if (liveness.isCurrent(turn)) preview.show(blob, name);
  } catch (cause) { if (liveness.isCurrent(turn)) reportFailure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}

function chosen(event: Event) {
  if (locked.value) return;
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}

watch(identity, () => { reset(); resetView(); void load(); }, { immediate: true });
onBeforeUnmount(resetView);
</script>

<template>
  <AppChrome>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>

    <div v-if="prepared" class="fams-panel fams-stack" role="dialog" aria-label="确认报失操作">
      <p>{{ preparedSummary }}</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="submitPrepared">确认提交</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="cancel">返回修改</button>
      </div>
    </div>

    <section v-if="pending" class="fams-notice" data-state="warning">
      <strong>{{ pendingSummary }}</strong>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="send">使用原请求重试</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load()">只读核对最新结果</button>
      </div>
    </section>

    <div class="fams-panel-actions">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="refreshDisabled" @click="load()">刷新报失记录</button>
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="switchMode(false)">全部报失</button>
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="switchMode(true)">遗失跟进清单</button>
    </div>

    <section v-if="showCreatePanel" class="fams-panel fams-stack" aria-label="发起报失">
      <div class="fams-panel-head"><h2 class="fams-panel-title">发起报失</h2></div>
      <fieldset class="fams-card fams-stack" :disabled="locked">
        <div class="fams-pagination">
          <span class="fams-pagination-summary">第 {{ assetPage }} 页，共 {{ assetTotal }} 项</span>
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="loadAssets()">查询可报失资产</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="assetPrevDisabled" @click="loadAssets(-1)">上一页资产</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="assetNextDisabled" @click="loadAssets(1)">下一页资产</button>
          </div>
        </div>
        <label class="fams-field"><span class="fams-field-label">资产</span><select class="fams-control" v-model="assetId"><option value="">请选择</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetCode }} · {{ asset.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">最后见到时间</span><input class="fams-control" v-model="lastSeenAt" type="datetime-local" step="1" /></label>
        <label class="fams-field"><span class="fams-field-label">最后位置</span><input class="fams-control" v-model="lastLocation" maxlength="500" /></label>
        <label class="fams-field"><span class="fams-field-label">找不到的情况</span><textarea class="fams-control" v-model="circumstances" maxlength="2000" /></label>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare('submit')">提交报失</button></div>
      </fieldset>
    </section>

    <section class="fams-panel fams-stack" aria-label="报失记录">
      <div class="fams-panel-head"><h2 class="fams-panel-title">{{ recordsTitle }}（{{ total }}）</h2></div>
      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">单号</th><th scope="col">资产</th><th scope="col">状态</th><th v-if="followUpOnly" scope="col">原责任 / 跟进库管 / 下次跟进日</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in reportRows" :key="row.id">
              <th scope="row" data-column-kind="identity">{{ row.id }}</th>
              <td>{{ row.source.assetCode }} · {{ row.source.name }}</td>
              <td><span class="fams-status">{{ row.stateText }}</span></td>
              <td v-if="followUpOnly">{{ row.source.responsibleEmployeeId ?? row.source.departmentResponsibleEmployeeId ?? '在库保管' }} / {{ row.processorAccountId }} / {{ row.nextReviewDate }}</td>
              <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="locked" @click="load(row.id)">查看</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="fams-pagination">
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="recordsPrevDisabled" @click="load('', -1)">上一页记录</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="recordsNextDisabled" @click="load('', 1)">下一页记录</button>
        </div>
      </div>
    </section>

    <section v-if="detail" class="fams-panel fams-stack" aria-label="报失详情">
      <div class="fams-panel-head">
        <div>
          <h2 class="fams-panel-title">报失单 {{ detail.id }} · {{ detailStateText }}</h2>
          <p class="fams-panel-subtitle">{{ detail.source.assetCode }} · {{ detail.source.name }}；最后见到 {{ detail.lastSeenAt }}；位置：{{ detail.lastLocation }}</p>
        </div>
      </div>
      <p>{{ detail.circumstances }}</p>
      <p>处理库管：{{ detail.processorAccountId ?? '尚未认领' }}；下次复核：{{ detail.nextReviewDate ?? '核实后填写' }}</p>
      <p v-if="showSearchingNote">已进入查找中，资产继续持锁。本次查找仍在处理中。</p>
      <p v-if="showConfirmedLostNote">已确认遗失，本次查找单结案，原责任和借用仍在，持续跟进。确认时间：{{ detail.confirmedLostAt }}；最近跟进：{{ detail.lastFollowUpAt ?? '尚未跟进' }}</p>
      <LossRecoveryPanel v-if="showRecoveryPanel" :key="recoveryPanelKey" :report="detail" :locked="locked" @prepare="prepareProgress" />
      <LossProgressPanel v-if="showProgressPanel" :key="progressPanelKey" :report="detail" :locked="locked" @prepare="prepareProgress" />
      <fieldset class="fams-card fams-stack" :disabled="locked">
        <template v-if="showPhotoForm">
          <label class="fams-file"><span class="fams-field-label">情况图片</span><input type="file" accept="image/png,image/jpeg,image/webp" @change="chosen" /></label>
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('photo')">上传情况图片</button>
        </template>
        <ul class="fams-timeline"><li v-for="photo in detail.photos" :key="photo.objectId">已保存原件：{{ photo.filename }}<button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="viewPhoto(photo.objectId, photo.filename)">查看原件</button></li></ul>
        <label v-if="showReasonField" class="fams-field fams-field-wide"><span class="fams-field-label">本次原因</span><textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
        <div class="fams-panel-actions">
          <button v-if="showDecisionButtons" class="fams-button" data-kind="primary" type="button" @click="prepare('approve')">批准核实</button>
          <button v-if="showDecisionButtons" class="fams-button" data-kind="danger" type="button" @click="prepare('reject')">驳回报告</button>
          <button v-if="showCancelButton" class="fams-button" data-kind="secondary" type="button" @click="prepare('cancel')">撤回报告</button>
          <button v-if="showClaimButton" class="fams-button" data-kind="secondary" type="button" @click="prepare('claim')">认领核实任务</button>
        </div>
        <template v-if="showSearchForm">
          <label class="fams-field"><span class="fams-field-label">最后保管人核实结果</span><textarea class="fams-control" v-model="custodian" maxlength="2000" /></label>
          <label class="fams-field"><span class="fams-field-label">已查找范围与线索</span><textarea class="fams-control" v-model="searchedScope" maxlength="2000" /></label>
          <label class="fams-field"><span class="fams-field-label">实际查找时间</span><input class="fams-control" v-model="searchedAt" type="datetime-local" step="1" /></label>
          <label class="fams-field"><span class="fams-field-label">下次复核日</span><input class="fams-control" v-model="nextReviewDate" type="date" /></label>
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('search')">记录核实并进入查找</button>
        </template>
      </fieldset>
      <h3 class="fams-panel-title">处理履历</h3>
      <ul class="fams-timeline"><li v-for="event in detail.history" :key="event.id">{{ event.occurred_at }} · {{ event.actor_username }} · {{ event.action }}</li></ul>
    </section>

    <FamsMediaPreview v-if="preview.url" @dismiss="preview.close()">
      <figure><img :src="preview.url" :alt="`报失原件：${preview.name}`"><figcaption>{{ preview.name }}</figcaption><button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="preview.close()">关闭原件</button></figure>
    </FamsMediaPreview>
  </AppChrome>
</template>
