<script setup lang="ts">
import { computed, ref, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { masterDataRequest, MasterDataApiError, type MasterDataMutation } from "@/masterdata/api";

interface Rule {
  id: string; code: string; target: "ASSET" | "INBOUND_RECEIPT"; mode: "ENTERPRISE" | "CATEGORY_PREFIX";
  fixedPrefix?: string; datePart: "NONE" | "YYYY" | "YYYYMM" | "YYYYMMDD";
  sequenceLength: number; status: string; version: number;
}
const targets = { ASSET: "资产编号", INBOUND_RECEIPT: "入库单编号" } as const;
const modes = { ENTERPRISE: "企业统一", CATEGORY_PREFIX: "分类前缀" } as const;
const dates = { NONE: "无日期", YYYY: "年", YYYYMM: "年月", YYYYMMDD: "年月日" } as const;
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const rows = ref<Rule[]>([]);
const page = ref(1); const total = ref(0);
const selected = ref<Rule | null>(null);
const formOpen = ref(false);
const code = ref(""); const target = ref<Rule["target"]>("ASSET");
const mode = ref<Rule["mode"]>("ENTERPRISE"); const prefix = ref("");
const datePart = ref<Rule["datePart"]>("NONE"); const length = ref(6);
const reason = ref(""); const busy = ref(false); const error = ref(""); const notice = ref("");
const pending = ref<MasterDataMutation | null>(null);
let generation = 0;

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
  const run = ++generation; busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<{ items: Rule[]; total: number }>(
      `/numbering-rules?page=${page.value}&pageSize=20`);
    if (run !== generation) return;
    rows.value = result.items; total.value = result.total;
    if (selected.value) {
      const row = result.items.find(item => item.id === selected.value?.id);
      if (row) open(row);
      else { selected.value = null; formOpen.value = false; }
    }
  } catch (cause) {
    if (run === generation) error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
}

async function execute(): Promise<void> {
  if (!identity.value || !pending.value || busy.value) return;
  const operation = pending.value; const run = generation;
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<Rule>(operation.path, operation);
    if (run !== generation) return;
    pending.value = null; selected.value = null; formOpen.value = false;
    notice.value = `规则 ${result.code} 已保存，状态 ${result.status}，版本 ${result.version}。`;
    busy.value = false; await load();
  } catch (cause) {
    if (run !== generation) return;
    if (cause instanceof MasterDataApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
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
  pending.value = { path: "/numbering-rules" + (selected.value ? "/" + selected.value.id : ""),
    method: selected.value ? "PATCH" : "POST", key: crypto.randomUUID(), version: selected.value?.version ?? 0, body };
  await execute();
}

async function changeStatus(): Promise<void> {
  const row = selected.value;
  if (!identity.value || !row || busy.value || pending.value || !reason.value.trim()) return;
  const disable = row.status === "ACTIVE";
  if (!window.confirm(`${disable ? "停用" : "启用"}编号规则 ${row.code}？`)) return;
  pending.value = { path: `/numbering-rules/${row.id}/${disable ? "disable" : "enable"}`, method: "POST",
    key: crypto.randomUUID(), version: row.version, body: { reason: reason.value.trim() } };
  await execute();
}
async function move(delta: number): Promise<void> {
  selected.value = null; formOpen.value = false; page.value += delta; await load();
}
watch(identity, () => {
  generation++; rows.value = []; page.value = 1; total.value = 0; selected.value = null;
  formOpen.value = false; code.value = ""; prefix.value = ""; reason.value = "";
  busy.value = false; pending.value = null; error.value = ""; notice.value = ""; void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <h1>编号规则</h1>
    <p v-if="!identity" role="alert">只有系统管理员可以维护编号规则。</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <button :disabled="busy || !!pending" @click="load">刷新规则</button>
      <button :disabled="busy || !!pending" @click="open(null)">新建规则</button>
      <button v-if="pending" :disabled="busy" @click="execute">原请求重试保存</button>
      <table><thead><tr><th>规则编码</th><th>目标</th><th>模式</th><th>固定前缀</th><th>日期</th><th>流水位数</th><th>状态</th><th>版本</th><th>操作</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.id">
          <td>{{ row.code }}</td><td>{{ targets[row.target] }}</td><td>{{ modes[row.mode] }}</td>
          <td>{{ row.mode === "CATEGORY_PREFIX" ? "按分类" : row.fixedPrefix || "无固定前缀" }}</td><td>{{ dates[row.datePart] }}</td><td>{{ row.sequenceLength }}</td>
          <td>{{ row.status }}</td><td>{{ row.version }}</td>
          <td><button :disabled="busy || !!pending" @click="open(row)">维护</button></td>
        </tr></tbody>
      </table>
      <p>第 {{ page }} 页，共 {{ total }} 条</p>
      <button :disabled="page <= 1 || busy || !!pending" @click="move(-1)">上一页</button>
      <button :disabled="page * 20 >= total || busy || !!pending" @click="move(1)">下一页</button>
      <section v-if="formOpen" aria-label="维护编号规则">
        <h2>{{ selected ? "维护规则" : "新建规则" }}</h2>
        <form @submit.prevent="save"><fieldset :disabled="busy || !!pending">
          <label>规则编码<input v-model="code" :disabled="!!selected" maxlength="64" required /></label>
          <label for="numbering-target">编号目标</label><select id="numbering-target" v-model="target">
            <option v-for="(label, value) in targets" :key="value" :value="value">{{ label }}</option>
          </select>
          <label for="numbering-mode">编号模式</label><select id="numbering-mode" v-model="mode">
            <option value="ENTERPRISE">企业统一</option><option value="CATEGORY_PREFIX" :disabled="target === 'INBOUND_RECEIPT'">分类前缀</option>
          </select>
          <label v-if="mode === 'ENTERPRISE'">固定前缀<input v-model="prefix" maxlength="32" /></label>
          <label for="numbering-date">日期部分</label><select id="numbering-date" v-model="datePart">
            <option v-for="(label, value) in dates" :key="value" :value="value">{{ label }}</option>
          </select>
          <label>流水位数<input v-model.number="length" type="number" min="1" max="16" required /></label>
          <p>格式组成：{{ mode === "CATEGORY_PREFIX" ? "各分类当前前缀" : prefix || "无固定前缀" }}、
            {{ dates[datePart] }}、{{ length }} 位流水。</p>
          <p>规则用于后续编号分配。正式编号由业务提交时生成。</p>
          <button type="submit">保存规则</button>
        </fieldset></form>
        <fieldset v-if="selected" :disabled="busy || !!pending">
          <label>状态变更原因<textarea v-model="reason" maxlength="500" /></label>
          <button :disabled="!reason.trim()" @click="changeStatus">{{ selected.status === "ACTIVE" ? "停用" : "启用" }}</button>
        </fieldset>
      </section>
    </template>
  </AppChrome>
</template>
