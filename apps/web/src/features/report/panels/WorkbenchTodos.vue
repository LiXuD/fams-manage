<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import type { RouteLocationRaw } from "vue-router";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

const scopes = { ASSET_ADMIN: "资管共享待办", WAREHOUSE_OPERATOR: "库管共享待办", MINE: "我的待办" };
type Scope = keyof typeof scopes;
interface Todo { id: string; businessType: string; businessId: string; stage: string; openedAt: string }
interface Page { items: Todo[]; nextBefore: string | null; stageCounts: { stage: string; count: number }[] }
interface ScopeRow { value: Scope; label: string; pressed: boolean }
interface StageRow { stage: string; label: string; count: number; pressed: boolean }
interface TodoRow { id: string; stageText: string; linkText: string; openedAt: string; timeText: string; dest: RouteLocationRaw }

const session = useSessionStore();
const available = computed(() => (Object.keys(scopes) as Scope[])
  .filter(scope => scope === "MINE" || session.user?.roles.includes(scope)));
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.permissions?.authorizationVersion]));
const scope = shallowRef<Scope>("MINE");
const result = ref<Page>();
const stage = shallowRef("");
const processorInput = shallowRef("");
const processorAccountId = shallowRef("");
const cursors = ref<(string | undefined)[]>([undefined]);
const pageIndex = shallowRef(0);
const total = computed(() => result.value?.stageCounts.reduce((sum, row) => sum + row.count, 0) ?? 0);
const busy = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);
const allStagesPressed = computed(() => !stage.value);
const hasNoRows = computed(() => result.value?.items.length === 0);
const showPrev = computed(() => pageIndex.value > 0);
const currentCursor = computed(() => cursors.value[pageIndex.value]);
const scopeRows = computed<ScopeRow[]>(() => available.value.map((item) => ({
  value: item, label: scopes[item], pressed: scope.value === item,
})));
const stageRows = computed<StageRow[]>(() => (result.value?.stageCounts ?? []).map((entry) => ({
  stage: entry.stage, label: stages[entry.stage] ?? entry.stage, count: entry.count, pressed: stage.value === entry.stage,
})));
const todoRows = computed<TodoRow[]>(() => (result.value?.items ?? []).map((todo) => ({
  id: todo.id,
  openedAt: todo.openedAt,
  stageText: stages[todo.stage] ?? todo.stage,
  linkText: `${todo.businessType} ${todo.businessId}`,
  timeText: new Date(todo.openedAt).toLocaleString(),
  dest: destination(todo),
})));

const stages: Record<string, string> = {
  APPROVAL: "待管理员确认", ADMIN_DECISION: "待管理员确认", CLAIM: "待库管领取",
  WAREHOUSE_CLAIM: "待库管领取", FIRST_EXECUTION: "待首次执行", PROCESS: "待处理",
  RECEIPT: "待实物接收", REASSIGNMENT_REQUIRED: "需要转派", DISPOSITION: "待处置",
  INVENTORY_CONFLICT: "盘点冲突待复核", INVENTORY_DIFFERENCE: "盘点差异待复核",
};

function destination(todo: Todo) {
  const id = todo.businessId;
  switch (todo.businessType) {
    case "INBOUND": return { name: "inbound", query: { receiptId: id } };
    case "INVENTORY_TASK": return { name: "inventory-tasks", query: { taskId: id } };
    case "REQUISITION": return { name: "requisitions", query: { id } };
    case "TRANSFER": return { name: "transfers", query: { id } };
    case "ASSET_RETURN": return { name: "loans", query: { return: id } };
    case "REPAIR": return { name: "repair-detail", params: { id } };
    case "LOST_REPORT": return { name: "loss-reports", params: { id } };
    case "SCRAP": return { name: "scrap-requests", params: { id } };
    case "PROXY_CONFIRMATION": return { name: "proxy-confirmations", query: { id } };
    default: return { name: "messages" };
  }
}

async function load(before?: string) {
  const turn = liveness.begin();
  const query = new URLSearchParams({ scope: scope.value });
  if (stage.value) query.set("stage", stage.value);
  if (scope.value === "ASSET_ADMIN" && processorAccountId.value) {
    query.set("processorAccountId", processorAccountId.value);
  }
  if (before) query.set("before", before);
  result.value = undefined;
  busy.value = true;
  error.value = "";
  try {
    const page = await reportRequest<Page>(`/workbench/todos?${query}`);
    if (liveness.isCurrent(turn)) result.value = page;
  } catch {
    if (liveness.isCurrent(turn)) error.value = "无法读取工作台待办。请检查当前权限后重试。";
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

function chooseStage(next: string) {
  stage.value = next;
  cursors.value = [undefined];
  pageIndex.value = 0;
  void load();
}

function choose(next: Scope) {
  scope.value = next;
  processorInput.value = "";
  processorAccountId.value = "";
  chooseStage("");
}

function applyProcessor() {
  processorAccountId.value = processorInput.value.trim();
  chooseStage("");
}

function nextPage(before: string) {
  cursors.value[pageIndex.value + 1] = before;
  pageIndex.value++;
  void load(before);
}

function previousPage() {
  pageIndex.value--;
  void load(cursors.value[pageIndex.value]);
}

watch(identity, () => choose(available.value[0] ?? "MINE"), { immediate: true, flush: "sync" });
</script>

<template>
  <section class="fams-panel fams-stack" aria-labelledby="workbench-todos-title">
    <div class="fams-panel-head">
      <h2 id="workbench-todos-title" class="fams-panel-title">工作台待办</h2>
      <div class="fams-panel-actions">
        <button v-for="item in scopeRows" :key="item.value" class="fams-button" data-kind="secondary" data-size="sm"
          type="button" :aria-pressed="item.pressed" @click="choose(item.value)">{{ item.label }}</button>
        <router-link class="fams-button" data-kind="link" data-size="sm" :to="{ name: 'messages' }">查看消息与异常</router-link>
      </div>
    </div>
    <form v-if="scope === 'ASSET_ADMIN'" class="fams-field-row" @submit.prevent="applyProcessor">
      <div class="fams-field">
        <label for="todos-processor">原库管账号 ID</label>
        <input id="todos-processor" class="fams-control" v-model="processorInput" inputmode="numeric" maxlength="19" pattern="[1-9][0-9]{0,18}">
      </div>
      <button class="fams-button" data-kind="secondary" type="submit">筛选转派待办</button>
    </form>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }} <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="load(currentCursor)">重试</button></strong></p>
    <template v-if="result">
      <div class="fams-inline" aria-label="待办阶段">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :aria-pressed="allStagesPressed" @click="chooseStage('')">全部 {{ total }}</button>
        <button v-for="entry in stageRows" :key="entry.stage" class="fams-button" data-kind="secondary" data-size="sm" type="button"
          :aria-pressed="entry.pressed" @click="chooseStage(entry.stage)">
          {{ entry.label }} {{ entry.count }}
        </button>
      </div>
      <p v-if="hasNoRows">当前没有待办。</p>
      <ul v-else class="fams-timeline">
        <li v-for="todo in todoRows" :key="todo.id">
          <router-link class="fams-button" data-kind="link" data-size="sm" :to="todo.dest">{{ todo.stageText }} · {{ todo.linkText }}</router-link>
          <time :datetime="todo.openedAt">{{ todo.timeText }}</time>
        </li>
      </ul>
      <div class="fams-pagination-actions">
        <button v-if="showPrev" class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="previousPage">上一页</button>
        <button v-if="result.nextBefore" class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="nextPage(result.nextBefore)">下一页</button>
      </div>
    </template>
  </section>
</template>
