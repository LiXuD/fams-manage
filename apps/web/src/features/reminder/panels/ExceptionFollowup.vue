<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { BusinessApiError, businessRequest } from "@/api/business";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { formatProxyTime } from "@/features/exception/time";

interface Followup {
  findings: string;
  nextFollowupAt: string;
  recordedAt: string;
}
/** 核查登记的在途意图：核查内容与跟进时间在起草时定格，幂等键只由提交底座持有。 */
interface FollowupIntent { findings: string; nextFollowupAt: string }

const props = defineProps<{ reminderId: string }>();
const emit = defineEmits<{ saved: [] }>();
const session = useSessionStore();
const open = shallowRef(false);
const busy = shallowRef(false);
const error = shallowRef("");
const findings = shallowRef("");
const nextTime = shallowRef("");
const latest = ref<Followup | null>(null);
const liveness = useRequestLiveness(() => [props.reminderId]);
/** 确定性判据沿用改造前的「状态码 < 500」口径：5xx 与网络失败保留在途意图，重试不换键。 */
const { pending, prepare, confirm: adopt, settle, fail } = useConfirmedSubmit<FollowupIntent>({
  failure: (cause) => cause instanceof BusinessApiError && cause.status < 500,
});

const endpoint = () => `/messages/reminders/${props.reminderId}/follow-up`;

/** 状态码到中文文案的映射逐字保留自改造前，读写两条路径共用。 */
function responseError(status: number) {
  if (status === 401) return "登录已失效，请重新登录。";
  if (status === 403) return "只有资产管理员可以登记核查。";
  if (status === 404 || status === 409) return "提醒已变化，请刷新异常清单后重试。";
  if (status === 400) return "请核对核查内容，并选择将来的跟进时间。";
  return "暂时无法读取核查记录，请重试。";
}

const latestText = computed(() => latest.value ? {
  headText: `最近核查 ${formatProxyTime(latest.value.recordedAt)} · 下次跟进 ${formatProxyTime(latest.value.nextFollowupAt)}`,
  findingsText: latest.value.findings,
} : null);
const formDisabled = computed(() => busy.value || pending.value !== null);
const submitText = computed(() => (pending.value ? "重试保存" : "保存核查"));

async function show() {
  open.value = true;
  if (pending.value) return;
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  try {
    const reply = await businessRequest<{ latest: Followup | null }>(endpoint());
    if (liveness.isCurrent(turn)) latest.value = reply.latest;
  } catch (cause) {
    if (liveness.isCurrent(turn)) {
      error.value = cause instanceof BusinessApiError ? responseError(cause.status) : "暂时无法读取核查记录，请重试。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function save() {
  const csrf = session.csrfToken;
  if (!csrf) {
    error.value = responseError(401);
    return;
  }
  let operation = pending.value;
  if (!operation) {
    const next = new Date(nextTime.value);
    if (!findings.value.trim() || !(next.getTime() > Date.now())) {
      error.value = responseError(400);
      return;
    }
    prepare(() => ({ findings: findings.value.trim(), nextFollowupAt: next.toISOString() }));
    operation = adopt();
  }
  if (!operation) return;
  busy.value = true;
  error.value = "";
  try {
    const saved = await businessRequest<Followup>(endpoint(),
      { path: endpoint(), key: operation.key, version: 0, body: operation.payload });
    latest.value = saved;
    settle();
    findings.value = "";
    nextTime.value = "";
    emit("saved");
  } catch (cause) {
    if (cause instanceof BusinessApiError && fail(cause)) error.value = responseError(cause.status);
    else error.value = "保存结果尚未确认，请重试原请求。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <button v-if="!open" class="fams-button" data-kind="secondary" type="button" @click="show">登记核查</button>
  <form v-else class="fams-panel fams-stack" aria-label="核查记录" @submit.prevent="save">
    <div v-if="latestText" class="fams-card fams-stack">
      <p>{{ latestText.headText }}</p>
      <p>{{ latestText.findingsText }}</p>
    </div>
    <label class="fams-field"><span class="fams-field-label">核查内容</span>
      <textarea class="fams-control" v-model="findings" maxlength="1000" required :disabled="formDisabled" /></label>
    <label class="fams-field"><span class="fams-field-label">下次跟进时间</span>
      <input class="fams-control" v-model="nextTime" type="datetime-local" required :disabled="formDisabled" /></label>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <div class="fams-form-actions">
      <button class="fams-button" data-kind="primary" :disabled="busy" type="submit">{{ submitText }}</button>
      <button class="fams-button" data-kind="secondary" :disabled="busy" type="button" @click="open = false">关闭</button>
    </div>
  </form>
</template>
