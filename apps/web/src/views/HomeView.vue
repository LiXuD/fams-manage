<script setup lang="ts">
import { computed } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import WorkbenchTodos from "./WorkbenchTodos.vue";
import OperationsReports from "./OperationsReports.vue";
import { useSessionStore } from "@/auth/sessionStore";

const session = useSessionStore();
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR"));
const administrator = computed(() => session.user?.roles.includes("ASSET_ADMIN"));
</script>

<template>
  <AppChrome>
    <div class="breadcrumb">工作台 / 当前权限</div>
    <section v-if="warehouse || administrator" aria-labelledby="workbench-shortcuts">
      <h2 id="workbench-shortcuts">快捷入口</h2>
      <nav class="actions" aria-label="工作台快捷入口">
        <router-link v-if="warehouse && session.hasPermission('ASSET_LEDGER_READ')"
          :to="{ name: 'assets', query: { action: 'create' } }">单个建卡</router-link>
        <router-link v-if="warehouse" :to="{ name: 'asset-import' }">批量导入</router-link>
        <router-link v-if="warehouse" :to="{ name: 'inbound' }">建立入库单</router-link>
        <router-link v-if="administrator" :to="{ name: 'asset-import-review' }">期初导入核验</router-link>
        <router-link v-if="administrator" :to="{ name: 'inventory-tasks' }">创建盘点草稿</router-link>
      </nav>
    </section>
    <WorkbenchTodos v-if="warehouse || administrator" />
    <OperationsReports v-if="administrator" />
    <section class="permission-summary" aria-labelledby="permission-title">
      <div>
        <h1 id="permission-title">当前身份与权限</h1>
        <p>页面只展示服务端当前授权结果；每次业务请求仍由服务端重新校验。</p>
      </div>
      <dl>
        <div><dt>账号</dt><dd>{{ session.user?.username }}</dd></div>
        <div><dt>角色</dt><dd>{{ session.user?.roles.join("、") }}</dd></div>
        <div><dt>授权版本</dt><dd>{{ session.permissions?.authorizationVersion ?? "—" }}</dd></div>
      </dl>
      <ul class="permission-list" aria-label="有效权限">
        <li v-for="permission in session.permissions?.permissions ?? []" :key="permission">{{ permission }}</li>
        <li v-if="!(session.permissions?.permissions.length)">当前没有可展示的业务动作</li>
      </ul>
    </section>
  </AppChrome>
</template>
