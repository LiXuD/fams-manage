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
  links: { transfer_id: string; decision_version: number }[]; repairLinks?: { repair_id: string; decision_version: number }[] }
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
const confirmation = ref<{ path: string; body: unknown; label: string } | null>(null);
const repairFault = ref(""), repairImpact = ref("HIGH"), repairDiscovery = ref(""), repairId = ref("");
const physicalChecked = ref(false), discoveryChecked = ref(false);
const linkedRepair = ref<{ id: string; assetId: string; version: number; status: string } | null>(null);
const repairMethod = computed(() => ["FORMAL_REPAIR", "LINK_REPAIR", "REFRESH_REPAIR"].includes(method.value));
const locked = computed(() => busy.value || !!pending.value || !!confirmation.value);
const visible = computed(() => subjects.value.filter(row => row.item_id === props.item.item.id));
const selected = computed(() => visible.value.filter(row => chosen.value.includes(`${row.observation_id}:${row.dimension}`)));
const dimensions: Record<string, string> = { NORMAL: "正常", LOCATION: "位置不符", USAGE_MODE: "使用方式不符", RESPONSIBILITY: "责任不符", CONDITION: "状况待核实", UNKNOWN: "未知事实", PERIOD_MOVEMENT: "期间流转", CONFLICT: "观察冲突", OLD_ASSIGNMENT: "旧分配证据", TIME: "时间异常", FINDING: "任务外线索", LATE: "迟到线索" };
const statuses: Record<string, string> = { OPEN: "待处理", PROCESSING: "处理中", CLOSED: "已关闭", TRANSFERRED: "已转后续业务" };
const methods: Record<string, string> = { BOOK_CORRECT: "有明确证据确认账面正确", CONTINUE_SEARCH: "继续查找与核实", FORMAL_TRANSFER: "转正式调拨", REFRESH_TRANSFER: "核对关联调拨进度", FORMAL_REPAIR: "转正式维修", LINK_REPAIR: "关联已有维修单", REFRESH_REPAIR: "核对关联维修进度" };
let epoch = 0, live = true;
const valid = (token: number, who: string) => live && token === epoch && who === identity.value;
function clear() { board.value = null; subjects.value = []; chosen.value = []; pending.value = null;
  organizations.value = []; warehouses.value = []; locations.value = []; employees.value = [];
  confirmation.value = null; linkedRepair.value = null; repairFault.value = ""; repairDiscovery.value = ""; repairId.value = ""; physicalChecked.value = false; discoveryChecked.value = false; reason.value = ""; evidence.value = ""; owning.value = ""; using.value = ""; employee.value = ""; warehouse.value = ""; location.value = ""; busy.value = false; }
function failure(cause: unknown) {
  if (cause instanceof ReviewApiError) {
    if ([401,403,404].includes(cause.status)) { clear(); emit("invalid"); error.value = "当前身份、权限或记录已失效，原资料已隐藏。"; return; }
    if (cause.fields.processedBy) { error.value = `该差异已由 ${cause.fields.processedBy} 于 ${new Date(cause.fields.processedAt).toLocaleString()} 处理，请刷新核对当前方式和结果。`; return; }
    if (cause.status === 409) { error.value = "任务、差异版本或关联业务已变化。请刷新并核对；未完成正式业务不能直接关闭差异。"; return; }
    if (cause.status === 400) { error.value = "请选择同一资产的适用差异，核对原因、明确证据、调拨目标或维修事实。"; return; }
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
  pending.value ??= { path, key: crypto.randomUUID(), version: board.value.task.version, body: JSON.parse(JSON.stringify(body)) as unknown };
  const original = pending.value, token = ++epoch, who = identity.value; busy.value = true; error.value = "";
  try {
    await reviewRequest(original.path, original);
    if (!valid(token, who)) return;
    pending.value = null; notice.value = "本次处理已保存。独立差异仍须逐项闭环，关联调拨或维修以实际业务进度为准。"; await load();
  } catch (cause) {
    if (!valid(token, who)) return;
    if (cause instanceof ReviewApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    failure(cause);
  } finally { if (valid(token, who)) busy.value = false; }
}
function prepare() {
  if (locked.value || !props.observationIds.length) return;
  confirmation.value = { path: `/inventory-reviews/tasks/${props.taskId}/differences/preparation`, body: { observationIds: [...props.observationIds] }, label: "将当前页原始证据中的差异列入独立复核；本次不采信现场事实。" };
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
  let repair: unknown;
  if (repairMethod.value) {
    if (selected.value.length !== 1 || selected.value[0].dimension !== "CONDITION") { error.value = "维修只处理一条损坏维度，其他差异须分别核实。"; return; }
    if (method.value !== "REFRESH_REPAIR") {
      const discovered = new Date(repairDiscovery.value);
      if (!physicalChecked.value || !discoveryChecked.value || !repairDiscovery.value || !Number.isFinite(discovered.getTime()) || discovered.getTime() > Date.now()) { error.value = "请独立核实实际损坏及发现时间，再勾选确认。"; return; }
      if (method.value === "FORMAL_REPAIR" && repairFault.value.trim().length < 3) { error.value = "请填写实际故障。"; return; }
      if (method.value === "LINK_REPAIR" && (!linkedRepair.value || linkedRepair.value.id !== repairId.value.trim() || linkedRepair.value.assetId !== currentItem.value.item.asset_id)) { error.value = "请先核对同一资产的当前正式维修单。"; return; }
      repair = { verifiedDiscoveryAt: discovered.toISOString(), physicalDamageChecked: true, discoveryTimeVerified: true,
        ...(method.value === "FORMAL_REPAIR" ? { fault: repairFault.value.trim(), impact: repairImpact.value } : { repairId: linkedRepair.value!.id, repairVersion: linkedRepair.value!.version }) };
    }
  }
  confirmation.value = { path: `/inventory-reviews/tasks/${props.taskId}/differences/decisions`, body: { subjects: selected.value.map(row => ({ observationId: row.observation_id, dimension: row.dimension, version: row.version })), assetVersion: currentItem.value.current.version, method: method.value, reason: reason.value.trim(), evidence: evidence.value.trim(), ...(transferTarget ? { transferTarget } : {}), ...(repair ? { repair } : {}) }, label: `${methods[method.value]}：共同提交 ${selected.value.length} 个差异；任一版本变化均不保存。` };
}
function confirmPrepared() {
  const prepared = confirmation.value;
  if (!prepared || busy.value) return;
  confirmation.value = null;
  void send(prepared.path, prepared.body);
}
async function inspectRepair() {
  if (locked.value || !/^[1-9][0-9]*$/.test(repairId.value.trim())) return;
  const token = ++epoch, who = identity.value, id = repairId.value.trim(); busy.value = true; linkedRepair.value = null;
  try {
    const row = await reviewRequest<{ id: string; assetId: string; version: number; status: string }>(`/repairs/${id}`);
    if (valid(token, who) && repairId.value.trim() === id) {
      if (row.assetId !== currentItem.value?.item.asset_id || !["PENDING_ADMIN_DECISION", "WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING"].includes(row.status)) error.value = "请选择同一资产的有效维修单。";
      else linkedRepair.value = row;
    }
  } catch (cause) { if (valid(token, who)) failure(cause); }
  finally { if (valid(token, who)) busy.value = false; }
}
async function pages(delta: number) { if (locked.value || page.value + delta < 1 || (page.value + delta - 1) * 100 >= total.value) return; page.value += delta; await load(); }
watch(identity, () => { epoch++; clear(); page.value = 1; notice.value = ""; error.value = ""; if (session.user?.roles.includes("ASSET_ADMIN")) void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; epoch++; clear(); });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="独立差异处理">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">独立差异处理<span v-if="board"> · {{ item.item.snapshot_json.assetCode }}</span></h3>
        <p class="fams-panel-subtitle">先将原始证据列入复核，再逐项记录明确依据。位置、使用方式和责任变更沿用正式调拨；冲突、时间、盘盈及损坏仍需对应事实或后续业务。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked" @click="load">刷新差异与当前资产</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || !observationIds.length" @click="prepare">列入当前页证据的差异</button>
      </div>
    </div>

    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>

    <div v-if="confirmation" class="fams-notice" role="group" aria-label="确认差异操作">
      <p>{{ confirmation.label }}</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" data-size="sm" type="button" :disabled="busy" @click="confirmPrepared">确认保存本次操作</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="confirmation = null">取消本次操作</button>
      </div>
    </div>
    <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="send(pending.path,pending.body)">重试原差异操作</button>

    <div class="fams-table-scroll">
      <table class="fams-table">
        <thead><tr><th scope="col">选择</th><th scope="col" data-column-kind="identity">原始证据与维度</th>
          <th scope="col">当前处理</th><th scope="col">决定及正式关联</th></tr></thead>
        <tbody>
          <tr v-for="row in visible" :key="`${row.observation_id}:${row.dimension}`">
            <td><label class="fams-selection"><input v-model="chosen" type="checkbox" :value="`${row.observation_id}:${row.dimension}`"
              :disabled="locked || ['CLOSED','TRANSFERRED'].includes(row.subject_status)" :aria-label="`选择证据 ${row.observation_id} 的${dimensions[row.dimension] ?? row.dimension}`" /></label></td>
            <th scope="row" data-column-kind="identity">证据 {{ row.observation_id }} · {{ dimensions[row.dimension] ?? row.dimension }}</th>
            <td>{{ statuses[row.subject_status] }} · 第 {{ row.version }} 版<p v-if="row.method">{{ methods[row.method] }}</p><p v-if="row.reviewer_username">{{ row.reviewer_username }} · {{ new Date(row.reviewed_at!).toLocaleString() }}</p><p>{{ row.reason }}</p><p>依据：{{ row.evidence || '尚未提交' }}</p></td>
            <td><p v-for="decision in row.decisions" :key="decision.after_version">第 {{ decision.after_version }} 版：{{ methods[decision.method] }} · {{ statuses[decision.subject_status] }}；{{ decision.actor_username }}；{{ decision.reason }}；{{ decision.evidence }}</p><p v-for="link in row.links" :key="link.decision_version"><router-link :to="{path:'/transfers',query:{id:link.transfer_id}}">查看关联调拨 {{ link.transfer_id }}</router-link></p><p v-for="link in row.repairLinks" :key="`repair:${link.decision_version}`"><router-link :to="{path:'/repairs/' + link.repair_id}">查看关联维修 {{ link.repair_id }}</router-link></p></td>
          </tr>
          <tr v-if="!visible.length" class="fams-table-empty"><td colspan="4"><strong>本页暂无此资产的已列入差异</strong>原始证据不会因当前列表为空而被采信。</td></tr>
        </tbody>
      </table>
    </div>

    <div class="fams-pagination">
      <div class="fams-pagination-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page === 1" @click="pages(-1)">上页差异</button>
        <span class="fams-pagination-page">第 {{ page }} 页</span>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page * 100 >= total" @click="pages(1)">下页差异</button>
      </div>
    </div>

    <fieldset class="fams-card fams-stack" :disabled="locked">
      <label class="fams-field"><span class="fams-field-label">处理方式</span>
        <select class="fams-control" v-model="method" @change="targetChoices"><option v-for="(label,value) in methods" :key="value" :value="value">{{ label }}</option></select></label>
      <template v-if="method === 'FORMAL_TRANSFER'">
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">所属组织</span>
            <select class="fams-control" v-model="owning"><option value="">未指定</option><option v-for="row in organizations" :key="row.id" :value="row.id">{{ row.name }}</option></select></label>
          <label v-if="stock" class="fams-field"><span class="fams-field-label">目标仓库</span>
            <select class="fams-control" v-model="warehouse" @change="location = ''"><option value="">请选择</option><option v-for="row in warehouses" :key="row.id" :value="row.id">{{ row.name }}</option></select></label>
          <template v-else>
            <label class="fams-field"><span class="fams-field-label">使用方式</span>
              <select class="fams-control" v-model="custody"><option value="PERSONAL">个人使用</option><option value="SHARED">部门公用</option></select></label>
            <label class="fams-field"><span class="fams-field-label">目标部门</span>
              <select class="fams-control" v-model="using" @change="people"><option value="">请选择</option><option v-for="row in organizations" :key="row.id" :value="row.id">{{ row.name }}</option></select></label>
            <label class="fams-field"><span class="fams-field-label">目标责任人</span>
              <select class="fams-control" v-model="employee"><option value="">请选择</option><option v-for="row in employees" :key="row.id" :value="row.id">{{ row.name }}</option></select></label>
          </template>
          <label class="fams-field"><span class="fams-field-label">目标位置</span>
            <select class="fams-control" v-model="location"><option value="">请选择</option><option v-for="row in bins" :key="row.id" :value="row.id">{{ row.name }}</option></select></label>
        </div>
        <p class="fams-panel-subtitle">保存后进入正式审批，库管实际执行及目标人接收仍按原流程处理。</p>
      </template>
      <template v-if="repairMethod">
        <p class="fams-panel-subtitle">维修只处理损坏维度。创建或关联后仍须正式审批、库管处理及实际完成；原始照片与时间保留，其他维度分别核实。</p>
        <template v-if="method !== 'REFRESH_REPAIR'">
          <label class="fams-field"><span class="fams-field-label">实际发现损坏时间（本机时区）</span>
            <input class="fams-control" v-model="repairDiscovery" type="datetime-local" step="0.001" /></label>
          <label class="fams-selection"><input v-model="physicalChecked" type="checkbox" />已通过实物与原始证据核实损坏</label>
          <label class="fams-selection"><input v-model="discoveryChecked" type="checkbox" />已独立核实上述发现时间，未直接采信异常设备时间</label>
          <div v-if="method === 'FORMAL_REPAIR'" class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">实际故障</span>
              <textarea class="fams-control" v-model="repairFault" minlength="3" maxlength="2000" /></label>
            <label class="fams-field"><span class="fams-field-label">影响程度</span>
              <select class="fams-control" v-model="repairImpact"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option></select></label>
          </div>
          <template v-else>
            <div class="fams-field-row">
              <label class="fams-field"><span class="fams-field-label">已有正式维修单编号</span>
                <input class="fams-control" v-model="repairId" inputmode="numeric" @input="linkedRepair = null" /></label>
              <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="inspectRepair">核对维修单</button>
            </div>
            <p v-if="linkedRepair" class="fams-panel-subtitle">已核对维修 {{ linkedRepair.id }} · 第 {{ linkedRepair.version }} 版 · {{ linkedRepair.status }}</p>
          </template>
        </template>
      </template>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">处理原因</span>
          <textarea class="fams-control" v-model="reason" minlength="3" maxlength="1000" /></label>
        <label class="fams-field"><span class="fams-field-label">明确采信依据</span>
          <textarea class="fams-control" v-model="evidence" minlength="10" maxlength="2000" placeholder="填写可追溯的实物核验、签字记录或其他明确证据" /></label>
      </div>
      <button class="fams-button" data-kind="primary" type="button" :disabled="!selected.length || reason.trim().length < 3 || evidence.trim().length < 10" @click="decide">共同提交所选差异</button>
    </fieldset>
  </section>
</template>
