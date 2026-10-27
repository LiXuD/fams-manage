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
    <div class="fams-breadcrumb">员工身份 / 首次登录</div>
    <section class="fams-auth-stage" aria-labelledby="password-title">
      <form class="fams-auth-card" novalidate @submit.prevent="submit">
        <h1 id="password-title">修改初始密码</h1>
        <p class="fams-auth-intro">完成密码修改后才能进入系统。</p>
        <div class="fams-field">
          <label for="current-password">当前密码</label>
          <input id="current-password" class="fams-control" v-model="form.currentPassword" type="password" autocomplete="current-password" required />
        </div>
        <div class="fams-field">
          <label for="new-password">新密码</label>
          <input id="new-password" class="fams-control" v-model="form.newPassword" type="password" autocomplete="new-password" minlength="12" required />
        </div>
        <div class="fams-field">
          <label for="confirm-password">确认新密码</label>
          <input id="confirm-password" class="fams-control" v-model="form.confirmPassword" type="password" autocomplete="new-password" minlength="12" required />
        </div>
        <p v-if="localError || session.error" class="fams-notice" data-state="error" role="alert"><strong>{{ localError || session.error?.message }}</strong></p>
        <button class="fams-button" data-kind="primary" type="submit" :disabled="session.isBusy">{{ session.isBusy ? "提交中…" : "保存并继续" }}</button>
      </form>
    </section>
  </AppChrome>
</template>
