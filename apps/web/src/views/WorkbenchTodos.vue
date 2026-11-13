<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";

const scopes = { ASSET_ADMIN: "资管共享待办", WAREHOUSE_OPERATOR: "库管共享待办", MINE: "我的待办" };
type Scope = keyof typeof scopes;
interface Todo { id: string; businessType: string; businessId: string; stage: string; openedAt: string }
interface Page { items: Todo[]; nextBefore: string | null }

const session = useSessionStore();
const available = computed(() => (Object.keys(scopes) as Scope[])
  .filter(scope => scope === "MINE" || session.user?.roles.includes(scope)));
const identity = computed(() => JSON.stringify([session.user?.id, session.user?.roles, session.permissions?.authorizationVersion]));
const scope = ref<Scope>("MINE");
const result = ref<Page>();
const busy = ref(false);
const error = ref("");
let generation = 0;

const stages: Record<string, string> = {
  APPROVAL: "待管理员确认", ADMIN_DECISION: "待管理员确认", CLAIM: "待库管领取",
  WAREHOUSE_CLAIM: "待库管领取", FIRST_EXECUTION: "待首次执行", PROCESS: "待处理",
  RECEIPT: "待实物接收", REASSIGNMENT_REQUIRED: "需要转派", DISPOSITION: "待处置",
};

function destination(todo: Todo) {
  const id = todo.businessId;
  switch (todo.businessType) {
    case "INBOUND": return { name: "inbound", query: { receiptId: id } };
    case "INVENTORY_TASK": return { name: "inventory-tasks", query: { taskId: id } };
    case "REQUISITION": return { name: "requisitions", query: { id } };
    case "TRANSFER": return { name: "transfers", query: { id } };
    case "REPAIR": return { name: "repairs", params: { id } };
    case "LOST_REPORT": return { name: "loss-reports", params: { id } };
    case "SCRAP": return { name: "scrap-requests", params: { id } };
    case "PROXY_CONFIRMATION": return { name: "proxy-confirmations", query: { id } };
    default: return { name: "messages" };
  }
}

async function load(before?: string) {
  const request = ++generation;
  const query = new URLSearchParams({ scope: scope.value });
  if (before) query.set("before", before);
  result.value = undefined;
  busy.value = true;
  error.value = "";
  try {
    const response = await fetch(`/api/v1/workbench/todos?${query}`, { credentials: "include", cache: "no-store" });
    if (!response.ok) throw new Error(String(response.status));
    const page = await response.json() as Page;
    if (request === generation) result.value = page;
  } catch {
    if (request === generation) error.value = "无法读取工作台待办。请检查当前权限后重试。";
  } finally {
    if (request === generation) busy.value = false;
  }
}

function choose(next: Scope) {
  scope.value = next;
  void load();
}

watch(identity, () => choose(available.value[0] ?? "MINE"), { immediate: true, flush: "sync" });
onBeforeUnmount(() => generation++);
</script>

<template>
  <section aria-labelledby="workbench-todos-title">
    <h2 id="workbench-todos-title">工作台待办</h2>
    <div class="actions">
      <button v-for="item in available" :key="item" type="button" :aria-pressed="scope === item"
        @click="choose(item)">{{ scopes[item] }}</button>
      <router-link :to="{ name: 'messages' }">查看消息与异常</router-link>
    </div>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="result">
      <p v-if="result.items.length === 0">当前没有待办。</p>
      <ul v-else>
        <li v-for="todo in result.items" :key="todo.id">
          <router-link :to="destination(todo)">{{ stages[todo.stage] ?? todo.stage }} · {{ todo.businessType }} {{ todo.businessId }}</router-link>
          <time :datetime="todo.openedAt">{{ new Date(todo.openedAt).toLocaleString() }}</time>
        </li>
      </ul>
      <button v-if="result.nextBefore" type="button" @click="load(result.nextBefore)">下一页</button>
    </template>
  </section>
</template>
