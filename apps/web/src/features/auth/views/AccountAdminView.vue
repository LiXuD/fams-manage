<script setup lang="ts">
import { computed, ref, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import AccountAuthorizationPanel from "../panels/AccountAuthorizationPanel.vue";
import { useSessionStore } from "@/auth/sessionStore";
import type { Role, SystemAccountStatus } from "@/auth/types";
import { businessRequest as loanRequest, BusinessApiError as LoanApiError, type BusinessMutation as LoanMutation } from "@/api/business";

interface Account {
  id: string; username: string; employeeId: string; status: SystemAccountStatus;
  version: number; requiresInitialPasswordChange: boolean; configurationWarnings?: string[];
}
interface AccountPage { items: Account[]; total: number }
interface RoleConfiguration { role: Role; permissions: string[]; businessScope: string }
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const accounts = ref<Account[]>([]);
const configurations = ref<RoleConfiguration[]>([]);
const page = ref(1);
const total = ref(0);
const selected = ref<Account | null>(null);
const role = ref<Role>("EMPLOYEE");
const reason = ref("");
const username = ref("");
const employeeId = ref("");
const employeeVersion = ref(0);
const password = ref("");
const initialPassword = ref("");
const error = ref("");
const notice = ref("");
const loading = ref(false);
const busy = ref(false);
const authorizationLocked = ref(false);
const pending = ref<(LoanMutation & { label: string }) | null>(null);
let generation = 0;

function problem(cause: unknown): string {
  if (cause instanceof LoanApiError) {
    if (cause.status === 401) return "登录已失效，请重新登录。";
    if (cause.status === 403) return "当前账号没有系统管理员权限。";
    if (cause.status === 409) return "账号版本、角色或员工资料已变化，请刷新后核对。";
    if (cause.status === 400 || cause.status === 404) return "请核对账号、员工版本、角色、密码和原因。";
  }
  return "操作结果尚未确认，请使用原请求重试。";
}

async function load(): Promise<void> {
  if (!identity.value) return;
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const [result, roles] = await Promise.all([
      loanRequest<AccountPage>(`/system-accounts?page=${page.value}&pageSize=20`),
      loanRequest<{ items: RoleConfiguration[] }>("/role-configurations?page=1&pageSize=20"),
    ]);
    if (current !== generation) return;
    accounts.value = result.items;
    total.value = result.total;
    configurations.value = roles.items;
    if (selected.value) selected.value = result.items.find(account => account.id === selected.value?.id) ?? null;
  } catch (cause) {
    if (current === generation) error.value = problem(cause);
  } finally {
    if (current === generation) loading.value = false;
  }
}

async function execute(): Promise<void> {
  if (!pending.value || !identity.value || busy.value) return;
  const operation = pending.value;
  const owner = identity.value;
  const current = generation;
  busy.value = true;
  error.value = "";
  try {
    const account = await loanRequest<Account>(operation.path, operation);
    if (owner !== identity.value || current !== generation) return;
    pending.value = null;
    password.value = "";
    initialPassword.value = "";
    selected.value = account;
    notice.value = `${operation.label}已保存。账号 ${account.username}，版本 ${account.version}。`;
    await load();
  } catch (cause) {
    if (owner !== identity.value || current !== generation) return;
    if (cause instanceof LoanApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    error.value = problem(cause);
  } finally {
    if (owner === identity.value) busy.value = false;
  }
}

async function change(action: "ENABLE" | "DISABLE" | "UNLOCK" | "GRANT" | "REVOKE" | "RESET"): Promise<void> {
  if (!selected.value || !reason.value.trim() || pending.value || busy.value) return;
  const account = selected.value;
  let path = `/system-accounts/${account.id}/status`;
  let body: unknown = { action, reason: reason.value.trim() };
  let label = { ENABLE: "启用账号", DISABLE: "停用账号", UNLOCK: "解锁账号",
    GRANT: "授予角色", REVOKE: "撤销角色", RESET: "重置初始密码" }[action];
  if (action === "GRANT" || action === "REVOKE") {
    path = `/system-accounts/${account.id}/roles${action === "REVOKE" ? "/revoke" : ""}`;
    body = { role: role.value, reason: reason.value.trim() };
    label += ` ${role.value}`;
  }
  if (action === "RESET") {
    if (password.value.length < 12) { error.value = "初始密码至少需要 12 个字符。"; return; }
    path = `/system-accounts/${account.id}/initial-password-reset`;
    body = { newInitialPassword: password.value, reason: reason.value.trim() };
  }
  if (!window.confirm(`${label} · ${account.username}\n原因 ${reason.value.trim()}`)) return;
  pending.value = { path, key: crypto.randomUUID(), version: account.version, body, label };
  await execute();
}

async function create(): Promise<void> {
  if (pending.value || busy.value) return;
  if (!username.value.trim() || !/^[1-9]\d*$/.test(employeeId.value) || initialPassword.value.length < 12
      || !Number.isSafeInteger(employeeVersion.value) || employeeVersion.value < 0) {
    error.value = "请填写账号、有效员工编号及版本、至少 12 位的初始密码。";
    return;
  }
  if (!window.confirm(`创建账号 ${username.value.trim()}，关联员工 ${employeeId.value}`)) return;
  pending.value = { path: "/system-accounts", key: crypto.randomUUID(), version: 0, label: "创建账号",
    body: { username: username.value.trim(), employee: { id: employeeId.value, version: employeeVersion.value },
      initialPassword: initialPassword.value } };
  await execute();
}

async function move(delta: number): Promise<void> {
  page.value += delta;
  await load();
}

watch(identity, () => {
  generation++;
  accounts.value = []; configurations.value = []; selected.value = null; pending.value = null;
  password.value = ""; initialPassword.value = ""; reason.value = ""; notice.value = ""; error.value = "";
  username.value = ""; employeeId.value = ""; employeeVersion.value = 0;
  loading.value = false; busy.value = false; page.value = 1; total.value = 0;
  void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <h1>账号与角色</h1>
    <p v-if="!identity" role="alert">只有系统管理员可以管理账号与角色。</p>
    <template v-else>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <button v-if="pending" :disabled="busy" @click="execute">原请求重试{{ pending.label }}</button>
      <button :disabled="loading || busy || authorizationLocked || !!pending" @click="load">刷新账号</button>
      <table>
        <thead><tr><th>账号</th><th>员工编号</th><th>状态</th><th>版本</th><th>初次改密</th><th>操作</th></tr></thead>
        <tbody><tr v-for="account in accounts" :key="account.id">
          <td>{{ account.username }}</td><td>{{ account.employeeId }}</td><td>{{ account.status }}</td>
          <td>{{ account.version }}</td><td>{{ account.requiresInitialPasswordChange ? "需要" : "已完成" }}</td>
          <td><button :disabled="busy || authorizationLocked || !!pending" @click="selected = account; reason = ''; password = ''">管理</button></td>
        </tr></tbody>
      </table>
      <p v-if="!loading && !accounts.length">当前没有账号。</p>
      <p>第 {{ page }} 页，共 {{ total }} 个账号</p>
      <button :disabled="page <= 1 || loading || busy || authorizationLocked || !!pending" @click="move(-1)">上一页</button>
      <button :disabled="page * 20 >= total || loading || busy || authorizationLocked || !!pending" @click="move(1)">下一页</button>
      <section v-if="selected" aria-label="管理当前账号">
        <h2>{{ selected.username }}</h2>
        <p v-for="warning in selected.configurationWarnings ?? []" :key="warning" role="alert">{{ warning }}</p>
        <fieldset :disabled="busy || authorizationLocked || !!pending">
          <label>变更原因<textarea v-model="reason" maxlength="500" /></label>
          <button :disabled="!reason.trim() || selected.status === 'ENABLED'" @click="change('ENABLE')">启用</button>
          <button :disabled="!reason.trim() || selected.status === 'DISABLED'" @click="change('DISABLE')">停用</button>
          <button :disabled="!reason.trim() || selected.status !== 'LOCKED'" @click="change('UNLOCK')">解锁</button>
          <label for="account-role">角色</label><select id="account-role" v-model="role">
            <option v-for="configuration in configurations" :key="configuration.role" :value="configuration.role">
              {{ configuration.role }}
            </option>
          </select>
          <button :disabled="!reason.trim()" @click="change('GRANT')">授予角色</button>
          <button :disabled="!reason.trim()" @click="change('REVOKE')">撤销角色</button>
          <label>新初始密码<input v-model="password" type="password" autocomplete="new-password" minlength="12" /></label>
          <button :disabled="!reason.trim() || password.length < 12" @click="change('RESET')">重置初始密码</button>
        </fieldset>
        <AccountAuthorizationPanel :key="selected.id + ':' + role" :account-id="selected.id"
          :username="selected.username" :role="role" :blocked="busy || !!pending"
          @locked="authorizationLocked = $event" />
      </section>
      <section aria-label="创建账号">
        <h2>创建账号</h2>
        <form @submit.prevent="create"><fieldset :disabled="busy || authorizationLocked || !!pending">
          <label>登录名<input v-model="username" maxlength="128" required /></label>
          <label>员工编号<input v-model="employeeId" inputmode="numeric" required /></label>
          <label>员工版本<input v-model.number="employeeVersion" type="number" min="0" required /></label>
          <label>初始密码<input v-model="initialPassword" type="password" autocomplete="new-password" minlength="12" required /></label>
          <button type="submit">创建账号</button>
        </fieldset></form>
      </section>
      <section aria-label="固定角色权限">
        <h2>固定角色权限</h2>
        <details v-for="configuration in configurations" :key="configuration.role">
          <summary>{{ configuration.role }} · {{ configuration.businessScope }}</summary>
          <ul><li v-for="permission in configuration.permissions" :key="permission">{{ permission }}</li></ul>
        </details>
      </section>
    </template>
  </AppChrome>
</template>
