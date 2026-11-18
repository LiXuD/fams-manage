<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import type { DataScopeGrant, Role } from "@/auth/types";
import { BusinessApiError, businessRequest } from "@/api/business";
import { authorizationRequest } from "@/features/auth/api";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";

type ScopeType = DataScopeGrant["scopeType"];
interface Authorization {
  id: string; role: Role; status: "ACTIVE" | "REVOKED"; version: number;
  dataScopes: DataScopeGrant[]; fieldPermissions: string[];
}
interface Reference { id: string; version: number; name?: string }
interface ScopeEditor { scopeType: ScopeType; organizations: Reference[]; warehouses: Reference[] }
/** 写意图：路径、方法、版本与请求体沿用功能线形状；幂等键只由提交底座持有，重试不换键。 */
type AuthorizationIntent = { path: string; method: "PATCH" | "POST"; version: number; body: unknown };

const props = defineProps<{ accountId: string; username: string; role: Role }>();
const REFERENCE_ID = /^[1-9]\d*$/;
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
const reason = shallowRef("");
const referenceId = shallowRef("");
const error = shallowRef("");
const notice = shallowRef("");
const busy = shallowRef(false);
/** 确定性失败（本域错误类 4xx）清键允许重新起草；响应丢失保留原键走重试。 */
const deterministic = (cause: unknown): boolean => cause instanceof BusinessApiError
  && cause.status >= 400 && cause.status < 500;
const { pending, prepare: draft, confirm: adopt, settle, fail } =
  useConfirmedSubmit<AuthorizationIntent>({ failure: deterministic });
const liveness = useRequestLiveness(() => [props.accountId, props.role]);
const path = `/system-accounts/${props.accountId}/authorization/${props.role}`;
const hasPending = computed(() => pending.value !== null);
const actionLocked = computed(() => busy.value || hasPending.value);
const hasReason = computed(() => reason.value.trim() !== "");
const saveDisabled = computed(() => !hasReason.value);
const revokeDisabled = computed(() => !hasReason.value || current.value?.status !== "ACTIVE");
const scopeEditorShown = computed(() => props.role !== "SYSTEM_ADMIN");
const scopeButtons = computed(() => (Object.keys(scopeNames) as ScopeType[]).map(scopeType => ({
  scopeType, label: scopeNames[scopeType], disabled: scopes.value.some(scope => scope.scopeType === scopeType),
})));
const scopeRows = computed(() => scopes.value.map((scope) => {
  const organizations = scope.scopeType === "SPECIFIED_ORGANIZATIONS";
  const specified = organizations || scope.scopeType === "SPECIFIED_WAREHOUSES";
  const references = specified ? (organizations ? scope.organizations : scope.warehouses) : [];
  return {
    scopeType: scope.scopeType,
    titleText: scopeNames[scope.scopeType],
    specified,
    resourceRows: references.map(reference => ({ id: reference.id, nameText: reference.name || reference.id })),
  };
}));

function show(reply: Authorization): void {
  current.value = reply;
  scopes.value = reply.dataScopes.map(scope => ({
    scopeType: scope.scopeType, organizations: [...scope.organizations], warehouses: [...scope.warehouses],
  }));
  fields.value = [...reply.fieldPermissions];
}

function problem(cause: unknown): string {
  if (cause instanceof BusinessApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或管理权限已失效，请重新登录核对。";
    if (cause.status === 409) return "角色、授权或引用资料已变化，请刷新核对；目标账号须先取得所选角色。";
    if (cause.status === 400 || cause.status === 404) return "请核对授权范围及组织、仓库编号。";
  }
  return "授权结果尚未确认，请保留原请求重试。";
}

async function load(): Promise<void> {
  if (actionLocked.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; current.value = null;
  try {
    const result = await businessRequest<Authorization>(path);
    if (liveness.isCurrent(turn)) show(result);
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

function addScope(scopeType: ScopeType): void {
  if (scopes.value.some(scope => scope.scopeType === scopeType)) return;
  scopes.value = [...scopes.value, { scopeType, organizations: [], warehouses: [] }];
}

function removeScope(scopeType: ScopeType): void {
  scopes.value = scopes.value.filter(scope => scope.scopeType !== scopeType);
}

function removeReference(scopeType: ScopeType, id: string): void {
  scopes.value = scopes.value.map(scope => scope.scopeType !== scopeType ? scope : {
    ...scope,
    organizations: scope.organizations.filter(row => row.id !== id),
    warehouses: scope.warehouses.filter(row => row.id !== id),
  });
}

async function addReference(scopeType: ScopeType): Promise<void> {
  if (!REFERENCE_ID.test(referenceId.value)) { error.value = "请输入有效的资料编号。"; return; }
  const organizations = scopeType === "SPECIFIED_ORGANIZATIONS";
  const resource = organizations ? "organizations" : "warehouses";
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await businessRequest<Reference & { status: string }>(`/${resource}/${referenceId.value}`);
    if (!liveness.isCurrent(turn)) return;
    if (result.status !== "ACTIVE") { error.value = "该资料已停用，请选择有效资料。"; return; }
    scopes.value = scopes.value.map((scope) => {
      if (scope.scopeType !== scopeType) return scope;
      const rows = organizations ? scope.organizations : scope.warehouses;
      if (rows.some(row => row.id === result.id)) return scope;
      const appended = [...rows, { id: result.id, version: result.version, name: result.name }];
      return organizations ? { ...scope, organizations: appended } : { ...scope, warehouses: appended };
    });
    referenceId.value = "";
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function send(): Promise<void> {
  const operation = pending.value;
  if (!operation || busy.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await authorizationRequest<Authorization>(operation.payload.path,
      { method: operation.payload.method, key: operation.key, version: operation.payload.version,
        body: operation.payload.body });
    if (!liveness.isCurrent(turn)) return;
    settle();
    show(result);
    notice.value = `授权已保存，当前状态 ${result.status}，版本 ${result.version}。`;
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    fail(cause);
    error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function save(revoke: boolean): Promise<void> {
  const target = current.value;
  if (!target || actionLocked.value || !hasReason.value) return;
  if (!revoke && scopes.value.some(scope =>
    (scope.scopeType === "SPECIFIED_ORGANIZATIONS" && !scope.organizations.length)
      || (scope.scopeType === "SPECIFIED_WAREHOUSES" && !scope.warehouses.length))) {
    error.value = "指定组织或仓库范围须至少添加一项有效资料。"; return;
  }
  const trimmed = reason.value.trim();
  const body = revoke ? { reason: trimmed } : {
    reason: trimmed, fieldPermissions: [...fields.value],
    dataScopes: scopes.value.map(scope => ({
      scopeType: scope.scopeType,
      organizations: scope.organizations.map(({ id, version }) => ({ id, version })),
      warehouses: scope.warehouses.map(({ id, version }) => ({ id, version })),
    })),
  };
  if (!window.confirm(`${revoke ? "撤销" : "保存"} ${props.username} 的 ${props.role} 授权`)) return;
  const writePath = path + (revoke ? "/revoke" : "");
  const writeMethod: "PATCH" | "POST" = revoke ? "POST" : "PATCH";
  draft(() => ({ path: writePath, method: writeMethod, version: target.version, body }));
  if (!adopt()) return;
  await send();
}
</script>

<template>
  <section class="fams-card fams-stack" aria-label="数据范围和敏感字段授权">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">数据范围和敏感字段授权 · {{ role }}</h3>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
    <div class="fams-panel-actions">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="actionLocked" @click="load">读取当前授权</button>
      <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="send">原请求重试授权</button>
    </div>
    <template v-if="current">
      <p>当前状态 {{ current.status }} · 版本 {{ current.version }}</p>
      <fieldset class="fams-card fams-stack" :disabled="actionLocked">
        <legend class="fams-panel-title">一般数据范围</legend>
        <template v-if="scopeEditorShown">
          <div class="fams-panel-actions">
            <button v-for="row in scopeButtons" :key="row.scopeType" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="row.disabled" @click="addScope(row.scopeType)">{{ row.label }}</button>
          </div>
          <article v-for="scope in scopeRows" :key="scope.scopeType" class="fams-card fams-stack">
            <h4 class="fams-panel-title">{{ scope.titleText }}</h4>
            <div class="fams-panel-actions">
              <button class="fams-button" data-kind="danger" data-size="sm" type="button" @click="removeScope(scope.scopeType)">移除{{ scope.titleText }}</button>
            </div>
            <template v-if="scope.specified">
              <label class="fams-field"><span class="fams-field-label">资料编号</span><input class="fams-control" v-model="referenceId" inputmode="numeric" /></label>
              <div class="fams-panel-actions">
                <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="addReference(scope.scopeType)">读取并添加有效资料</button>
              </div>
              <ul><li v-for="row in scope.resourceRows" :key="row.id">{{ row.nameText }}
                <button class="fams-button" data-kind="danger" data-size="sm" type="button" @click="removeReference(scope.scopeType, row.id)">移除资料</button>
              </li></ul>
            </template>
          </article>
          <fieldset class="fams-card fams-stack"><legend class="fams-panel-title">敏感字段和独立动作</legend>
            <label v-for="(label, field) in fieldNames" :key="field" class="fams-selection">
              <input v-model="fields" type="checkbox" :value="field" />{{ label }}
            </label>
          </fieldset>
        </template>
        <label class="fams-field fams-field-wide"><span class="fams-field-label">授权变更原因</span><textarea class="fams-control" v-model="reason" maxlength="500" /></label>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="primary" type="button" :disabled="saveDisabled" @click="save(false)">保存授权</button>
          <button class="fams-button" data-kind="danger" type="button" :disabled="revokeDisabled" @click="save(true)">撤销授权</button>
        </div>
      </fieldset>
    </template>
  </section>
</template>
