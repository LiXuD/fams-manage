<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";

const router = useRouter();
const session = useSessionStore();
const form = reactive({ currentPassword: "", newPassword: "", confirmPassword: "" });
const localError = ref("");

async function submit(): Promise<void> {
  localError.value = "";
  if (form.newPassword.length < 12) {
    localError.value = "新密码至少需要 12 个字符。";
    return;
  }
  if (form.newPassword !== form.confirmPassword) {
    localError.value = "两次输入的新密码不一致。";
    return;
  }
  try {
    await session.completeInitialPasswordChange(form.currentPassword, form.newPassword);
    await router.replace({ name: "home" });
  } catch {
    // Store error remains visible and contains only the safe contract message.
  }
}
</script>

<template>
  <AppChrome>
    <div class="breadcrumb">员工身份 / 首次登录</div>
    <section class="auth-stage" aria-labelledby="password-title">
      <form class="auth-form" novalidate @submit.prevent="submit">
        <h1 id="password-title">修改初始密码</h1>
        <p class="auth-intro">完成密码修改后才能进入系统。</p>
        <label for="current-password">当前密码</label>
        <input id="current-password" v-model="form.currentPassword" type="password" autocomplete="current-password" required />
        <label for="new-password">新密码</label>
        <input id="new-password" v-model="form.newPassword" type="password" autocomplete="new-password" minlength="12" required />
        <label for="confirm-password">确认新密码</label>
        <input id="confirm-password" v-model="form.confirmPassword" type="password" autocomplete="new-password" minlength="12" required />
        <p v-if="localError || session.error" class="form-error" role="alert">{{ localError || session.error?.message }}</p>
        <button class="primary-button" type="submit" :disabled="session.isBusy">{{ session.isBusy ? "提交中…" : "保存并继续" }}</button>
      </form>
    </section>
  </AppChrome>
</template>
