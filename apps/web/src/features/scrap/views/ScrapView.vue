<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { ScrapApiError, scrapRequest, scrapRequestState, type ScrapAsset, type ScrapChoice,
  type ScrapIntent, type ScrapRequest } from "@/features/scrap/api";

const PAGE_SIZE = 20;
/** 执行目录单次读取的页大小；加载更多按已读页数顺延。 */
const CATALOG_PAGE_SIZE = 100;
/** 报废单号是纯数字主键；路由参数只有在合形时才值得单独发一次详情读取。 */
const REQUEST_ID = /^[1-9][0-9]{0,18}$/;

const route = useRoute(), session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, route.params.id]));
const rows = ref<ScrapRequest[]>([]), detail = ref<ScrapRequest | null>(null);
const assets = ref<ScrapAsset[]>([]), places = ref<ScrapChoice[]>([]), people = ref<ScrapChoice[]>([]);
const page = shallowRef(1), total = shallowRef(0);
const assetPage = shallowRef(1), assetTotal = shallowRef(0), selectedAsset = shallowRef("");
const reason = shallowRef(""), condition = shallowRef(""), proposed = shallowRef(""), decisionReason = shallowRef(""), approved = shallowRef("");
const observed = shallowRef(""), serial = shallowRef(""), occurred = shallowRef(""), physical = shallowRef(false), evidence = shallowRef(""), due = shallowRef("");
const location = shallowRef(""), custodian = shallowRef("");
const locationPage = shallowRef(0), locationTotal = shallowRef(0), peoplePage = shallowRef(0), peopleTotal = shallowRef(0);
const busy = shallowRef(false), stale = shallowRef(true), error = shallowRef(""), notice = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
/** 确认式幂等提交：草稿/在途两槽与幂等键归底座；本域确定性失败判据（4xx 结果已定）留在视图声明。 */
const { prepared, pending, prepare: draft, confirm: adopt, settle, fail, cancel, reset } = useConfirmedSubmit<ScrapIntent>({
  identity: () => identity.value,
  failure: (cause) => cause instanceof ScrapApiError && cause.status >= 400 && cause.status < 500,
});

const locked = computed(() => busy.value || !!prepared.value || !!pending.value);
const refreshDisabled = computed(() => busy.value || !!prepared.value);
const requestRows = computed(() => rows.value.map((row) => ({ ...row, stateText: scrapRequestState(row) })));
const detailStateText = computed(() => detail.value ? scrapRequestState(detail.value) : "");
const detailTitle = computed(() => detail.value ? `${detail.value.source.assetCode} · ${detail.value.source.name}` : "");
const detailFactsText = computed(() => detail.value
  ? `${detailStateText.value} · 原因 ${detail.value.reason} · 现状 ${detail.value.observedCondition}` : "");
const detailPlanText = computed(() => detail.value
  ? `建议处置 ${detail.value.proposedDisposition} · 当前处理库管 ${detail.value.processorAccountId || "尚未认领"}` : "");
const approvedDispositionText = computed(() => detail.value?.decision?.decision?.approvedDisposition ?? "");
const dispositionText = computed(() => {
  const row = detail.value?.disposition;
  if (!row) return "";
  return `处置进度 ${row.disposition_status === "PENDING_DISPOSITION" ? "待实际处置" : row.disposition_status} · 计划日期 ${row.due_date}`;
});
const assetPrevDisabled = computed(() => assetPage.value === 1);
const assetNextDisabled = computed(() => assetPage.value * PAGE_SIZE >= assetTotal.value);
const recordsPrevDisabled = computed(() => locked.value || page.value === 1);
const recordsNextDisabled = computed(() => locked.value || page.value * PAGE_SIZE >= total.value);
const locationLoadDisabled = computed(() => locationPage.value > 0 && places.value.length >= locationTotal.value);
const peopleLoadDisabled = computed(() => peoplePage.value > 0 && people.value.length >= peopleTotal.value);
const preparedSummary = computed(() => prepared.value?.payload.summary ?? "");

function clearFacts() {
  places.value = []; people.value = []; location.value = ""; custodian.value = "";
  locationPage.value = 0; peoplePage.value = 0; locationTotal.value = 0; peopleTotal.value = 0;
  decisionReason.value = ""; approved.value = ""; observed.value = ""; serial.value = "";
  occurred.value = ""; physical.value = false; evidence.value = ""; due.value = "";
}
function resetView() {
  liveness.invalidate(); clearFacts();
  rows.value = []; detail.value = null; assets.value = []; selectedAsset.value = "";
  page.value = 1; total.value = 0; assetPage.value = 1; assetTotal.value = 0;
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
  try {
    const result = await scrapRequest<{ items: ScrapRequest[]; total: number }>(`?page=${next}&pageSize=${PAGE_SIZE}`);
    const selected = REQUEST_ID.test(id) ? await scrapRequest<ScrapRequest>(`/${id}`) : null;
    if (!liveness.isCurrent(turn)) return;
    if (detail.value?.id !== selected?.id) clearFacts();
    rows.value = result.items; total.value = result.total; page.value = next; detail.value = selected; stale.value = false;
  } catch (cause) { if (liveness.isCurrent(turn)) { stale.value = true; failure(cause); } }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
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

async function catalog(kind: "location" | "employee") {
  const row = detail.value;
  if (locked.value || stale.value || !row?.actions.execute) return;
  const next = (kind === "location" ? locationPage.value : peoplePage.value) + 1;
  const turn = liveness.begin(); busy.value = true;
  try {
    const result = await scrapRequest<{ items: ScrapChoice[]; total: number }>(
      `/${row.id}/execution-catalog/${kind}?page=${next}&pageSize=${CATALOG_PAGE_SIZE}`);
    if (!liveness.isCurrent(turn)) return;
    if (kind === "location") { places.value.push(...result.items); locationPage.value = next; locationTotal.value = result.total; }
    else { people.value.push(...result.items); peoplePage.value = next; peopleTotal.value = result.total; }
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
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
  draft(() => Object.freeze({ path, version, body: Object.freeze(body), summary }));
}

async function send() {
  const operation = pending.value;
  if (!operation || busy.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = ""; let saved = "";
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
      <div class="fams-panel-head"><h2 class="fams-panel-title">申请进度</h2></div>
      <ul class="fams-timeline">
        <li v-for="row in requestRows" :key="row.id"><button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="load(row.id)">{{ row.source.assetCode }} · {{ row.stateText }}</button></li>
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
      <p>{{ detailPlanText }}</p>
      <fieldset v-if="detail.actions.decide" class="fams-card fams-stack" :disabled="locked || stale">
        <label class="fams-field fams-field-wide"><span class="fams-field-label">本次决定原因</span><textarea class="fams-control" v-model="decisionReason" maxlength="1000"/></label>
        <label class="fams-field"><span class="fams-field-label">批准的处置范围</span><input class="fams-control" v-model="approved" maxlength="500"/></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" @click="prepare('approve')">批准报废范围</button>
          <button class="fams-button" data-kind="danger" type="button" @click="prepare('reject')">驳回报废申请</button>
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
      <p v-if="detail.disposition">{{ dispositionText }}</p>
    </section>
  </AppChrome>
</template>
