<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, type Repair } from "@/repair/api";
import { factoryProblem, factoryRequest, type FactoryMutation } from "@/repair/factory-api";
import type { FactoryDetail, FactoryFile, FactoryRoundDetail } from "@/repair/factory-types";
import type { AssetReference, Page } from "@/asset/api";

interface Replacement { id: string; sourceId: string; originalDisposition: string; replacementAssetId: string | null; inbound: { id: string; receiptNumber: string; status: string } | null }
interface Replacements { items: Replacement[]; canRecord: boolean }
type Kind = "category" | "organization" | "warehouse" | "location";
const sources: { kind: Kind; label: string; path: string }[] = [
  { kind: "category", label: "资产分类", path: "/asset-categories" }, { kind: "organization", label: "归属组织", path: "/organizations" },
  { kind: "warehouse", label: "计划入库仓库", path: "/warehouses" }, { kind: "location", label: "计划入库库位", path: "/locations" },
];
const props = defineProps<{ repair: Repair; round: FactoryRoundDetail; blocked: boolean }>();
const emit = defineEmits<{ "busy-change": [boolean]; changed: []; invalidated: []; "read-file": [FactoryFile] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id, props.round.id]));
const canRead = computed(() => !!session.user?.roles.includes("ASSET_ADMIN") || !!session.user?.roles.includes("WAREHOUSE_OPERATOR") && String(session.user.id) === props.repair.processorAccountId);
const opened = ref(false), busy = ref(false), stale = ref(true), error = ref(""), notice = ref("");
const header = ref<FactoryDetail | null>(null), roundData = ref<FactoryRoundDetail | null>(null), records = ref<Replacements | null>(null);
const confirmation = ref<FactoryMutation | null>(null), pending = ref<FactoryMutation | null>(null);
const name = ref(""), serial = ref(""), model = ref(""), unit = ref(""), date = ref(""), observed = ref(""), disposition = ref(""), reason = ref("");
const checked = ref(false), evidenceIds = ref<string[]>([]), file = ref<File | null>(null);
const choices = ref<Record<Kind, string>>({ category: "", organization: "", warehouse: "", location: "" });
const references = ref<Partial<Record<Kind, Page<AssetReference>>>>({});
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value && canRead.value;
const locked = computed(() => props.blocked || busy.value || !!pending.value || !!confirmation.value);
const ready = computed(() => !stale.value && header.value?.version === props.repair.version && roundData.value?.version === props.round.version);
const originals = computed(() => roundData.value?.evidence.filter(item => item.purpose === "REPLACEMENT" && roundData.value?.files.some(f => f.objectId === item.object_id && f.scanStatus === "CLEAN")) ?? []);
const destinations: Record<string, string> = { FACTORY_RETAINED: "厂家已收走旧机，责任待办理", FACTORY_DESTROYED: "厂家已确认销毁旧机，待关联报废", CONTINUES_REPAIR: "旧机暂留维修，责任继续保留" };
watch([busy, pending, confirmation], () => emit("busy-change", busy.value || !!pending.value || !!confirmation.value), { flush: "sync" });
watch(() => choices.value.warehouse, () => { choices.value.location = ""; });
function clearDraft() { name.value = ""; serial.value = ""; model.value = ""; unit.value = ""; date.value = ""; observed.value = ""; disposition.value = ""; reason.value = ""; checked.value = false; evidenceIds.value = []; file.value = null; }
function reset() { generation++; opened.value = false; busy.value = false; stale.value = true; header.value = null; roundData.value = null; records.value = null; confirmation.value = null; pending.value = null; references.value = {}; choices.value = { category: "", organization: "", warehouse: "", location: "" }; clearDraft(); }
function failure(cause: unknown) {
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) { reset(); emit("invalidated"); }
  if (cause instanceof RepairApiError && [400, 409, 413, 422].includes(cause.status)) stale.value = true;
  error.value = factoryProblem(cause);
}
async function fetchRefs(kind: Kind, page: number, epoch: number, owner: string) {
  const path = sources.find(source => source.kind === kind)!.path;
  const response = await fetch(`/api/v1${path}/active-references?page=${page}&pageSize=50`, { credentials: "include" });
  if (!response.ok) throw new RepairApiError(response.status, "REPAIR_REPLACEMENT_REFERENCE_UNAVAILABLE", {});
  const result = await response.json() as Page<AssetReference>;
  if (current(epoch, owner)) references.value[kind] = result;
}
async function pageRefs(kind: Kind, delta: number) {
  if (locked.value) return;
  const old = references.value[kind], next = (old?.page ?? 1) + delta;
  if (next < 1 || delta > 0 && next > Math.ceil((old?.total ?? 0) / 50)) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await fetchRefs(kind, next, epoch, owner); if (current(epoch, owner)) choices.value[kind] = ""; }
  catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function fetchState(epoch: number, owner: string) {
  const id = props.repair.id, round = props.round.id;
  const nextHeader = await factoryRequest<FactoryDetail>(id);
  if (!current(epoch, owner)) return;
  const nextRound = await factoryRequest<FactoryRoundDetail>(id, `/rounds/${round}`);
  if (!current(epoch, owner)) return;
  const nextRecords = await factoryRequest<Replacements>(id, `/rounds/${round}/replacements`);
  if (!current(epoch, owner)) return;
  header.value = nextHeader; roundData.value = nextRound; records.value = nextRecords; stale.value = false;
}
async function load() {
  if (busy.value || props.blocked || confirmation.value || !canRead.value) return;
  opened.value = true; const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    await fetchState(epoch, owner);
    if (current(epoch, owner) && records.value?.canRecord) await Promise.all(sources.map(source => fetchRefs(source.kind, references.value[source.kind]?.page ?? 1, epoch, owner)));
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
  if (current(epoch, owner) && header.value?.version !== props.repair.version) emit("changed");
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
  let mutation: FactoryMutation;
  if (upload) {
    const chosen = file.value, pdf = chosen && /\.pdf$/i.test(chosen.name);
    if (!chosen || !chosen.size || chosen.name.length > 255 || !/\.(pdf|png|jpe?g|webp)$/i.test(chosen.name) || chosen.size > (pdf ? 20 : 10) * 1024 * 1024) { error.value = "请选择不超过10MB的图片或不超过20MB的PDF换机原件。"; return; }
    mutation = { repairId: props.repair.id, path: `/rounds/${round.id}/evidence`, version: meta.version, key: crypto.randomUUID(), file: chosen,
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
    mutation = { repairId: props.repair.id, path: `/rounds/${round.id}/replacements`, version: meta.version, key: crypto.randomUUID(), body,
      label: `为新机 ${name.value.trim()}（序列号 ${serial.value.trim()}）建立独立新卡及换机来源入库草稿；旧机：${destinations[disposition.value]}。草稿还须单独提交、批准和实际验收入库。` };
  }
  confirmation.value = Object.freeze(mutation); error.value = ""; notice.value = "";
}
async function perform() {
  if (!pending.value || busy.value || props.blocked) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    await factoryRequest(operation.repairId, operation.path, operation);
    if (!current(epoch, owner)) return;
    pending.value = null; clearDraft(); stale.value = true; notice.value = "本次换机资料已保存。新机须独立入库，旧机责任仍待按实际去向办理。";
    await fetchState(epoch, owner);
  } catch (cause) { if (current(epoch, owner)) { if (cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); } }
  finally { if (current(epoch, owner)) busy.value = false; }
  if (current(epoch, owner) && !pending.value) emit("changed");
}
async function confirm() { if (busy.value || props.blocked || pending.value || !confirmation.value) return; pending.value = confirmation.value; confirmation.value = null; await perform(); }
watch(identity, () => { reset(); error.value = ""; notice.value = ""; }, { immediate: true, flush: "sync" });
watch(canRead, value => { if (!value) { reset(); error.value = ""; notice.value = ""; } }, { flush: "sync" });
onBeforeUnmount(() => { live = false; reset(); emit("busy-change", false); });
</script>
<template>
  <section v-if="canRead" aria-label="整机换新与独立入库" class="replacement-panel">
    <h4>整机换新与独立入库</h4><p>新实物使用独立新卡、新编码和新序列号。新机入库不结束旧机维修、借用或待处理责任。</p>
    <button :disabled="props.blocked || busy || !!confirmation" @click="load">核对整机换新记录</button>
    <template v-if="opened">
      <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="pending">本次结果尚待核对，重试将沿用原请求。</p><button v-if="pending" :disabled="busy || props.blocked" @click="perform">重试原换机办理</button>
      <section v-if="confirmation" aria-label="确认本次换机办理"><p>{{ confirmation.label }}</p><button :disabled="busy || props.blocked" @click="confirm">确认保存本次换机办理</button><button :disabled="busy || props.blocked" @click="confirmation = null">返回修改换机办理</button></section>
      <article v-for="item in records?.items" :key="item.id"><p>旧机：{{ destinations[item.originalDisposition] }}。</p><p v-if="item.inbound">新机资产 {{ item.replacementAssetId }}；入库单 {{ item.inbound.receiptNumber }} · {{ item.inbound.status }}。<a :href="'/inbound?receiptId=' + item.inbound.id">进入这张入库单核对并办理</a></p><p v-else>新机建卡及入库草稿按原创建者权限保留，尚未进入当前身份可办理范围。</p></article>
      <template v-if="records?.canRecord && header && roundData">
        <fieldset :disabled="locked || !ready || !header.photosAvailable"><legend>厂家整机换新原件</legend><label>选择厂家换机图片或PDF原件<input type="file" accept=".jpg,.jpeg,.png,.webp,.pdf" @change="fileChosen"></label><p v-if="file">已选换机原件：{{ file.name }}</p><button @click="prepare(true)">核对追加厂家换机原件</button></fieldset>
        <fieldset :disabled="locked || !ready || !header.photosAvailable"><legend>新机资料及旧机去向</legend>
          <label>新机资产名称<input v-model="name" maxlength="256"></label><label>新机独立序列号<input v-model="serial" maxlength="128"></label><label>新机型号<input v-model="model" maxlength="128"></label><label>新机计量单位<input v-model="unit" maxlength="32"></label>
          <div v-for="source in sources" :key="source.kind"><label>{{ source.label }}<select v-model="choices[source.kind]"><option value="">请选择当前页有效资料</option><option v-for="item in references[source.kind]?.items.filter(item => source.kind !== 'location' || item.warehouse?.id === choices.warehouse)" :key="item.id" :value="item.id">{{ item.code }} · {{ item.name }}</option></select></label><button :disabled="(references[source.kind]?.page ?? 1) === 1" @click="pageRefs(source.kind, -1)">上一页{{ source.label }}</button><button :disabled="(references[source.kind]?.page ?? 1) * 50 >= (references[source.kind]?.total ?? 0)" @click="pageRefs(source.kind, 1)">下一页{{ source.label }}</button></div>
          <label>新机入库业务日期<input v-model="date" type="date"></label><label>实际换机核实日期和时刻<input v-model="observed" type="datetime-local" step="1"></label>
          <label>旧机实际去向<select v-model="disposition"><option value="">请按原实物事实选择</option><option v-for="(label, key) in destinations" :key="key" :value="key" :disabled="roundData.roundStatus === 'RECEIVED' && key !== 'CONTINUES_REPAIR'">{{ label }}</option></select></label>
          <label>厂家换机及旧机去向依据<textarea v-model="reason" maxlength="1000" /></label>
          <label v-for="item in originals" :key="item.object_id"><input v-model="evidenceIds" type="checkbox" :value="item.object_id">选用厂家换机原件 {{ roundData.files.find(f => f.objectId === item.object_id)?.filename }}</label>
          <button v-for="item in originals" :key="`read-${item.object_id}`" @click="emit('read-file', roundData.files.find(f => f.objectId === item.object_id)!)">读取厂家换机原件 {{ roundData.files.find(f => f.objectId === item.object_id)?.filename }}</button>
          <label><input v-model="checked" type="checkbox">已核实新实物、新序列号及厂家换机凭证，旧机去向依据真实</label><button @click="prepare(false)">核对建立新卡及入库草稿</button>
        </fieldset>
      </template>
    </template>
  </section>
</template>
<style scoped>
.replacement-panel { border-top: 1px solid #d9e0e8; margin-top: 16px; padding-top: 12px; } fieldset { border: 1px solid #d9e0e8; margin: 16px 0; padding: 16px; } label { display: block; margin: 12px 0; } textarea { display: block; width: min(100%, 720px); min-height: 70px; } button { margin: 6px; }
</style>
