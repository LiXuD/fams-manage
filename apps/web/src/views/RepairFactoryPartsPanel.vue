<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, type Repair } from "@/repair/api";
import { factoryProblem, factoryRequest, type FactoryMutation } from "@/repair/factory-api";
import type { FactoryDetail, FactoryFile, FactoryPartsPage, FactoryReply, FactoryRoundDetail } from "@/repair/factory-types";

const props = defineProps<{ repair: Repair; round: FactoryRoundDetail; blocked: boolean }>();
const emit = defineEmits<{ "busy-change": [boolean]; changed: []; invalidated: []; "read-file": [FactoryFile] }>();
const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken, props.repair.id, props.round.id]));
const canRead = computed(() => !!session.user?.roles.includes("ASSET_ADMIN") || !!session.user?.roles.includes("WAREHOUSE_OPERATOR") && String(session.user.id) === props.repair.processorAccountId);
const opened = ref(false), busy = ref(false), stale = ref(true), error = ref(""), notice = ref("");
const header = ref<FactoryDetail | null>(null), roundData = ref<FactoryRoundDetail | null>(null), records = ref<FactoryPartsPage | null>(null), page = ref(1);
const confirmation = ref<FactoryMutation | null>(null), pending = ref<FactoryMutation | null>(null);
const part = ref(""), before = ref(""), after = ref(""), code = ref(""), serial = ref(""), dateTime = ref(""), reason = ref("");
const checked = ref(false), evidenceIds = ref<string[]>([]), file = ref<File | null>(null);
let generation = 0, live = true;
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value && canRead.value;
const locked = computed(() => props.blocked || busy.value || !!pending.value || !!confirmation.value);
const ready = computed(() => !stale.value && header.value?.version === props.repair.version && roundData.value?.version === props.round.version);
const originals = computed(() => roundData.value?.evidence.filter(item => item.purpose === "PARTS_CHANGE" && roundData.value?.files.some(f => f.objectId === item.object_id && f.scanStatus === "CLEAN")) ?? []);
watch([busy, pending, confirmation], () => emit("busy-change", busy.value || !!pending.value || !!confirmation.value), { flush: "sync" });
function clearDraft() { part.value = ""; before.value = ""; after.value = ""; code.value = ""; serial.value = ""; dateTime.value = ""; reason.value = ""; checked.value = false; evidenceIds.value = []; file.value = null; }
function reset() { generation++; opened.value = false; busy.value = false; stale.value = true; header.value = null; roundData.value = null; records.value = null; page.value = 1; confirmation.value = null; pending.value = null; clearDraft(); }
function failure(cause: unknown) {
  if (cause instanceof RepairApiError && [401, 403, 404].includes(cause.status)) { reset(); emit("invalidated"); }
  if (cause instanceof RepairApiError && [400, 409, 413, 422].includes(cause.status)) stale.value = true;
  error.value = factoryProblem(cause);
}
async function fetchState(epoch: number, owner: string) {
  const id = props.repair.id, round = props.round.id;
  const nextHeader = await factoryRequest<FactoryDetail>(id);
  if (!current(epoch, owner)) return;
  const nextRound = await factoryRequest<FactoryRoundDetail>(id, `/rounds/${round}`);
  if (!current(epoch, owner)) return;
  const nextRecords = await factoryRequest<FactoryPartsPage>(id, `/rounds/${round}/parts?page=${page.value}&pageSize=20`);
  if (!current(epoch, owner)) return;
  header.value = nextHeader; roundData.value = nextRound; records.value = nextRecords; stale.value = false;
}
async function load(delta = 0) {
  if (busy.value || props.blocked || confirmation.value || !canRead.value) return;
  const next = page.value + delta;
  if (next < 1 || delta > 0 && (next - 1) * 20 >= (records.value?.total ?? 0)) return;
  page.value = next; opened.value = true; const epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try { await fetchState(epoch, owner); }
  catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
  if (current(epoch, owner) && header.value?.version !== props.repair.version) emit("changed");
}
function observationTime() {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(dateTime.value);
  if (!match) throw new Error("Observation time required");
  const [year, month, day, hour, minute, second] = match.slice(1).map(value => Number(value ?? 0));
  const date = new Date(year, month - 1, day, hour, minute, second);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day || date.getHours() !== hour || date.getMinutes() !== minute || date.getSeconds() !== second) throw new Error("Observation time invalid");
  return date.toISOString();
}
function fileChosen(event: Event) {
  if (locked.value) return;
  const input = event.target as HTMLInputElement; file.value = input.files?.[0] ?? null; input.value = "";
}
function prepare(upload: boolean) {
  const meta = header.value, round = roundData.value;
  if (locked.value || !ready.value || !records.value?.canRecord || !meta || !round || meta.lockVersion == null || !meta.photosAvailable) return;
  const guards = { roundVersion: round.version, assetVersion: meta.assetVersion, ownershipVersion: meta.ownershipVersion, lockVersion: meta.lockVersion };
  let mutation: FactoryMutation;
  if (upload) {
    const chosen = file.value, pdf = chosen && /\.pdf$/i.test(chosen.name);
    if (!chosen || !chosen.size || chosen.name.length > 255 || !/\.(pdf|png|jpe?g|webp)$/i.test(chosen.name) || chosen.size > (pdf ? 20 : 10) * 1024 * 1024) { error.value = "请选择不超过10MB的图片或不超过20MB的PDF部件变更原件。"; return; }
    mutation = { repairId: props.repair.id, path: `/rounds/${round.id}/evidence`, version: meta.version, key: crypto.randomUUID(), file: chosen,
      form: Object.freeze({ roundVersion: String(guards.roundVersion), assetVersion: String(guards.assetVersion), ownershipVersion: String(guards.ownershipVersion), lockVersion: String(guards.lockVersion), purpose: "PARTS_CHANGE" }), label: `追加本轮部件变更原件：${chosen.name}` };
  } else {
    let observedAt: string; try { observedAt = observationTime(); } catch { error.value = "请填写真实核实日期和时刻（本机时区，含秒）。"; return; }
    const allowed = new Set(originals.value.map(item => item.object_id)), selected = [...evidenceIds.value];
    if (!part.value.trim() || !before.value.trim() || !after.value.trim() || before.value.trim() === after.value.trim() || !code.value.trim() || round.originalIdentity.serialNumber && !serial.value.trim() || !checked.value || reason.value.trim().length < 10 || !selected.length || selected.length > 14 || !selected.every(id => allowed.has(id))) { error.value = "请核对同一原机身份，填写部件前后资料、依据并选用本轮部件变更原件。"; return; }
    const body = Object.freeze({ ...guards, partName: part.value.trim(), beforeInfo: before.value.trim(), afterInfo: after.value.trim(), observedCode: code.value.trim(), observedSerial: serial.value.trim() || null, observedAt, evidenceIds: Object.freeze(selected), reason: reason.value.trim(), originalPhysicalIdentityChecked: true });
    mutation = { repairId: props.repair.id, path: `/rounds/${round.id}/parts`, version: meta.version, key: crypto.randomUUID(), body, label: `核实原机部件变更：${part.value.trim()}；变更前：${before.value.trim()}；变更后：${after.value.trim()}` };
  }
  confirmation.value = Object.freeze(mutation); error.value = ""; notice.value = "";
}
async function perform() {
  if (!pending.value || busy.value || props.blocked) return;
  const operation = pending.value, epoch = ++generation, owner = identity.value; busy.value = true; error.value = "";
  try {
    await factoryRequest<FactoryReply>(operation.repairId, operation.path, operation);
    if (!current(epoch, owner)) return;
    pending.value = null; clearDraft(); stale.value = true; notice.value = "本次部件资料已保存，原记录保留。维修仍须按实际去向办理结束。";
    await fetchState(epoch, owner);
  } catch (cause) {
    if (current(epoch, owner)) { if (cause instanceof RepairApiError && cause.status >= 400 && cause.status < 500) pending.value = null; failure(cause); }
  } finally { if (current(epoch, owner)) busy.value = false; }
  if (current(epoch, owner) && !pending.value) emit("changed");
}
async function confirm() { if (busy.value || props.blocked || pending.value || !confirmation.value) return; pending.value = confirmation.value; confirmation.value = null; await perform(); }
watch(identity, () => { reset(); error.value = ""; notice.value = ""; }, { immediate: true, flush: "sync" });
watch(canRead, value => { if (!value) { reset(); error.value = ""; notice.value = ""; } }, { flush: "sync" });
watch([() => props.repair.version, () => props.round.version], () => { if (header.value?.version !== props.repair.version || roundData.value?.version !== props.round.version) stale.value = true; });
onBeforeUnmount(() => { live = false; reset(); emit("busy-change", false); });
</script>
<template>
  <section v-if="canRead" class="fams-card fams-stack" aria-label="原机部件变更">
    <div class="fams-panel-head">
      <div>
        <h4 class="fams-panel-title">同一原机的部件变更</h4>
        <p class="fams-panel-subtitle">核实部件前后资料并保留原记录。整机换新须另建独立资产卡；本次核实不结束维修。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="props.blocked || busy || !!confirmation" @click="load()">核对部件变更记录</button>
      </div>
    </div>
    <template v-if="opened">
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <p v-if="pending">本次结果尚待核对，重试将沿用本次原请求。</p>
      <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="busy || props.blocked" @click="perform">重试原部件办理</button>
      <div v-if="confirmation" class="fams-card fams-stack" role="group" aria-label="确认本次部件办理">
        <p>{{ confirmation.label }}</p>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="busy || props.blocked" @click="confirm">确认保存本次部件办理</button>
          <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || props.blocked" @click="confirmation = null">返回修改部件办理</button>
        </div>
      </div>
      <template v-if="records && header && roundData">
        <p>本轮部件核实记录共 {{ records.total }} 条。</p>
        <article v-for="item in records.items" :key="item.id" class="fams-card fams-stack">
          <h5 class="fams-panel-title">{{ item.partName }}</h5>
          <p>变更前：{{ item.beforeInfo }}</p>
          <p>变更后：{{ item.afterInfo }}</p>
          <p>核实于 {{ new Date(item.observedAt).toLocaleString() }}；记录于 {{ new Date(item.recordedAt).toLocaleString() }}。</p>
        </article>
        <div class="fams-pagination">
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page === 1" @click="load(-1)">上一页部件记录</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page * 20 >= records.total" @click="load(1)">下一页部件记录</button>
          </div>
        </div>
        <p v-if="!records.canRecord">当前仅可核对原记录；须由当前处理库管在最新原机收回且实物仍待办理时核实部件。</p>
        <template v-else>
          <p v-if="!header.photosAvailable">原件服务暂不可用，请保留部件变更原件。</p>
          <fieldset class="fams-card fams-stack" :disabled="locked || !ready || !header.photosAvailable">
            <legend class="fams-panel-title">本轮部件变更原件</legend>
            <label class="fams-file"><span class="fams-field-label">选择部件变更图片或PDF原件</span><input type="file" accept=".jpg,.jpeg,.png,.webp,.pdf" @change="fileChosen"></label>
            <p v-if="file">已选部件原件：{{ file.name }}</p>
            <button class="fams-button" data-kind="secondary" type="button" @click="prepare(true)">核对追加部件变更原件</button>
          </fieldset>
          <fieldset class="fams-card fams-stack" :disabled="locked || !ready || !header.photosAvailable">
            <legend class="fams-panel-title">核实同一原机部件</legend>
            <p>本轮原机 {{ roundData.originalIdentity.assetCode }}{{ roundData.originalIdentity.serialNumber ? ` · 序列号 ${roundData.originalIdentity.serialNumber}` : '' }}。</p>
            <div class="fams-field-grid">
              <label class="fams-field"><span class="fams-field-label">核实的部件名称</span><input class="fams-control" v-model="part" maxlength="100"></label>
              <label class="fams-field fams-field-wide"><span class="fams-field-label">变更前部件或身份资料</span><textarea class="fams-control" v-model="before" maxlength="2000" /></label>
              <label class="fams-field fams-field-wide"><span class="fams-field-label">变更后部件或身份资料</span><textarea class="fams-control" v-model="after" maxlength="2000" /></label>
              <label class="fams-field"><span class="fams-field-label">部件核实的原机资产编码</span><input class="fams-control" v-model="code" maxlength="2048"></label>
              <label v-if="roundData.originalIdentity.serialNumber" class="fams-field"><span class="fams-field-label">部件核实的原机序列号</span><input class="fams-control" v-model="serial" maxlength="128"></label>
              <label class="fams-field"><span class="fams-field-label">部件核实日期和时刻（本机时区，含秒）</span><input class="fams-control" v-model="dateTime" type="datetime-local" step="1"></label>
              <label class="fams-field fams-field-wide"><span class="fams-field-label">部件变更核实依据</span><textarea class="fams-control" v-model="reason" maxlength="1000" /></label>
            </div>
            <div class="fams-selection-group">
              <label v-for="item in originals" :key="item.object_id" class="fams-selection"><input v-model="evidenceIds" type="checkbox" :value="item.object_id">选用部件变更原件 {{ roundData.files.find(f => f.objectId === item.object_id)?.filename }}</label>
            </div>
            <div class="fams-inline">
              <button v-for="item in originals" :key="`read-${item.object_id}`" class="fams-button" data-kind="link" data-size="sm" type="button" @click="emit('read-file', roundData.files.find(f => f.objectId === item.object_id)!)">读取部件变更原件 {{ roundData.files.find(f => f.objectId === item.object_id)?.filename }}</button>
            </div>
            <label class="fams-selection"><input v-model="checked" type="checkbox">已亲自核实仍为原实物，部件前后资料与所选原件相符</label>
            <button class="fams-button" data-kind="primary" type="button" @click="prepare(false)">核对保存部件变更</button>
          </fieldset>
        </template>
      </template>
    </template>
  </section>
</template>
