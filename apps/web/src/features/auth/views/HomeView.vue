<script setup lang="ts">
import { computed } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import OperationsReports from "@/features/report/panels/OperationsReports.vue";
import WorkbenchTodos from "@/features/report/panels/WorkbenchTodos.vue";
import { useSessionStore } from "@/auth/sessionStore";

const session = useSessionStore();
const permissions = computed(() => session.permissions?.permissions ?? []);
const permissionCount = computed(() => permissions.value.length);
const authorizationVersionText = computed(() => session.permissions?.authorizationVersion ?? "—");
const userName = computed(() => session.user?.username);
const roleText = computed(() => session.user?.roles.join("、"));
const warehouse = computed(() => session.user?.roles.includes("WAREHOUSE_OPERATOR") ?? false);
const administrator = computed(() => session.user?.roles.includes("ASSET_ADMIN") ?? false);
const canReadLedger = computed(() => session.hasPermission("ASSET_LEDGER_READ"));
const canLookup = computed(() => session.user?.roles.some(
  role => ["EMPLOYEE", "DEPARTMENT_ASSET_MANAGER", "ASSET_ADMIN", "WAREHOUSE_OPERATOR"].includes(role)) ?? false);
</script>

<template>
  <AppChrome>
    <router-link v-if="canLookup" class="fams-button" data-kind="secondary" data-size="sm"
      :to="{ name: 'asset-lookup' }">按编码查询资产</router-link>
    <section v-if="warehouse || administrator" class="fams-panel fams-stack" aria-labelledby="workbench-shortcuts">
      <div class="fams-panel-head">
        <h2 id="workbench-shortcuts" class="fams-panel-title">快捷入口</h2>
      </div>
      <div class="fams-inline" aria-label="工作台快捷入口">
        <router-link v-if="warehouse && canReadLedger" class="fams-button" data-kind="secondary" data-size="sm"
          :to="{ name: 'assets', query: { action: 'create' } }">单个建卡</router-link>
        <router-link v-if="warehouse" class="fams-button" data-kind="secondary" data-size="sm"
          :to="{ name: 'asset-import' }">批量导入</router-link>
        <router-link v-if="warehouse" class="fams-button" data-kind="secondary" data-size="sm"
          :to="{ name: 'inbound' }">建立入库单</router-link>
        <router-link v-if="administrator" class="fams-button" data-kind="secondary" data-size="sm"
          :to="{ name: 'asset-import-review' }">期初导入核验</router-link>
        <router-link v-if="administrator" class="fams-button" data-kind="secondary" data-size="sm"
          :to="{ name: 'inventory-tasks' }">创建盘点草稿</router-link>
      </div>
    </section>
    <WorkbenchTodos v-if="warehouse || administrator" />
    <OperationsReports v-if="administrator" />
    <div class="fams-metric-grid">
      <div class="fams-metric">
        <span>有效权限</span>
        <strong>{{ permissionCount }}</strong>
        <small>由服务端按当前会话返回</small>
      </div>
      <div class="fams-metric">
        <span>授权版本</span>
        <strong>{{ authorizationVersionText }}</strong>
        <small>变更后本页重新拉取</small>
      </div>
    </div>

    <section class="fams-panel" aria-labelledby="identity-title">
      <div class="fams-panel-head">
        <h2 id="identity-title" class="fams-panel-title">当前身份</h2>
      </div>
      <dl class="fams-facts">
        <div><dt>账号</dt><dd>{{ userName }}</dd></div>
        <div><dt>角色</dt><dd class="u-break">{{ roleText }}</dd></div>
      </dl>
    </section>

    <section class="fams-panel" aria-labelledby="permission-title">
      <div class="fams-panel-head">
        <h2 id="permission-title" class="fams-panel-title">有效权限</h2>
        <span class="fams-status" data-tone="info">{{ permissionCount }} 项</span>
      </div>
      <div class="fams-inline" aria-label="有效权限">
        <span v-for="permission in permissions" :key="permission" class="fams-status">{{ permission }}</span>
      </div>
      <p v-if="!permissionCount" class="fams-panel-subtitle">当前没有可展示的业务动作</p>
    </section>
  </AppChrome>
</template>
