<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "@/auth/sessionStore";

const router = useRouter();
const route = useRoute();
const session = useSessionStore();

const displayRole = computed(() => session.user?.roles[0] ?? "未登录");

async function handleLogout(): Promise<void> {
  try {
    await session.signOut();
    await router.replace({ name: "login" });
  } catch {
    // Keep the current page and show the server-safe error until logout succeeds.
  }
}
</script>

<template>
  <div class="app-chrome">
    <header class="topbar">
      <div class="brand"><span class="brand-mark">F</span><span>示例企业 · 固定资产</span></div>
      <div v-if="session.isAuthenticated" class="topbar-actions">
        <span class="account-label">{{ session.user?.username }} · {{ displayRole }}</span>
        <button type="button" class="text-button" :disabled="session.isBusy" @click="handleLogout">退出</button>
      </div>
    </header>
    <div class="workspace" :class="{ 'workspace-anonymous': !session.isAuthenticated }">
      <nav v-if="session.isAuthenticated" class="sidebar" aria-label="主导航">
        <router-link to="/" class="nav-item nav-item-active">工作台</router-link>
        <span v-if="session.hasPermission('ACCOUNT_ADMIN')" class="nav-note">账号与角色 · 后续开放</span>
        <router-link v-if="session.hasPermission('ASSET_LEDGER_READ')" to="/assets" class="nav-item">资产台账</router-link>
        <router-link v-if="session.user?.roles.includes('WAREHOUSE_OPERATOR')" to="/asset-imports"
          class="nav-item">批量导入</router-link>
        <router-link v-if="session.user?.roles.includes('ASSET_ADMIN')" to="/asset-import-reviews"
          class="nav-item">期初导入核验</router-link>
        <router-link v-if="session.user?.roles.includes('SYSTEM_ADMIN')" to="/asset-initialization"
          class="nav-item">期初窗口</router-link>
        <router-link v-if="session.user?.roles.some((role) => ['WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/inbound" class="nav-item">入库管理</router-link>
        <router-link v-if="session.user?.roles.some((role) => ['EMPLOYEE', 'DEPARTMENT_ASSET_MANAGER', 'WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/requisitions" class="nav-item">领用申请与分配</router-link>
        <router-link v-if="session.user?.roles.some(role => ['EMPLOYEE', 'DEPARTMENT_ASSET_MANAGER', 'WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/inventory-tasks" class="nav-item">盘点任务</router-link>
        <router-link v-if="session.user?.roles.some(role => ['EMPLOYEE', 'DEPARTMENT_ASSET_MANAGER', 'WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/repairs" class="nav-item">报修与维修</router-link>
        <router-link v-if="session.user?.roles.includes('TECH_ASSISTANT')" to="/repair-assistance" class="nav-item">我协助的报修</router-link>
        <router-link v-if="session.user?.roles.some((role) => ['EMPLOYEE', 'DEPARTMENT_ASSET_MANAGER', 'WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/loans" class="nav-item">借用、延期与退还</router-link>
        <router-link v-if="session.user?.roles.some((role) => ['EMPLOYEE', 'DEPARTMENT_ASSET_MANAGER', 'WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/transfers" class="nav-item">调拨与人员交接</router-link>
        <router-link v-if="session.user?.roles.some(role => ['WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/proxy-confirmations" class="nav-item">异常代确认</router-link>
        <router-link v-if="session.user?.roles.some(role => ['WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/requisition-target-changes" class="nav-item">批准目标变更</router-link>
        <router-link v-if="session.user?.roles.some(role => ['WAREHOUSE_OPERATOR', 'ASSET_ADMIN'].includes(role))" to="/transfer-target-changes" class="nav-item">调拨目标变更</router-link>
        <router-link v-if="session.user?.roles.includes('ASSET_ADMIN')" to="/business-result-reviews" class="nav-item">代确认结果核查</router-link>
        <router-link to="/business-result-notices" class="nav-item">业务结果通知</router-link>
      </nav>
      <main class="workspace-content">
        <p v-if="session.isAuthenticated && session.error && route.name !== 'password-change'" class="form-error" role="alert">{{ session.error.message }}</p>
        <slot />
      </main>
    </div>
  </div>
</template>
