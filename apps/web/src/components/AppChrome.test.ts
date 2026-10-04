import { mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import AppChrome from "./AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";

function mountChrome(permissions: string[] | null) {
  const pinia = createPinia();
  const router = createRouter({ history: createMemoryHistory(), routes: [] });
  const session = useSessionStore(pinia);
  if (permissions) {
    session.state = "authenticated";
    session.user = {
      id: "1", username: "alice", status: "ENABLED",
      roles: permissions.includes("ACCOUNT_ADMIN") ? ["SYSTEM_ADMIN"] : ["ASSET_ADMIN"],
      requiresInitialPasswordChange: false,
    };
    session.permissions = {
      userId: "1", permissions, dataScopes: [], fieldPermissions: [], authorizationVersion: 1,
    };
  }
  return mount(AppChrome, { global: { plugins: [pinia, router] } });
}

describe("AppChrome", () => {
  it("keeps navigation and logout hidden before authentication", () => {
    const wrapper = mountChrome(null);
    expect(wrapper.find("nav").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("退出");
  });

  it("only shows scoped modules and never links to unimplemented business routes", () => {
    const systemAdmin = mountChrome(["ACCOUNT_ADMIN"]);
    expect(systemAdmin.text()).toContain("账号与角色");
    expect(systemAdmin.text()).not.toContain("资产台账");
    expect(systemAdmin.find("a[href='#assets']").exists()).toBe(false);

    const assetAdmin = mountChrome(["ASSET_LEDGER_READ"]);
    expect(assetAdmin.text()).toContain("资产台账");
    expect(assetAdmin.text()).not.toContain("账号与角色");
  });
});
