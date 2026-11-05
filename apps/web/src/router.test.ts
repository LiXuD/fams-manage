import { describe, expect, it } from "vitest";
import { router } from "./router";

const NAV_GROUPS = ["总览", "资产", "业务流转", "异常与核查"];
/** 登录与首次改密使用独立版面，不套外壳页头条。 */
const BARE_ROUTES = new Set(["login", "password-change"]);
/** 首屏入口三页同步加载，其余视图按路由切分。 */
const EAGER_ROUTES = new Set(["home", "login", "password-change"]);

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

  // 顺序解析 37 个路由 chunk，耗时随全量套件的 worker 竞争浮动，默认 5s 上限贴边。
  it("除入口三页外每条路由都是可解析的懒加载工厂", async () => {
    const lazy = router.getRoutes().filter((route) => !EAGER_ROUTES.has(String(route.name)));
    expect(lazy).toHaveLength(37);
    for (const route of lazy) {
      const factory: unknown = route.components?.default;
      expect(typeof factory, `${String(route.name)} 未改为懒加载`).toBe("function");
      const namespace = await (factory as () => Promise<{ default?: unknown }>)();
      expect(typeof namespace.default, `${String(route.name)} 未解析出视图组件`).toBe("object");
    }
  }, 30_000);
});
