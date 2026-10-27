<script setup lang="ts">
import { computed } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";

const session = useSessionStore();
const permissions = computed(() => session.permissions?.permissions ?? []);
</script>

<template>
  <AppChrome>
    <div class="fams-metric-grid">
      <div class="fams-metric">
        <span>有效权限</span>
        <strong>{{ permissions.length }}</strong>
        <small>由服务端按当前会话返回</small>
      </div>
      <div class="fams-metric">
        <span>授权版本</span>
        <strong>{{ session.permissions?.authorizationVersion ?? "—" }}</strong>
        <small>变更后本页重新拉取</small>
      </div>
    </div>

    <section class="fams-panel" aria-labelledby="identity-title">
      <div class="fams-panel-head">
        <h2 id="identity-title" class="fams-panel-title">当前身份</h2>
      </div>
      <dl class="fams-facts">
        <div><dt>账号</dt><dd>{{ session.user?.username }}</dd></div>
        <div><dt>角色</dt><dd class="u-break">{{ session.user?.roles.join("、") }}</dd></div>
      </dl>
    </section>

    <section class="fams-panel" aria-labelledby="permission-title">
      <div class="fams-panel-head">
        <h2 id="permission-title" class="fams-panel-title">有效权限</h2>
        <span class="fams-status" data-tone="info">{{ permissions.length }} 项</span>
      </div>
      <div class="fams-inline" aria-label="有效权限">
        <span v-for="permission in permissions" :key="permission" class="fams-status">{{ permission }}</span>
      </div>
      <p v-if="!permissions.length" class="fams-panel-subtitle">当前没有可展示的业务动作</p>
    </section>
  </AppChrome>
</template>
