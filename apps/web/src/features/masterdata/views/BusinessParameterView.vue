<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { BusinessApiError, businessRequest } from "@/api/business";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";

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
/** 保存意图：只带路径、版本与快照体；幂等键归 useConfirmedSubmit 底座。 */
type SaveIntent = { path: string; version: number; body: { snapshot: Snapshot } };
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
const current = shallowRef<ParameterVersion | null>(null);
const history = ref<ParameterVersion[]>([]);
const selectedHistory = shallowRef<ParameterVersion | null>(null);
const draft = ref<Snapshot>(defaults());
const holidays = shallowRef("");
const adjusted = shallowRef("");
const page = shallowRef(1);
const total = shallowRef(0);
const ready = shallowRef(false);
const busy = shallowRef(false);
const error = shallowRef("");
const notice = shallowRef("");
const proposal = shallowRef<Snapshot | null>(null);
const liveness = useRequestLiveness(() => [identity.value]);
const deterministic = (cause: unknown): boolean =>
  cause instanceof BusinessApiError && cause.status >= 400 && cause.status < 500;
const { pending, prepare: draftSave, confirm: adoptSave, settle, fail, reset } =
  useConfirmedSubmit<SaveIntent>({ identity: () => identity.value, failure: deterministic });

const legendText = computed(() => current.value ? "创建下一版本" : "设置初始参数");
const refreshDisabled = computed(() => busy.value || pending.value !== null);
const backDisabled = computed(() => busy.value || pending.value !== null);
const saveDisabled = computed(() => busy.value);
const saveLabel = computed(() => pending.value ? "原请求重试保存" : "确认立即生效");
const prevDisabled = computed(() => page.value <= 1 || busy.value || pending.value !== null);
const nextDisabled = computed(() => page.value * 20 >= total.value || busy.value || pending.value !== null);
function weekdayReadout(days: readonly string[]): string {
  return days.map(day => weekdays[day as keyof typeof weekdays]).join("、");
}
const proposalWeekdaysText = computed(() => proposal.value ? weekdayReadout(proposal.value.workWeek) : "");
const proposalHolidaysText = computed(() => proposal.value && proposal.value.holidays.length
  ? proposal.value.holidays.join("、") : "无");
const proposalAdjustedText = computed(() => proposal.value && proposal.value.adjustedWorkdays.length
  ? proposal.value.adjustedWorkdays.join("、") : "无");
const selectedWeekdaysText = computed(() => selectedHistory.value
  ? weekdayReadout(selectedHistory.value.snapshot.workWeek) : "");
const selectedHolidaysText = computed(() => selectedHistory.value && selectedHistory.value.snapshot.holidays.length
  ? selectedHistory.value.snapshot.holidays.join("、") : "无");
const selectedAdjustedText = computed(() => selectedHistory.value && selectedHistory.value.snapshot.adjustedWorkdays.length
  ? selectedHistory.value.snapshot.adjustedWorkdays.join("、") : "无");

function problem(cause: unknown): string {
  if (cause instanceof BusinessApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效，请重新登录。";
    if (cause.status === 409) return "参数版本已变化，请刷新并核对当前版本后重新预览。";
    if (cause.status === 400) return "请核对时区、日历日期和正整数时限。";
  }
  return "保存结果尚未确认，请使用原请求重试。";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || pending.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; ready.value = false;
  try {
    const result = await businessRequest<{ items: ParameterVersion[]; total: number }>(
      `/business-parameters?page=${page.value}&pageSize=20`);
    const active = result.total ? await businessRequest<ParameterVersion>("/business-parameters/current") : null;
    if (!liveness.isCurrent(turn)) return;
    current.value = active; history.value = result.items; total.value = result.total;
    selectedHistory.value = null; proposal.value = null;
    draft.value = active ? JSON.parse(JSON.stringify(active.snapshot)) as Snapshot : defaults();
    holidays.value = draft.value.holidays.join("\n");
    adjusted.value = draft.value.adjustedWorkdays.join("\n");
    ready.value = true;
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
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
  let operation = pending.value;
  if (!operation) {
    const snapshot = JSON.parse(JSON.stringify(proposal.value)) as Snapshot;
    draftSave(() => ({ path: "/business-parameters", version: current.value?.version ?? 0,
      body: { snapshot } }));
    operation = adoptSave();
  }
  if (!operation) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await businessRequest<ParameterVersion>(operation.payload.path,
      { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settle(); current.value = result; proposal.value = null;
    notice.value = `版本 ${result.version} 已生效，生效时间 ${result.effectiveAt}。`;
    busy.value = false;
    await load();
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    if (fail(cause)) { proposal.value = null; ready.value = false; }
    error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function move(delta: number): Promise<void> {
  page.value += delta;
  await load();
}
watch(identity, () => {
  liveness.invalidate(); reset();
  current.value = null; history.value = []; selectedHistory.value = null;
  draft.value = defaults(); holidays.value = ""; adjusted.value = ""; page.value = 1; total.value = 0;
  ready.value = false; busy.value = false; proposal.value = null;
  error.value = ""; notice.value = ""; void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="noaccess" role="alert"><strong>只有系统管理员可以维护业务参数。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="refreshDisabled" @click="load">刷新参数</button>
      </div>
      <p v-if="current">当前版本 {{ current.version }} · {{ current.effectiveAt }} · {{ current.actor.username }}</p>
      <form v-if="ready && !proposal" @submit.prevent="preview">
        <fieldset class="fams-stack" :disabled="busy">
          <legend>{{ legendText }}</legend>
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">企业时区</span><input class="fams-control" v-model="draft.timezone" required /></label>
            <fieldset class="fams-field-wide"><legend>每周工作日</legend>
              <label v-for="(label, day) in weekdays" :key="day" class="fams-selection"><input v-model="draft.workWeek" type="checkbox" :value="day" />{{ label }}</label>
            </fieldset>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">节假日（每行一个日期）</span><textarea class="fams-control" v-model="holidays" placeholder="YYYY-MM-DD" /></label>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">调休工作日（每行一个日期）</span><textarea class="fams-control" v-model="adjusted" placeholder="YYYY-MM-DD" /></label>
            <label v-for="(label, key) in durationLabels" :key="key" class="fams-field"><span class="fams-field-label">{{ label }}</span>
              <input class="fams-control" v-model.number="draft[key]" type="number" min="1" max="2147483647" required />
            </label>
            <label v-for="(label, key) in thresholdLabels" :key="key" class="fams-field"><span class="fams-field-label">{{ label }}</span>
              <input class="fams-control" v-model.number="draft.notificationEscalationThresholds[key]" type="number" min="1" max="2147483647" required />
            </label>
          </div>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit">预览新版本</button></div>
        </fieldset>
      </form>
      <section v-if="proposal" class="fams-panel fams-stack" aria-label="新版本预览">
        <div class="fams-panel-head"><h2 class="fams-panel-title">新版本预览</h2></div>
        <p>确认后立即生效，新建业务使用此版本；现有业务按原冻结参数继续办理。</p>
        <dl class="fams-facts"><dt>企业时区</dt><dd>{{ current?.snapshot.timezone ?? "尚未设置" }} → {{ proposal.timezone }}</dd>
          <dt>每周工作日</dt><dd>{{ proposalWeekdaysText }}</dd>
          <dt>节假日</dt><dd>{{ proposalHolidaysText }}</dd>
          <dt>调休工作日</dt><dd>{{ proposalAdjustedText }}</dd>
          <template v-for="(label, key) in durationLabels" :key="key">
            <dt>{{ label }}</dt><dd>{{ current?.snapshot[key] ?? "尚未设置" }} → {{ proposal[key] }}</dd>
          </template>
          <template v-for="(label, key) in thresholdLabels" :key="key">
            <dt>{{ label }}</dt><dd>{{ current?.snapshot.notificationEscalationThresholds[key] ?? "尚未设置" }} → {{ proposal.notificationEscalationThresholds[key] }}</dd>
          </template>
        </dl>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" type="button" :disabled="backDisabled" @click="proposal = null">返回修改</button>
          <button class="fams-button" data-kind="primary" type="button" :disabled="saveDisabled" @click="save">{{ saveLabel }}</button>
        </div>
      </section>
      <section class="fams-panel fams-stack" aria-label="参数历史">
        <div class="fams-panel-head"><h2 class="fams-panel-title">历史版本</h2></div>
        <div class="fams-table-scroll"><table class="fams-table"><thead><tr><th scope="col">版本</th><th scope="col">状态</th><th scope="col">生效时间</th><th scope="col">登记人</th><th scope="col" data-column-kind="action">查看</th></tr></thead>
          <tbody><tr v-for="row in history" :key="row.id">
            <td>{{ row.version }}</td><td>{{ row.status }}</td><td>{{ row.effectiveAt }}</td><td>{{ row.actor.username }}</td>
            <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button" @click="selectedHistory = row">查看版本 {{ row.version }}</button></td>
          </tr></tbody>
        </table></div>
        <div class="fams-pagination">
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="move(-1)">上一页</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="move(1)">下一页</button>
          </div>
        </div>
        <dl v-if="selectedHistory" class="fams-facts">
          <dt>版本</dt><dd>{{ selectedHistory.version }}</dd>
          <dt>企业时区</dt><dd>{{ selectedHistory.snapshot.timezone }}</dd>
          <dt>每周工作日</dt><dd>{{ selectedWeekdaysText }}</dd>
          <dt>节假日</dt><dd>{{ selectedHolidaysText }}</dd>
          <dt>调休工作日</dt><dd>{{ selectedAdjustedText }}</dd>
          <template v-for="(label, key) in durationLabels" :key="key"><dt>{{ label }}</dt><dd>{{ selectedHistory.snapshot[key] }}</dd></template>
          <template v-for="(label, key) in thresholdLabels" :key="key"><dt>{{ label }}</dt><dd>{{ selectedHistory.snapshot.notificationEscalationThresholds[key] }}</dd></template>
        </dl>
      </section>
    </template>
  </AppChrome>
</template>
