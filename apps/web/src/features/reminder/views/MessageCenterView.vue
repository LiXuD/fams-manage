<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import type { RouteLocationRaw } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { AuthApiError } from "@/auth/api";
import { BusinessApiError, businessMessage, businessRequest } from "@/api/business";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness, type LivenessToken } from "@/composables/useRequestLiveness";
import { formatProxyTime } from "@/features/exception/time";
import { dutyRequest, ReminderApiError, reminderMessage,
  messageBusinessLabels, messageDestinations, messageRepairSegmentTypes, messageStageLabels,
  messageTierLabels, messageTodoLabels,
  type DutyAssignment, type DutyList, type ReminderMessage, type ReminderPage } from "@/features/reminder/api";

const PAGE_SIZE = 20;
const ACCOUNT_NUMBER = /^[1-9][0-9]*$/;
const DUTY_ACCOUNT_REQUIRED_MESSAGE = "请填写账号的数字编号。";
const EMPTY_INBOX_TEXT = "暂无站内消息。";
const EMPTY_EXCEPTION_TEXT = "当前没有需要跟进的超时异常。";

type MessageView = "INBOX" | "EXCEPTION";
type DutyRole = "ASSET_ADMIN" | "WAREHOUSE_OPERATOR";
/** 已读登记的在途意图：只带路径、版本与行号，幂等键归提交底座。 */
type ReadIntent = { id: string; path: string; version: number };
/** 值班登记的在途意图：role 决定 PUT 路径，version 取当前链版本。 */
type DutyIntent = { role: DutyRole; version: number; body: { primaryAccountId: string; backupAccountId: string | null } };
interface MessageRow extends ReminderMessage { rowKey: string; titleText: string; metaText: string;
  timeText: string; to?: RouteLocationRaw }
interface DutyRow extends DutyAssignment { poolText: string; peopleText: string; effectiveText: string }

const session = useSessionStore();
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const privileged = computed(() => session.user?.roles.some(
  role => ["ASSET_ADMIN", "WAREHOUSE_OPERATOR"].includes(role)) ?? false);
const administrator = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ?? false);
const canSeeDuty = computed(() => session.user?.roles.some(
  role => ["SYSTEM_ADMIN", "ASSET_ADMIN", "WAREHOUSE_OPERATOR"].includes(role)) ?? false);

const view = shallowRef<MessageView>("INBOX");
const rows = ref<ReminderMessage[]>([]);
const duties = ref<DutyAssignment[]>([]);
const page = shallowRef(1), total = shallowRef(0), unread = shallowRef(0);
const busy = shallowRef(false), error = shallowRef(""), dutyError = shallowRef("");
const contact = ref<{ role: DutyRole; primary: string; backup: string }>(
  { role: "ASSET_ADMIN", primary: "", backup: "" });
const liveness = useRequestLiveness(() => [identity.value]);
const dutyLiveness = useRequestLiveness(() => [identity.value]);
/** 消息侧与值班侧的确定性失败判据各自取自本域错误类，4xx 才允许清键重新起草。 */
const deterministic = (cause: unknown): boolean => (cause instanceof BusinessApiError || cause instanceof AuthApiError)
  && cause.status >= 400 && cause.status < 500;
const dutyDeterministic = (cause: unknown): boolean => cause instanceof ReminderApiError && cause.status < 500;
/** 确认式幂等提交：已读与值班两条在途意图各自持键，重试复用原键；busy、路径、请求体与文案留在本视图。 */
const { pending: pendingRead, prepare: draftRead, confirm: adoptRead, settle: settleRead, fail: failRead, reset: resetRead } =
  useConfirmedSubmit<ReadIntent>({ failure: deterministic });
const { pending: pendingDuty, prepare: draftDuty, confirm: adoptDuty, settle: settleDuty, fail: failDuty, reset: resetDuty } =
  useConfirmedSubmit<DutyIntent>({ failure: dutyDeterministic });

function titleOf(message: ReminderMessage): string {
  if (message.kind === "TODO") return `待办 · ${messageTodoLabels[message.stage] ?? message.stage}`;
  return `提醒 · ${messageStageLabels[message.objectStage] ?? message.objectStage} ${messageTierLabels[message.stage] ?? message.stage}`;
}
/** 维修及其派生单据落报修详情，其余业务类型落各自列表路由并带 id 查询串；无映射则不给链接。 */
function destinationOf(message: ReminderMessage): RouteLocationRaw | undefined {
  if (messageRepairSegmentTypes.has(message.businessType)) return { path: `/repairs/${message.businessId}` };
  const base = messageDestinations[message.businessType];
  return base === undefined ? undefined : { path: base, query: { id: message.businessId } };
}

const messageRows = computed<MessageRow[]>(() => rows.value.map((message) => ({
  ...message,
  rowKey: message.kind + message.id,
  titleText: `${titleOf(message)} · ${message.read ? "已读" : "未读"}`,
  metaText: `${messageBusinessLabels[message.businessType] ?? message.businessType} ${message.businessId}`
    + ` · ${message.poolType}`
    + (message.assigneeAccountId ? ` · 处理账号 ${message.assigneeAccountId}` : ""),
  timeText: `登记 ${formatProxyTime(message.openedAt)}`
    + (message.deadlineAt ? ` · 截止 ${formatProxyTime(message.deadlineAt)}` : ""),
  to: destinationOf(message),
})));
const dutyRows = computed<DutyRow[]>(() => duties.value.map((duty) => ({
  ...duty,
  poolText: duty.roleCode === "ASSET_ADMIN" ? "管理员共享池" : "库管共享池",
  peopleText: `值班 ${duty.primary.name}${duty.primary.active ? "" : "（已失效）"} · 替补 `
    + (duty.backup ? `${duty.backup.name}${duty.backup.active ? "" : "（已失效）"}` : "未配置"),
  effectiveText: `生效 ${formatProxyTime(duty.effectiveAt)} · 版本 ${duty.version}`,
})));

const unreadText = computed(() => `未读 ${unread.value} 条`);
const showEmpty = computed(() => rows.value.length === 0);
const emptyText = computed(() => view.value === "EXCEPTION" ? EMPTY_EXCEPTION_TEXT : EMPTY_INBOX_TEXT);
const listLabel = computed(() => view.value === "EXCEPTION" ? "异常清单" : "我的消息");
const inboxDisabled = computed(() => busy.value || view.value === "INBOX");
const exceptionDisabled = computed(() => busy.value || view.value === "EXCEPTION");
const refreshDisabled = computed(() => busy.value);
const retryReadDisabled = computed(() => busy.value);
const readDisabled = computed(() => busy.value || pendingRead.value !== null);
const prevDisabled = computed(() => busy.value || page.value <= 1);
const nextDisabled = computed(() => busy.value || page.value * PAGE_SIZE >= total.value);
const dutyFormDisabled = computed(() => pendingDuty.value !== null);
const dutySubmitLabel = computed(() => pendingDuty.value ? "原请求重试值班登记" : "登记值班人");
const dutySubmitDisabled = computed(() => busy.value);

function clear() {
  liveness.invalidate(); dutyLiveness.invalidate();
  rows.value = []; resetRead();
  duties.value = []; resetDuty();
  page.value = 1; total.value = 0; unread.value = 0; busy.value = false;
}
/** 身份、权限或记录失效时整页作废； mutation 失败只在确定性（4xx）时清掉已读在途意图。 */
function failure(cause: unknown, mutation = false) {
  if (cause instanceof BusinessApiError || cause instanceof AuthApiError) {
    if ([401, 403, 404].includes(cause.status)) clear();
    else if (mutation && cause.status >= 400 && cause.status < 500) failRead(cause);
  }
  error.value = businessMessage(cause);
}
async function load(turn: LivenessToken) {
  const result = await businessRequest<ReminderPage>(
    `/messages?view=${view.value}&page=${page.value}&pageSize=${PAGE_SIZE}`);
  if (liveness.isCurrent(turn)) { rows.value = result.items; total.value = result.total; unread.value = result.unread; }
}
async function refresh(delta = 0) {
  if (busy.value || page.value + delta < 1 || delta && pendingRead.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; page.value += delta;
  try { await load(turn); } catch (cause) { if (liveness.isCurrent(turn)) failure(cause); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function switchTo(next: MessageView) {
  if (busy.value || next === view.value || pendingRead.value) return;
  view.value = next; await refresh();
}
async function reload() { await refresh(); await loadDuty(); }
async function read(id: string) {
  if (busy.value || pendingRead.value && pendingRead.value.payload.id !== id) return;
  let operation = pendingRead.value;
  if (!operation) {
    const kind = rows.value.find(message => message.id === id)?.kind ?? "TODO";
    draftRead(() => ({ id, path: `/messages/${kind}/${id}/read`, version: 0 }));
    operation = adoptRead();
  }
  if (!operation) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    await businessRequest(operation.payload.path, { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settleRead(); await load(turn);
  } catch (cause) { if (liveness.isCurrent(turn)) failure(cause, true); }
  finally { if (liveness.isCurrent(turn)) busy.value = false; }
}
async function loadDuty() {
  if (!canSeeDuty.value) return;
  const turn = dutyLiveness.begin();
  try {
    const result = await dutyRequest<DutyList>("/duty-assignments");
    if (dutyLiveness.isCurrent(turn)) duties.value = result.items;
  } catch (cause) { if (dutyLiveness.isCurrent(turn)) dutyError.value = reminderMessage(cause); }
}
/** 值班登记：首次点击校验并起草（键由底座生成），响应丢失后再次提交只复用同一把键。 */
async function rotate() {
  if (!administrator.value) return;
  let intent = pendingDuty.value;
  if (!intent) {
    const role = contact.value.role;
    const primary = contact.value.primary.trim(), backup = contact.value.backup.trim();
    if (!ACCOUNT_NUMBER.test(primary) || backup !== "" && !ACCOUNT_NUMBER.test(backup)) {
      dutyError.value = DUTY_ACCOUNT_REQUIRED_MESSAGE; return;
    }
    const version = Number(duties.value.find(duty => duty.roleCode === role)?.version ?? 0);
    draftDuty(() => ({ role, version,
      body: { primaryAccountId: primary, backupAccountId: backup === "" ? null : backup } }));
    intent = adoptDuty();
  }
  if (!intent) return;
  const operation = intent;
  const turn = dutyLiveness.begin();
  dutyError.value = "";
  try {
    const saved = await dutyRequest<DutyAssignment>(`/duty-assignments/${operation.payload.role}`,
      { ...operation.payload, key: operation.key });
    if (!dutyLiveness.isCurrent(turn)) return;
    duties.value = [...duties.value.filter(duty => duty.roleCode !== operation.payload.role), saved];
    settleDuty();
  } catch (cause) {
    if (!dutyLiveness.isCurrent(turn)) return;
    failDuty(cause);
    dutyError.value = reminderMessage(cause);
  }
}
watch(identity, () => { clear(); error.value = ""; dutyError.value = "";
  if (session.user) { void refresh(); void loadDuty(); } },
  { immediate: true, flush: "sync" });
watch(privileged, allowed => { if (!allowed && view.value === "EXCEPTION") { view.value = "INBOX"; void refresh(); } });
onBeforeUnmount(clear);
</script>
<template>
  <AppChrome>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <div class="fams-panel-actions">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="inboxDisabled" @click="switchTo('INBOX')">我的消息</button>
      <button v-if="privileged" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="exceptionDisabled" @click="switchTo('EXCEPTION')">异常清单</button>
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="refreshDisabled" @click="reload()">刷新</button>
      <button v-if="pendingRead" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="retryReadDisabled" @click="read(pendingRead.payload.id)">原请求重试已读登记</button>
    </div>

    <section class="fams-panel fams-stack" :aria-label="listLabel">
      <p role="status">{{ unreadText }}</p>
      <article v-for="row in messageRows" :key="row.rowKey" class="fams-card fams-stack">
        <div class="fams-panel-head">
          <div>
            <h2 class="fams-panel-title">{{ row.titleText }}</h2>
            <p class="fams-panel-subtitle">{{ row.metaText }}</p>
          </div>
        </div>
        <p>{{ row.timeText }}</p>
        <router-link v-if="row.to" :to="row.to">查看业务单据</router-link>
        <button v-if="!row.read" class="fams-button" data-kind="secondary" type="button" :disabled="readDisabled" @click="read(row.id)">标记已读</button>
      </article>
      <p v-if="showEmpty">{{ emptyText }}</p>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">{{ page }} 页 / 共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="refresh(-1)">上一页</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="refresh(1)">下一页</button>
        </div>
      </div>
    </section>

    <section v-if="canSeeDuty" class="fams-panel fams-stack" aria-label="值班提醒">
      <div class="fams-panel-head"><h2 class="fams-panel-title">值班提醒</h2></div>
      <p>值班提醒人只接收升级通知，不取得审批权或处理权；被替换的配置保留为历史，便于追溯当时谁在值班。</p>
      <article v-for="duty in dutyRows" :key="duty.roleCode" class="fams-card fams-stack">
        <h3 class="fams-panel-title">{{ duty.poolText }}</h3>
        <p>{{ duty.peopleText }}</p>
        <p>{{ duty.effectiveText }}</p>
      </article>
      <form v-if="administrator" @submit.prevent="rotate">
        <label class="fams-field"><span class="fams-field-label">共享池</span><select class="fams-control" v-model="contact.role" :disabled="dutyFormDisabled"><option value="ASSET_ADMIN">管理员</option><option value="WAREHOUSE_OPERATOR">库管</option></select></label>
        <label class="fams-field"><span class="fams-field-label">值班提醒人账号</span><input class="fams-control" v-model="contact.primary" :disabled="dutyFormDisabled" placeholder="账号数字编号"></label>
        <label class="fams-field"><span class="fams-field-label">替补账号</span><input class="fams-control" v-model="contact.backup" :disabled="dutyFormDisabled" placeholder="留空表示不设备补"></label>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit" :disabled="dutySubmitDisabled">{{ dutySubmitLabel }}</button></div>
      </form>
      <p v-if="dutyError" class="fams-notice" data-state="error" role="alert"><strong>{{ dutyError }}</strong></p>
    </section>
  </AppChrome>
</template>
