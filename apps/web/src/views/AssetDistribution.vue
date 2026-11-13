<script setup lang="ts">
import { ref } from "vue";

type Dimension = "organizationId" | "categoryId";
interface Group { id: string; name: string | null; count: number }
interface Distribution { organizations: Group[]; categories: Group[] }
const emit = defineEmits<{ select: [dimension: Dimension, id: string] }>();
const distribution = ref<Distribution>();
const busy = ref(false);
const error = ref("");

async function load() {
  busy.value = true;
  error.value = "";
  distribution.value = undefined;
  try {
    const response = await fetch("/api/v1/operations-reports/asset-distribution", {
      credentials: "include", cache: "no-store",
    });
    if (!response.ok) throw new Error(String(response.status));
    distribution.value = await response.json() as Distribution;
  } catch {
    error.value = "无法读取资产分布。请检查权限后重试。";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section>
    <h3>部门与分类资产分布</h3>
    <button type="button" :disabled="busy" @click="load">查询资产分布</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在查询</p>
    <template v-if="distribution">
      <p v-if="!distribution.organizations.length">当前没有有效资产。</p>
      <template v-else>
        <h4>按当前使用部门</h4>
        <ul>
          <li v-for="group in distribution.organizations" :key="group.id">
            <button type="button" @click="emit('select', 'organizationId', group.id)">
              <meter aria-hidden="true" :max="distribution.organizations[0].count" :value="group.count"></meter>
              {{ group.name ?? "无使用部门" }} · {{ group.count }} 件
            </button>
          </li>
        </ul>
        <h4>按资产分类</h4>
        <ul>
          <li v-for="group in distribution.categories" :key="group.id">
            <button type="button" @click="emit('select', 'categoryId', group.id)">
              <meter aria-hidden="true" :max="distribution.categories[0].count" :value="group.count"></meter>
              {{ group.name ?? "未分类" }} · {{ group.count }} 件
            </button>
          </li>
        </ul>
      </template>
    </template>
  </section>
</template>
