import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import InboundDetailView from "./InboundDetailView.vue";
const api = vi.hoisted(() => ({ listReceipts: vi.fn(), getReceipt: vi.fn(), submitReceipt: vi.fn(),
  cancelReceipt: vi.fn(), decideReceipt: vi.fn(), claimReceipt: vi.fn(), acceptItem: vi.fn() }));
const assetApi = vi.hoisted(() => ({ listAssets: vi.fn(), activeReferences: vi.fn(), getAsset: vi.fn() }));
const session = vi.hoisted(() => ({ roles: ["WAREHOUSE_OPERATOR"] as string[] }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: session.roles, id: "1" } }) }));
vi.mock("@/inbound/api", async importOriginal => ({ ...await importOriginal<typeof import("@/inbound/api")>(), ...api, createReceipt: vi.fn(), updateReceipt: vi.fn() }));
vi.mock("@/asset/api", () => assetApi);
const draft = { id: "9007199254740993", receiptNumber: "RK-FIND-001", status: "DRAFT", businessDate: "2026-10-19",
  version: 1, items: [], createdBy: { id: "1", username: "warehouse" } };
const panel = (id: string) => mount(InboundDetailView, { props: { id }, global: { stubs: {
  AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
  InboundReassignment: true, AttachmentPanel: true,
  RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
afterEach(() => { window.history.replaceState(null, "", "/"); session.roles = ["WAREHOUSE_OPERATOR"]; vi.clearAllMocks(); });

describe("入库单详情", () => {
  it("只用合法且原样的字符串单号请求详情", async () => {
    api.getReceipt.mockResolvedValue(draft);
    const wrapper = panel("9007199254740993"); await flushPromises();
    expect(api.getReceipt).toHaveBeenCalledExactlyOnceWith("9007199254740993");
    expect(wrapper.text()).toContain("RK-FIND-001 · 草稿");
    expect(wrapper.text()).not.toContain("待入库资产");
    wrapper.unmount();
  });
  it("非法单号不向服务端发出", async () => {
    api.getReceipt.mockResolvedValue(draft);
    const malformed = panel("../other"); await flushPromises();
    expect(api.getReceipt).not.toHaveBeenCalled();
    expect(malformed.text()).toContain("详情加载失败");
    malformed.unmount();
  });
  it("草稿单给出修订入口与提交审批，并按服务端返回更新状态", async () => {
    api.getReceipt.mockResolvedValue(draft);
    api.submitReceipt.mockResolvedValue({ ...draft, status: "PENDING_ADMIN_DECISION", version: 2 });
    const wrapper = panel("9007199254740993"); await flushPromises();
    const revise = wrapper.findAll("a[data-destination]").find((link) => link.text() === "修订草稿");
    expect(revise?.attributes("data-destination")).toBe("/inbound/9007199254740993/edit");
    const submit = wrapper.findAll("button").find((button) => button.text() === "提交审批");
    await submit?.trigger("click"); await flushPromises();
    expect(api.submitReceipt).toHaveBeenCalledExactlyOnceWith(draft);
    expect(wrapper.text()).toContain("入库单状态已更新");
    expect(wrapper.text()).toContain("RK-FIND-001 · 待管理员确认");
    wrapper.unmount();
  });
  it("保存草稿跳转来的地址栏标记只播报一次", async () => {
    window.history.replaceState(null, "", "/inbound/9007199254740993?saved=1");
    api.getReceipt.mockResolvedValue(draft);
    const wrapper = panel("9007199254740993"); await flushPromises();
    expect(wrapper.text()).toContain("入库草稿 RK-FIND-001 已保存");
    expect(window.location.search).toBe("");
    wrapper.unmount();
  });
  it("取消先在页内确认块收原因，缺依据时零请求", async () => {
    api.getReceipt.mockResolvedValue(draft);
    api.cancelReceipt.mockResolvedValue({ ...draft, status: "CANCELLED", version: 2 });
    const wrapper = panel("9007199254740993"); await flushPromises();
    const cancel = wrapper.findAll("button").find((button) => button.text() === "取消")!;
    await cancel.trigger("click"); await flushPromises();
    expect(api.cancelReceipt).not.toHaveBeenCalled();
    await wrapper.find("div[role=\"group\"] input").setValue("重复单");
    const confirm = wrapper.findAll("button").find((button) => button.text() === "确认取消")!;
    await confirm.trigger("click"); await flushPromises();
    expect(api.cancelReceipt).toHaveBeenCalledExactlyOnceWith(draft, "重复单");
    expect(wrapper.text()).toContain("入库单状态已更新");
    wrapper.unmount();
  });
  it("验收入库先在页内确认块核对实物编码", async () => {
    const item = { id: "i1", asset: { id: "21", version: 3 }, executionStatus: "PENDING",
      assetSnapshot: { assetCode: "FA-2026-000101", assetName: "办公笔记本" } };
    api.getReceipt.mockResolvedValue({ ...draft, status: "WAREHOUSE_PROCESSING", items: [item],
      currentProcessor: { processor: { id: "1", username: "warehouse" } } });
    assetApi.getAsset.mockResolvedValue({ id: "21", version: 3 });
    api.acceptItem.mockResolvedValue({ receipt: { ...draft, status: "COMPLETED", items: [] } });
    const wrapper = panel("9007199254740993"); await flushPromises();
    const accept = wrapper.findAll("button").find((button) => button.text() === "验收入库")!;
    await accept.trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain("核对实物编码：FA-2026-000101");
    expect(api.acceptItem).not.toHaveBeenCalled();
    await wrapper.find("div[role=\"group\"] input").setValue("FA-2026-000101");
    const confirm = wrapper.findAll("button").find((button) => button.text() === "确认验收入库")!;
    await confirm.trigger("click"); await flushPromises();
    expect(api.acceptItem).toHaveBeenCalledExactlyOnceWith(
      { ...draft, status: "WAREHOUSE_PROCESSING", items: [item], currentProcessor: { processor: { id: "1", username: "warehouse" } } },
      item, 3, "FA-2026-000101");
    expect(wrapper.text()).toContain("FA-2026-000101 已验收入库");
    wrapper.unmount();
  });
});
