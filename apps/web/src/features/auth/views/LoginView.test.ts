import { createPinia } from "pinia";
import { mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import { createRouter, createMemoryHistory } from "vue-router";
import LoginView from "./LoginView.vue";
import { useSessionStore } from "@/auth/sessionStore";

describe("LoginView", () => {
  it("submits a real account form and preserves the D0.4 P01 copy", async () => {
    const pinia = createPinia();
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/login", component: LoginView }] });
    await router.push("/login");
    await router.isReady();
    const store = useSessionStore(pinia);
    store.signIn = vi.fn().mockResolvedValue(undefined);

    const wrapper = mount(LoginView, {
      global: { plugins: [pinia, router] },
    });

    expect(wrapper.get("h1").text()).toBe("登录固定资产管理");
    expect(wrapper.findAll("button")).toHaveLength(1);
    await wrapper.get("input[name='username']").setValue("system.admin");
    await wrapper.get("input[name='password']").setValue("password");
    await wrapper.get("form").trigger("submit");

    expect(store.signIn).toHaveBeenCalledWith("system.admin", "password");
  });
});
