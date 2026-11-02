<script setup lang="ts">
import { computed } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { businessMessage, businessRequest } from "@/api/business";
import { inventoryTaskStatusLabels as labels } from "@/features/inventory/api";
import { usePagedResourceList } from "@/composables/usePagedResourceList";
import { useSessionStore } from "@/auth/sessionStore";

type Task = { id: string; name: string; task_status: string; starts_at: string; due_at: string; overdue?: boolean };
const session = useSessionStore();
const admin = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
const { rows: tasks, total, page, busy, error, refresh, pages } = usePagedResourceList<Task>(
  (page) => businessRequest<{ items: Task[]; total: number }>(`/inventory-tasks?page=${page}&pageSize=20`),
  { problem: businessMessage });
type TaskRow = Task & { startsAtText: string; dueAtText: string; statusTone: string; overdueText: string };
const taskRows = computed<TaskRow[]>(() => tasks.value.map((t) => ({
  ...t,
  startsAtText: new Date(t.starts_at).toLocaleString(),
  dueAtText: new Date(t.due_at).toLocaleString(),
  statusTone: t.overdue ? "warning" : "info",
  overdueText: t.overdue ? " · 逾期" : "",
})));
const scopeTitle = computed(() => (admin.value ? "全部任务" : "我的任务"));
const emptyHintText = computed(() => admin.value
  ? "新建盘点草稿设置范围，保存后到详情页预览并发布。"
  : "等待管理员发布盘点任务后在此处理。");
const prevDisabled = computed(() => busy.value || page.value === 1);
const nextDisabled = computed(() => busy.value || page.value * 20 >= total.value);
</script>
<template>
  <AppChrome>
    <template #actions>
      <router-link v-if="admin" class="fams-button" data-kind="primary" to="/inventory-tasks/new">新建盘点草稿</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section class="fams-panel fams-stack" aria-label="盘点任务列表">
      <div class="fams-panel-head">
        <h2 class="fams-panel-title">{{ scopeTitle }}</h2>
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
            <tr v-for="t in taskRows" :key="t.id">
              <th scope="row" data-column-kind="identity">{{ t.name }}</th>
              <td><span class="fams-status" :data-tone="t.statusTone">{{ labels[t.task_status] }}{{ t.overdueText }}</span></td>
              <td>{{ t.startsAtText }}</td>
              <td>{{ t.dueAtText }}</td>
              <td data-column-kind="action"><router-link class="fams-button" data-kind="link" data-size="sm"
                :to="`/inventory-tasks/${t.id}`">打开</router-link></td>
            </tr>
            <tr v-if="!tasks.length" class="fams-table-empty"><td colspan="5"><strong>当前范围没有盘点任务</strong>{{ emptyHintText }}</td></tr>
          </tbody>
        </table>
      </div>

      <div class="fams-pagination">
        <span class="fams-pagination-summary">共 {{ total }} 条</span>
        <div class="fams-pagination-actions">
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled"
            @click="pages(-1)">上一页</button>
          <span class="fams-pagination-page">第 {{ page }} 页</span>
          <button class="fams-button" data-kind="secondary" data-size="sm" type="button"
            :disabled="nextDisabled" @click="pages(1)">下一页</button>
        </div>
      </div>
    </section>
  </AppChrome>
</template>
