<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness, type LivenessToken } from "@/composables/useRequestLiveness";
import { RepairApiError, type Repair } from "@/features/repair/api";
import { factoryProblem, factoryRequest, type FactoryMutation } from "@/features/repair/factory-api";
import { ScrapApiError, scrapRequest } from "@/features/scrap/api";
import type { FactoryDetail, FactoryFile, FactoryRoundDetail } from "@/features/repair/factory-types";
import { activeReferences, AssetApiError, type AssetReference, type Page } from "@/features/asset/api";

interface Replacement { id: string; sourceId: string; originalDisposition: string; replacementAssetId: string | null; inbound: { id: string; receiptNumber: string; status: string } | null }
interface Replacements { items: Replacement[]; canRecord: boolean; canRequestScrap?: boolean }
/** 面板在确认底座里持有的意图：常规换机突变，或带 scrap 标记的旧机关联报废突变。 */
type Mutation = Omit<FactoryMutation, "key"> | { scrap: true; repairId: string; path: string; version: number; body: Readonly<Record<string, unknown>>; label: string };
/** 只有厂家收旧/销毁两种旧机去向才能转关联报废；CONTINUES_REPAIR 留在维修线内。 */
const SCRAP_ELIGIBLE_DISPOSITIONS = new Set(["FACTORY_RETAINED", "FACTORY_DESTROYED"]);
const SCRAP_INCOMPLETE_MESSAGE = "请填写旧机报废原因及已核实情况。";
const SCRAP_SAVED_NOTICE = "旧机关联报废申请已提交。";
const SCRAP_RECEIPT_LABEL = "查看关联报废单";
type Kind = "category" | "organization" | "warehouse" | "location";
/** 四类参考资料按页取用；页大小同时供翻页边界与禁用判据使用。 */
const REFERENCE_PAGE_SIZE = 50;
const sources: { kind: Kind; label: string; path: string }[] = [
  { kind: "category", label: "资产分类", path: "/asset-categories" },
  { kind: "organization", label: "归属组织", path: "/organizations" },
  { kind: "warehouse", label: "计划入库仓库", path: "/warehouses" },
  { kind: "location", label: "计划入库库位", path: "/locations" },
];
const destinations: Record<string, string> = { FACTORY_RETAINED: "厂家已收走旧机，责任待办理", FACTORY_DESTROYED: "厂家已确认销毁旧机，待关联报废", CONTINUES_REPAIR: "旧机暂留维修，责任继续保留" };

const props = defineProps<{ repair: Repair; round: FactoryRoundDetail; blocked: boolean }>();
const emit = defineEmits<{ "busy-change": [boolean]; changed: []; invalidated: []; "read-file": [FactoryFile] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id, props.round.id]));
const canRead = computed(() => !!session.user?.roles.includes("ASSET_ADMIN") || !!session.user?.roles.includes("WAREHOUSE_OPERATOR") && String(session.user.id) === props.repair.processorAccountId);
const opened = shallowRef(false), busy = shallowRef(false), stale = shallowRef(true), error = shallowRef(""), notice = shallowRef("");
const header = ref<FactoryDetail | null>(null), roundData = ref<FactoryRoundDetail | null>(null), records = ref<Replacements | null>(null);
const name = shallowRef(""), serial = shallowRef(""), model = shallowRef(""), unit = shallowRef(""), date = shallowRef(""), observed = shallowRef(""), disposition = shallowRef(""), reason = shallowRef("");
const checked = shallowRef(false), evidenceIds = ref<string[]>([]), file = ref<File | null>(null);
const scrapReason = shallowRef(""), scrapCondition = shallowRef(""), scrapReceipt = shallowRef<string | null>(null);
const choices = ref<Record<Kind, string>>({ category: "", organization: "", warehouse: "", location: "" });
const references = ref<Partial<Record<Kind, Page<AssetReference>>>>({});
/** 确认式幂等提交：草稿/在途两槽与幂等键簿记归底座；busy/locked、URL、文件选择与文案留在本面板。 */
const { prepared: confirmation, pending, prepare: draft, confirm: adopt, settle, fail, cancel, reset: resetSubmit } = useConfirmedSubmit<Mutation>({
  identity: () => identity.value,
  failure: (cause) => cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500,
});
const liveness = useRequestLiveness(() => [identity.value, canRead.value]);
const locked = computed(() => props.blocked || busy.value || !!pending.value || !!confirmation.value);
const ready = computed(() => !stale.value && header.value?.version === props.repair.version && roundData.value?.version === props.round.version);
const originals = computed(() => roundData.value?.evidence.filter(item => item.purpose === "REPLACEMENT" && roundData.value?.files.some(f => f.objectId === item.object_id && f.scanStatus === "CLEAN")) ?? []);
const headerRefreshDisabled = computed(() => props.blocked || busy.value || !!confirmation.value);
const retryDisabled = computed(() => busy.value || props.blocked);
const confirmDisabled = computed(() => busy.value || props.blocked);
const originalsDisabled = computed(() => locked.value || !ready.value || !header.value?.photosAvailable);
const replacementRows = computed(() => (records.value?.items ?? []).map(item => ({
  id: item.id,
  dispositionText: destinations[item.originalDisposition],
  canScrap: SCRAP_ELIGIBLE_DISPOSITIONS.has(item.originalDisposition),
  replacementAssetId: item.replacementAssetId,
  inbound: item.inbound,
  inboundHref: item.inbound ? `/inbound?receiptId=${item.inbound.id}` : "",
})));
/** 可转关联报废的旧机行；候选资格与 prepareScrap 的复核同源。 */
const scrapReplacementRows = computed(() => (records.value?.items ?? [])
  .filter(item => SCRAP_ELIGIBLE_DISPOSITIONS.has(item.originalDisposition))
  .map(item => ({ id: item.id })));
const showScrapForm = computed(() => records.value?.canRequestScrap === true);
const showScrapReceipt = computed(() => scrapReceipt.value !== null);
const scrapReceiptHref = computed(() => `/scrap-requests/${scrapReceipt.value ?? ""}`);
const referenceRows = computed(() => sources.map(source => {
  const refs = references.value[source.kind], currentPage = refs?.page ?? 1;
  const options = (refs?.items ?? []).filter(item => source.kind !== "location" || item.warehouse?.id === choices.value.warehouse)
    .map(item => ({ id: item.id, text: `${item.code} · ${item.name}` }));
  return { kind: source.kind, label: source.label, options, prevDisabled: currentPage === 1, nextDisabled: currentPage * REFERENCE_PAGE_SIZE >= (refs?.total ?? 0) };
}));
const dispositionOptions = computed(() => Object.entries(destinations).map(([key, label]) => ({ key, label, disabled: roundData.value?.roundStatus === "RECEIVED" && key !== "CONTINUES_REPAIR" })));
const originalsRows = computed(() => originals.value.map(item => {
  const matched = roundData.value?.files.find(f => f.objectId === item.object_id);
  return { ...item, filename: matched?.filename, file: matched, readKey: `read-${item.object_id}` };
}));
watch([busy, pending, confirmation], () => emit("busy-change", busy.value || !!pending.value || !!confirmation.value), { flush: "sync" });
watch(() => choices.value.warehouse, () => { choices.value.location = ""; });
function clearDraft() { name.value = ""; serial.value = ""; model.value = ""; unit.value = ""; date.value = ""; observed.value = ""; disposition.value = ""; reason.value = ""; checked.value = false; evidenceIds.value = []; file.value = null; }
function reset() { liveness.invalidate(); resetSubmit(); opened.value = false; busy.value = false; stale.value = true; header.value = null; roundData.value = null; records.value = null; references.value = {}; choices.value = { category: "", organization: "", warehouse: "", location: "" }; scrapReason.value = ""; scrapCondition.value = ""; scrapReceipt.value = null; clearDraft(); }
function failure(cause: unknown) {
  if (cause instanceof ScrapApiError) cause = new RepairApiError(cause.status, cause.code, {});
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) { reset(); emit("invalidated"); }
  if (cause instanceof RepairApiError && [400, 409, 413, 422].includes(cause.status)) stale.value = true;
  error.value = factoryProblem(cause);
}
async function fetchRefs(kind: Kind, page: number, turn: LivenessToken) {
  const source = sources.find(item => item.kind === kind)!;
  // 参考资料读走资产域底座；失败仍按本面板既有的维修错误口径判定失效与文案。
  const result = await activeReferences(source.path, page, REFERENCE_PAGE_SIZE)
    .catch((cause: unknown) => { throw cause instanceof AssetApiError ? new RepairApiError(cause.status, cause.code, {}) : cause; });
  if (liveness.isCurrent(turn)) references.value[kind] = result;
}
async function pageRefs(kind: Kind, delta: number) {
  if (locked.value) return;
  const old = references.value[kind], next = (old?.page ?? 1) + delta;
  if (next < 1 || delta > 0 && next > Math.ceil((old?.total ?? 0) / REFERENCE_PAGE_SIZE)) return;
  const turn = liveness.begin(); busy.value = true; error.value = "";
  try { await fetchRefs(kind, next, turn); if (liveness.isCurrent(turn)) choices.value[kind] = ""; }
  catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function fetchState(turn: LivenessToken) {
  const id = props.repair.id, round = props.round.id;
  const nextHeader = await factoryRequest<FactoryDetail>(id);
  if (!liveness.isCurrent(turn)) return;
  const nextRound = await factoryRequest<FactoryRoundDetail>(id, `/rounds/${round}`);
  if (!liveness.isCurrent(turn)) return;
  const nextRecords = await factoryRequest<Replacements>(id, `/rounds/${round}/replacements`);
  if (!liveness.isCurrent(turn)) return;
  header.value = nextHeader; roundData.value = nextRound; records.value = nextRecords; stale.value = false;
}
async function load() {
  if (busy.value || props.blocked || confirmation.value || !canRead.value) return;
  opened.value = true; const turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    await fetchState(turn);
    if (liveness.isCurrent(turn) && records.value?.canRecord) await Promise.all(sources.map(source => fetchRefs(source.kind, references.value[source.kind]?.page ?? 1, turn)));
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
  if (liveness.isCurrent(turn) && header.value?.version !== props.repair.version) emit("changed");
}
function observationTime() {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(observed.value);
  if (!match) throw new Error("Observation required");
  const [year, month, day, hour, minute, second] = match.slice(1).map(value => Number(value ?? 0));
  const value = new Date(year, month - 1, day, hour, minute, second);
  if (value.getFullYear() !== year || value.getMonth() !== month - 1 || value.getDate() !== day || value.getHours() !== hour || value.getMinutes() !== minute || value.getSeconds() !== second) throw new Error("Observation invalid");
  return value.toISOString();
}
function fileChosen(event: Event) { if (locked.value) return; const input = event.target as HTMLInputElement; file.value = input.files?.[0] ?? null; input.value = ""; }
function prepare(upload: boolean) {
  const meta = header.value, round = roundData.value;
  if (locked.value || !ready.value || !records.value?.canRecord || !meta || !round || meta.lockVersion == null || !meta.photosAvailable) return;
  const guards = { roundVersion: round.version, assetVersion: meta.assetVersion, ownershipVersion: meta.ownershipVersion, lockVersion: meta.lockVersion };
  let submission: Mutation;
  if (upload) {
    const chosen = file.value, pdf = chosen && /\.pdf$/i.test(chosen.name);
    if (!chosen || !chosen.size || chosen.name.length > 255 || !/\.(pdf|png|jpe?g|webp)$/i.test(chosen.name) || chosen.size > (pdf ? 20 : 10) * 1024 * 1024) { error.value = "请选择不超过10MB的图片或不超过20MB的PDF换机原件。"; return; }
    submission = { repairId: props.repair.id, path: `/rounds/${round.id}/evidence`, version: meta.version, file: chosen,
      form: Object.freeze({ roundVersion: String(guards.roundVersion), assetVersion: String(guards.assetVersion), ownershipVersion: String(guards.ownershipVersion), lockVersion: String(guards.lockVersion), purpose: "REPLACEMENT" }), label: `追加厂家换机原件：${chosen.name}` };
  } else {
    let observedAt: string; try { observedAt = observationTime(); } catch { error.value = "请填写真实换机核实日期和时刻。"; return; }
    const selected = (kind: Kind) => references.value[kind]?.items.find(item => item.id === choices.value[kind]);
    const category = selected("category"), organization = selected("organization"), warehouse = selected("warehouse"), location = selected("location");
    const allowed = new Set(originals.value.map(item => item.object_id)), evidence = [...evidenceIds.value];
    if (!name.value.trim() || !serial.value.trim() || !model.value.trim() || !unit.value.trim() || !date.value || !destinations[disposition.value] || reason.value.trim().length < 10 || !checked.value || !category || !organization || !warehouse || !location || location.warehouse?.id !== warehouse.id || !evidence.length || !evidence.every(id => allowed.has(id))) { error.value = "请核对新机资料、旧机实际去向、本轮厂家换机原件及入库目标。"; return; }
    const reference = (item: AssetReference) => Object.freeze({ id: item.id, version: item.version });
    const body = Object.freeze({ ...guards, originalDisposition: disposition.value, observedAt, evidenceIds: Object.freeze(evidence), reason: reason.value.trim(), replacementPhysicalIdentityChecked: true,
      newAsset: Object.freeze({ name: name.value.trim(), serialNumber: serial.value.trim(), model: model.value.trim(), unit: unit.value.trim(), category: reference(category), organization: reference(organization) }),
      businessDate: date.value, warehouseId: warehouse.id, warehouseVersion: warehouse.version, locationId: location.id, locationVersion: location.version });
    submission = { repairId: props.repair.id, path: `/rounds/${round.id}/replacements`, version: meta.version, body,
      label: `为新机 ${name.value.trim()}（序列号 ${serial.value.trim()}）建立独立新卡及换机来源入库草稿；旧机：${destinations[disposition.value]}。草稿还须单独提交、批准和实际验收入库。` };
  }
  draft(() => Object.freeze(submission)); error.value = ""; notice.value = "";
}
/** 旧机关联报废冻结本轮四个版本；去向码原样转写为建议处置，不由前端改写。 */
function prepareScrap(id: string) {
  const meta = header.value, round = roundData.value;
  const item = records.value?.items.find(value => value.id === id);
  if (locked.value || !ready.value || !records.value?.canRequestScrap || !item || !meta || !round || meta.lockVersion == null
      || !SCRAP_ELIGIBLE_DISPOSITIONS.has(item.originalDisposition)) return;
  if (!scrapReason.value.trim() || !scrapCondition.value.trim()) { error.value = SCRAP_INCOMPLETE_MESSAGE; return; }
  const scrap = Object.freeze({ assetVersion: meta.assetVersion, ownershipVersion: meta.ownershipVersion,
    lockVersion: meta.lockVersion, reason: scrapReason.value.trim(), observedCondition: scrapCondition.value.trim(),
    proposedDisposition: item.originalDisposition });
  draft(() => Object.freeze({ scrap: true as const, repairId: props.repair.id, version: meta.version,
    path: `/from-factory/${props.repair.id}/rounds/${round.id}/replacements/${item.id}`,
    body: Object.freeze({ roundVersion: round.version, scrap }), label: "按本轮厂家凭证提交旧机关联报废，等待管理员确认。" }));
  error.value = ""; notice.value = "";
}
async function perform() {
  if (!pending.value || busy.value || props.blocked) return;
  const operation = pending.value, turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    const linked = "scrap" in operation.payload;
    if (linked) {
      const receipt = await scrapRequest<{ id: string }>(operation.payload.path,
        { path: operation.payload.path, version: operation.payload.version, body: operation.payload.body,
          summary: operation.payload.label, key: operation.key });
      if (!liveness.isCurrent(turn)) return;
      settle(); clearDraft(); stale.value = true;
      scrapReceipt.value = receipt.id; scrapReason.value = ""; scrapCondition.value = "";
      notice.value = SCRAP_SAVED_NOTICE;
    } else {
      await factoryRequest(operation.payload.repairId, operation.payload.path, { ...operation.payload, key: operation.key });
      if (!liveness.isCurrent(turn)) return;
      settle(); clearDraft(); stale.value = true;
      notice.value = "本次换机资料已保存。新机须独立入库，旧机责任仍待按实际去向办理。";
    }
    await fetchState(turn);
  } catch (cause) { if (liveness.isCurrent(turn)) { fail(cause); failure(cause); } }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
  if (liveness.isCurrent(turn) && !pending.value) emit("changed");
}
async function confirm() { if (busy.value || props.blocked || pending.value || !adopt()) return; await perform(); }
watch(identity, () => { reset(); error.value = ""; notice.value = ""; }, { immediate: true, flush: "sync" });
watch(canRead, value => { if (!value) { reset(); error.value = ""; notice.value = ""; } }, { flush: "sync" });
onBeforeUnmount(() => { reset(); emit("busy-change", false); });
</script>
<template>
  <section v-if="canRead" class="fams-card fams-stack" aria-label="整机换新与独立入库">
    <div class="fams-panel-head">
      <div>
        <h4 class="fams-panel-title">整机换新与独立入库</h4>
        <p class="fams-panel-subtitle">新实物使用独立新卡、新编码和新序列号。新机入库不结束旧机维修、借用或待处理责任。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="headerRefreshDisabled" @click="load">核对整机换新记录</button>
      </div>
    </div>
    <template v-if="opened">
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <a v-if="showScrapReceipt" :href="scrapReceiptHref">{{ SCRAP_RECEIPT_LABEL }}</a>
      <p v-if="pending">本次结果尚待核对，重试将沿用原请求。</p>
      <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="retryDisabled" @click="perform">重试原换机办理</button>
      <div v-if="confirmation" class="fams-card fams-stack" role="group" aria-label="确认本次换机办理">
        <p>{{ confirmation.payload.label }}</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="confirmDisabled" @click="confirm">确认保存本次换机办理</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="confirmDisabled" @click="cancel()">返回修改换机办理</button>
        </div>
      </div>
      <article v-for="row in replacementRows" :key="row.id" class="fams-card fams-stack">
        <p>旧机：{{ row.dispositionText }}。</p>
        <p v-if="row.inbound">新机资产 {{ row.replacementAssetId }}；入库单 {{ row.inbound.receiptNumber }} · {{ row.inbound.status }}。<a :href="row.inboundHref">进入这张入库单核对并办理</a></p>
        <p v-else>新机建卡及入库草稿按原创建者权限保留，尚未进入当前身份可办理范围。</p>
      </article>
      <fieldset v-if="showScrapForm" class="fams-card fams-stack" :disabled="locked || !ready">
        <legend class="fams-panel-title">依据厂家凭证申请旧机报废</legend>
        <div class="fams-field-grid">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">旧机报废原因</span><textarea class="fams-control" v-model="scrapReason" maxlength="2000"/></label>
          <label class="fams-field fams-field-wide"><span class="fams-field-label">旧机已核实情况</span><textarea class="fams-control" v-model="scrapCondition" maxlength="2000"/></label>
        </div>
        <div class="fams-panel-actions">
          <button v-for="row in scrapReplacementRows" :key="row.id" class="fams-button" data-kind="primary" type="button" @click="prepareScrap(row.id)">核对旧机关联报废</button>
        </div>
      </fieldset>
      <template v-if="records?.canRecord && header && roundData">
        <fieldset class="fams-card fams-stack" :disabled="originalsDisabled">
          <legend class="fams-panel-title">厂家整机换新原件</legend>
          <label class="fams-file"><span class="fams-field-label">选择厂家换机图片或PDF原件</span><input type="file" accept=".jpg,.jpeg,.png,.webp,.pdf" @change="fileChosen"></label>
          <p v-if="file">已选换机原件：{{ file.name }}</p>
          <button class="fams-button" data-kind="secondary" type="button" @click="prepare(true)">核对追加厂家换机原件</button>
        </fieldset>
        <fieldset class="fams-card fams-stack" :disabled="originalsDisabled">
          <legend class="fams-panel-title">新机资料及旧机去向</legend>
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">新机资产名称</span><input class="fams-control" v-model="name" maxlength="256"></label>
            <label class="fams-field"><span class="fams-field-label">新机独立序列号</span><input class="fams-control" v-model="serial" maxlength="128"></label>
            <label class="fams-field"><span class="fams-field-label">新机型号</span><input class="fams-control" v-model="model" maxlength="128"></label>
            <label class="fams-field"><span class="fams-field-label">新机计量单位</span><input class="fams-control" v-model="unit" maxlength="32"></label>
          </div>
          <div v-for="row in referenceRows" :key="row.kind" class="fams-field-row">
            <label class="fams-field"><span class="fams-field-label">{{ row.label }}</span><select class="fams-control" v-model="choices[row.kind]"><option value="">请选择当前页有效资料</option><option v-for="item in row.options" :key="item.id" :value="item.id">{{ item.text }}</option></select></label>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="row.prevDisabled" @click="pageRefs(row.kind, -1)">上一页{{ row.label }}</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="row.nextDisabled" @click="pageRefs(row.kind, 1)">下一页{{ row.label }}</button>
          </div>
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">新机入库业务日期</span><input class="fams-control" v-model="date" type="date"></label>
            <label class="fams-field"><span class="fams-field-label">实际换机核实日期和时刻</span><input class="fams-control" v-model="observed" type="datetime-local" step="1"></label>
            <label class="fams-field"><span class="fams-field-label">旧机实际去向</span><select class="fams-control" v-model="disposition"><option value="">请按原实物事实选择</option><option v-for="opt in dispositionOptions" :key="opt.key" :value="opt.key" :disabled="opt.disabled">{{ opt.label }}</option></select></label>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">厂家换机及旧机去向依据</span><textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
          </div>
          <div class="fams-selection-group">
            <label v-for="item in originalsRows" :key="item.object_id" class="fams-selection"><input v-model="evidenceIds" type="checkbox" :value="item.object_id">选用厂家换机原件 {{ item.filename }}</label>
          </div>
          <div class="fams-inline">
            <button v-for="item in originalsRows" :key="item.readKey" class="fams-button" data-kind="link" data-size="sm" type="button" @click="emit('read-file', item.file!)">读取厂家换机原件 {{ item.filename }}</button>
          </div>
          <label class="fams-selection"><input v-model="checked" type="checkbox">已核实新实物、新序列号及厂家换机凭证，旧机去向依据真实</label>
          <button class="fams-button" data-kind="primary" type="button" @click="prepare(false)">核对建立新卡及入库草稿</button>
        </fieldset>
      </template>
    </template>
  </section>
</template>
