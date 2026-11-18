<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { masterDataRequest, MasterDataApiError, type MasterDataMutation } from "@/features/masterdata/api";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";

interface Rule {
  id: string; code: string; target: "ASSET" | "INBOUND_RECEIPT"; mode: "ENTERPRISE" | "CATEGORY_PREFIX";
  fixedPrefix?: string; datePart: "NONE" | "YYYY" | "YYYYMM" | "YYYYMMDD";
  sequenceLength: number; status: string; version: number;
}
/** 行显示模型：固定前缀读法归位，模板只读 prefixText。 */
interface RuleRow extends Rule { prefixText: string }
/** 在途意图：只带路径、方法、版本与请求体；幂等键归 useConfirmedSubmit 底座。 */
type RuleIntent = { path: string; method: MasterDataMutation["method"]; version: number; body: unknown };
const targets = { ASSET: "资产编号", INBOUND_RECEIPT: "入库单编号" } as const;
const modes = { ENTERPRISE: "企业统一", CATEGORY_PREFIX: "分类前缀" } as const;
const dates = { NONE: "无日期", YYYY: "年", YYYYMM: "年月", YYYYMMDD: "年月日" } as const;
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const rows = ref<Rule[]>([]);
const page = shallowRef(1); const total = shallowRef(0);
const selected = shallowRef<Rule | null>(null);
const formOpen = shallowRef(false);
const code = shallowRef(""); const target = shallowRef<Rule["target"]>("ASSET");
const mode = shallowRef<Rule["mode"]>("ENTERPRISE"); const prefix = shallowRef("");
const datePart = shallowRef<Rule["datePart"]>("NONE"); const length = shallowRef(6);
const reason = shallowRef(""); const busy = shallowRef(false); const error = shallowRef(""); const notice = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
const deterministic = (cause: unknown): boolean =>
  cause instanceof MasterDataApiError && cause.status >= 400 && cause.status < 500;
const { pending, prepare: draftOp, confirm: adoptOp, settle, fail, reset } =
  useConfirmedSubmit<RuleIntent>({ identity: () => identity.value, failure: deterministic });

const locked = computed(() => busy.value || pending.value !== null);
const retryDisabled = computed(() => busy.value);
const prevDisabled = computed(() => page.value <= 1 || locked.value);
const nextDisabled = computed(() => page.value * 20 >= total.value || locked.value);
const formTitle = computed(() => selected.value ? "维护规则" : "新建规则");
const codeDisabled = computed(() => selected.value !== null);
const categoryDisabled = computed(() => target.value === "INBOUND_RECEIPT");
const isEnterprise = computed(() => mode.value === "ENTERPRISE");
const prefixReadout = computed(() => isEnterprise.value ? prefix.value || "无固定前缀" : "各分类当前前缀");
const statusActionLabel = computed(() => selected.value?.status === "ACTIVE" ? "停用" : "启用");
const statusDisabled = computed(() => reason.value.trim() === "");
const ruleRows = computed<RuleRow[]>(() => rows.value.map(row => ({
  ...row,
  prefixText: row.mode === "CATEGORY_PREFIX" ? "按分类" : row.fixedPrefix || "无固定前缀",
})));

function problem(cause: unknown): string {
  if (cause instanceof MasterDataApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效。";
    if (cause.status === 409) return "版本、规则编码或有效目标冲突，请刷新；分类前缀还需有效且互不重复。";
    if (cause.status === 400 || cause.status === 404) return "请核对编号目标、模式、前缀、日期和流水位数。";
  }
  return "操作结果尚未确认，请使用原请求重试。";
}

function open(row: Rule | null): void {
  selected.value = row; formOpen.value = true; code.value = row?.code ?? "";
  target.value = row?.target ?? "ASSET"; mode.value = row?.mode ?? "ENTERPRISE";
  prefix.value = row?.fixedPrefix ?? ""; datePart.value = row?.datePart ?? "NONE";
  length.value = row?.sequenceLength ?? 6; reason.value = ""; error.value = ""; notice.value = "";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || pending.value) return;
  const turn = liveness.begin(); busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<{ items: Rule[]; total: number }>(
      `/numbering-rules?page=${page.value}&pageSize=20`);
    if (!liveness.isCurrent(turn)) return;
    rows.value = result.items; total.value = result.total;
    if (selected.value) {
      const row = result.items.find(item => item.id === selected.value?.id);
      if (row) open(row);
      else { selected.value = null; formOpen.value = false; }
    }
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function send(): Promise<void> {
  if (!identity.value || !pending.value || busy.value) return;
  const operation = pending.value; const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<Rule>(operation.payload.path, { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settle(); selected.value = null; formOpen.value = false;
    notice.value = `规则 ${result.code} 已保存，状态 ${result.status}，版本 ${result.version}。`;
    busy.value = false; await load();
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    fail(cause);
    error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function save(): Promise<void> {
  if (!identity.value || busy.value || pending.value) return;
  if (!code.value.trim() || !Number.isInteger(length.value) || length.value < 1 || length.value > 16) {
    error.value = "请填写规则编码和 1～16 位的流水长度。"; return;
  }
  if (target.value === "INBOUND_RECEIPT" && mode.value === "CATEGORY_PREFIX") {
    error.value = "入库单只支持企业统一编号。"; return;
  }
  if (mode.value === "ENTERPRISE" && !/^[A-Za-z0-9_-]{0,32}$/.test(prefix.value)) {
    error.value = "固定前缀限 32 位以内的字母、数字、下划线或连字符。"; return;
  }
  const body: Record<string, unknown> = { target: target.value, mode: mode.value,
    datePart: datePart.value, sequenceLength: length.value };
  if (!selected.value) body.code = code.value.trim();
  if (mode.value === "ENTERPRISE") body.fixedPrefix = prefix.value;
  if (!window.confirm(`保存 ${code.value}，应用于后续的${targets[target.value]}分配？`)) return;
  const editing = selected.value;
  draftOp(() => ({ path: "/numbering-rules" + (editing ? "/" + editing.id : ""),
    method: editing ? "PATCH" : "POST", version: editing?.version ?? 0, body }));
  if (!adoptOp()) return;
  await send();
}

async function changeStatus(): Promise<void> {
  const row = selected.value;
  if (!identity.value || !row || busy.value || pending.value || !reason.value.trim()) return;
  const disable = row.status === "ACTIVE";
  if (!window.confirm(`${disable ? "停用" : "启用"}编号规则 ${row.code}？`)) return;
  draftOp(() => ({ path: `/numbering-rules/${row.id}/${disable ? "disable" : "enable"}`, method: "POST",
    version: row.version, body: { reason: reason.value.trim() } }));
  if (!adoptOp()) return;
  await send();
}
async function move(delta: number): Promise<void> {
  selected.value = null; formOpen.value = false; page.value += delta; await load();
}
watch(identity, () => {
  liveness.invalidate(); reset();
  rows.value = []; page.value = 1; total.value = 0; selected.value = null;
  formOpen.value = false; code.value = ""; prefix.value = ""; reason.value = "";
  busy.value = false; error.value = ""; notice.value = ""; void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="noaccess" role="alert"><strong>只有系统管理员可以维护编号规则。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="locked" @click="load">刷新规则</button>
        <button class="fams-button" data-kind="primary" type="button" :disabled="locked" @click="open(null)">新建规则</button>
        <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="retryDisabled" @click="send">原请求重试保存</button>
      </div>
      <div class="fams-table-scroll"><table class="fams-table"><thead><tr><th scope="col">规则编码</th><th scope="col">目标</th><th scope="col">模式</th><th scope="col">固定前缀</th><th scope="col">日期</th><th scope="col">流水位数</th><th scope="col">状态</th><th scope="col">版本</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
        <tbody><tr v-for="row in ruleRows" :key="row.id">
          <td>{{ row.code }}</td><td>{{ targets[row.target] }}</td><td>{{ modes[row.mode] }}</td>
          <td>{{ row.prefixText }}</td><td>{{ dates[row.datePart] }}</td><td>{{ row.sequenceLength }}</td>
          <td>{{ row.status }}</td><td>{{ row.version }}</td>
          <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button" :disabled="locked" @click="open(row)">维护</button></td>
        </tr></tbody>
      </table></div>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">第 {{ page }} 页，共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="move(-1)">上一页</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="move(1)">下一页</button>
        </div>
      </div>
      <section v-if="formOpen" class="fams-panel fams-stack" aria-label="维护编号规则">
        <div class="fams-panel-head"><h2 class="fams-panel-title">{{ formTitle }}</h2></div>
        <form @submit.prevent="save"><fieldset class="fams-stack" :disabled="locked">
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">规则编码</span><input class="fams-control" v-model="code" :disabled="codeDisabled" maxlength="64" required /></label>
            <label class="fams-field"><span class="fams-field-label">编号目标</span><select id="numbering-target" class="fams-control" v-model="target">
              <option v-for="(label, value) in targets" :key="value" :value="value">{{ label }}</option>
            </select></label>
            <label class="fams-field"><span class="fams-field-label">编号模式</span><select id="numbering-mode" class="fams-control" v-model="mode">
              <option value="ENTERPRISE">企业统一</option><option value="CATEGORY_PREFIX" :disabled="categoryDisabled">分类前缀</option>
            </select></label>
            <label v-if="isEnterprise" class="fams-field"><span class="fams-field-label">固定前缀</span><input class="fams-control" v-model="prefix" maxlength="32" /></label>
            <label class="fams-field"><span class="fams-field-label">日期部分</span><select id="numbering-date" class="fams-control" v-model="datePart">
              <option v-for="(label, value) in dates" :key="value" :value="value">{{ label }}</option>
            </select></label>
            <label class="fams-field"><span class="fams-field-label">流水位数</span><input class="fams-control" v-model.number="length" type="number" min="1" max="16" required /></label>
          </div>
          <p>格式组成：{{ prefixReadout }}、 {{ dates[datePart] }}、{{ length }} 位流水。</p>
          <p>规则用于后续编号分配。正式编号由业务提交时生成。</p>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit">保存规则</button></div>
        </fieldset></form>
        <fieldset v-if="selected" class="fams-stack" :disabled="locked">
          <label class="fams-field"><span class="fams-field-label">状态变更原因</span><textarea class="fams-control" v-model="reason" maxlength="500" /></label>
          <div class="fams-panel-actions"><button class="fams-button" data-kind="secondary" type="button" :disabled="statusDisabled" @click="changeStatus">{{ statusActionLabel }}</button></div>
        </fieldset>
      </section>
    </template>
  </AppChrome>
</template>
