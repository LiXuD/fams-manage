import { mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter, type RouteRecordRaw } from "vue-router";
import AppChrome from "./AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";

const Blank = { template: "<div />" };

async function mountChrome(permissions: string[] | null, routes: RouteRecordRaw[] = [], initialPath = "/", slots?: Record<string, string>) {
  const pinia = createPinia();
  const router = createRouter({ history: createMemoryHistory(), routes });
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
  if (routes.length) { await router.push(initialPath); await router.isReady(); }
  return mount(AppChrome, { global: { plugins: [pinia, router] }, slots });
}

const LEDGER_ROUTES: RouteRecordRaw[] = [
  { path: "/", name: "home", component: Blank },
  {
    path: "/assets", name: "assets", component: Blank,
    meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "资产台账", uiDesc: "查看资产状态、建卡和标签。" },
  },
];

describe("AppChrome", () => {
  it("keeps navigation and logout hidden before authentication", async () => {
    const wrapper = await mountChrome(null);
    expect(wrapper.find("nav").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("退出");
  });

  it("only shows scoped modules and never links to unimplemented business routes", async () => {
    const systemAdmin = await mountChrome(["ACCOUNT_ADMIN"]);
    expect(systemAdmin.text()).toContain("账号与角色");
    expect(systemAdmin.text()).not.toContain("资产台账");
    expect(systemAdmin.find("a[href='#assets']").exists()).toBe(false);

    const assetAdmin = await mountChrome(["ASSET_LEDGER_READ"]);
    expect(assetAdmin.text()).toContain("资产台账");
    expect(assetAdmin.text()).not.toContain("账号与角色");
  });

  it("renders one page head from route meta and none without it", async () => {
    const ledger = await mountChrome(["ASSET_LEDGER_READ"], LEDGER_ROUTES, "/assets");
    const head = ledger.find("main > .fams-page-head");
    expect(head.exists()).toBe(true);
    expect(head.findAll("h1")).toHaveLength(1);
    expect(head.text()).toContain("资产 / 资产台账");
    expect(head.text()).toContain("查看资产状态、建卡和标签。");

    const home = await mountChrome(["ASSET_LEDGER_READ"], LEDGER_ROUTES, "/");
    expect(home.find("main > .fams-page-head").exists()).toBe(false);
  });

  it("marks the sidebar entry of the current page including its detail routes", async () => {
    const routes: RouteRecordRaw[] = [...LEDGER_ROUTES, { path: "/assets/:id", name: "asset-detail", component: Blank }];
    const detail = await mountChrome(["ASSET_LEDGER_READ"], routes, "/assets/12");
    expect(detail.findAll(".fams-navigation a").filter(a => a.attributes("aria-current")).map(a => a.text())).toEqual(["资产台账"]);
    const home = await mountChrome(["ASSET_LEDGER_READ"], routes, "/");
    expect(home.findAll(".fams-navigation a").filter(a => a.attributes("aria-current")).map(a => a.text())).toEqual(["工作台"]);
  });

  it("places the view's primary action inside the page head", async () => {    const wrapper = await mountChrome(["ASSET_LEDGER_READ"], LEDGER_ROUTES, "/assets", {
      actions: '<button class="fams-button" type="button">单个建卡</button>',
    });
    expect(wrapper.find("main > .fams-page-head button").text()).toBe("单个建卡");
  });
});
