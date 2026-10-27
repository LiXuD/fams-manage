<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { ReviewApiError, reviewRequest } from "@/inventory-review/api";
interface Device { deviceId: string; bindingId: string | null; declarationId?: string; current: boolean; reason: string; declaredAt?: string; unresolvedCount?: number }
interface Participant { accountId: string; name: string; current: boolean; reason: string; devices: Device[] }
const props = defineProps<{ taskId: string }>();
const session = useSessionStore();
const owner = computed(() => JSON.stringify([props.taskId, session.user?.id, session.user?.roles, session.csrfToken]));
const rows = ref<Participant[]>([]), total = ref(0), page = ref(1), busy = ref(false), error = ref("");
const reasons: Record<string, string> = { DECLARED: "声明仍有效", PARTICIPATION_REVOKED: "参与关系已撤销，仍待补证",
  IDENTITY_DISABLED: "身份已失效，仍待补证", BINDING_MISSING: "当前没有有效绑定", NO_DEVICE_DECLARATION: "尚无设备声明",
  DEVICE_CONFIRMATION_MISSING: "部分设备待确认或补证", UNBOUND_OBSERVATION: "原证据来自无绑定通道，待核实",
  BINDING_CHANGED: "原绑定已失效，待补证", NO_DECLARATION: "尚未声明", ASSIGNMENT_CHANGED: "分配已变化，需重新确认",
  NEW_OBSERVATION: "声明后有新证据，需重新确认", LOCAL_EVIDENCE_MISSING: "本机报告缺失或拒绝，待补证" };
let generation = 0, live = true;
const current = (epoch: number, identity: string) => live && generation === epoch && owner.value === identity;
async function load() {
  const epoch = ++generation, identity = owner.value; busy.value = true; error.value = ""; rows.value = [];
  try {
    const result = await reviewRequest<{ items: Participant[]; total: number }>(`/inventory-synchronization/tasks/${props.taskId}?page=${page.value}&pageSize=20`);
    if (current(epoch, identity)) { rows.value = result.items; total.value = result.total; }
  } catch (cause) {
    if (current(epoch, identity)) {
      total.value = 0;
      error.value = cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)
        ? "身份、权限或任务已失效，已隐藏原资料。" : "同步声明读取未完成，请重试。";
    }
  } finally { if (current(epoch, identity)) busy.value = false; }
}
function change(delta: number) { page.value += delta; void load(); }
watch(owner, () => { generation++; rows.value = []; total.value = 0; page.value = 1; void load(); }, { immediate: true });
onBeforeUnmount(() => { live = false; generation++; rows.value = []; });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="执行人同步声明">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">执行人同步声明</h3>
        <p class="fams-panel-subtitle">仅展示执行人的设备声明，不能证明未知离线设备为空。失效身份、旧绑定及缺失证据仍需补证，不会自动排除。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">刷新同步声明</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <article v-for="row in rows" :key="row.accountId" class="fams-card fams-stack">
      <h4 class="fams-panel-title">{{ row.name }} · {{ reasons[row.reason] || '待核实' }}</h4>
      <ul class="fams-timeline">
        <li v-for="device in row.devices" :key="`${device.bindingId}:${device.deviceId}`">设备 {{ device.deviceId }} · {{ reasons[device.reason] || '待核实' }}
          <small v-if="device.declaredAt || device.unresolvedCount"><template v-if="device.declaredAt"> · 声明时间 {{ device.declaredAt }}</template><template v-if="device.unresolvedCount"> · 缺失提示 {{ device.unresolvedCount }}</template></small></li>
      </ul>
    </article>
    <div class="fams-pagination">
      <span class="fams-pagination-summary">共 {{ total }} 人</span>
      <div class="fams-pagination-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || page === 1" @click="change(-1)">上页执行人</button>
        <span class="fams-pagination-page">第 {{ page }} 页</span>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || page * 20 >= total" @click="change(1)">下页执行人</button>
      </div>
    </div>
  </section>
</template>
