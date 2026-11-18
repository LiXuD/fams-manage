<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import type { DataScopeGrant, Role } from "@/auth/types";
import { useSessionStore } from "@/auth/sessionStore";
import { BusinessApiError as LoanApiError, businessRequest as loanRequest } from "@/api/business";

type ScopeType = DataScopeGrant["scopeType"];
interface Authorization {
  id: string; role: Role; status: "ACTIVE" | "REVOKED"; version: number;
  dataScopes: DataScopeGrant[]; fieldPermissions: string[];
}
interface Reference { id: string; version: number; name?: string }
interface ScopeEditor { scopeType: ScopeType; organizations: Reference[]; warehouses: Reference[] }
const props = defineProps<{ accountId: string; username: string; role: Role; blocked: boolean }>();
const emit = defineEmits<{ locked: [value: boolean] }>();
const session = useSessionStore();
const scopeNames: Record<ScopeType, string> = {
  SELF: "本人", OWN_ORGANIZATION: "本部门", OWN_ORGANIZATION_AND_DESCENDANTS: "本部门及下级",
  SPECIFIED_ORGANIZATIONS: "指定组织", SPECIFIED_WAREHOUSES: "指定仓库", ENTERPRISE: "全企业",
};
const fieldNames: Record<string, string> = {
  EMPLOYEE_PHONE: "员工电话", ASSET_SERVICE_PHONE: "资产服务电话", BUSINESS_ATTACHMENT: "业务附件",
  REPAIR_COST: "维修费用", BULK_EXPORT: "批量导出", SCRAP_ATTACHMENT: "报废附件",
  SYSTEM_OPERATION_LOG: "系统操作日志",
};
const current = ref<Authorization | null>(null);
const scopes = ref<ScopeEditor[]>([]);
const fields = ref<string[]>([]);
const reason = ref("");
const referenceId = ref("");
const error = ref("");
const notice = ref("");
const busy = ref(false);
const pending = ref<{ path: string; method: "PATCH" | "POST"; key: string; version: number; body: unknown } | null>(null);
let active = true;
const path = `/system-accounts/${props.accountId}/authorization/${props.role}`;
onBeforeUnmount(() => { active = false; emit("locked", false); });

function show(reply: Authorization): void {
  current.value = reply;
  scopes.value = reply.dataScopes.map(scope => ({
    scopeType: scope.scopeType, organizations: [...scope.organizations], warehouses: [...scope.warehouses],
  }));
  fields.value = [...reply.fieldPermissions];
}

function problem(cause: unknown): string {
  if (cause instanceof LoanApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或管理权限已失效，请重新登录核对。";
    if (cause.status === 409) return "角色、授权或引用资料已变化，请刷新核对；目标账号须先取得所选角色。";
    if (cause.status === 400 || cause.status === 404) return "请核对授权范围及组织、仓库编号。";
  }
  return "授权结果尚未确认，请保留原请求重试。";
}

async function load(): Promise<void> {
  if (busy.value || pending.value || props.blocked) return;
  busy.value = true; emit("locked", true); error.value = ""; current.value = null;
  try {
    const result = await loanRequest<Authorization>(path);
    if (active) show(result);
  } catch (cause) {
    if (active) error.value = problem(cause);
  } finally {
    if (active) { busy.value = false; emit("locked", false); }
  }
}

function addScope(scopeType: ScopeType): void {
  if (!scopes.value.some(scope => scope.scopeType === scopeType)) {
    scopes.value.push({ scopeType, organizations: [], warehouses: [] });
  }
}

async function addReference(scope: ScopeEditor): Promise<void> {
  if (!/^[1-9]\d*$/.test(referenceId.value)) { error.value = "请输入有效的资料编号。"; return; }
  const organizations = scope.scopeType === "SPECIFIED_ORGANIZATIONS";
  const resource = organizations ? "organizations" : "warehouses";
  busy.value = true; emit("locked", true); error.value = "";
  try {
    const result = await loanRequest<Reference & { status: string }>(`/${resource}/${referenceId.value}`);
    if (!active) return;
    if (result.status !== "ACTIVE") { error.value = "该资料已停用，请选择有效资料。"; return; }
    const list = organizations ? scope.organizations : scope.warehouses;
    if (!list.some(row => row.id === result.id)) {
      list.push({ id: result.id, version: result.version, name: result.name });
    }
    referenceId.value = "";
  } catch (cause) {
    if (active) error.value = problem(cause);
  } finally {
    if (active) { busy.value = false; emit("locked", false); }
  }
}

async function execute(): Promise<void> {
  if (!pending.value || busy.value || props.blocked) return;
  const operation = pending.value;
  busy.value = true; emit("locked", true); error.value = "";
  try {
    if (!session.csrfToken) throw new LoanApiError(401, "AUTH_SESSION_INVALID");
    const response = await fetch("/api/v1" + operation.path, {
      method: operation.method, credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-Token": session.csrfToken,
        "Idempotency-Key": operation.key, "If-Match-Version": String(operation.version) },
      body: JSON.stringify(operation.body),
    });
    if (!response.ok) {
      const failure = await response.json().catch(() => ({})) as { code?: string };
      throw new LoanApiError(response.status, failure.code ?? "AUTHORIZATION_REQUEST_FAILED");
    }
    const result = await response.json() as Authorization;
    if (!active) return;
    pending.value = null;
    show(result);
    notice.value = `授权已保存，当前状态 ${result.status}，版本 ${result.version}。`;
  } catch (cause) {
    if (!active) return;
    if (cause instanceof LoanApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    error.value = problem(cause);
  } finally {
    if (active) { busy.value = false; emit("locked", !!pending.value); }
  }
}

async function save(revoke: boolean): Promise<void> {
  if (!current.value || busy.value || pending.value || props.blocked || !reason.value.trim()) return;
  if (!revoke && scopes.value.some(scope =>
    scope.scopeType === "SPECIFIED_ORGANIZATIONS" && !scope.organizations.length
      || scope.scopeType === "SPECIFIED_WAREHOUSES" && !scope.warehouses.length)) {
    error.value = "指定组织或仓库范围须至少添加一项有效资料。"; return;
  }
  const body = revoke ? { reason: reason.value.trim() } : {
    reason: reason.value.trim(), fieldPermissions: [...fields.value],
    dataScopes: scopes.value.map(scope => ({
      scopeType: scope.scopeType,
      organizations: scope.organizations.map(({ id, version }) => ({ id, version })),
      warehouses: scope.warehouses.map(({ id, version }) => ({ id, version })),
    })),
  };
  if (!window.confirm(`${revoke ? "撤销" : "保存"} ${props.username} 的 ${props.role} 授权`)) return;
  pending.value = { path: path + (revoke ? "/revoke" : ""), method: revoke ? "POST" : "PATCH",
    key: crypto.randomUUID(), version: current.value.version, body };
  await execute();
}
</script>

<template>
  <section aria-label="数据范围和敏感字段授权">
    <h3>数据范围和敏感字段授权 · {{ role }}</h3>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <button :disabled="blocked || busy || !!pending" @click="load">读取当前授权</button>
    <button v-if="pending" :disabled="blocked || busy" @click="execute">原请求重试授权</button>
    <template v-if="current">
      <p>当前状态 {{ current.status }} · 版本 {{ current.version }}</p>
      <fieldset :disabled="blocked || busy || !!pending">
        <legend>一般数据范围</legend>
        <template v-if="role !== 'SYSTEM_ADMIN'">
          <button v-for="(label, type) in scopeNames" :key="type"
            :disabled="scopes.some(scope => scope.scopeType === type)" @click="addScope(type)">{{ label }}</button>
          <article v-for="(scope, index) in scopes" :key="scope.scopeType">
            <h4>{{ scopeNames[scope.scopeType] }}</h4>
            <button @click="scopes.splice(index, 1)">移除{{ scopeNames[scope.scopeType] }}</button>
            <template v-if="scope.scopeType === 'SPECIFIED_ORGANIZATIONS' || scope.scopeType === 'SPECIFIED_WAREHOUSES'">
              <label>资料编号<input v-model="referenceId" inputmode="numeric" /></label>
              <button @click="addReference(scope)">读取并添加有效资料</button>
              <ul><li v-for="(row, rowIndex) in scope.scopeType === 'SPECIFIED_ORGANIZATIONS' ? scope.organizations : scope.warehouses"
                :key="row.id">{{ row.name || row.id }}
                <button @click="(scope.scopeType === 'SPECIFIED_ORGANIZATIONS' ? scope.organizations : scope.warehouses).splice(rowIndex, 1)">移除资料</button>
              </li></ul>
            </template>
          </article>
          <fieldset><legend>敏感字段和独立动作</legend>
            <label v-for="(label, field) in fieldNames" :key="field">
              <input v-model="fields" type="checkbox" :value="field" />{{ label }}
            </label>
          </fieldset>
        </template>
        <label>授权变更原因<textarea v-model="reason" maxlength="500" /></label>
        <button :disabled="!reason.trim()" @click="save(false)">保存授权</button>
        <button :disabled="!reason.trim() || current.status !== 'ACTIVE'" @click="save(true)">撤销授权</button>
      </fieldset>
    </template>
  </section>
</template>
