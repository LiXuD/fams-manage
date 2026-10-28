import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import InboundView from "./InboundView.vue";
const api = vi.hoisted(() => ({ listReceipts: vi.fn(), getReceipt: vi.fn() }));
const assetApi = vi.hoisted(() => ({ listAssets: vi.fn(), activeReferences: vi.fn(), getAsset: vi.fn() }));
const session = vi.hoisted(() => ({ roles: ["ASSET_ADMIN"] as string[] }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: session.roles, id: "1" } }) }));
vi.mock("@/inbound/api", () => ({ ...api, acceptItem: vi.fn(), cancelReceipt: vi.fn(), claimReceipt: vi.fn(), createReceipt: vi.fn(),
  decideReceipt: vi.fn(), submitReceipt: vi.fn(), updateReceipt: vi.fn() }));
vi.mock("@/asset/api", () => assetApi);
const links = (wrapper: ReturnType<typeof mount>) => wrapper.findAll("a[data-destination]").map((link) =>
  [link.text(), String(link.attributes("data-destination"))]);
const panel = () => mount(InboundView, { global: { stubs: {
  AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
  RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
afterEach(() => { session.roles = ["ASSET_ADMIN"]; vi.clearAllMocks(); });

describe("入库单列表", () => {
  it("只读列表：行链接指向详情路由，不加载建表单所需的目录", async () => {
    api.listReceipts.mockResolvedValue({ items: [{ id: "9007199254740993", receiptNumber: "RK-001", status: "DRAFT",
      businessDate: "2026-10-19", version: 1, items: [], createdBy: { id: "1", username: "warehouse" } }], total: 1 });
    const wrapper = panel(); await flushPromises();
    expect(api.getReceipt).not.toHaveBeenCalled();
    expect(assetApi.listAssets).not.toHaveBeenCalled();
    expect(assetApi.activeReferences).not.toHaveBeenCalled();
    expect(links(wrapper)).toEqual([["详情", "/inbound/9007199254740993"]]);
    wrapper.unmount();
  });
  it("库管得到建立入库单入口，管理员没有", async () => {
    api.listReceipts.mockResolvedValue({ items: [], total: 0 });
    session.roles = ["WAREHOUSE_OPERATOR"];
    const warehouse = panel(); await flushPromises();
    expect(warehouse.text()).toContain("暂无入库单");
    expect(links(warehouse)).toEqual([["建立入库单", "/inbound/new"]]);
    warehouse.unmount();
    session.roles = ["ASSET_ADMIN"];
    api.listReceipts.mockResolvedValue({ items: [], total: 0 });
    const admin = panel(); await flushPromises();
    expect(links(admin)).toEqual([]);
    admin.unmount();
  });
});
