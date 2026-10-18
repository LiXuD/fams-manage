<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import type { LoanMutation } from "@/loan/api";
import { reviewRequest, ReviewApiError, type Board, type ReviewItem } from "@/inventory-review/api";
import type { TransferChoice, TransferTarget } from "../../../../packages/api-types/src/transfer";
const props = defineProps<{ taskId: string; item: ReviewItem; itemPage: number; observationIds: string[] }>();
const emit = defineEmits<{ invalid: [] }>();
interface Subject { observation_id: string; item_id: string | null; dimension: string; version: number;
  subject_status: string; method: string | null; reason: string | null; evidence: string | null;
  reviewer_username: string | null; reviewed_at: string | null;
  decisions: { after_version: number; method: string; subject_status: string; actor_username: string; reason: string; evidence: string }[];
  links: { transfer_id: string; decision_version: number }[] }
const session = useSessionStore();
const owner = computed(() => JSON.stringify([session.user?.id, session.csrfToken, session.user?.roles]));
const identity = computed(() => `${props.taskId}:${props.item.item.id}:${owner.value}`);
const board = ref<Board | null>(null), subjects = ref<Subject[]>([]), chosen = ref<string[]>([]);
const page = ref(1), total = ref(0), busy = ref(false), error = ref(""), notice = ref("");
const method = ref("BOOK_CORRECT"), reason = ref(""), evidence = ref(""), pending = ref<LoanMutation | null>(null);
const organizations = ref<TransferChoice[]>([]), warehouses = ref<TransferChoice[]>([]), locations = ref<TransferChoice[]>([]), employees = ref<TransferChoice[]>([]);
const owning = ref(""), using = ref(""), employee = ref(""), warehouse = ref(""), location = ref(""), custody = ref("PERSONAL");
const currentItem = computed(() => board.value?.items.find(row => row.item.id === props.item.item.id));
const stock = computed(() => currentItem.value?.current?.custodyType === "STORAGE");
const bins = computed(() => locations.value.filter(row => stock.value ? row.locationType === "BIN" && row.warehouseId === warehouse.value : row.locationType !== "BIN"));
const locked = computed(() => busy.value || !!pending.value);
const visible = computed(() => subjects.value.filter(row => row.item_id === props.item.item.id));
const selected = computed(() => visible.value.filter(row => chosen.value.includes(`${row.observation_id}:${row.dimension}`)));
const dimensions: Record<string, string> = { NORMAL: "正常", LOCATION: "位置不符", USAGE_MODE: "使用方式不符", RESPONSIBILITY: "责任不符", CONDITION: "状况待核实", UNKNOWN: "未知事实", PERIOD_MOVEMENT: "期间流转", CONFLICT: "观察冲突", OLD_ASSIGNMENT: "旧分配证据", TIME: "时间异常", FINDING: "任务外线索", LATE: "迟到线索" };
const statuses: Record<string, string> = { OPEN: "待处理", PROCESSING: "处理中", CLOSED: "已关闭", TRANSFERRED: "已转后续业务" };
const methods: Record<string, string> = { BOOK_CORRECT: "有明确证据确认账面正确", CONTINUE_SEARCH: "继续查找与核实", FORMAL_TRANSFER: "转正式调拨", REFRESH_TRANSFER: "核对关联调拨进度" };
let epoch = 0, live = true;
const valid = (token: number, who: string) => live && token === epoch && who === identity.value;
function clear() { board.value = null; subjects.value = []; chosen.value = []; pending.value = null;
  organizations.value = []; warehouses.value = []; locations.value = []; employees.value = [];
  reason.value = ""; evidence.value = ""; owning.value = ""; using.value = ""; employee.value = ""; warehouse.value = ""; location.value = ""; busy.value = false; }
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError) {
    if ([401,403,404].includes(cause.status)) { clear(); emit("invalid"); error.value = "当前身份、权限或记录已失效，原资料已隐藏。"; return; }
    if (cause.fields.processedBy) { error.value = `该差异已由 ${cause.fields.processedBy} 于 ${new Date(cause.fields.processedAt).toLocaleString()} 处理，请刷新核对当前方式和结果。`; return; }
    if (cause.status === 409) { error.value = "任务、差异版本或关联业务已变化。请刷新并核对；未完成调拨不能直接关闭差异。"; return; }
    if (cause.status === 400) { error.value = "请选择同一资产的适用差异，核对原因、明确证据和调拨目标。"; return; }
  }
  error.value = pending.value ? "操作结果尚未确认，请保留原操作并重试。" : "读取失败，请重新刷新。";
}
async function load() {
  const token = ++epoch, who = identity.value; busy.value = true; error.value = "";
  try {
    const [task, data] = await Promise.all([
      reviewRequest<Board>(`/inventory-reviews/tasks/${props.taskId}?page=${props.itemPage}&pageSize=20`),
      reviewRequest<{ items: Subject[]; total: number }>(`/inventory-reviews/tasks/${props.taskId}/differences?page=${page.value}&pageSize=100`)]);
    if (valid(token, who)) { board.value = task; subjects.value = data.items; total.value = data.total; chosen.value = []; }
  } catch (cause) { if (valid(token, who)) failure(cause); }
  finally { if (valid(token, who)) busy.value = false; }
}
async function send(path: string, body: unknown) {
  if (busy.value || !board.value) return;
  pending.value ??= { path, key: crypto.randomUUID(), version: board.value.task.version, body: structuredClone(body) };
  const original = pending.value, token = ++epoch, who = identity.value; busy.value = true; error.value = "";
  try {
    await reviewRequest(original.path, original);
    if (!valid(token, who)) return;
    pending.value = null; notice.value = "本次处理已保存。独立差异仍须逐项闭环，关联调拨以实际业务进度为准。"; await load();
  } catch (cause) {
    if (!valid(token, who)) return;
    if (cause instanceof ReviewApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    failure(cause);
  } finally { if (valid(token, who)) busy.value = false; }
}
function prepare() {
  if (locked.value || !props.observationIds.length || !window.confirm("将当前页原始证据中的差异列入独立复核？此操作不采信现场事实。")) return;
  void send(`/inventory-reviews/tasks/${props.taskId}/differences/preparation`, { observationIds: [...props.observationIds] });
}
async function catalog(kind: string, organization?: string) {
  const rows: TransferChoice[] = []; let number = 1, part: TransferChoice[];
  do { part = (await reviewRequest<{ items: TransferChoice[] }>(`/transfers/catalog/${kind}?page=${number++}&pageSize=200${organization ? `&organizationId=${organization}` : ""}`)).items; rows.push(...part); } while (part.length === 200);
  return rows;
}
async function targetChoices() {
  if (locked.value || method.value !== "FORMAL_TRANSFER") return;
  const token = ++epoch, who = identity.value; busy.value = true;
  try {
    const [orgs, stores, places] = await Promise.all([catalog("organization"), catalog("warehouse"), catalog("location")]);
    if (valid(token, who)) { organizations.value = orgs; warehouses.value = stores; locations.value = places;
      owning.value = (currentItem.value?.current as { owningOrganizationId?: string | null } | null)?.owningOrganizationId ?? ""; }
  } catch (cause) { if (valid(token, who)) failure(cause); }
  finally { if (valid(token, who)) busy.value = false; }
}
async function people() {
  employee.value = ""; employees.value = [];
  if (!using.value || locked.value) return;
  const token = ++epoch, who = identity.value, org = using.value; busy.value = true;
  try { const rows = await catalog("employee", org); if (valid(token, who) && using.value === org) employees.value = rows; }
  catch (cause) { if (valid(token, who)) failure(cause); }
  finally { if (valid(token, who)) busy.value = false; }
}
function target(): TransferTarget | null {
  const loc = bins.value.find(row => row.id === location.value), owner = organizations.value.find(row => row.id === owning.value);
  const wh = warehouses.value.find(row => row.id === warehouse.value), org = organizations.value.find(row => row.id === using.value), person = employees.value.find(row => row.id === employee.value);
  if (!loc || stock.value && !wh || !stock.value && (!org || !person || custody.value === "SHARED" && !person.sharedEligible)) return null;
  const versions: Record<string, number> = { location: loc.version };
  if (owner) versions.owningOrganization = owner.version;
  if (stock.value) versions.warehouse = wh!.version;
  else { versions.usingOrganization = org!.version; versions.employee = person!.version; }
  return { owningOrganizationId: owner?.id ?? null, custodyType: stock.value ? "STORAGE" : custody.value,
    usingOrganizationId: stock.value ? null : org!.id, employeeId: stock.value ? null : person!.id,
    warehouseId: stock.value ? wh!.id : null, locationId: loc.id, referenceVersions: versions };
}
function decide() {
  if (locked.value || !selected.value.length || !currentItem.value?.current || reason.value.trim().length < 3 || evidence.value.trim().length < 10) return;
  const transferTarget = method.value === "FORMAL_TRANSFER" ? target() : undefined;
  if (method.value === "FORMAL_TRANSFER" && !transferTarget) { error.value = "请核对有效目标位置、部门、责任人及仓库。"; return; }
  if (!window.confirm(`确认${methods[method.value]}？本次 ${selected.value.length} 个差异将共同提交，任一版本变化均不保存。`)) return;
  void send(`/inventory-reviews/tasks/${props.taskId}/differences/decisions`, { subjects: selected.value.map(row => ({ observationId: row.observation_id, dimension: row.dimension, version: row.version })), assetVersion: currentItem.value.current.version, method: method.value, reason: reason.value.trim(), evidence: evidence.value.trim(), ...(transferTarget ? { transferTarget } : {}) });
}
async function pages(delta: number) { if (locked.value || page.value + delta < 1 || (page.value + delta - 1) * 100 >= total.value) return; page.value += delta; await load(); }
watch(identity, () => { epoch++; clear(); page.value = 1; notice.value = ""; error.value = ""; if (session.user?.roles.includes("ASSET_ADMIN")) void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; epoch++; clear(); });
</script>
<template>
  <section class="panel" aria-label="独立差异处理"><h3>独立差异处理<span v-if="board"> · {{ item.item.snapshot_json.assetCode }}</span></h3>
    <p>先将原始证据列入复核，再逐项记录明确依据。位置、使用方式和责任变更沿用正式调拨；冲突、时间、盘盈及损坏仍需对应事实或后续业务。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <button :disabled="locked" @click="load">刷新差异与当前资产</button><button :disabled="locked || !observationIds.length" @click="prepare">列入当前页证据的差异</button>
    <button v-if="pending" :disabled="busy" @click="send(pending.path,pending.body)">重试原差异操作</button>
    <table><thead><tr><th>选择</th><th>原始证据与维度</th><th>当前处理</th><th>决定及正式关联</th></tr></thead><tbody><tr v-for="row in visible" :key="`${row.observation_id}:${row.dimension}`">
      <td><input v-model="chosen" type="checkbox" :value="`${row.observation_id}:${row.dimension}`" :disabled="locked || ['CLOSED','TRANSFERRED'].includes(row.subject_status)" :aria-label="`选择证据 ${row.observation_id} 的${dimensions[row.dimension] ?? row.dimension}`"></td>
      <td>证据 {{ row.observation_id }} · {{ dimensions[row.dimension] ?? row.dimension }}</td>
      <td>{{ statuses[row.subject_status] }} · 第 {{ row.version }} 版<p v-if="row.method">{{ methods[row.method] }}</p><p v-if="row.reviewer_username">{{ row.reviewer_username }} · {{ new Date(row.reviewed_at!).toLocaleString() }}</p><p>{{ row.reason }}</p><p>依据：{{ row.evidence || '尚未提交' }}</p></td>
      <td><p v-for="decision in row.decisions" :key="decision.after_version">第 {{ decision.after_version }} 版：{{ methods[decision.method] }} · {{ statuses[decision.subject_status] }}；{{ decision.actor_username }}；{{ decision.reason }}；{{ decision.evidence }}</p><p v-for="link in row.links" :key="link.decision_version"><router-link :to="{path:'/transfers',query:{id:link.transfer_id}}">查看关联调拨 {{ link.transfer_id }}</router-link></p></td>
    </tr></tbody></table>
    <p v-if="!visible.length">本页暂无此资产的已列入差异；原始证据不会因当前列表为空而被采信。</p><button :disabled="locked || page === 1" @click="pages(-1)">上页差异</button>第 {{ page }} 页<button :disabled="locked || page * 100 >= total" @click="pages(1)">下页差异</button>
    <fieldset :disabled="locked"><label>处理方式<select v-model="method" @change="targetChoices"><option v-for="(label,value) in methods" :key="value" :value="value">{{ label }}</option></select></label>
      <template v-if="method === 'FORMAL_TRANSFER'"><label>所属组织<select v-model="owning"><option value="">未指定</option><option v-for="row in organizations" :key="row.id" :value="row.id">{{ row.name }}</option></select></label>
        <template v-if="stock"><label>目标仓库<select v-model="warehouse" @change="location = ''"><option value="">请选择</option><option v-for="row in warehouses" :key="row.id" :value="row.id">{{ row.name }}</option></select></label></template>
        <template v-else><label>使用方式<select v-model="custody"><option value="PERSONAL">个人使用</option><option value="SHARED">部门公用</option></select></label><label>目标部门<select v-model="using" @change="people"><option value="">请选择</option><option v-for="row in organizations" :key="row.id" :value="row.id">{{ row.name }}</option></select></label><label>目标责任人<select v-model="employee"><option value="">请选择</option><option v-for="row in employees" :key="row.id" :value="row.id">{{ row.name }}</option></select></label></template>
        <label>目标位置<select v-model="location"><option value="">请选择</option><option v-for="row in bins" :key="row.id" :value="row.id">{{ row.name }}</option></select></label><p>保存后进入正式审批，库管实际执行及目标人接收仍按原流程处理。</p>
      </template>
      <label>处理原因<textarea v-model="reason" minlength="3" maxlength="1000" /></label><label>明确采信依据<textarea v-model="evidence" minlength="10" maxlength="2000" placeholder="填写可追溯的实物核验、签字记录或其他明确证据" /></label><button :disabled="!selected.length || reason.trim().length < 3 || evidence.trim().length < 10" @click="decide">共同提交所选差异</button>
    </fieldset>
  </section>
</template>
<style scoped>
table { width: 100%; border-collapse: collapse; } th,td { padding: 12px; border-bottom: 1px solid #e4e7ed; text-align: left; vertical-align: top; } label { display: block; margin: 8px 0; } textarea { display: block; width: 100%; min-height: 64px; } button { margin: 8px; padding: 8px 12px; }
</style>
