<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit, type Submission } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { masterDataRequest, MasterDataApiError, type MasterDataMutation } from "@/features/masterdata/api";

interface Reference { id: string; version: number }
interface IdentityRow {
  id: string; version: number; status: "ACTIVE" | "DISABLED";
  code?: string; name?: string; parentOrganization?: Reference;
  employeeNo?: string; displayName?: string; organization?: Reference;
}
/** 保存意图只带路径、方法、版本与请求体；幂等键一律由 useConfirmedSubmit 底座生成，重试不换键。 */
type IdentityIntent = Pick<MasterDataMutation, "path" | "method" | "version" | "body">;
interface IdentityRowView extends IdentityRow { codeText?: string; nameText?: string; orgText: string;
  phoneTo: { name: string; query: { employeeId: string } } }

const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const kind = shallowRef<"organizations" | "employees">("organizations");
const rows = ref<IdentityRow[]>([]);
const page = shallowRef(1);
const total = shallowRef(0);
const selected = shallowRef<IdentityRow | null>(null);
const formOpen = shallowRef(false);
const code = shallowRef("");
const name = shallowRef("");
const organizationId = shallowRef("");
const phone = shallowRef("");
const reason = shallowRef("");
const busy = shallowRef(false);
const error = shallowRef("");
const notice = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value, kind.value]);
/** 确定性失败判据取本域 MasterDataApiError 的 4xx 区间：清意在途意图，允许重新起草。 */
const deterministic = (cause: unknown): boolean => cause instanceof MasterDataApiError
  && cause.status >= 400 && cause.status < 500;
const { pending: pendingSave, prepare: draftSave, confirm: adoptSave, settle: settleSave,
  fail: failSave, reset: resetSave } = useConfirmedSubmit<IdentityIntent>({ failure: deterministic });

const employeesMode = computed(() => kind.value === "employees");
const codeColumnLabel = computed(() => employeesMode.value ? "工号" : "编码");
const orgColumnLabel = computed(() => employeesMode.value ? "所属组织" : "上级组织");
const createLabel = computed(() => employeesMode.value ? "新建员工" : "新建组织");
const formTitle = computed(() => `${selected.value ? "维护" : "新建"}${employeesMode.value ? "员工" : "组织"}`);
const codeFieldLabel = computed(() => employeesMode.value ? "工号" : "组织编码");
const orgFieldLabel = computed(() => employeesMode.value ? "所属组织编号" : "上级组织编号");
const orgFieldRequired = computed(() => employeesMode.value);
const orgHintText = computed(() => "组织编号可在组织列表中查询。"
  + (kind.value === "organizations" ? selected.value ? "上级留空保留原归属。" : "上级留空创建根组织。" : ""));
const showPhoneField = computed(() => employeesMode.value && !selected.value);
const statusSectionVisible = computed(() => !!selected.value
  && (employeesMode.value || selected.value.status === "ACTIVE"));
const statusActionDisabled = computed(() => !reason.value.trim());
const statusActionLabel = computed(() => selected.value?.status === "ACTIVE" ? "停用" : "启用");
const statusActionKind = computed(() => selected.value?.status === "ACTIVE" ? "danger" : "secondary");
const canCorrectPhone = computed(() => employeesMode.value && pendingSave.value === null && !busy.value);
const controlsDisabled = computed(() => busy.value || pendingSave.value !== null);
const retryDisabled = computed(() => busy.value);
const prevDisabled = computed(() => page.value <= 1 || busy.value || pendingSave.value !== null);
const nextDisabled = computed(() => page.value * 20 >= total.value || busy.value || pendingSave.value !== null);
const identityRows = computed<IdentityRowView[]>(() => rows.value.map(row => ({
  ...row,
  codeText: row.code ?? row.employeeNo,
  nameText: row.name ?? row.displayName,
  orgText: (row.organization ?? row.parentOrganization)?.id ?? "根组织",
  phoneTo: { name: "employee-phone-corrections", query: { employeeId: row.id } },
})));

function problem(cause: unknown): string {
  if (cause instanceof MasterDataApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效。";
    if (cause.status === 409) return "资料版本、编码或依赖关系冲突，请刷新核对；有有效下级或成员的组织不能停用。";
    if (cause.status === 400 || cause.status === 404) return "请核对必填资料和组织编号。";
  }
  return "操作结果尚未确认，请使用原请求重试。";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || pendingSave.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<{ items: IdentityRow[]; total: number }>(
      `/${kind.value}?page=${page.value}&pageSize=20`);
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

function open(row: IdentityRow | null): void {
  selected.value = row;
  code.value = row?.code ?? row?.employeeNo ?? "";
  name.value = row?.name ?? row?.displayName ?? "";
  organizationId.value = (row?.parentOrganization ?? row?.organization)?.id ?? "";
  phone.value = ""; reason.value = ""; error.value = ""; notice.value = "";
  formOpen.value = true;
}

async function execute(): Promise<void> {
  if (!identity.value || !pendingSave.value || busy.value) return;
  const operation = pendingSave.value;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<IdentityRow>(operation.payload.path,
      { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settleSave(); selected.value = null; formOpen.value = false; phone.value = "";
    notice.value = `${result.name ?? result.displayName} 已保存，状态 ${result.status}，版本 ${result.version}。`;
    busy.value = false;
    await load();
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    failSave(cause);
    error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function save(): Promise<void> {
  if (!identity.value || busy.value || pendingSave.value || !code.value.trim() || !name.value.trim()) return;
  const row = selected.value;
  const employee = kind.value === "employees";
  const turn = liveness.begin();
  const body: Record<string, unknown> = employee
    ? { employeeNo: code.value.trim(), displayName: name.value.trim() }
    : { code: code.value.trim(), name: name.value.trim() };
  busy.value = true; error.value = "";
  let adopted: Submission<IdentityIntent> | null = null;
  try {
    const oldReference = row?.organization ?? row?.parentOrganization;
    let referenceName = "";
    if (organizationId.value && organizationId.value !== oldReference?.id || employee && !row) {
      if (!/^[1-9]\d*$/.test(organizationId.value)) throw new MasterDataApiError(400, "ORGANIZATION_REQUIRED");
      const reference = await masterDataRequest<IdentityRow>("/organizations/" + organizationId.value);
      if (!liveness.isCurrent(turn)) return;
      if (reference.status !== "ACTIVE") throw new MasterDataApiError(409, "ORGANIZATION_DISABLED");
      body[employee ? "organization" : "parentOrganization"] = { id: reference.id, version: reference.version };
      referenceName = reference.name ?? reference.id;
    }
    if (!row && employee && phone.value.trim()) body.phone = phone.value.trim();
    if (!window.confirm(`${row ? "更新" : "新建"}${employee ? "员工" : "组织"} ${name.value.trim()}${referenceName ? " · " + referenceName : ""}`)) return;
    draftSave(() => ({ path: `/${kind.value}${row ? "/" + row.id : ""}`,
      method: row ? "PATCH" : "POST", version: row?.version ?? 0, body }));
    adopted = adoptSave();
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
  if (adopted && liveness.isCurrent(turn)) await execute();
}

async function changeStatus(): Promise<void> {
  const row = selected.value;
  if (!identity.value || !row || busy.value || pendingSave.value || !reason.value.trim()) return;
  const action = row.status === "ACTIVE" ? "disable" : "enable";
  if (kind.value === "organizations" && action === "enable") return;
  if (!window.confirm(`${action === "disable" ? "停用" : "启用"} ${row.name ?? row.displayName}\n${reason.value.trim()}`)) return;
  draftSave(() => ({ path: `/${kind.value}/${row.id}/${action}`, method: "POST",
    version: row.version, body: { reason: reason.value.trim() } }));
  adoptSave();
  await execute();
}

async function move(delta: number): Promise<void> {
  selected.value = null; formOpen.value = false; page.value += delta;
  await load();
}
watch([identity, kind], () => {
  liveness.invalidate();
  rows.value = []; total.value = 0; page.value = 1; selected.value = null;
  formOpen.value = false; code.value = ""; name.value = ""; organizationId.value = ""; phone.value = "";
  reason.value = ""; busy.value = false; error.value = ""; notice.value = ""; resetSave();
  void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="noaccess" role="alert"><strong>只有系统管理员可以维护组织与人员。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <section class="fams-panel fams-stack">
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="controlsDisabled" @click="kind = 'organizations'">组织</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="controlsDisabled" @click="kind = 'employees'">人员</button>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="controlsDisabled" @click="load">刷新列表</button>
          <button class="fams-button" data-kind="primary" data-size="sm" type="button" :disabled="controlsDisabled" @click="open(null)">{{ createLabel }}</button>
          <button v-if="pendingSave" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="retryDisabled" @click="execute">原请求重试保存</button>
        </div>
        <div class="fams-table-scroll">
          <table class="fams-table"><thead><tr><th scope="col" data-column-kind="identity">编号</th><th scope="col">{{ codeColumnLabel }}</th>
            <th scope="col">名称</th><th scope="col">{{ orgColumnLabel }}</th><th scope="col">状态</th><th scope="col">版本</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody><tr v-for="row in identityRows" :key="row.id">
            <td data-column-kind="identity">{{ row.id }}</td><td>{{ row.codeText }}</td><td>{{ row.nameText }}</td>
            <td>{{ row.orgText }}</td>
            <td>{{ row.status }}</td><td>{{ row.version }}</td>
            <td data-column-kind="action"><button class="fams-button" data-kind="secondary" data-size="sm"
              type="button" :disabled="controlsDisabled" @click="open(row)">维护</button>
              <router-link v-if="canCorrectPhone" class="fams-button" data-kind="link" data-size="sm"
                :to="row.phoneTo">纠正手机号</router-link>
            </td>
          </tr></tbody>
        </table>
        </div>
        <div class="fams-pagination">
          <span class="fams-pagination-summary">第 {{ page }} 页，共 {{ total }} 条</span>
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="move(-1)">上一页</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="move(1)">下一页</button>
          </div>
        </div>
      </section>
      <section v-if="formOpen" class="fams-panel fams-stack" aria-label="维护资料">
        <div class="fams-panel-head"><h2 class="fams-panel-title">{{ formTitle }}</h2></div>
        <form @submit.prevent="save"><fieldset :disabled="controlsDisabled">
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">{{ codeFieldLabel }}</span><input class="fams-control" v-model="code" maxlength="64" required /></label>
            <label class="fams-field"><span class="fams-field-label">名称</span><input class="fams-control" v-model="name" maxlength="128" required /></label>
            <label class="fams-field"><span class="fams-field-label">{{ orgFieldLabel }}</span>
              <input class="fams-control" v-model="organizationId" inputmode="numeric" :required="orgFieldRequired" />
            </label>
            <p class="fams-field-hint fams-field-wide">{{ orgHintText }}</p>
            <label v-if="showPhoneField" class="fams-field"><span class="fams-field-label">企业登记手机号</span>
              <input class="fams-control" v-model="phone" type="tel" maxlength="32" autocomplete="off" />
            </label>
          </div>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit">保存资料</button></div>
        </fieldset></form>
        <fieldset v-if="statusSectionVisible" :disabled="controlsDisabled">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">状态变更原因</span><textarea class="fams-control" v-model="reason" maxlength="500" /></label>
          <div class="fams-form-actions"><button class="fams-button" type="button" :data-kind="statusActionKind"
            :disabled="statusActionDisabled" @click="changeStatus">{{ statusActionLabel }}</button></div>
        </fieldset>
      </section>
    </template>
  </AppChrome>
</template>
