<script setup lang="ts">
import { ref } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { formatProxyTime } from "@/features/exception/time";

interface Followup {
  findings: string;
  nextFollowupAt: string;
  recordedAt: string;
}

const props = defineProps<{ reminderId: string }>();
const emit = defineEmits<{ saved: [] }>();
const session = useSessionStore();
const open = ref(false);
const busy = ref(false);
const error = ref("");
const findings = ref("");
const nextTime = ref("");
const latest = ref<Followup | null>(null);
const pending = ref<{ key: string; body: string } | null>(null);
const endpoint = () => `/api/v1/messages/reminders/${props.reminderId}/follow-up`;

function responseError(status: number) {
  if (status === 401) return "登录已失效，请重新登录。";
  if (status === 403) return "只有资产管理员可以登记核查。";
  if (status === 404 || status === 409) return "提醒已变化，请刷新异常清单后重试。";
  if (status === 400) return "请核对核查内容，并选择将来的跟进时间。";
  return "暂时无法读取核查记录，请重试。";
}

async function show() {
  open.value = true;
  if (pending.value) return;
  busy.value = true;
  error.value = "";
  try {
    const reply = await fetch(endpoint(), { credentials: "include" });
    if (reply.ok) latest.value = (await reply.json() as { latest: Followup | null }).latest;
    else error.value = responseError(reply.status);
  } catch {
    error.value = "暂时无法读取核查记录，请重试。";
  } finally {
    busy.value = false;
  }
}

async function save() {
  const csrf = session.csrfToken;
  if (!csrf) {
    error.value = responseError(401);
    return;
  }
  if (!pending.value) {
    const next = new Date(nextTime.value);
    if (!findings.value.trim() || !(next.getTime() > Date.now())) {
      error.value = responseError(400);
      return;
    }
    pending.value = { key: crypto.randomUUID(),
      body: JSON.stringify({ findings: findings.value.trim(), nextFollowupAt: next.toISOString() }) };
  }
  const request = pending.value;
  busy.value = true;
  error.value = "";
  try {
    const reply = await fetch(endpoint(), {
      credentials: "include", method: "POST", body: request.body,
      headers: { "Content-Type": "application/json", "Idempotency-Key": request.key, "X-CSRF-Token": csrf },
    });
    if (reply.ok) {
      latest.value = await reply.json() as Followup;
      pending.value = null;
      findings.value = "";
      nextTime.value = "";
      emit("saved");
    } else if (reply.status < 500) {
      pending.value = null;
      error.value = responseError(reply.status);
    } else {
      error.value = "保存结果尚未确认，请重试原请求。";
    }
  } catch {
    error.value = "保存结果尚未确认，请重试原请求。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <button v-if="!open" type="button" @click="show">登记核查</button>
  <form v-else aria-label="核查记录" @submit.prevent="save">
    <div v-if="latest">
      <p>最近核查 {{ formatProxyTime(latest.recordedAt) }} · 下次跟进 {{ formatProxyTime(latest.nextFollowupAt) }}</p>
      <p>{{ latest.findings }}</p>
    </div>
    <label>核查内容<textarea v-model="findings" maxlength="1000" required :disabled="busy || !!pending" /></label>
    <label>下次跟进时间<input v-model="nextTime" type="datetime-local" required :disabled="busy || !!pending" /></label>
    <p v-if="error" role="alert">{{ error }}</p>
    <div>
      <button :disabled="busy" type="submit">{{ pending ? "重试保存" : "保存核查" }}</button>
      <button :disabled="busy" type="button" @click="open = false">关闭</button>
    </div>
  </form>
</template>

<style scoped>
form, label { display: grid; gap: 0.5rem; }
form { margin-block: 0.75rem; }
textarea { min-height: 4rem; }
form button + button { margin-inline-start: 0.5rem; }
</style>
