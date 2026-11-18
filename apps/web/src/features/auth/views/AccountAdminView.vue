<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import AccountAuthorizationPanel from "../panels/AccountAuthorizationPanel.vue";
import { useSessionStore } from "@/auth/sessionStore";
import type { Role, SystemAccountStatus } from "@/auth/types";
import { BusinessApiError, businessRequest, type BusinessMutation } from "@/api/business";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness, type LivenessToken } from "@/composables/useRequestLiveness";

interface Account {
  id: string; username: string; employeeId: string; status: SystemAccountStatus;
  version: number; requiresInitialPasswordChange: boolean; configurationWarnings?: string[];
}
interface AccountPage { items: Account[]; total: number }
interface RoleConfiguration { role: Role; permissions: string[]; businessScope: string }
/** 写意图：路径、版本与请求体沿用功能线形状；幂等键只由提交底座持有，重试不换键。 */
type AccountIntent = Omit<BusinessMutation, "key"> & { label: string };

const PAGE_SIZE = 20;
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const accounts = ref<Account[]>([]);
const configurations = ref<RoleConfiguration[]>([]);
const page = shallowRef(1);
const total = shallowRef(0);
const selected = ref<Account | null>(null);
const role = shallowRef<Role>("EMPLOYEE");
const reason = shallowRef("");
const username = shallowRef("");
const employeeId = shallowRef("");
const employeeVersion = shallowRef(0);
const password = shallowRef("");
const initialPassword = shallowRef("");
const error = shallowRef("");
const notice = shallowRef("");
const loading = shallowRef(false);
const busy = shallowRef(false);
/** 确定性失败（本域错误类 4xx）清键允许重新起草；响应丢失保留原键走重试。 */
const deterministic = (cause: unknown): boolean => cause instanceof BusinessApiError
  && cause.status >= 400 && cause.status < 500;
const { pending, prepare: draft, confirm: adopt, settle, fail, reset } =
  useConfirmedSubmit<AccountIntent>({ failure: deterministic });
const liveness = useRequestLiveness(() => [identity.value]);

const accountRows = computed(() => accounts.value.map(account => ({
  ...account,
  initialPasswordText: account.requiresInitialPasswordChange ? "需要" : "已完成",
})));
const selectedWarnings = computed(() => selected.value?.configurationWarnings ?? []);
const panelKey = computed(() => `${selected.value?.id}:${role.value}`);
const hasReason = computed(() => reason.value.trim() !== "");
const passwordTooShort = computed(() => password.value.length < 12);
const writeLocked = computed(() => busy.value || pending.value !== null);
const listLocked = computed(() => loading.value || writeLocked.value);
const prevDisabled = computed(() => listLocked.value || page.value <= 1);
const nextDisabled = computed(() => listLocked.value || page.value * PAGE_SIZE >= total.value);
const showEmpty = computed(() => !loading.value && accounts.value.length === 0);
const enableDisabled = computed(() => !hasReason.value || selected.value?.status === "ENABLED");
const disableDisabled = computed(() => !hasReason.value || selected.value?.status === "DISABLED");
const unlockDisabled = computed(() => !hasReason.value || selected.value?.status !== "LOCKED");
const roleChangeDisabled = computed(() => !hasReason.value);
const resetDisabled = computed(() => !hasReason.value || passwordTooShort.value);

function problem(cause: unknown): string {
  if (cause instanceof BusinessApiError) {
    if (cause.status === 401) return "登录已失效，请重新登录。";
    if (cause.status === 403) return "当前账号没有系统管理员权限。";
    if (cause.status === 409) return "账号版本、角色或员工资料已变化，请刷新后核对。";
    if (cause.status === 400 || cause.status === 404) return "请核对账号、员工版本、角色、密码和原因。";
  }
  return "操作结果尚未确认，请使用原请求重试。";
}

async function load(externalTurn?: LivenessToken): Promise<void> {
  if (!identity.value) return;
  const turn = externalTurn ?? liveness.begin();
  loading.value = true;
  error.value = "";
  try {
    const [result, roles] = await Promise.all([
      businessRequest<AccountPage>(`/system-accounts?page=${page.value}&pageSize=${PAGE_SIZE}`),
      businessRequest<{ items: RoleConfiguration[] }>("/role-configurations?page=1&pageSize=20"),
    ]);
    if (!liveness.isCurrent(turn)) return;
    accounts.value = result.items;
    total.value = result.total;
    configurations.value = roles.items;
    if (selected.value) selected.value = result.items.find(account => account.id === selected.value?.id) ?? null;
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) loading.value = false;
  }
}

async function send(externalTurn?: LivenessToken): Promise<void> {
  const operation = pending.value;
  if (!operation || !identity.value || busy.value) return;
  const turn = externalTurn ?? liveness.begin();
  busy.value = true;
  error.value = "";
  try {
    const account = await businessRequest<Account>(operation.payload.path,
      { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settle();
    password.value = "";
    initialPassword.value = "";
    selected.value = account;
    notice.value = `${operation.payload.label}已保存。账号 ${account.username}，版本 ${account.version}。`;
    await load(turn);
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    fail(cause);
    error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function change(action: "ENABLE" | "DISABLE" | "UNLOCK" | "GRANT" | "REVOKE" | "RESET"): Promise<void> {
  const account = selected.value;
  if (!account || !hasReason.value || writeLocked.value) return;
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
    if (passwordTooShort.value) { error.value = "初始密码至少需要 12 个字符。"; return; }
    path = `/system-accounts/${account.id}/initial-password-reset`;
    body = { newInitialPassword: password.value, reason: reason.value.trim() };
  }
  if (!window.confirm(`${label} · ${account.username}\n原因 ${reason.value.trim()}`)) return;
  draft(() => ({ path, version: account.version, body, label }));
  if (!adopt()) return;
  await send();
}

async function create(): Promise<void> {
  if (writeLocked.value) return;
  if (!username.value.trim() || !/^[1-9]\d*$/.test(employeeId.value) || initialPassword.value.length < 12
      || !Number.isSafeInteger(employeeVersion.value) || employeeVersion.value < 0) {
    error.value = "请填写账号、有效员工编号及版本、至少 12 位的初始密码。";
    return;
  }
  if (!window.confirm(`创建账号 ${username.value.trim()}，关联员工 ${employeeId.value}`)) return;
  draft(() => ({ path: "/system-accounts", version: 0, label: "创建账号",
    body: { username: username.value.trim(), employee: { id: employeeId.value, version: employeeVersion.value },
      initialPassword: initialPassword.value } }));
  if (!adopt()) return;
  await send();
}

function selectAccount(row: Account): void {
  selected.value = accounts.value.find(account => account.id === row.id) ?? null;
  reason.value = "";
  password.value = "";
}

async function move(delta: number): Promise<void> {
  page.value += delta;
  await load();
}

watch(identity, () => {
  liveness.invalidate();
  accounts.value = []; configurations.value = []; selected.value = null; reset();
  password.value = ""; initialPassword.value = ""; reason.value = ""; notice.value = ""; error.value = "";
  username.value = ""; employeeId.value = ""; employeeVersion.value = 0;
  loading.value = false; busy.value = false; page.value = 1; total.value = 0;
  void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="error" role="alert"><strong>只有系统管理员可以管理账号与角色。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <div class="fams-panel-actions">
        <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="send()">原请求重试{{ pending.payload.label }}</button>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="listLocked" @click="load()">刷新账号</button>
      </div>
      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">账号</th><th scope="col">员工编号</th><th scope="col">状态</th><th scope="col">版本</th><th scope="col">初次改密</th><th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody><tr v-for="row in accountRows" :key="row.id">
            <td>{{ row.username }}</td><td>{{ row.employeeId }}</td><td>{{ row.status }}</td>
            <td>{{ row.version }}</td><td>{{ row.initialPasswordText }}</td>
            <td><button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="writeLocked" @click="selectAccount(row)">管理</button></td>
          </tr></tbody>
        </table>
      </div>
      <p v-if="showEmpty">当前没有账号。</p>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">第 {{ page }} 页，共 {{ total }} 个账号</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="move(-1)">上一页</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="move(1)">下一页</button>
        </div>
      </div>
      <section v-if="selected" class="fams-panel fams-stack" aria-label="管理当前账号">
        <div class="fams-panel-head"><h2 class="fams-panel-title">{{ selected.username }}</h2></div>
        <p v-for="warning in selectedWarnings" :key="warning" class="fams-notice" data-state="warning" role="alert"><strong>{{ warning }}</strong></p>
        <fieldset class="fams-card fams-stack" :disabled="writeLocked">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">变更原因</span><textarea class="fams-control" v-model="reason" maxlength="500" /></label>
          <div class="fams-panel-actions">
            <button class="fams-button" data-kind="primary" type="button" :disabled="enableDisabled" @click="change('ENABLE')">启用</button>
            <button class="fams-button" data-kind="danger" type="button" :disabled="disableDisabled" @click="change('DISABLE')">停用</button>
            <button class="fams-button" data-kind="secondary" type="button" :disabled="unlockDisabled" @click="change('UNLOCK')">解锁</button>
          </div>
          <label class="fams-field"><span class="fams-field-label">角色</span><select class="fams-control" v-model="role">
            <option v-for="configuration in configurations" :key="configuration.role" :value="configuration.role">
              {{ configuration.role }}
            </option>
          </select></label>
          <div class="fams-panel-actions">
            <button class="fams-button" data-kind="primary" type="button" :disabled="roleChangeDisabled" @click="change('GRANT')">授予角色</button>
            <button class="fams-button" data-kind="danger" type="button" :disabled="roleChangeDisabled" @click="change('REVOKE')">撤销角色</button>
          </div>
          <label class="fams-field"><span class="fams-field-label">新初始密码</span><input class="fams-control" v-model="password" type="password" autocomplete="new-password" minlength="12" /></label>
          <div class="fams-panel-actions">
            <button class="fams-button" data-kind="secondary" type="button" :disabled="resetDisabled" @click="change('RESET')">重置初始密码</button>
          </div>
        </fieldset>
        <AccountAuthorizationPanel :key="panelKey" :account-id="selected.id"
          :username="selected.username" :role="role" />
      </section>
      <section class="fams-card fams-stack" aria-label="创建账号">
        <div class="fams-panel-head"><h2 class="fams-panel-title">创建账号</h2></div>
        <form @submit.prevent="create"><fieldset class="fams-field-grid" :disabled="writeLocked">
          <label class="fams-field"><span class="fams-field-label">登录名</span><input class="fams-control" v-model="username" maxlength="128" required /></label>
          <label class="fams-field"><span class="fams-field-label">员工编号</span><input class="fams-control" v-model="employeeId" inputmode="numeric" required /></label>
          <label class="fams-field"><span class="fams-field-label">员工版本</span><input class="fams-control" v-model.number="employeeVersion" type="number" min="0" required /></label>
          <label class="fams-field"><span class="fams-field-label">初始密码</span><input class="fams-control" v-model="initialPassword" type="password" autocomplete="new-password" minlength="12" required /></label>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit">创建账号</button></div>
        </fieldset></form>
      </section>
      <section class="fams-panel fams-stack" aria-label="固定角色权限">
        <div class="fams-panel-head"><h2 class="fams-panel-title">固定角色权限</h2></div>
        <details v-for="configuration in configurations" :key="configuration.role">
          <summary>{{ configuration.role }} · {{ configuration.businessScope }}</summary>
          <ul><li v-for="permission in configuration.permissions" :key="permission">{{ permission }}</li></ul>
        </details>
      </section>
    </template>
  </AppChrome>
</template>
