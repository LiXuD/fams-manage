import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Role } from "@/auth/types";
import { useSessionStore } from "@/auth/sessionStore";
import AssetLedgerView from "./AssetLedgerView.vue";

const api = vi.hoisted(() => ({ list: vi.fn(), roles: ["WAREHOUSE_OPERATOR"] as Role[] }));
vi.mock("@/asset/api", async importOriginal => ({
  ...await importOriginal<typeof import("@/asset/api")>(), listAssets: api.list }));

const rows = [
  { id: "21", assetCode: "FA-2026-000101", name: "办公笔记本 ThinkPad T14", model: "T14 Gen 4", lifecycle: "IN_USE",
    exception: "NONE", cardStage: "ACTIVE", version: 7,
    currentCustody: { custodyType: "PERSONAL", responsibleEmployee: { id: "9" } }, physicalDestination: null },
  { id: "22", assetCode: "FA-2026-000102", name: "27 寸显示器", model: null, lifecycle: "IN_STOCK",
    exception: "NONE", cardStage: "PENDING_INBOUND", version: 3,
    currentCustody: { custodyType: "STORAGE", warehouse: { id: "4" } }, physicalDestination: null }];

async function fixture(total = 2, items: unknown[] = rows) {
  api.list.mockResolvedValue({ page: 1, pageSize: 20, total, items });
  const pinia = createPinia(); const session = useSessionStore(pinia);
  session.state = "authenticated";
  session.user = { id: "1", username: "wh", roles: api.roles, status: "ENABLED", requiresInitialPasswordChange: false };
  const wrapper = mount(AssetLedgerView, { global: { plugins: [pinia], stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, button: (text: string) => wrapper.findAll("button").find(b => b.text() === text)!,
    control: (id: string) => wrapper.find(`#${id}`) };
}

describe("资产台账列表", () => {
  afterEach(() => { vi.clearAllMocks(); api.roles = ["WAREHOUSE_OPERATOR"]; });

  it("只读台账一次，行操作与建卡入口指向路由", async () => {
    const f = await fixture();
    expect(api.list).toHaveBeenCalledTimes(1);
    expect(api.list).toHaveBeenCalledWith(1, "", "");
    expect(f.wrapper.text()).toContain("FA-2026-000101");
    expect(f.wrapper.text()).toContain("办公笔记本 ThinkPad T14");
    expect(f.wrapper.text()).toContain("个人保管 · 责任人 9");
    expect(f.wrapper.text()).toContain("在库 · 仓库 4");
    expect(f.wrapper.findAll("a[data-destination]").map(a => [a.text(), a.attributes("data-destination")]))
      .toEqual([["详情", "/assets/21"], ["详情", "/assets/22"], ["单个建卡", "/assets/new"]]);
    expect(f.wrapper.text()).toContain("共 2 项");
    f.wrapper.unmount();
  });

  it("非库管不出现建卡入口，翻页只改页码", async () => {
    api.roles = ["EMPLOYEE"];
    const f = await fixture(25);
    expect(f.wrapper.findAll("a[data-destination]").map(a => a.text())).toEqual(["详情", "详情"]);
    expect(f.button("上一页").attributes("disabled")).toBeDefined();
    f.button("下一页").trigger("click"); await flushPromises();
    expect(api.list).toHaveBeenLastCalledWith(2, "", "");
    expect(f.wrapper.text()).toContain("第 2 页");
    expect(f.wrapper.text()).toContain("共 25 项");
    f.wrapper.unmount();
  });

  it("查询回到第一页并带上编码与名称条件", async () => {
    const f = await fixture(25);
    f.button("下一页").trigger("click"); await flushPromises();
    await f.control("ledger-code").setValue("000101");
    await f.control("ledger-name").setValue("显示器");
    await f.wrapper.find("form").trigger("submit"); await flushPromises();
    expect(api.list).toHaveBeenLastCalledWith(1, "000101", "显示器");
    f.wrapper.unmount();
  });

  it("空结果与加载失败各用本域原句", async () => {
    const f = await fixture(0, []);
    expect(f.wrapper.text()).toContain("暂无符合条件的资产");
    expect(f.wrapper.text()).toContain("调整资产编码或名称条件后重新查询。");
    f.wrapper.unmount();
    api.list.mockRejectedValueOnce(new Error("接口暂不可用"));
    const g = await fixture();
    expect(g.wrapper.text()).toContain("接口暂不可用");
    g.wrapper.unmount();
  });
});
