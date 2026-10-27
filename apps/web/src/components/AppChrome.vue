<script setup lang="ts">
import { computed, reactive, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "@/auth/sessionStore";

const router = useRouter();
const route = useRoute();
const session = useSessionStore();

type NavAccess = { permission?: string; roles?: readonly string[] };
type NavItem = NavAccess & { label: string; path: string };

const LEDGER_ROLES = ["WAREHOUSE_OPERATOR", "ASSET_ADMIN"];
const FIELD_ROLES = ["EMPLOYEE", "DEPARTMENT_ASSET_MANAGER", "WAREHOUSE_OPERATOR", "ASSET_ADMIN"];

/** 侧栏分组与路由 meta 的 uiGroup 同源；条目顺序即展示顺序。 */
const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  { label: "总览", items: [{ label: "工作台", path: "/" }] },
  {
    label: "资产",
    items: [
      { label: "资产台账", path: "/assets", permission: "ASSET_LEDGER_READ" },
      { label: "批量导入", path: "/asset-imports", roles: ["WAREHOUSE_OPERATOR"] },
      { label: "期初导入核验", path: "/asset-import-reviews", roles: ["ASSET_ADMIN"] },
      { label: "期初窗口", path: "/asset-initialization", roles: ["SYSTEM_ADMIN"] },
      { label: "入库管理", path: "/inbound", roles: LEDGER_ROLES },
    ],
  },
  {
    label: "业务流转",
    items: [
      { label: "领用申请与分配", path: "/requisitions", roles: FIELD_ROLES },
      { label: "盘点任务", path: "/inventory-tasks", roles: FIELD_ROLES },
      { label: "报修与维修", path: "/repairs", roles: FIELD_ROLES },
      { label: "我协助的报修", path: "/repair-assistance", roles: ["TECH_ASSISTANT"] },
      { label: "借用、延期与退还", path: "/loans", roles: FIELD_ROLES },
      { label: "调拨与人员交接", path: "/transfers", roles: FIELD_ROLES },
    ],
  },
  {
    label: "异常与核查",
    items: [
      { label: "异常代确认", path: "/proxy-confirmations", roles: LEDGER_ROLES },
      { label: "批准目标变更", path: "/requisition-target-changes", roles: LEDGER_ROLES },
      { label: "调拨目标变更", path: "/transfer-target-changes", roles: LEDGER_ROLES },
      { label: "代确认结果核查", path: "/business-result-reviews", roles: ["ASSET_ADMIN"] },
      { label: "业务结果通知", path: "/business-result-notices" },
    ],
  },
];

const COLLAPSE_KEY = "fams.navCollapsed";

function readCollapsed(): string[] {
  try {
    const raw = localStorage.getItem(COLLAPSE_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((g): g is string => typeof g === "string") : [];
  } catch {
    return [];
  }
}

const collapsed = reactive(new Set(readCollapsed()));

function persistCollapsed(): void {
  try {
    localStorage.setItem(COLLAPSE_KEY, JSON.stringify([...collapsed]));
  } catch {
    // 私密浏览环境下 localStorage 会抛错，折叠状态退化为会话内有效。
  }
}

/** 当前页所在分组不允许收起，避免把自己正在用的导航藏起来。 */
const activeGroup = computed(() => route.meta.uiGroup ?? null);

function toggleGroup(group: string): void {
  if (group === activeGroup.value) {
    return;
  }
  if (collapsed.has(group)) {
    collapsed.delete(group);
  } else {
    collapsed.add(group);
  }
  persistCollapsed();
}

// 当前分组必须可见：整页加载时 activeGroup 不发生变化，所以用 immediate 在建立时先展开一次。
watch(activeGroup, (group) => {
  if (group && collapsed.delete(group)) {
    persistCollapsed();
  }
}, { immediate: true });

const displayRole = computed(() => session.user?.roles[0] ?? "未登录");

function hasRole(roles: readonly string[]): boolean {
  return session.user?.roles.some((role) => roles.includes(role)) ?? false;
}

function visible(item: NavItem): boolean {
  if (item.permission && !session.hasPermission(item.permission)) {
    return false;
  }
  if (item.roles && !hasRole(item.roles)) {
    return false;
  }
  return true;
}

/** 可见条目为空的分组整组不渲染。 */
const navGroups = computed(() => NAV_GROUPS
  .map((group) => ({ ...group, items: group.items.filter(visible) }))
  .filter((group) => group.items.length > 0));

/** 页头由路由 meta 单点定义，保证不同菜单的标题落在同一垂直位置。 */
const pageHead = computed(() => {
  const { uiGroup, uiTitle, uiDesc } = route.meta;
  if (!session.isAuthenticated || !uiGroup || !uiTitle) {
    return null;
  }
  return { group: uiGroup, title: uiTitle, desc: uiDesc };
});

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
  <div class="fams-shell">
    <header class="fams-topbar">
      <div class="fams-brand"><span class="fams-brand-mark">F</span><span>示例企业 · 固定资产</span></div>
      <div v-if="session.isAuthenticated" class="fams-topbar-actions">
        <div class="fams-account">
          <span class="fams-account-name">{{ session.user?.username }}</span>
          <span class="fams-account-role">{{ displayRole }}</span>
        </div>
        <button type="button" class="fams-button" data-kind="secondary" data-size="sm" :disabled="session.isBusy"
          @click="handleLogout">退出</button>
      </div>
    </header>
    <div class="fams-layout" :data-mode="session.isAuthenticated ? null : 'anonymous'">
      <nav v-if="session.isAuthenticated" class="fams-sidebar" aria-label="主导航">
        <div class="fams-navigation" data-mode="sidebar">
          <div v-for="group in navGroups" :key="group.label" class="fams-navigation-group">
            <button class="fams-button fams-navigation-toggle" type="button" :aria-expanded="!collapsed.has(group.label)"
              :aria-controls="`nav-group-${group.label}`" :disabled="group.label === activeGroup" @click="toggleGroup(group.label)">
              <svg class="fams-nav-chevron" viewBox="0 0 16 16" aria-hidden="true">
                <path d="M6 4l4 4-4 4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span class="fams-nav-label">{{ group.label }}</span>
            </button>
            <div :id="`nav-group-${group.label}`" class="fams-navigation-items" :data-collapsed="collapsed.has(group.label) ? 'true' : 'false'">
              <div class="fams-navigation-items-inner">
                <router-link v-for="item in group.items" :key="item.path" :to="item.path" class="fams-button">{{ item.label }}</router-link>
              </div>
            </div>
          </div>
          <span v-if="session.hasPermission('ACCOUNT_ADMIN')" class="fams-navigation-note">账号与角色 · 后续开放</span>
        </div>
      </nav>
      <main class="fams-main">
        <header v-if="pageHead" class="fams-page-head">
          <div class="fams-page-head-text">
            <p class="fams-breadcrumb">{{ pageHead.group }} <span aria-hidden="true">/</span> {{ pageHead.title }}</p>
            <h1>{{ pageHead.title }}</h1>
            <p v-if="pageHead.desc">{{ pageHead.desc }}</p>
          </div>
          <slot name="actions" />
        </header>
        <section v-if="session.isAuthenticated && session.error && route.name !== 'password-change'" class="fams-notice"
          data-state="error" role="alert">
          <strong>{{ session.error.message }}</strong>
        </section>
        <slot />
      </main>
    </div>
  </div>
</template>
