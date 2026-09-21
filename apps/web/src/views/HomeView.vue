<script setup lang="ts">
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";

const session = useSessionStore();
</script>

<template>
  <AppChrome>
    <div class="breadcrumb">工作台 / 当前权限</div>
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
