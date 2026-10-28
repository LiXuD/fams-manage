<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { loanMessage, loanRequest } from "@/loan/api";
import { useSessionStore } from "@/auth/sessionStore";

type Task = { id: string; name: string; task_status: string; starts_at: string; due_at: string; overdue?: boolean };
const session = useSessionStore();
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const tasks = ref<Task[]>([]); const total = ref(0); const page = ref(1);
const busy = ref(false); const error = ref("");
const labels: Record<string, string> = { DRAFT: "草稿", READY: "待开始", IN_PROGRESS: "进行中", WAITING_REVIEW: "待复核", COMPLETED: "已完成", CANCELLED: "已取消", TERMINATED: "已终止" };
async function list() {
  const result = await loanRequest<{ items: Task[]; total: number }>(`/inventory-tasks?page=${page.value}&pageSize=20`);
  tasks.value = result.items; total.value = result.total;
}
async function refresh() {
  busy.value = true; error.value = "";
  try { await list(); } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
}
async function pages(delta: number) {
  page.value += delta; await refresh();
}
onMounted(refresh);
</script>
<template>
  <AppChrome>
    <template #actions>
      <router-link v-if="admin" class="fams-button" data-kind="primary" to="/inventory-tasks/new">新建盘点草稿</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section class="fams-panel fams-stack" aria-label="盘点任务列表">
      <div class="fams-panel-head">
        <h2 class="fams-panel-title">{{ admin ? "全部任务" : "我的任务" }}</h2>
        <div class="fams-panel-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy"
            @click="refresh">刷新</button>
        </div>
      </div>

      <div class="fams-table-scroll">
        <table class="fams-table">
          <thead><tr><th scope="col" data-column-kind="identity">任务</th><th scope="col">状态</th>
            <th scope="col">开始时间</th><th scope="col">截止时间</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody>
            <tr v-for="t in tasks" :key="t.id">
              <th scope="row" data-column-kind="identity">{{ t.name }}</th>
              <td><span class="fams-status" :data-tone="t.overdue ? 'warning' : 'info'">{{ labels[t.task_status] }}{{ t.overdue ? ' · 逾期' : '' }}</span></td>
              <td>{{ new Date(t.starts_at).toLocaleString() }}</td>
              <td>{{ new Date(t.due_at).toLocaleString() }}</td>
              <td data-column-kind="action"><router-link class="fams-button" data-kind="link" data-size="sm"
                :to="`/inventory-tasks/${t.id}`">打开</router-link></td>
            </tr>
            <tr v-if="!tasks.length" class="fams-table-empty"><td colspan="5"><strong>当前范围没有盘点任务</strong>{{ admin ? '新建盘点草稿设置范围，保存后到详情页预览并发布。' : '等待管理员发布盘点任务后在此处理。' }}</td></tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || page === 1"
            @click="pages(-1)">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button"
            :disabled="busy || page * 20 >= total" @click="pages(1)">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
