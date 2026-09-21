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
    <div class="breadcrumb">员工身份 / 系统入口</div>
    <section class="auth-stage" aria-labelledby="login-title">
      <form class="auth-form" novalidate @submit.prevent="submit">
        <h1 id="login-title">登录固定资产管理</h1>
        <p class="auth-intro">使用企业员工账号继续</p>
        <label for="username">账号</label>
        <input
          id="username"
          v-model="form.username"
          name="username"
          type="text"
          autocomplete="username"
          maxlength="128"
          required
        />
        <label for="password">密码</label>
        <input
          id="password"
          v-model="form.password"
          name="password"
          type="password"
          autocomplete="current-password"
          maxlength="128"
          required
        />
        <p v-if="session.error" class="form-error" role="alert">{{ session.error.message }}</p>
        <button class="primary-button" type="submit" :disabled="session.isBusy || !form.username || !form.password">
          {{ session.isBusy ? "登录中…" : "登录" }}
        </button>
        <p class="auth-note">首次登录将要求修改初始密码。账号停用后无法继续访问。</p>
      </form>
    </section>
  </AppChrome>
</template>
