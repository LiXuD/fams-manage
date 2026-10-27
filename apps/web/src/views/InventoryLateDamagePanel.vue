<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { reviewRequest, ReviewApiError } from "@/inventory-review/api";
import type { LoanMutation } from "@/loan/api";
interface Damage { observation_id: string; version: number; damage_status: string; repair_id: string | null; repair_status: string | null }
interface Context { assetId: string; currentAsset: { version: number; assetCode?: string; name?: string } | null; damage: Damage;
  originalEvidence: { rawObservedAt: string }; recheckResult: string; todoOpen: boolean;
  history: { items: { after_version: number; method: string; damage_status: string; repair_id: string; actor_username: string; reason: string; evidence: string }[]; page: number; total: number } }
interface Linked { id: string; assetId: string; version: number; status: string }
const props = defineProps<{ taskId: string; observationId: string; blocked?: boolean }>();
const emit = defineEmits<{ changed: []; invalidated: []; busyChange: [value: boolean] }>();
const session = useSessionStore(), owner = computed(() => JSON.stringify([props.taskId, props.observationId, session.user?.id, session.user?.roles, session.csrfToken]));
const context = ref<Context | null>(null), busy = ref(false), error = ref(""), notice = ref(""), page = ref(1);
const pending = ref<LoanMutation | null>(null), confirming = ref(false), method = ref("FORMAL_REPAIR");
const reason = ref(""), evidence = ref(""), fault = ref(""), impact = ref("HIGH"), discoveredAt = ref(""), checked = ref(false), timeChecked = ref(false);
const repairId = ref(""), linked = ref<Linked | null>(null);
const locked = computed(() => busy.value || !!pending.value || !!props.blocked), states: Record<string, string> = { OPEN: "待实际处理", PROCESSING: "正式维修处理中", CLOSED: "实际维修已完成" };
const methods: Record<string, string> = { FORMAL_REPAIR: "创建正式维修", LINK_REPAIR: "关联已有正式维修", REFRESH_REPAIR: "核对正式维修实际进度" };
let generation = 0, live = true;
const current = (epoch: number, who: string) => live && generation === epoch && owner.value === who;
const path = () => `/inventory-late/tasks/${props.taskId}/observations/${props.observationId}/damage-repair`;
function clear() { generation++; context.value = null; pending.value = null; confirming.value = false; busy.value = false; reason.value = ""; evidence.value = ""; fault.value = ""; discoveredAt.value = ""; repairId.value = ""; linked.value = null; checked.value = false; timeChecked.value = false; }
function fail(cause: unknown, write = false) {
  if (cause instanceof ReviewApiError && [401, 403, 404].includes(cause.status)) { clear(); emit("invalidated"); error.value = "当前身份、权限或原证据已失效，已隐藏资料。"; return; }
  if (write && cause instanceof ReviewApiError && cause.status >= 400 && cause.status < 500) { pending.value = null; confirming.value = false; }
  error.value = cause instanceof ReviewApiError && cause.fields.processedBy ? `已由 ${cause.fields.processedBy} 处理，请刷新核对独立损坏版本。`
    : pending.value ? "结果尚未确认，请保留原键载荷并重试。" : "读取或核实未完成，请刷新当前损坏义务、资产和正式维修。";
}
async function load(delta = 0) {
  if (busy.value || props.blocked || confirming.value || page.value + delta < 1 || delta > 0 && context.value && page.value * 20 >= context.value.history.total) return;
  const epoch = ++generation, who = owner.value; busy.value = true; error.value = "";
  try {
    const result = await reviewRequest<Context>(path() + `?page=${page.value + delta}&pageSize=20`);
    if (current(epoch, who)) { context.value = result; page.value += delta;
      if (!pending.value) { method.value = result.damage.damage_status === "PROCESSING" ? "REFRESH_REPAIR" : "FORMAL_REPAIR"; checked.value = false; timeChecked.value = false; } }
  } catch (cause) { if (current(epoch, who)) fail(cause); }
  finally { if (current(epoch, who)) busy.value = false; }
}
async function inspect() {
  if (locked.value || !/^[1-9][0-9]{0,18}$/.test(repairId.value.trim())) return;
  const epoch = ++generation, who = owner.value, requested = repairId.value.trim(); busy.value = true; linked.value = null; error.value = "";
  try { const result = await reviewRequest<Linked>(`/repairs/${requested}`);
    if (current(epoch, who) && requested === repairId.value.trim()) {
      if (result.id !== requested || result.assetId !== context.value?.assetId || !["PENDING_ADMIN_DECISION", "WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING"].includes(result.status)) error.value = "请选择同一资产的有效正式维修单。";
      else linked.value = result;
    }
  } catch (cause) { if (current(epoch, who)) fail(cause); }
  finally { if (current(epoch, who)) busy.value = false; }
}
function prepare() {
  if (locked.value || !context.value || context.value.damage.damage_status === "CLOSED" || reason.value.trim().length < 3 || evidence.value.trim().length < 10) return;
  const refresh = method.value === "REFRESH_REPAIR"; let repair: unknown;
  if (!refresh) {
    if (context.value.damage.damage_status !== "OPEN" || !context.value.currentAsset) { error.value = "请先核对已有正式维修的实际进度。"; return; }
    const time = new Date(discoveredAt.value);
    if (!checked.value || !timeChecked.value || !discoveredAt.value || !Number.isFinite(time.getTime()) || time.getTime() > Date.now()) { error.value = "请独立核实实际损坏及发现时间。"; return; }
    if (method.value === "FORMAL_REPAIR" && fault.value.trim().length < 3) { error.value = "请填写实际故障。"; return; }
    if (method.value === "LINK_REPAIR" && (!linked.value || linked.value.id !== repairId.value.trim() || linked.value.assetId !== context.value.assetId)) { error.value = "请先核对同资产正式维修单。"; return; }
    repair = { verifiedDiscoveryAt: time.toISOString(), physicalDamageChecked: true, discoveryTimeVerified: true,
      ...(method.value === "FORMAL_REPAIR" ? { fault: fault.value.trim(), impact: impact.value } : { repairId: linked.value!.id, repairVersion: linked.value!.version }) };
  } else if (!context.value.damage.repair_id) { error.value = "本条损坏尚未关联正式维修。"; return; }
  const body = { method: method.value, assetVersion: context.value.currentAsset?.version ?? 0, reason: reason.value.trim(), evidence: evidence.value.trim(), ...(repair ? { repair } : {}) };
  pending.value = { path: path(), key: crypto.randomUUID(), version: context.value.damage.version, body: JSON.parse(JSON.stringify(body)) as unknown }; confirming.value = true;
}
async function send() {
  if (busy.value || props.blocked || !pending.value) return;
  const original = pending.value, epoch = ++generation, who = owner.value; busy.value = true; confirming.value = false; error.value = ""; let saved = false;
  try { await reviewRequest(original.path, original); if (current(epoch, who)) { pending.value = null; saved = true; reason.value = ""; evidence.value = ""; notice.value = "损坏处理已追加；复盘和其他义务仍须分别完成。"; } }
  catch (cause) { if (current(epoch, who)) fail(cause, true); }
  finally { if (current(epoch, who)) busy.value = false; }
  if (saved && current(epoch, who)) { await load(); if (live && who === owner.value) emit("changed"); }
}
watch([busy, pending], () => emit("busyChange", busy.value || !!pending.value), { flush: "sync" });
watch(owner, () => { clear(); page.value = 1; error.value = ""; notice.value = ""; void load(); }, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { live = false; clear(); emit("busyChange", false); });
</script>
<template>
  <section class="fams-card fams-stack" aria-label="迟到损坏独立维修">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">迟到损坏独立维修</h3>
        <p class="fams-panel-subtitle">维修和原补充复核各有独立版本与待办。维修实际完成只关闭损坏义务；新复盘不会替代实际维修，原封存报告不改。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || blocked || confirming" @click="load()">刷新独立损坏与当前资产</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
    <template v-if="context">
      <p>{{ context.currentAsset?.assetCode || context.assetId }} · {{ states[context.damage.damage_status] }} · 独立损坏第 {{ context.damage.version }} 版</p>
      <p>原观察 {{ context.originalEvidence.rawObservedAt }} · 补充复核 {{ context.recheckResult }} · {{ context.todoOpen ? '仍有开放待办' : '两项待办均已结束' }}</p>
      <router-link v-if="context.damage.repair_id" class="fams-button" data-kind="link" :to="'/repairs/' + context.damage.repair_id">查看正式维修 {{ context.damage.repair_id }}（上次核对 {{ context.damage.repair_status }}）</router-link>
      <ul class="fams-timeline">
        <li v-for="row in context.history.items" :key="row.after_version">第 {{ row.after_version }} 版 · {{ methods[row.method] }} · {{ states[row.damage_status] }} · {{ row.actor_username }}
          <small>{{ row.reason }} · {{ row.evidence }} · 维修 {{ row.repair_id }}</small></li>
      </ul>
      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ context.history.total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page === 1" @click="load(-1)">上页损坏历史</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || page * 20 >= context.history.total" @click="load(1)">下页损坏历史</button>
        </div>
      </div>
      <fieldset class="fams-card fams-stack" :disabled="locked || context.damage.damage_status === 'CLOSED'">
        <label class="fams-field"><span class="fams-field-label">损坏处理</span>
          <select class="fams-control" v-model="method"><option v-for="(label,value) in methods" :key="value" :value="value">{{ label }}</option></select></label>
        <template v-if="method !== 'REFRESH_REPAIR'">
          <label class="fams-field"><span class="fams-field-label">实际发现损坏时间（本机时区）</span>
            <input class="fams-control" v-model="discoveredAt" type="datetime-local" step="0.001" /></label>
          <div class="fams-selection-group">
            <label class="fams-selection"><input v-model="checked" type="checkbox" />已按实物和原始证据核实损坏</label>
            <label class="fams-selection"><input v-model="timeChecked" type="checkbox" />已独立核实发现时间，未直接采用异常设备时间</label>
          </div>
          <template v-if="method === 'FORMAL_REPAIR'">
            <label class="fams-field"><span class="fams-field-label">实际故障</span>
              <textarea class="fams-control" v-model="fault" minlength="3" maxlength="2000" /></label>
            <label class="fams-field"><span class="fams-field-label">影响程度</span>
              <select class="fams-control" v-model="impact"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option></select></label>
          </template>
          <template v-else>
            <div class="fams-field-row">
              <label class="fams-field"><span class="fams-field-label">已有维修编号</span>
                <input class="fams-control" v-model="repairId" inputmode="numeric" @input="linked = null" /></label>
              <button class="fams-button" data-kind="secondary" type="button" @click="inspect">核对同资产维修</button>
            </div>
            <p v-if="linked">维修 {{ linked.id }} · 第 {{ linked.version }} 版 · {{ linked.status }}</p>
          </template>
        </template>
        <label class="fams-field"><span class="fams-field-label">处理原因</span>
          <textarea class="fams-control" v-model="reason" minlength="3" maxlength="1000" /></label>
        <label class="fams-field"><span class="fams-field-label">核实依据</span>
          <textarea class="fams-control" v-model="evidence" minlength="10" maxlength="2000" /></label>
        <button class="fams-button" data-kind="primary" type="button" @click="prepare">核对独立损坏处理</button>
      </fieldset>
    </template>
    <div v-if="confirming" class="fams-card fams-stack" role="group" aria-label="确认独立损坏处理">
      <p>确认{{ methods[method] }}？本次仅追加损坏决定，其他待办及原报告不改。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || blocked" @click="send">确认保存独立损坏处理</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="pending = null; confirming = false">取消本次损坏处理</button>
      </div>
    </div>
    <button v-if="pending && !confirming" class="fams-button" data-kind="secondary" type="button" :disabled="busy || blocked" @click="send">原键重试独立损坏处理</button>
  </section>
</template>
