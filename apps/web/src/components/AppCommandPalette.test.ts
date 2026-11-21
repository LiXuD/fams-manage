import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { afterEach, describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter, type RouteRecordRaw } from "vue-router";
import AppCommandPalette from "./AppCommandPalette.vue";

const Blank = { template: "<div />" };

const ROUTES: RouteRecordRaw[] = [
  { path: "/", name: "home", component: Blank, meta: { requiresAuth: true, uiGroup: "总览", uiTitle: "当前身份与权限" } },
  { path: "/assets", name: "assets", component: Blank, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "资产台账" } },
  { path: "/assets/:id", name: "asset-detail", component: Blank, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "资产详情" } },
  { path: "/login", name: "login", component: Blank },
];

const mounted: VueWrapper[] = [];

async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: ROUTES });
  await router.push("/login");
  await router.isReady();
  mounted.push(mount(AppCommandPalette, { attachTo: document.body, global: { plugins: [router] } }));
  return router;
}

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()?.unmount();
  }
});

function pressKey(key: string, init: KeyboardEventInit = {}): void {
  window.dispatchEvent(new KeyboardEvent("keydown", { key, ...init }));
}

describe("AppCommandPalette", () => {
  it("opens on Cmd+K, filters with highlight, jumps on Enter and closes", async () => {
    const router = await setup();
    expect(document.querySelector(".fams-cmd-overlay")).toBeNull();

    pressKey("k", { metaKey: true });
    await flushPromises();
    const list = document.querySelector(".fams-cmd-list");
    expect(list?.textContent).toContain("资产台账");
    // 带路径参数的详情路由无法直达，登录页不属于授权命令源。
    expect(list?.textContent).not.toContain("资产详情");
    expect(list?.textContent).not.toContain("登录");

    const input = document.querySelector(".fams-cmd-input") as HTMLInputElement;
    input.value = "台账";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    await flushPromises();
    const options = document.querySelectorAll(".fams-cmd-list [role='option']");
    expect(options).toHaveLength(1);
    expect(options[0].querySelector("mark")?.textContent).toBe("台账");

    input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter" }));
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/assets");
    expect(document.querySelector(".fams-cmd-overlay")).toBeNull();
  });

  it("toggles closed with a second Cmd+K and Escape closes without navigating", async () => {
    const router = await setup();
    pressKey("k", { metaKey: true });
    await flushPromises();
    expect(document.querySelectorAll(".fams-cmd-overlay")).toHaveLength(1);

    pressKey("k", { ctrlKey: true });
    await flushPromises();
    expect(document.querySelector(".fams-cmd-overlay")).toBeNull();

    pressKey("k", { metaKey: true });
    await flushPromises();
    const input = document.querySelector(".fams-cmd-input") as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await flushPromises();
    expect(document.querySelector(".fams-cmd-overlay")).toBeNull();
    expect(router.currentRoute.value.path).toBe("/login");
  });
});
