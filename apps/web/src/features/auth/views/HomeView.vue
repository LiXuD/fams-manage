<script setup lang="ts">
import { computed } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import OperationsReports from "@/features/report/panels/OperationsReports.vue";
import WorkbenchTodos from "@/features/report/panels/WorkbenchTodos.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useModuleFocus, type FocusModule } from "@/composables/useModuleFocus";

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

/** 缩略条清单与下方各模块的渲染条件同源；条目顺序即舞台顺序。 */
const moduleDefs = computed<FocusModule[]>(() => {
  const defs: FocusModule[] = [];
  if (warehouse.value || administrator.value) {
    defs.push({ id: "shortcuts", title: "快捷入口" }, { id: "todos", title: "工作台待办" });
  }
  if (administrator.value) {
    defs.push({ id: "reports", title: "运营报表" });
  }
  defs.push({ id: "metrics", title: "授权指标" }, { id: "identity", title: "当前身份" }, { id: "permissions", title: "有效权限" });
  return defs;
});

const { focusId, thumbnailModules, isVisible, focusModule, toggleModule, closeModules } = useModuleFocus(moduleDefs);
</script>

<template>
  <AppChrome>
    <router-link v-if="canLookup" class="fams-button" data-kind="secondary" data-size="sm"
      :to="{ name: 'asset-lookup' }">按编码查询资产</router-link>

    <Transition name="fams-module">
      <div v-show="isVisible('shortcuts')" class="fams-module-host" :class="{ 'is-focused': focusId === 'shortcuts' }">
        <div class="fams-module-bar">
          <button class="fams-module-toggle" type="button" :aria-expanded="focusId === 'shortcuts'"
            @click="toggleModule('shortcuts')">{{ focusId === 'shortcuts' ? "沿原路收回" : "展开" }}</button>
        </div>
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
      </div>
    </Transition>

    <Transition name="fams-module">
      <div v-show="isVisible('todos')" class="fams-module-host" :class="{ 'is-focused': focusId === 'todos' }">
        <div class="fams-module-bar">
          <button class="fams-module-toggle" type="button" :aria-expanded="focusId === 'todos'"
            @click="toggleModule('todos')">{{ focusId === 'todos' ? "沿原路收回" : "展开" }}</button>
        </div>
        <WorkbenchTodos v-if="warehouse || administrator" />
      </div>
    </Transition>

    <Transition name="fams-module">
      <div v-show="isVisible('reports')" class="fams-module-host" :class="{ 'is-focused': focusId === 'reports' }">
        <div class="fams-module-bar">
          <button class="fams-module-toggle" type="button" :aria-expanded="focusId === 'reports'"
            @click="toggleModule('reports')">{{ focusId === 'reports' ? "沿原路收回" : "展开" }}</button>
        </div>
        <OperationsReports v-if="administrator" />
      </div>
    </Transition>

    <Transition name="fams-module">
      <div v-show="isVisible('metrics')" class="fams-module-host" :class="{ 'is-focused': focusId === 'metrics' }">
        <div class="fams-module-bar">
          <button class="fams-module-toggle" type="button" :aria-expanded="focusId === 'metrics'"
            @click="toggleModule('metrics')">{{ focusId === 'metrics' ? "沿原路收回" : "展开" }}</button>
        </div>
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
      </div>
    </Transition>

    <Transition name="fams-module">
      <div v-show="isVisible('identity')" class="fams-module-host" :class="{ 'is-focused': focusId === 'identity' }">
        <div class="fams-module-bar">
          <button class="fams-module-toggle" type="button" :aria-expanded="focusId === 'identity'"
            @click="toggleModule('identity')">{{ focusId === 'identity' ? "沿原路收回" : "展开" }}</button>
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
      </div>
    </Transition>

    <Transition name="fams-module">
      <div v-show="isVisible('permissions')" class="fams-module-host" :class="{ 'is-focused': focusId === 'permissions' }">
        <div class="fams-module-bar">
          <button class="fams-module-toggle" type="button" :aria-expanded="focusId === 'permissions'"
            @click="toggleModule('permissions')">{{ focusId === 'permissions' ? "沿原路收回" : "展开" }}</button>
        </div>
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
      </div>
    </Transition>

    <Transition name="fams-thumbs">
      <nav v-if="focusId" class="fams-module-thumbs" aria-label="其他模块缩略">
        <button v-for="module in thumbnailModules" :key="module.id" class="fams-thumb" type="button"
          @click="focusModule(module.id)">{{ module.title }}</button>
        <button class="fams-thumb fams-thumb--close" type="button" @click="closeModules">沿原路收回</button>
      </nav>
    </Transition>
  </AppChrome>
</template>
