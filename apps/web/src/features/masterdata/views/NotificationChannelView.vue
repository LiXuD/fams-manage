<script setup lang="ts">
import { computed, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { MasterDataApiError, masterDataRequest } from "@/features/masterdata/api";
import { useConfirmedSubmit } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";

interface ChannelConfig {
  smsTemplate: string;
  wechatTemplate: string;
  version: number;
  updatedAt?: string;
  actorAccountId?: string;
  actorUsername?: string;
}
/** 保存意图：只带路径、版本与模板体；幂等键归 useConfirmedSubmit 底座。 */
type SaveIntent = { path: string; version: number; body: { smsTemplate: string; wechatTemplate: string } };
const TEMPLATE = /^[A-Za-z0-9_-]{1,80}$/;

const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const current = shallowRef<ChannelConfig | null>(null);
const smsTemplate = shallowRef("");
const wechatTemplate = shallowRef("");
const ready = shallowRef(false);
const busy = shallowRef(false);
const error = shallowRef("");
const notice = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
const deterministic = (cause: unknown): boolean =>
  cause instanceof MasterDataApiError && cause.status >= 400 && cause.status < 500;
const { pending, prepare: draftSave, confirm: adoptSave, settle, fail, reset } =
  useConfirmedSubmit<SaveIntent>({ identity: () => identity.value, failure: deterministic });

const refreshDisabled = computed(() => busy.value || pending.value !== null);
const saveDisabled = computed(() => busy.value);
const saveLabel = computed(() => pending.value ? "原请求重试保存" : "确认保存新版本");

function problem(cause: unknown): string {
  if (cause instanceof MasterDataApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效，请重新登录。";
    if (cause.status === 409) return "通道配置版本已变化，请刷新并核对当前版本后重试。";
    if (cause.status === 400) return "模板 ID 只能包含字母、数字、下划线和连字符，最长 80 位；留空表示未配置。";
  }
  return "保存结果尚未确认，请使用原请求重试。";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || pending.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = ""; ready.value = false;
  try {
    const result = await masterDataRequest<ChannelConfig>("/notification-channels");
    if (!liveness.isCurrent(turn)) return;
    current.value = result;
    smsTemplate.value = result.smsTemplate;
    wechatTemplate.value = result.wechatTemplate;
    ready.value = true;
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

function template(value: string): string {
  const trimmed = value.trim();
  if (trimmed && !TEMPLATE.test(trimmed)) {
    throw new Error("模板 ID 只能包含字母、数字、下划线和连字符，最长 80 位；留空表示未配置。");
  }
  return trimmed;
}

async function save(): Promise<void> {
  if (!identity.value || busy.value || !current.value) return;
  let operation = pending.value;
  if (!operation) {
    let body: SaveIntent["body"];
    try {
      body = { smsTemplate: template(smsTemplate.value), wechatTemplate: template(wechatTemplate.value) };
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "请核对模板 ID。";
      return;
    }
    error.value = "";
    draftSave(() => ({ path: "/notification-channels", version: current.value!.version, body }));
    operation = adoptSave();
  }
  if (!operation) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<ChannelConfig>(operation.payload.path,
      { ...operation.payload, method: "POST", key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settle();
    notice.value = `版本 ${result.version} 已生效；提醒投递与异常扫描立即读取新版本。`;
    busy.value = false;
    await load();
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    if (fail(cause)) ready.value = false;
    error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

watch(identity, () => {
  liveness.invalidate(); reset();
  current.value = null; smsTemplate.value = ""; wechatTemplate.value = "";
  ready.value = false; busy.value = false; error.value = ""; notice.value = "";
  void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="noaccess" role="alert"><strong>只有系统管理员可以维护通知通道。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <section class="fams-panel fams-stack" aria-label="当前通道配置">
        <div class="fams-panel-head"><h2 class="fams-panel-title">当前配置</h2></div>
        <p>站内消息必达，不受通道配置影响；短信与微信为尽力通道。模板留空表示该通道未配置，不发送外部消息，配置异常清单会持续提示缺口。</p>
        <dl v-if="current" class="fams-facts">
          <dt>配置版本</dt><dd>{{ current.version }}</dd>
          <dt>短信模板</dt><dd>{{ current.smsTemplate || "未配置" }}</dd>
          <dt>微信模板</dt><dd>{{ current.wechatTemplate || "未配置" }}</dd>
          <template v-if="current.updatedAt"><dt>最近生效</dt><dd>{{ current.updatedAt }}<template v-if="current.actorUsername"> · {{ current.actorUsername }}</template></dd></template>
        </dl>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" type="button" :disabled="refreshDisabled" @click="load">刷新配置</button>
        </div>
      </section>
      <form v-if="ready" @submit.prevent="save">
        <fieldset class="fams-stack" :disabled="busy">
          <legend>保存新版本</legend>
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">短信模板 ID</span>
              <input id="sms-template" class="fams-control" v-model="smsTemplate" maxlength="80" placeholder="留空表示未配置" />
            </label>
            <label class="fams-field"><span class="fams-field-label">微信模板 ID</span>
              <input id="wechat-template" class="fams-control" v-model="wechatTemplate" maxlength="80" placeholder="留空表示未配置" />
            </label>
          </div>
          <div class="fams-form-actions">
            <button class="fams-button" data-kind="primary" type="button" :disabled="saveDisabled" @click="save">{{ saveLabel }}</button>
          </div>
        </fieldset>
      </form>
    </template>
  </AppChrome>
</template>
