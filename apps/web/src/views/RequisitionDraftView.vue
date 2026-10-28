<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import RequisitionDemandForm from "./RequisitionDemandForm.vue";
import { loadRequisitionCatalogs, requisitionMessage, requisitionRequest } from "@/requisition/api";
import type { Requisition, RequisitionChoice, RequisitionDemand, RequisitionDemandInput }
  from "../../../../packages/api-types/src/requisition";

const props = defineProps<{ id?: string }>();
const router = useRouter();
const catalogs = ref<Record<string, RequisitionChoice[]>>({});
const demands = ref<RequisitionDemand[] | null>(null);
const requestType = ref<"REQUISITION" | "BORROW">("REQUISITION");
const expectedReturnDate = ref<string | null>(null);
const canShared = ref(false); const selfEmployee = ref(""); const selfOrg = ref("");
const version = ref(0); const ready = ref(false);
const busy = ref(false); const error = ref("");
let pending: { path: string; method: string; key: string; version: number; body?: unknown } | null = null;
const retryable = ref(false);

async function send(path: string, body?: unknown, method = "POST", expectedVersion = 0) {
  if (busy.value) return;
  if (pending && pending.path !== path) { error.value = "上次操作结果尚未确认，请先重试或刷新。"; return; }
  pending ??= { path, method, key: crypto.randomUUID(), version: expectedVersion,
    body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const result = await requisitionRequest<Requisition>(pending.path, pending.method, pending);
    pending = null; retryable.value = false;
    await router.push(`/requisitions/${encodeURIComponent(result.id)}?saved=1`);
  } catch (e) { error.value = requisitionMessage(e); retryable.value = true; }
  finally { busy.value = false; }
}
function submit(payload: { demands: RequisitionDemandInput[]; requestType: "REQUISITION" | "BORROW";
  expectedReturnDate: string | null }) {
  void send(props.id ? "/" + props.id : "", payload, props.id ? "PATCH" : "POST", version.value);
}
async function retry() { if (pending) await send(pending.path); }
onMounted(async () => {
  busy.value = true;
  try {
    if (props.id && !/^[1-9][0-9]{0,18}$/.test(props.id)) { error.value = "没有此申请或操作的当前权限。"; return; }
    const loaded = await loadRequisitionCatalogs();
    catalogs.value = loaded.catalogs; canShared.value = loaded.canShared;
    selfEmployee.value = loaded.employeeId; selfOrg.value = loaded.organizationId;
    if (props.id) {
      const result = await requisitionRequest<Requisition>("/" + props.id);
      version.value = result.version; demands.value = result.demands;
      requestType.value = result.requestType ?? "REQUISITION"; expectedReturnDate.value = result.expectedReturnDate ?? null;
    }
    ready.value = true;
  } catch (e) { error.value = requisitionMessage(e); } finally { busy.value = false; }
});
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/requisitions">返回申请列表</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <RequisitionDemandForm v-if="ready" :mode="id ? 'revise' : 'new'" :catalogs="catalogs" :can-shared="canShared"
      :self-employee-id="selfEmployee" :self-organization-id="selfOrg" :demands="demands" :request-type="requestType"
      :expected-return-date="expectedReturnDate" :disabled="busy || retryable" @submit="submit" />
    <div v-if="retryable" class="fams-form-actions">
      <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="retry">重试上次操作</button>
    </div>
  </AppChrome>
</template>
