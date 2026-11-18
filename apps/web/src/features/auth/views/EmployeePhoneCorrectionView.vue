<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { masterDataRequest, MasterDataApiError, type MasterDataMutation } from "@/features/masterdata/api";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";

interface Employee { id: string; employeeNo: string; displayName: string; status: string; version: number }
/** 写意图：路径、方法与版本沿用功能线形状；幂等键只由提交底座持有，重试不换键。 */
type PhoneIntent = Omit<MasterDataMutation, "key">;
const EMPLOYEE_ID = /^[1-9]\d*$/;
const PHONE = /^1[3-9]\d{9}$/;

const route = useRoute();
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const employeeId = shallowRef("");
const employee = ref<Employee | null>(null);
const phone = shallowRef("");
const method = shallowRef("");
const reason = shallowRef("");
const busy = shallowRef(false);
const error = shallowRef("");
const notice = shallowRef("");
/** 确定性失败（本域错误类 4xx）清键允许重新起草；响应丢失保留原键走重试。 */
const deterministic = (cause: unknown): boolean => cause instanceof MasterDataApiError
  && cause.status >= 400 && cause.status < 500;
const { pending, prepare: draft, confirm: adopt, settle, fail, reset } =
  useConfirmedSubmit<PhoneIntent>({ failure: deterministic });
const liveness = useRequestLiveness(() => [identity.value]);
const hasPending = computed(() => pending.value !== null);
const editorDisabled = computed(() => busy.value || hasPending.value);
const submitDisabled = computed(() => busy.value);
const submitLabel = computed(() => hasPending.value ? "原请求重试纠正" : "确认纠正手机号");

function problem(cause: unknown): string {
  if (cause instanceof MasterDataApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效。";
    if (cause.status === 409) return "员工版本或请求已变化，请重新读取员工资料并核对。";
    if (cause.status === 400 || cause.status === 404) return "请核对员工编号、新号码、核验方式和纠正原因。";
  }
  return "纠正结果尚未确认，请使用原请求重试。";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || hasPending.value) return;
  if (!EMPLOYEE_ID.test(employeeId.value)) { error.value = "请输入有效员工编号。"; return; }
  const turn = liveness.begin();
  busy.value = true; error.value = ""; notice.value = ""; employee.value = null;
  phone.value = ""; method.value = ""; reason.value = "";
  try {
    const result = await masterDataRequest<Employee>("/employees/" + employeeId.value);
    if (liveness.isCurrent(turn)) employee.value = result;
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

function clearTarget(): void {
  employee.value = null; phone.value = ""; method.value = ""; reason.value = "";
}

async function submit(): Promise<void> {
  const target = employee.value;
  if (!identity.value || !target || busy.value) return;
  let operation = pending.value;
  if (!operation) {
    const newPhone = phone.value.trim();
    const verificationMethod = method.value.trim();
    const correctionReason = reason.value.trim();
    if (!PHONE.test(newPhone) || !verificationMethod || !correctionReason) {
      error.value = "请填写有效新号码、身份核验方式及纠正原因。"; return;
    }
    if (!window.confirm(`确认纠正 ${target.displayName}（${target.employeeNo}）的企业登记手机号？`)) return;
    draft(() => ({ path: `/employees/${target.id}/phone-correction`, method: "POST",
      version: target.version,
      body: { phone: newPhone, verificationMethod, reason: correctionReason } }));
    operation = adopt();
  }
  if (!operation) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<{ employeeId: string; version: number }>(
      operation.payload.path, { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    employee.value = { ...target, version: result.version };
    phone.value = ""; method.value = ""; reason.value = "";
    settle();
    notice.value = `手机号纠正已保存，员工编号 ${result.employeeId}，结果版本 ${result.version}。`;
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    fail(cause);
    error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

watch([identity, () => route.query.employeeId], () => {
  liveness.invalidate(); clearTarget();
  error.value = ""; notice.value = ""; busy.value = false; reset();
  employeeId.value = typeof route.query.employeeId === "string" ? route.query.employeeId : "";
  if (employeeId.value) void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="error" role="alert"><strong>只有系统管理员可以纠正员工登记手机号。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">员工编号</span><input class="fams-control" v-model="employeeId" inputmode="numeric" :disabled="editorDisabled"
          @input="clearTarget" /></label>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" type="button" :disabled="editorDisabled" @click="load">读取员工资料</button>
        <router-link class="fams-button" data-kind="link" to="/identity">返回组织与人员</router-link>
      </div>
      <section v-if="employee" class="fams-panel fams-stack" aria-label="核验纠正员工">
        <div class="fams-panel-head">
          <h2 class="fams-panel-title">{{ employee.displayName }} · {{ employee.employeeNo }}</h2>
        </div>
        <p>员工编号 {{ employee.id }} · 状态 {{ employee.status }} · 资料版本 {{ employee.version }}</p>
        <p>请先完成身份核验。提交会更新登记号码和员工资料版本，并使旧短信核验请求失效。</p>
        <form @submit.prevent="submit">
          <fieldset class="fams-field-grid" :disabled="editorDisabled">
            <label class="fams-field"><span class="fams-field-label">新登记手机号</span><input class="fams-control" v-model="phone" type="tel" pattern="1[3-9][0-9]{9}" maxlength="11" autocomplete="off" required /></label>
            <label class="fams-field"><span class="fams-field-label">身份核验方式</span><input class="fams-control" v-model="method" maxlength="128" required /></label>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">纠正原因</span><textarea class="fams-control" v-model="reason" maxlength="500" required /></label>
          </fieldset>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit" :disabled="submitDisabled">{{ submitLabel }}</button></div>
        </form>
      </section>
    </template>
  </AppChrome>
</template>
