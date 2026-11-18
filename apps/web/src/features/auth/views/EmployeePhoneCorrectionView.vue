<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { masterDataRequest, MasterDataApiError, type MasterDataMutation } from "@/features/masterdata/api";

interface Employee { id: string; employeeNo: string; displayName: string; status: string; version: number }
const route = useRoute();
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const employeeId = ref("");
const employee = ref<Employee | null>(null);
const phone = ref("");
const method = ref("");
const reason = ref("");
const busy = ref(false);
const error = ref("");
const notice = ref("");
const pending = ref<MasterDataMutation | null>(null);
let generation = 0;

function problem(cause: unknown): string {
  if (cause instanceof MasterDataApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效。";
    if (cause.status === 409) return "员工版本或请求已变化，请重新读取员工资料并核对。";
    if (cause.status === 400 || cause.status === 404) return "请核对员工编号、新号码、核验方式和纠正原因。";
  }
  return "纠正结果尚未确认，请使用原请求重试。";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || pending.value) return;
  if (!/^[1-9]\d*$/.test(employeeId.value)) { error.value = "请输入有效员工编号。"; return; }
  const run = ++generation;
  busy.value = true; error.value = ""; notice.value = ""; employee.value = null;
  phone.value = ""; method.value = ""; reason.value = "";
  try {
    const result = await masterDataRequest<Employee>("/employees/" + employeeId.value);
    if (run === generation) employee.value = result;
  } catch (cause) {
    if (run === generation) error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
}

async function submit(): Promise<void> {
  const selected = employee.value;
  if (!identity.value || !selected || busy.value) return;
  if (!pending.value) {
    if (!/^1[3-9]\d{9}$/.test(phone.value.trim()) || !method.value.trim() || !reason.value.trim()) {
      error.value = "请填写有效新号码、身份核验方式及纠正原因。"; return;
    }
    if (!window.confirm(`确认纠正 ${selected.displayName}（${selected.employeeNo}）的企业登记手机号？`)) return;
    pending.value = { path: `/employees/${selected.id}/phone-correction`, method: "POST",
      key: crypto.randomUUID(), version: selected.version,
      body: { phone: phone.value.trim(), verificationMethod: method.value.trim(), reason: reason.value.trim() } };
  }
  const operation = pending.value;
  const run = generation;
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<{ employeeId: string; version: number }>(operation.path, operation);
    if (run !== generation) return;
    employee.value = { ...selected, version: result.version };
    phone.value = ""; method.value = ""; reason.value = ""; pending.value = null;
    notice.value = `手机号纠正已保存，员工编号 ${result.employeeId}，结果版本 ${result.version}。`;
  } catch (cause) {
    if (run !== generation) return;
    if (cause instanceof MasterDataApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
}

watch([identity, () => route.query.employeeId], () => {
  generation++; employee.value = null; phone.value = ""; method.value = ""; reason.value = "";
  error.value = ""; notice.value = ""; busy.value = false; pending.value = null;
  employeeId.value = typeof route.query.employeeId === "string" ? route.query.employeeId : "";
  if (employeeId.value) void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <h1>员工手机号纠正</h1>
    <p v-if="!identity" role="alert">只有系统管理员可以纠正员工登记手机号。</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <label>员工编号<input v-model="employeeId" inputmode="numeric" :disabled="busy || !!pending"
        @input="employee = null; phone = ''; method = ''; reason = ''" /></label>
      <button :disabled="busy || !!pending" @click="load">读取员工资料</button>
      <router-link to="/identity">返回组织与人员</router-link>
      <section v-if="employee" aria-label="核验纠正员工">
        <h2>{{ employee.displayName }} · {{ employee.employeeNo }}</h2>
        <p>员工编号 {{ employee.id }} · 状态 {{ employee.status }} · 资料版本 {{ employee.version }}</p>
        <p>请先完成身份核验。提交会更新登记号码和员工资料版本，并使旧短信核验请求失效。</p>
        <form @submit.prevent="submit">
          <fieldset :disabled="busy || !!pending">
            <label>新登记手机号<input v-model="phone" type="tel" pattern="1[3-9][0-9]{9}" maxlength="11" autocomplete="off" required /></label>
            <label>身份核验方式<input v-model="method" maxlength="128" required /></label>
            <label>纠正原因<textarea v-model="reason" maxlength="500" required /></label>
          </fieldset>
          <button type="submit" :disabled="busy">{{ pending ? "原请求重试纠正" : "确认纠正手机号" }}</button>
        </form>
      </section>
    </template>
  </AppChrome>
</template>
