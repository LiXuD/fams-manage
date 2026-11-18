<script setup lang="ts">
import { computed, ref, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { loanRequest, LoanApiError, type LoanMutation } from "@/loan/api";

const durationLabels = {
  adminDecisionDeadlineWorkdays: "管理员确认时限（工作日）",
  warehouseClaimDeadlineWorkdays: "库管领取时限（工作日）",
  warehouseFirstExecutionDeadlineWorkdays: "库管首次执行时限（工作日）",
  handoverAcceptanceDeadlineNaturalDays: "实物接收时限（自然日）",
} as const;
const thresholdLabels = {
  administratorUnreadHours: "管理员未读升级（小时）",
  onDutyWarehouseOperatorUnreadHours: "值班库管未读升级（小时）",
  firstExecutionOverdueNaturalDays: "首次执行超期升级（自然日）",
  receiptDueUnreadHours: "接收到期未读升级（小时）",
} as const;
const weekdays = { MONDAY: "周一", TUESDAY: "周二", WEDNESDAY: "周三", THURSDAY: "周四",
  FRIDAY: "周五", SATURDAY: "周六", SUNDAY: "周日" } as const;
type Snapshot = Record<keyof typeof durationLabels, number> & {
  timezone: string; workWeek: string[]; holidays: string[]; adjustedWorkdays: string[];
  notificationEscalationThresholds: Record<keyof typeof thresholdLabels, number>;
};
interface ParameterVersion {
  id: string; version: number; status: string; effectiveAt: string;
  actor: { id: string; username: string }; snapshot: Snapshot;
}
function defaults(): Snapshot {
  return { timezone: "Asia/Shanghai", workWeek: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
    holidays: [], adjustedWorkdays: [], adminDecisionDeadlineWorkdays: 2,
    warehouseClaimDeadlineWorkdays: 1, warehouseFirstExecutionDeadlineWorkdays: 2,
    handoverAcceptanceDeadlineNaturalDays: 3, notificationEscalationThresholds: {
      administratorUnreadHours: 4, onDutyWarehouseOperatorUnreadHours: 4,
      firstExecutionOverdueNaturalDays: 1, receiptDueUnreadHours: 24,
    } };
}
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const current = ref<ParameterVersion | null>(null);
const history = ref<ParameterVersion[]>([]);
const selectedHistory = ref<ParameterVersion | null>(null);
const draft = ref<Snapshot>(defaults());
const holidays = ref("");
const adjusted = ref("");
const page = ref(1);
const total = ref(0);
const ready = ref(false);
const busy = ref(false);
const error = ref("");
const notice = ref("");
const proposal = ref<Snapshot | null>(null);
const pending = ref<LoanMutation | null>(null);
let generation = 0;

function problem(cause: unknown): string {
  if (cause instanceof LoanApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效，请重新登录。";
    if (cause.status === 409) return "参数版本已变化，请刷新并核对当前版本后重新预览。";
    if (cause.status === 400) return "请核对时区、日历日期和正整数时限。";
  }
  return "保存结果尚未确认，请使用原请求重试。";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || pending.value) return;
  const run = ++generation;
  busy.value = true; error.value = ""; ready.value = false;
  try {
    const result = await loanRequest<{ items: ParameterVersion[]; total: number }>(
      `/business-parameters?page=${page.value}&pageSize=20`);
    const active = result.total ? await loanRequest<ParameterVersion>("/business-parameters/current") : null;
    if (run !== generation) return;
    current.value = active; history.value = result.items; total.value = result.total;
    selectedHistory.value = null; proposal.value = null;
    draft.value = active ? JSON.parse(JSON.stringify(active.snapshot)) as Snapshot : defaults();
    holidays.value = draft.value.holidays.join("\n");
    adjusted.value = draft.value.adjustedWorkdays.join("\n");
    ready.value = true;
  } catch (cause) {
    if (run === generation) error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
}

function dates(value: string): string[] {
  const result = value.split(/[\s,，]+/).filter(Boolean);
  for (const date of result) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isFinite(Date.parse(date + "T00:00:00Z"))
      || new Date(date + "T00:00:00Z").toISOString().slice(0, 10) !== date) {
      throw new Error("日期须为有效的 YYYY-MM-DD。");
    }
  }
  if (new Set(result).size !== result.length) throw new Error("日历中存在重复日期。");
  return result;
}

function preview(): void {
  error.value = ""; notice.value = "";
  try {
    const snapshot = JSON.parse(JSON.stringify(draft.value)) as Snapshot;
    snapshot.timezone = snapshot.timezone.trim();
    new Intl.DateTimeFormat("zh-CN", { timeZone: snapshot.timezone }).format();
    if (!snapshot.workWeek.length) throw new Error("请选择至少一个每周工作日。");
    const values = [...Object.keys(durationLabels).map(key => snapshot[key as keyof typeof durationLabels]),
      ...Object.values(snapshot.notificationEscalationThresholds)];
    if (values.some(value => !Number.isInteger(value) || value < 1 || value > 2147483647)) {
      throw new Error("时限和升级阈值须为正整数。");
    }
    snapshot.holidays = dates(holidays.value);
    snapshot.adjustedWorkdays = dates(adjusted.value);
    if (snapshot.holidays.some(day => snapshot.adjustedWorkdays.includes(day))) {
      throw new Error("节假日与调休工作日不能重叠。");
    }
    proposal.value = snapshot;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "请核对参数。";
  }
}

async function save(): Promise<void> {
  if (!identity.value || busy.value || !proposal.value) return;
  if (!pending.value) {
    pending.value = { path: "/business-parameters", key: crypto.randomUUID(), version: current.value?.version ?? 0,
      body: { snapshot: JSON.parse(JSON.stringify(proposal.value)) as Snapshot } };
  }
  const operation = pending.value;
  const run = generation;
  busy.value = true; error.value = "";
  try {
    const result = await loanRequest<ParameterVersion>(operation.path, operation);
    if (run !== generation) return;
    current.value = result; pending.value = null; proposal.value = null;
    notice.value = `版本 ${result.version} 已生效，生效时间 ${result.effectiveAt}。`;
    busy.value = false;
    await load();
  } catch (cause) {
    if (run !== generation) return;
    if (cause instanceof LoanApiError && cause.status >= 400 && cause.status < 500) {
      pending.value = null; proposal.value = null; ready.value = false;
    }
    error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
}

async function move(delta: number): Promise<void> {
  page.value += delta;
  await load();
}
watch(identity, () => {
  generation++; current.value = null; history.value = []; selectedHistory.value = null;
  draft.value = defaults(); holidays.value = ""; adjusted.value = ""; page.value = 1; total.value = 0;
  ready.value = false; busy.value = false; proposal.value = null; pending.value = null;
  error.value = ""; notice.value = ""; void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <h1>业务参数</h1>
    <p v-if="!identity" role="alert">只有系统管理员可以维护业务参数。</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <button :disabled="busy || !!pending" @click="load">刷新参数</button>
      <p v-if="current">当前版本 {{ current.version }} · {{ current.effectiveAt }} · {{ current.actor.username }}</p>
      <form v-if="ready && !proposal" @submit.prevent="preview">
        <fieldset :disabled="busy">
          <legend>{{ current ? "创建下一版本" : "设置初始参数" }}</legend>
          <label>企业时区<input v-model="draft.timezone" required /></label>
          <fieldset><legend>每周工作日</legend>
            <label v-for="(label, day) in weekdays" :key="day"><input v-model="draft.workWeek" type="checkbox" :value="day" />{{ label }}</label>
          </fieldset>
          <label>节假日（每行一个日期）<textarea v-model="holidays" placeholder="YYYY-MM-DD" /></label>
          <label>调休工作日（每行一个日期）<textarea v-model="adjusted" placeholder="YYYY-MM-DD" /></label>
          <label v-for="(label, key) in durationLabels" :key="key">{{ label }}
            <input v-model.number="draft[key]" type="number" min="1" max="2147483647" required />
          </label>
          <label v-for="(label, key) in thresholdLabels" :key="key">{{ label }}
            <input v-model.number="draft.notificationEscalationThresholds[key]" type="number" min="1" max="2147483647" required />
          </label>
          <button type="submit">预览新版本</button>
        </fieldset>
      </form>
      <section v-if="proposal" aria-label="新版本预览">
        <h2>新版本预览</h2>
        <p>确认后立即生效，新建业务使用此版本；现有业务按原冻结参数继续办理。</p>
        <dl><dt>企业时区</dt><dd>{{ current?.snapshot.timezone ?? "尚未设置" }} → {{ proposal.timezone }}</dd>
          <dt>每周工作日</dt><dd>{{ proposal.workWeek.map(day => weekdays[day as keyof typeof weekdays]).join("、") }}</dd>
          <dt>节假日</dt><dd>{{ proposal.holidays.join("、") || "无" }}</dd>
          <dt>调休工作日</dt><dd>{{ proposal.adjustedWorkdays.join("、") || "无" }}</dd>
          <template v-for="(label, key) in durationLabels" :key="key">
            <dt>{{ label }}</dt><dd>{{ current?.snapshot[key] ?? "尚未设置" }} → {{ proposal[key] }}</dd>
          </template>
          <template v-for="(label, key) in thresholdLabels" :key="key">
            <dt>{{ label }}</dt><dd>{{ current?.snapshot.notificationEscalationThresholds[key] ?? "尚未设置" }} → {{ proposal.notificationEscalationThresholds[key] }}</dd>
          </template>
        </dl>
        <button :disabled="busy || !!pending" @click="proposal = null">返回修改</button>
        <button :disabled="busy" @click="save">{{ pending ? "原请求重试保存" : "确认立即生效" }}</button>
      </section>
      <section aria-label="参数历史">
        <h2>历史版本</h2>
        <table><thead><tr><th>版本</th><th>状态</th><th>生效时间</th><th>登记人</th><th>查看</th></tr></thead>
          <tbody><tr v-for="row in history" :key="row.id">
            <td>{{ row.version }}</td><td>{{ row.status }}</td><td>{{ row.effectiveAt }}</td><td>{{ row.actor.username }}</td>
            <td><button @click="selectedHistory = row">查看版本 {{ row.version }}</button></td>
          </tr></tbody>
        </table>
        <button :disabled="page <= 1 || busy || !!pending" @click="move(-1)">上一页</button>
        <button :disabled="page * 20 >= total || busy || !!pending" @click="move(1)">下一页</button>
        <dl v-if="selectedHistory">
          <dt>版本</dt><dd>{{ selectedHistory.version }}</dd>
          <dt>企业时区</dt><dd>{{ selectedHistory.snapshot.timezone }}</dd>
          <dt>每周工作日</dt><dd>{{ selectedHistory.snapshot.workWeek.map(day => weekdays[day as keyof typeof weekdays]).join("、") }}</dd>
          <dt>节假日</dt><dd>{{ selectedHistory.snapshot.holidays.join("、") || "无" }}</dd>
          <dt>调休工作日</dt><dd>{{ selectedHistory.snapshot.adjustedWorkdays.join("、") || "无" }}</dd>
          <template v-for="(label, key) in durationLabels" :key="key"><dt>{{ label }}</dt><dd>{{ selectedHistory.snapshot[key] }}</dd></template>
          <template v-for="(label, key) in thresholdLabels" :key="key"><dt>{{ label }}</dt><dd>{{ selectedHistory.snapshot.notificationEscalationThresholds[key] }}</dd></template>
        </dl>
      </section>
    </template>
  </AppChrome>
</template>
