<script setup lang="ts">
import { reactive } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";

const router = useRouter();
const session = useSessionStore();
const form = reactive({ username: "", password: "" });

async function submit(): Promise<void> {
  try {
    await session.signIn(form.username.trim(), form.password);
    await router.replace(session.state === "password-change-required"
      ? { name: "password-change" }
      : { name: "home" });
  } catch {
    // The store exposes the contract-safe message next to the form.
  }
}
</script>

<template>
  <AppChrome>
    <div class="fams-breadcrumb">员工身份 / 系统入口</div>
    <section class="fams-auth-stage" aria-labelledby="login-title">
      <form class="fams-auth-card" novalidate @submit.prevent="submit">
        <h1 id="login-title">登录固定资产管理</h1>
        <p class="fams-auth-intro">使用企业员工账号继续</p>
        <div class="fams-field">
          <label for="username">账号</label>
          <input id="username" class="fams-control" v-model="form.username" name="username" type="text"
            autocomplete="username" maxlength="128" required />
        </div>
        <div class="fams-field">
          <label for="password">密码</label>
          <input id="password" class="fams-control" v-model="form.password" name="password" type="password"
            autocomplete="current-password" maxlength="128" required />
        </div>
        <section v-if="session.error" class="fams-notice" data-state="error" role="alert">
          <strong>{{ session.error.message }}</strong>
        </section>
        <button class="fams-button" data-kind="primary" type="submit" :disabled="session.isBusy || !form.username || !form.password"
          :data-state="session.isBusy ? 'loading' : null">
          <span v-if="session.isBusy" class="fams-spinner" aria-hidden="true" />
          {{ session.isBusy ? "登录中…" : "登录" }}
        </button>
        <p class="fams-auth-note">首次登录将要求修改初始密码。账号停用后无法继续访问。</p>
      </form>
    </section>
  </AppChrome>
</template>
