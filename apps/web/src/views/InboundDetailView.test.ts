import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import InboundDetailView from "./InboundDetailView.vue";
const api = vi.hoisted(() => ({ listReceipts: vi.fn(), getReceipt: vi.fn(), submitReceipt: vi.fn(),
  cancelReceipt: vi.fn(), decideReceipt: vi.fn(), claimReceipt: vi.fn(), acceptItem: vi.fn() }));
const assetApi = vi.hoisted(() => ({ listAssets: vi.fn(), activeReferences: vi.fn(), getAsset: vi.fn() }));
const session = vi.hoisted(() => ({ roles: ["WAREHOUSE_OPERATOR"] as string[] }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: session.roles, id: "1" } }) }));
vi.mock("@/inbound/api", () => ({ ...api, createReceipt: vi.fn(), updateReceipt: vi.fn() }));
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
    expect(wrapper.text()).toContain("RK-FIND-001 · DRAFT");
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
    expect(wrapper.text()).toContain("RK-FIND-001 · PENDING_ADMIN_DECISION");
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
});
