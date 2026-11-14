<script setup lang="ts">
import { ref } from "vue";

interface Row { taskId: string; name: string; status: string; dueAt: string; expected: number; accepted: number }
interface Page { items: Row[]; nextBefore: string | null }
const page = ref<Page>();
const busy = ref(false);
const error = ref("");
const statuses: Record<string, string> = { READY: "待开始", IN_PROGRESS: "进行中", WAITING_REVIEW: "待复核" };

async function load(before?: string) {
  busy.value = true;
  error.value = "";
  try {
    const query = before ? "?" + new URLSearchParams({ before }) : "";
    const response = await fetch("/api/v1/operations-reports/inventory-progress" + query, {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    page.value = await response.json() as Page;
  } catch {
    page.value = undefined;
    error.value = "无法读取当前盘点进度，请重试。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section aria-label="当前盘点进度">
    <h3>当前盘点进度</h3>
    <button type="button" :disabled="busy" @click="load()">查询当前盘点任务</button>
    <p v-if="busy" role="status">正在查询</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="page">
      <p v-if="!page.items.length">没有进行中的盘点任务。</p>
      <table v-else>
        <thead><tr><th scope="col">任务</th><th scope="col">状态</th><th scope="col">截止时间</th>
          <th scope="col">已采信 / 应盘</th></tr></thead>
        <tbody>
          <tr v-for="task in page.items" :key="task.taskId">
            <td><router-link :to="{ name: 'inventory-tasks', query: { taskId: task.taskId } }">{{ task.name }}</router-link></td>
            <td>{{ statuses[task.status] }}</td><td>{{ task.dueAt }}</td><td>{{ task.accepted }}/{{ task.expected }}</td>
          </tr>
        </tbody>
      </table>
      <button v-if="page.nextBefore" type="button" :disabled="busy" @click="load(page.nextBefore)">下一页盘点任务</button>
    </template>
  </section>
</template>
