import { describe, expect, it } from "vitest";
import { router } from "./router";

const NAV_GROUPS = ["总览", "资产", "业务流转", "异常与核查"];
/** 登录与首次改密使用独立版面，不套外壳页头条。 */
const BARE_ROUTES = new Set(["login", "password-change"]);

describe("router 页头元数据", () => {
  it("每个业务路由都带分组与标题，供外壳统一渲染页头", () => {
    const routes = router
      .getRoutes()
      .filter((route) => route.meta.requiresAuth && !BARE_ROUTES.has(String(route.name)));
    expect(routes.length).toBeGreaterThan(0);
    for (const route of routes) {
      expect(NAV_GROUPS, `${String(route.name)} 的页头分组不在侧栏分组内`).toContain(route.meta.uiGroup);
      expect(route.meta.uiTitle, `${String(route.name)} 缺少页头标题`).toBeTruthy();
    }
  });
});
