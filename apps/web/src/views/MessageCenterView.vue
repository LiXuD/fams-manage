<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { AuthApiError } from "@/auth/api";
import { formatProxyTime } from "@/proxy/time";
import AppChrome from "@/components/AppChrome.vue";
import ConfigurationAnomalies from "@/reminder/ConfigurationAnomalies.vue";
import { LoanApiError, loanMessage, loanRequest, type LoanMutation } from "@/loan/api";
import { ReminderApiError, dutyRequest, reminderMessage,
  type DutyAssignment, type DutyList, type DutyMutation } from "@/reminder/api";

interface Message {
  kind: "TODO" | "REMINDER"; id: string; businessType: string; businessId: string; stage: string;
  objectStage: string; poolType: string; assigneeAccountId: string; openedAt: string; deadlineAt: string; read: boolean
}
interface Page { items: Message[]; total: number; unread: number; view: string }

const TIERS: Record<string, string> = { DUE_IN_3_DAYS: "提前3日", DUE_IN_1_DAY: "提前1日",
  DUE_IN_24_HOURS: "截止前24小时", DUE_TODAY: "今日到期", OVERDUE: "已逾期",
  OVERDUE_EVERY_7_DAYS: "逾期每7日跟进", UNRESOLVED_AFTER_1_WORKDAY: "1个工作日未处理",
  DUTY_UNREAD_ESCALATION: "升级值班提醒", OVERDUE_UNRESOLVED_WORKDAY: "超时1个工作日仍未处理" };
const STAGES: Record<string, string> = { ADMIN_DECISION: "管理员确认", WAREHOUSE_CLAIM: "库管领取",
  FIRST_EXECUTION: "首次执行", HANDOVER_RECEIPT: "实物接收", INVENTORY_COMPLETION: "盘点完成",
  FACTORY_RETURN: "返厂收回", LOSS_REVIEW: "报失复核", SCRAP_DISPOSITION: "报废处置" };
const TODOS: Record<string, string> = { APPROVAL: "待管理员确认", CLAIM: "待库管领取", PROCESS: "待维修处理",
  RECEIPT: "待实物接收", OPINION: "待补充技术意见", VERIFY: "待核实", SEARCH: "待查找跟进",
  HANDOVER_EXCEPTION: "交接异常待处理", RETURN_EXCEPTION: "返还异常待处理", OVERDUE: "逾期跟进",
  FACTORY_DISPATCH: "待寄出返厂", FACTORY_SCOPE_APPROVAL: "待返厂范围确认", RESTORE_REQUIRED: "待恢复保管",
  DISPOSITION: "待实际处置", LOST_FOLLOW_UP: "待遗失跟进", REASSIGNMENT_REQUIRED: "需要转派" };
const BUSINESSES: Record<string, string> = { INBOUND: "入库单", REQUISITION: "领用申请",
  REQUISITION_ALLOCATION: "领用分配", LOAN: "借用", LOAN_EXTENSION: "借用延期", ASSET_RETURN: "退还",
  TRANSFER: "调拨", TRANSFER_ITEM: "调拨明细", REQUISITION_HANDOVER: "领用交接",
  INVENTORY_TASK: "盘点任务", REPAIR: "报修单", REPAIR_FACTORY_ROUND: "返厂轮次",
  LOST_REPORT: "报失单", SCRAP: "报废申请", ITEM_TARGET_CHANGE: "目标变更" };

const destinations: Record<string, string> = { INBOUND: "/inbound", REQUISITION: "/requisitions",
  REQUISITION_ALLOCATION: "/requisitions", LOAN: "/loans", LOAN_EXTENSION: "/loans", ASSET_RETURN: "/loans",
  TRANSFER: "/transfers", TRANSFER_ITEM: "/transfers", REQUISITION_HANDOVER: "/requisitions",
  INVENTORY_TASK: "/inventory-tasks", LOST_REPORT: "/loss-reports", SCRAP: "/scrap-requests",
  ITEM_TARGET_CHANGE: "/requisition-target-changes" };
const segments = new Set(["REPAIR", "REPAIR_FACTORY_ROUND", "REPAIR_OPINION", "REPAIR_TECH", "PROXY_CONFIRMATION"]);

const session = useSessionStore();
const privileged = computed(() => session.user?.roles.some(
  role => ["ASSET_ADMIN", "WAREHOUSE_OPERATOR"].includes(role)) ?? false);
const view = ref<"INBOX" | "EXCEPTION">("INBOX");
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.csrfToken]));
const rows = ref<Message[]>([]); const page = ref(1); const total = ref(0); const unread = ref(0);
const busy = ref(false); const error = ref("");
const pendingRead = ref<(LoanMutation & { id: string }) | null>(null);
const duties = ref<DutyAssignment[]>([]); const dutyError = ref("");
const contact = ref<{ role: "ASSET_ADMIN" | "WAREHOUSE_OPERATOR"; primary: string; backup: string }>(
  { role: "ASSET_ADMIN", primary: "", backup: "" });
const pendingDuty = ref<(DutyMutation & { role: string }) | null>(null);
let generation = 0, dutyGeneration = 0, live = true;

const administrator = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ?? false);
const canSeeDuty = computed(() => session.user?.roles.some(
  role => ["SYSTEM_ADMIN", "ASSET_ADMIN", "WAREHOUSE_OPERATOR"].includes(role)) ?? false);

function label(message: Message): string {
  if (message.kind === "TODO") return `待办 · ${TODOS[message.stage] ?? message.stage}`;
  return `提醒 · ${STAGES[message.objectStage] ?? message.objectStage} ${TIERS[message.stage] ?? message.stage}`;
}
function destination(message: Message) {
  if (segments.has(message.businessType)) return { path: `/repairs/${message.businessId}` };
  const base = destinations[message.businessType];
  return base ? { path: base, query: { id: message.businessId } } : null;
}

function clear() {
  generation++; rows.value = []; pendingRead.value = null;
  duties.value = []; pendingDuty.value = null;
  page.value = 1; total.value = 0; unread.value = 0; busy.value = false;
}
function failure(cause: unknown, mutation = false) {
  if (cause instanceof LoanApiError || cause instanceof AuthApiError) {
    if ([401, 403, 404].includes(cause.status)) clear();
    else if (mutation && cause.status >= 400 && cause.status < 500) pendingRead.value = null;
  }
  error.value = loanMessage(cause);
}
async function load(epoch: number, owner: string) {
  const result = await loanRequest<Page>(`/messages?view=${view.value}&page=${page.value}&pageSize=20`);
  if (current(epoch, owner)) { rows.value = result.items; total.value = result.total; unread.value = result.unread; }
}
const current = (epoch: number, owner: string) => live && epoch === generation && owner === identity.value;
async function refresh(delta = 0) {
  if (busy.value || page.value + delta < 1 || delta && pendingRead.value) return;
  const epoch = ++generation, owner = identity.value;
  busy.value = true; error.value = ""; page.value += delta;
  try { await load(epoch, owner); } catch (cause) { if (current(epoch, owner)) failure(cause); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
async function switchTo(next: "INBOX" | "EXCEPTION") {
  if (busy.value || next === view.value || pendingRead.value) return;
  view.value = next; await refresh();
}
async function reload() { await refresh(); await loadDuty(); }
async function read(id: string) {
  if (busy.value || pendingRead.value && pendingRead.value.id !== id) return;
  pendingRead.value ??= { id, path: `/messages/${rows.value.find(m => m.id === id)?.kind ?? "TODO"}/${id}/read`,
    key: crypto.randomUUID(), version: 0 };
  const operation = pendingRead.value, epoch = ++generation, owner = identity.value;
  busy.value = true; error.value = "";
  try {
    await loanRequest(operation.path, operation); if (!current(epoch, owner)) return;
    pendingRead.value = null; await load(epoch, owner);
  } catch (cause) { if (current(epoch, owner)) failure(cause, true); }
  finally { if (current(epoch, owner)) busy.value = false; }
}
const dutyAlive = (epoch: number, owner: string) => live && epoch === dutyGeneration && owner === identity.value;
async function loadDuty() {
  if (!canSeeDuty.value) return;
  const epoch = ++dutyGeneration, owner = identity.value;
  try {
    const result = await dutyRequest<DutyList>("/duty-assignments");
    if (dutyAlive(epoch, owner)) duties.value = result.items;
  } catch (cause) { if (dutyAlive(epoch, owner)) dutyError.value = reminderMessage(cause); }
}
async function rotate() {
  if (!administrator.value) return;
  if (!pendingDuty.value) {
    const role = contact.value.role;
    const primary = contact.value.primary.trim(), backup = contact.value.backup.trim();
    if (!/^[1-9][0-9]*$/.test(primary) || backup !== "" && !/^[1-9][0-9]*$/.test(backup)) {
      dutyError.value = "请填写账号的数字编号。"; return;
    }
    pendingDuty.value = { role, key: crypto.randomUUID(),
      version: Number(duties.value.find(duty => duty.roleCode === role)?.version ?? 0),
      body: { primaryAccountId: primary, backupAccountId: backup === "" ? null : backup } };
  }
  const operation = pendingDuty.value, epoch = ++dutyGeneration, owner = identity.value;
  dutyError.value = "";
  try {
    const saved = await dutyRequest<DutyAssignment>(`/duty-assignments/${operation.role}`, operation);
    if (!dutyAlive(epoch, owner)) return;
    duties.value = [...duties.value.filter(duty => duty.roleCode !== operation.role), saved];
    pendingDuty.value = null;
  } catch (cause) {
    if (!dutyAlive(epoch, owner)) return;
    if (cause instanceof ReminderApiError && cause.status < 500) pendingDuty.value = null;
    dutyError.value = reminderMessage(cause);
  }
}
watch(identity, () => { clear(); error.value = ""; dutyError.value = "";
  if (session.user) { void refresh(); void loadDuty(); } },
  { immediate: true, flush: "sync" });
watch(privileged, allowed => { if (!allowed && view.value === "EXCEPTION") { view.value = "INBOX"; void refresh(); } });
onBeforeUnmount(() => { live = false; clear(); });
</script>
<template>
  <AppChrome>
    <h1>消息中心</h1>
    <p>站内消息与工作台待办是权威事实源；未读 {{ unread }} 条，阅读不改变业务状态，也不代替办理。</p>
    <div class="actions">
      <button :disabled="busy || view === 'INBOX'" @click="switchTo('INBOX')">我的消息</button>
      <button v-if="privileged" :disabled="busy || view === 'EXCEPTION'" @click="switchTo('EXCEPTION')">异常清单</button>
      <button :disabled="busy" @click="reload()">刷新</button>
      <button v-if="pendingRead" :disabled="busy" @click="read(pendingRead.id)">原请求重试已读登记</button>
    </div>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <article v-for="message in rows" :key="message.kind + message.id">
      <h2>{{ label(message) }} · {{ message.read ? "已读" : "未读" }}</h2>
      <p>{{ BUSINESSES[message.businessType] ?? message.businessType }} {{ message.businessId }}
        · {{ message.poolType }}<template v-if="message.assigneeAccountId"> · 处理账号 {{ message.assigneeAccountId }}</template></p>
      <p>登记 {{ formatProxyTime(message.openedAt) }}<template v-if="message.deadlineAt"> · 截止 {{ formatProxyTime(message.deadlineAt) }}</template></p>
      <router-link v-if="destination(message)" :to="destination(message)!">查看业务单据</router-link>
      <button v-if="!message.read" :disabled="busy || !!pendingRead" @click="read(message.id)">标记已读</button>
    </article>
    <p v-if="!rows.length">{{ view === "EXCEPTION" ? "当前没有需要跟进的超时异常。" : "暂无站内消息。" }}</p>
    <div class="actions">
      <button :disabled="busy || page <= 1" @click="refresh(-1)">上一页</button>
      <span>{{ page }} 页 / 共 {{ total }} 条</span>
      <button :disabled="busy || page * 20 >= total" @click="refresh(1)">下一页</button>
    </div>
    <section v-if="canSeeDuty">
      <h2>值班提醒</h2>
      <p>值班提醒人只接收升级通知，不取得审批权或处理权；被替换的配置保留为历史，便于追溯当时谁在值班。</p>
      <article v-for="duty in duties" :key="duty.roleCode">
        <h3>{{ duty.roleCode === "ASSET_ADMIN" ? "管理员共享池" : "库管共享池" }}</h3>
        <p>值班 {{ duty.primary.name }}<template v-if="!duty.primary.active">（已失效）</template>
          · 替补 <template v-if="duty.backup">{{ duty.backup.name }}<template v-if="!duty.backup.active">（已失效）</template></template>
          <template v-else>未配置</template></p>
        <p>生效 {{ formatProxyTime(duty.effectiveAt) }} · 版本 {{ duty.version }}</p>
      </article>
      <form v-if="administrator" @submit.prevent="rotate">
        <label>共享池
          <select v-model="contact.role" :disabled="!!pendingDuty">
            <option value="ASSET_ADMIN">管理员</option>
            <option value="WAREHOUSE_OPERATOR">库管</option>
          </select>
        </label>
        <label>值班提醒人账号
          <input v-model="contact.primary" :disabled="!!pendingDuty" placeholder="账号数字编号">
        </label>
        <label>替补账号
          <input v-model="contact.backup" :disabled="!!pendingDuty" placeholder="留空表示不设备补">
        </label>
        <button type="submit" :disabled="busy">{{ pendingDuty ? "原请求重试值班登记" : "登记值班人" }}</button>
      </form>
      <p v-if="dutyError" role="alert" class="form-error">{{ dutyError }}</p>
    </section>
    <ConfigurationAnomalies v-if="administrator" />
  </AppChrome>
</template>
