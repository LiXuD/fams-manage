import { createPinia } from "pinia";
import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { AuthApiError } from "@/auth/api";
import { useSessionStore } from "@/auth/sessionStore";
import PasswordChangeView from "./PasswordChangeView.vue";

describe("PasswordChangeView", () => {
  it("shows a rejected password once next to the form", async () => {
    const pinia = createPinia();
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/password/change", name: "password-change", component: PasswordChangeView }],
    });
    await router.push("/password/change");
    await router.isReady();
    const session = useSessionStore(pinia);
    session.state = "password-change-required";
    session.error = new AuthApiError(400, "AUTH_PASSWORD_INVALID", "Password must not contain the username.");

    const wrapper = mount(PasswordChangeView, { global: { plugins: [pinia, router] } });
    expect(wrapper.findAll("[role='alert']")).toHaveLength(1);
    expect(wrapper.get("form [role='alert']").text()).toContain("Password must not contain the username.");
  });
});
