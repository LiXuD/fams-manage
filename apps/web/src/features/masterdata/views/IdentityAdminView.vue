<script setup lang="ts">
import { computed, ref, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { masterDataRequest, MasterDataApiError, type MasterDataMutation } from "@/features/masterdata/api";

interface Reference { id: string; version: number }
interface IdentityRow {
  id: string; version: number; status: "ACTIVE" | "DISABLED";
  code?: string; name?: string; parentOrganization?: Reference;
  employeeNo?: string; displayName?: string; organization?: Reference;
}
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const kind = ref<"organizations" | "employees">("organizations");
const rows = ref<IdentityRow[]>([]);
const page = ref(1);
const total = ref(0);
const selected = ref<IdentityRow | null>(null);
const formOpen = ref(false);
const code = ref("");
const name = ref("");
const organizationId = ref("");
const phone = ref("");
const reason = ref("");
const busy = ref(false);
const error = ref("");
const notice = ref("");
const pending = ref<MasterDataMutation | null>(null);
let generation = 0;

function problem(cause: unknown): string {
  if (cause instanceof MasterDataApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效。";
    if (cause.status === 409) return "资料版本、编码或依赖关系冲突，请刷新核对；有有效下级或成员的组织不能停用。";
    if (cause.status === 400 || cause.status === 404) return "请核对必填资料和组织编号。";
  }
  return "操作结果尚未确认，请使用原请求重试。";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || pending.value) return;
  const run = ++generation;
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<{ items: IdentityRow[]; total: number }>(
      `/${kind.value}?page=${page.value}&pageSize=20`);
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

function open(row: IdentityRow | null): void {
  selected.value = row;
  code.value = row?.code ?? row?.employeeNo ?? "";
  name.value = row?.name ?? row?.displayName ?? "";
  organizationId.value = (row?.parentOrganization ?? row?.organization)?.id ?? "";
  phone.value = ""; reason.value = ""; error.value = ""; notice.value = "";
  formOpen.value = true;
}

async function execute(): Promise<void> {
  if (!identity.value || !pending.value || busy.value) return;
  const operation = pending.value;
  const run = generation;
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<IdentityRow>(operation.path, operation);
    if (run !== generation) return;
    pending.value = null; selected.value = null; formOpen.value = false; phone.value = "";
    notice.value = `${result.name ?? result.displayName} 已保存，状态 ${result.status}，版本 ${result.version}。`;
    busy.value = false;
    await load();
  } catch (cause) {
    if (run !== generation) return;
    if (cause instanceof MasterDataApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
}

async function save(): Promise<void> {
  if (!identity.value || busy.value || pending.value || !code.value.trim() || !name.value.trim()) return;
  const row = selected.value;
  const employee = kind.value === "employees";
  const run = generation;
  const body: Record<string, unknown> = employee
    ? { employeeNo: code.value.trim(), displayName: name.value.trim() }
    : { code: code.value.trim(), name: name.value.trim() };
  busy.value = true; error.value = "";
  let operation: MasterDataMutation | null = null;
  try {
    const oldReference = row?.organization ?? row?.parentOrganization;
    let referenceName = "";
    if (organizationId.value && organizationId.value !== oldReference?.id || employee && !row) {
      if (!/^[1-9]\d*$/.test(organizationId.value)) throw new MasterDataApiError(400, "ORGANIZATION_REQUIRED");
      const reference = await masterDataRequest<IdentityRow>("/organizations/" + organizationId.value);
      if (run !== generation) return;
      if (reference.status !== "ACTIVE") throw new MasterDataApiError(409, "ORGANIZATION_DISABLED");
      body[employee ? "organization" : "parentOrganization"] = { id: reference.id, version: reference.version };
      referenceName = reference.name ?? reference.id;
    }
    if (!row && employee && phone.value.trim()) body.phone = phone.value.trim();
    if (!window.confirm(`${row ? "更新" : "新建"}${employee ? "员工" : "组织"} ${name.value.trim()}${referenceName ? " · " + referenceName : ""}`)) return;
    operation = { path: `/${kind.value}${row ? "/" + row.id : ""}`, method: row ? "PATCH" : "POST",
      key: crypto.randomUUID(), version: row?.version ?? 0, body };
  } catch (cause) {
    if (run === generation) error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
  if (operation && run === generation) {
    pending.value = operation;
    await execute();
  }
}

async function changeStatus(): Promise<void> {
  const row = selected.value;
  if (!identity.value || !row || busy.value || pending.value || !reason.value.trim()) return;
  const action = row.status === "ACTIVE" ? "disable" : "enable";
  if (kind.value === "organizations" && action === "enable") return;
  if (!window.confirm(`${action === "disable" ? "停用" : "启用"} ${row.name ?? row.displayName}\n${reason.value.trim()}`)) return;
  pending.value = { path: `/${kind.value}/${row.id}/${action}`, method: "POST",
    key: crypto.randomUUID(), version: row.version, body: { reason: reason.value.trim() } };
  await execute();
}

async function move(delta: number): Promise<void> {
  selected.value = null; formOpen.value = false; page.value += delta;
  await load();
}
watch([identity, kind], () => {
  generation++; rows.value = []; total.value = 0; page.value = 1; selected.value = null;
  formOpen.value = false; code.value = ""; name.value = ""; organizationId.value = ""; phone.value = "";
  reason.value = ""; busy.value = false; error.value = ""; notice.value = ""; pending.value = null;
  void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <h1>组织与人员</h1>
    <p v-if="!identity" role="alert">只有系统管理员可以维护组织与人员。</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <button :disabled="busy || !!pending" @click="kind = 'organizations'">组织</button>
      <button :disabled="busy || !!pending" @click="kind = 'employees'">人员</button>
      <button :disabled="busy || !!pending" @click="load">刷新列表</button>
      <button :disabled="busy || !!pending" @click="open(null)">{{ kind === "employees" ? "新建员工" : "新建组织" }}</button>
      <button v-if="pending" :disabled="busy" @click="execute">原请求重试保存</button>
      <table><thead><tr><th>编号</th><th>{{ kind === "employees" ? "工号" : "编码" }}</th><th>名称</th>
        <th>{{ kind === "employees" ? "所属组织" : "上级组织" }}</th><th>状态</th><th>版本</th><th>操作</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.id">
          <td>{{ row.id }}</td><td>{{ row.code ?? row.employeeNo }}</td><td>{{ row.name ?? row.displayName }}</td>
          <td>{{ (row.organization ?? row.parentOrganization)?.id ?? "根组织" }}</td>
          <td>{{ row.status }}</td><td>{{ row.version }}</td>
          <td><button :disabled="busy || !!pending" @click="open(row)">维护</button>
            <router-link v-if="kind === 'employees' && !pending && !busy"
              :to="{ name: 'employee-phone-corrections', query: { employeeId: row.id } }">纠正手机号</router-link>
          </td>
        </tr></tbody>
      </table>
      <p>第 {{ page }} 页，共 {{ total }} 条</p>
      <button :disabled="page <= 1 || busy || !!pending" @click="move(-1)">上一页</button>
      <button :disabled="page * 20 >= total || busy || !!pending" @click="move(1)">下一页</button>
      <section v-if="formOpen" aria-label="维护资料">
        <h2>{{ selected ? "维护" : "新建" }}{{ kind === "employees" ? "员工" : "组织" }}</h2>
        <form @submit.prevent="save"><fieldset :disabled="busy || !!pending">
          <label>{{ kind === "employees" ? "工号" : "组织编码" }}<input v-model="code" maxlength="64" required /></label>
          <label>名称<input v-model="name" maxlength="128" required /></label>
          <label>{{ kind === "employees" ? "所属组织编号" : "上级组织编号" }}
            <input v-model="organizationId" inputmode="numeric" :required="kind === 'employees'" />
          </label>
          <p>组织编号可在组织列表中查询。{{ kind === "organizations" ? selected ? "上级留空保留原归属。" : "上级留空创建根组织。" : "" }}</p>
          <label v-if="kind === 'employees' && !selected">企业登记手机号<input v-model="phone" type="tel" maxlength="32" autocomplete="off" /></label>
          <button type="submit">保存资料</button>
        </fieldset></form>
        <fieldset v-if="selected && (kind === 'employees' || selected.status === 'ACTIVE')" :disabled="busy || !!pending">
          <label>状态变更原因<textarea v-model="reason" maxlength="500" /></label>
          <button :disabled="!reason.trim()" @click="changeStatus">{{ selected.status === "ACTIVE" ? "停用" : "启用" }}</button>
        </fieldset>
      </section>
    </template>
  </AppChrome>
</template>
