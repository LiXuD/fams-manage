<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import RepairCollaborationPanel from "./RepairCollaborationPanel.vue";
import RepairPhysicalPanel from "./RepairPhysicalPanel.vue";
import RepairReturnPanel from "./RepairReturnPanel.vue";
import RepairTargetChangePanel from "./RepairTargetChangePanel.vue";
import RepairReassignmentPanel from "./RepairReassignmentPanel.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, repairProblem, repairRequest, repairStates, type Repair, type RepairAsset, type RepairMutation, type RepairPhoto } from "@/repair/api";
const session = useSessionStore(), route = useRoute();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, route.params.id]));
const rows = ref<Repair[]>([]), total = ref(0), page = ref(1), detail = ref<Repair | null>(null);
const assets = ref<RepairAsset[]>([]), assetTotal = ref(0), assetPage = ref(1), assetId = ref("");
const fault = ref(""), impact = ref("MEDIUM"), occurredAt = ref(""), reason = ref("");
const observedCode = ref(""), evidence = ref(""), confirmedPlace = ref(false), neverLeft = ref(false);
const selectedFile = ref<File | null>(null), preview = ref(""), previewName = ref("");
const pending = ref<RepairMutation | null>(null), confirmation = ref<RepairMutation | null>(null);
const busy = ref(false), error = ref(""), notice = ref("");
const collaborationLocked = ref(false);
const physicalLocked = ref(false);
const returnLocked = ref(false);
const targetChangeLocked = ref(false);
const reassignmentLocked = ref(false);
const locked = computed(() => busy.value || !!pending.value || !!confirmation.value || collaborationLocked.value || physicalLocked.value || returnLocked.value || targetChangeLocked.value || reassignmentLocked.value);
const eventNames: Record<string, string> = { REPAIR_SUBMITTED: "已提交报修", REPAIR_DECIDED: "管理员已决定", REPAIR_CLAIMED: "库管已领取", REPAIR_CONTROLLED_REASSIGNMENT: "管理员已受控转派",
  REPAIR_ACCEPTED_IN_PLACE: "已受理原地维修", REPAIR_COMPLETED_IN_PLACE: "已核对原地恢复", REPAIR_CANCELLED: "已取消", REPAIR_FAULT_PHOTO_ADDED: "已补充故障照片", REPAIR_CONFLICT_REJECTED: "旧操作已拒绝" };
let generation = 0, live = true, download: AbortController | null = null;
const current = (epoch: number, owner: string) => live && generation === epoch && identity.value === owner;
function clearPhoto() { download?.abort(); download = null; if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ""; previewName.value = ""; }
function reset() {
  generation++; clearPhoto(); rows.value = []; assets.value = []; detail.value = null; pending.value = null; confirmation.value = null; selectedFile.value = null;
  fault.value = ""; reason.value = ""; evidence.value = ""; observedCode.value = ""; assetId.value = ""; occurredAt.value = ""; neverLeft.value = false; confirmedPlace.value = false;
  busy.value = false; total.value = 0; assetTotal.value = 0; page.value = 1; assetPage.value = 1;
}
function failure(cause: unknown) {
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) reset();
  error.value = repairProblem(cause);
}
async function load() {
  if (busy.value || confirmation.value) return;
  const epoch = ++generation, owner = identity.value, selected = detail.value?.id ?? (/^[1-9][0-9]{0,18}$/.test(String(route.params.id ?? "")) ? String(route.params.id) : "");
  busy.value = true; error.value = "";
  try {
    const result = await repairRequest<{ items: Repair[]; total: number }>(`?page=${page.value}&pageSize=20`);
    const selectedResult = selected ? await repairRequest<Repair>(`/${selected}`) : null;
    if (current(epoch, owner)) { rows.value = result.items; total.value = result.total; detail.value = selectedResult; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function open(row: Repair) {
  if (locked.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = ""; clearPhoto(); detail.value = null;
  selectedFile.value = null; reason.value = ""; observedCode.value = ""; evidence.value = ""; confirmedPlace.value = false; neverLeft.value = false;
  try { const result = await repairRequest<Repair>(`/${row.id}`); if (current(epoch, owner)) detail.value = result; }
  catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function loadAssets(delta = 0) {
  if (locked.value) return; const next = assetPage.value + delta;
  if (next < 1 || delta > 0 && (next - 1) * 20 >= assetTotal.value) return;
  const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { const result = await repairRequest<{ items: RepairAsset[]; total: number }>(`/eligible?page=${next}&pageSize=20`);
    if (current(epoch, owner)) { assets.value = result.items; assetTotal.value = result.total; assetPage.value = next; assetId.value = ""; }
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
function fileChosen(event: Event) { if (!locked.value) selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null; }
function prepare(action: "submit" | "approve" | "reject" | "cancel" | "claim" | "accept" | "complete" | "photo") {
  if (locked.value) return;
  error.value = ""; const selected = detail.value; let path = "", body: unknown, file: File | undefined;
  if (action === "submit") {
    const asset = assets.value.find(value => value.id === assetId.value), date = new Date(occurredAt.value);
    if (!asset || fault.value.trim().length < 3 || !Number.isFinite(date.getTime())) { error.value = "请选择资产并填写实际故障和发生时间。"; return; }
    body = { assetId: asset.id, assetVersion: asset.version, fault: fault.value.trim(), impact: impact.value, occurredAt: date.toISOString() };
  } else {
    if (!selected) return; path = `/${selected.id}`;
    if (["approve", "reject", "cancel"].includes(action)) {
      if (reason.value.trim().length < 3) { error.value = "请填写本次决定的明确原因。"; return; }
      path += action === "cancel" ? "/cancel" : "/decision";
      body = action === "cancel" ? { reason: reason.value.trim() } : { result: action === "approve" ? "APPROVE" : "REJECT", allowedMethod: action === "approve" ? "INTERNAL_NO_COST" : null, reason: reason.value.trim() };
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
  }
  confirmation.value = { path, key: crypto.randomUUID(), version: action === "submit" ? 0 : selected!.version, body, file };
}
async function perform() {
  if (busy.value || !pending.value) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const result = await repairRequest<Repair & { repairId?: string }>(operation.path, operation);
    const id = result.repairId ?? result.id;
    const selected = await repairRequest<Repair>(`/${id}`);
    const list = await repairRequest<{ items: Repair[]; total: number }>(`?page=${page.value}&pageSize=20`);
    if (current(epoch, owner)) { pending.value = null; detail.value = selected; rows.value = list.items; total.value = list.total;
      selectedFile.value = null; confirmedPlace.value = false; neverLeft.value = false; evidence.value = ""; observedCode.value = "";
      notice.value = "本次结果已保存。批准、库管受理和实际修复分别留痕。"; clearPhoto(); }
  } catch (cause) {
    if (current(epoch, owner)) { if (cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); }
  } finally { if (current(epoch, owner)) busy.value = false; }
}
async function confirm() { if (!confirmation.value || busy.value) return; pending.value = confirmation.value; confirmation.value = null; await perform(); }
async function readPhoto(photo: RepairPhoto) {
  if (locked.value || !detail.value) return; clearPhoto();
  const id = detail.value.id, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    const grant = await repairRequest<{ url: string }>(`/${id}/photos/${photo.objectId}/download-grant`, { path: "", key: crypto.randomUUID(), version: detail.value.version, body: {} });
    if (!current(epoch, owner)) return;
    if (!grant.url.startsWith(`/api/v1/repairs/${id}/photos/${photo.objectId}/content?grant=`)) throw new Error("Invalid photo location");
    download = new AbortController(); const response = await fetch(grant.url, { credentials: "include", cache: "no-store", signal: download.signal });
    if (!response.ok) throw new RepairApiError(response.status, "REPAIR_PHOTO_READ_FAILED", {});
    const blob = await response.blob();
    if (!current(epoch, owner)) return;
    if (blob.size !== photo.sizeBytes || blob.size > 10 * 1024 * 1024 || !["image/jpeg", "image/png", "image/webp"].includes(blob.type)) throw new Error("Invalid photo bytes");
    preview.value = URL.createObjectURL(blob); previewName.value = photo.filename;
  } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function paginate(delta: number) { if (locked.value || page.value + delta < 1 || (page.value + delta - 1) * 20 >= total.value) return; page.value += delta; await load(); }
watch(identity, () => { reset(); error.value = ""; notice.value = ""; void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; reset(); });
</script>
<template>
  <AppChrome><section aria-label="报修与内部维修" class="repair-page"><h1>报修与内部维修</h1><p>为本人责任资产或本部门公用资产报修。管理员批准处理范围后，由唯一处理库管核实实物并办理。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p><button :disabled="busy || !!confirmation" @click="load">刷新并核对当前结果</button>
    <button v-if="pending" :disabled="busy" @click="perform">重试原报修操作</button>
    <section v-if="confirmation" aria-label="确认本次报修办理"><p>请核对本次资产、实际依据及处理范围。批准不会自动领取，原地关闭须确认已恢复且实物从未离开原保管人。</p><button :disabled="busy" @click="confirm">确认保存本次办理</button><button :disabled="busy" @click="confirmation = null">返回修改</button></section>
    <section><h2>提交报修</h2><button :disabled="locked" @click="loadAssets()">查找可报修资产</button>
      <label v-for="asset in assets" :key="asset.id"><input v-model="assetId" type="radio" :value="asset.id" :disabled="locked">{{ asset.assetCode }} · {{ asset.name }} · {{ asset.locationName || '位置待核实' }}</label>
      <button :disabled="locked || assetPage === 1" @click="loadAssets(-1)">上一页可报修资产</button>第 {{ assetPage }} 页 / 共 {{ assetTotal }} 项<button :disabled="locked || assetPage * 20 >= assetTotal" @click="loadAssets(1)">下一页可报修资产</button>
      <fieldset :disabled="locked"><label>实际故障<textarea v-model="fault" maxlength="2000" /></label><label>影响程度<select v-model="impact"><option value="LOW">轻微影响</option><option value="MEDIUM">部分影响使用</option><option value="HIGH">无法正常使用</option></select></label><label>实际发生时间<input v-model="occurredAt" type="datetime-local"></label><button @click="prepare('submit')">核对提交报修</button></fieldset>
    </section>
    <section><h2>报修进度</h2><ul><li v-for="row in rows" :key="row.id"><button :disabled="locked" @click="open(row)">{{ row.source.assetCode }} · {{ row.fault }} · {{ repairStates[row.status] || '待核实' }}</button></li></ul>
      <button :disabled="locked || page === 1" @click="paginate(-1)">上一页报修</button>第 {{ page }} 页 / 共 {{ total }} 条<button :disabled="locked || page * 20 >= total" @click="paginate(1)">下一页报修</button>
    </section>
    <section v-if="detail" aria-label="当前报修详情"><h2>{{ detail.source.assetCode }} · {{ detail.source.name }}</h2><p>{{ repairStates[detail.status] }} · {{ detail.fault }} · 实际发生 {{ new Date(detail.occurredAt).toLocaleString() }}</p>
      <p>提交时位置：{{ detail.source.locationName || '待核实' }}。原责任与借用作为返回依据保留；实物离开过或原目标失效时，不能直接原地恢复。</p>
      <section><h3>原始故障照片</h3><p v-if="!detail.photosAvailable">照片服务暂不可用，请联系管理员。</p><ul><li v-for="photo in detail.photos" :key="photo.objectId">{{ photo.filename }}<button :disabled="locked || photo.scanStatus !== 'CLEAN'" @click="readPhoto(photo)">读取故障照片 {{ photo.filename }}</button></li></ul>
        <fieldset v-if="detail.actions?.addFaultPhoto" :disabled="locked"><label>选择故障图片<input type="file" accept=".jpg,.jpeg,.png,.webp" @change="fileChosen"></label><button @click="prepare('photo')">核对上传故障照片</button><p>管理员决定后保留批准时的照片依据，不能覆盖原记录。</p></fieldset>
        <figure v-if="preview"><img :src="preview" :alt="`故障照片：${previewName}`"><figcaption>{{ previewName }}</figcaption><button @click="clearPhoto">关闭故障照片</button></figure>
      </section>
      <fieldset v-if="detail.actions?.approve || detail.actions?.cancel" :disabled="locked"><label>本次决定原因<textarea v-model="reason" maxlength="1000" /></label><template v-if="detail.actions.approve"><p>本轮批准范围：内部无费用处理。批准不等于已受理或已修复。</p><button @click="prepare('approve')">批准内部无费用处理</button><button @click="prepare('reject')">驳回无效报修</button></template><button v-if="detail.actions.cancel" @click="prepare('cancel')">取消尚未批准的报修</button></fieldset>
      <button v-if="detail.actions?.claim" :disabled="locked" @click="prepare('claim')">领取维修处理</button>
      <fieldset v-if="detail.actions?.acceptInPlace || detail.actions?.completeInPlace" :disabled="locked"><h3>{{ detail.actions.completeInPlace ? '核对已恢复并原地关闭' : '现场核实并受理原地维修' }}</h3><label>现场核对的资产编码<input v-model="observedCode" maxlength="2048"></label><label><input v-model="confirmedPlace" type="checkbox">已现场核对实物仍在 {{ detail.source.locationName || '原位置' }}</label><label><input v-model="neverLeft" type="checkbox">实物从未离开原保管人，本次按原地处理核实</label><label>实际处理及核实依据<textarea v-model="evidence" maxlength="2000" /></label><button v-if="detail.actions.acceptInPlace" @click="prepare('accept')">核对受理原地维修</button><button v-if="detail.actions.completeInPlace" @click="prepare('complete')">确认已恢复并原地关闭</button></fieldset>
      <RepairReassignmentPanel :repair="detail" :blocked="busy || !!pending || !!confirmation || collaborationLocked || physicalLocked || returnLocked || targetChangeLocked" @changed="load" @invalidated="reset" @busy-change="reassignmentLocked = $event" />
      <RepairPhysicalPanel :repair="detail" :blocked="busy || !!pending || !!confirmation || collaborationLocked || returnLocked || targetChangeLocked || reassignmentLocked" @changed="load" @invalidated="reset" @busy-change="physicalLocked = $event" />
      <RepairReturnPanel :repair="detail" :blocked="busy || !!pending || !!confirmation || collaborationLocked || physicalLocked || targetChangeLocked || reassignmentLocked" @changed="load" @invalidated="reset" @busy-change="returnLocked = $event" />
      <RepairTargetChangePanel :repair="detail" :blocked="busy || !!pending || !!confirmation || collaborationLocked || physicalLocked || returnLocked || reassignmentLocked" @changed="load" @invalidated="reset" @busy-change="targetChangeLocked = $event" />
      <RepairCollaborationPanel :repair-id="detail.id" :request-version="detail.version" :blocked="busy || !!pending || !!confirmation || physicalLocked || returnLocked || targetChangeLocked || reassignmentLocked" @changed="load" @invalidated="reset" @busy-change="collaborationLocked = $event" />
      <h3>最近100条处理历史 / 共 {{ detail.historyTotal || 0 }} 条</h3><ul><li v-for="event in detail.history" :key="event.id">{{ event.actor_username }} · {{ new Date(event.occurred_at).toLocaleString() }} · {{ eventNames[event.action] || '历史处理' }} · {{ event.detail_json.reason || event.detail_json.evidence || event.detail_json.fault || '' }}</li></ul>
    </section>
  </section></AppChrome>
</template>
<style scoped>
.repair-page { max-width: 1080px; } section { margin: 20px 0; } fieldset { border: 1px solid #d9e0e8; padding: 16px; } label { display: block; margin: 12px 0; } textarea { display: block; width: min(100%, 720px); min-height: 70px; } button { margin: 6px; } img { max-width: min(100%, 800px); max-height: 560px; } figure { margin: 12px 0; }
</style>
