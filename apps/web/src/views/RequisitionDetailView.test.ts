import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import RequisitionDetailView from "./RequisitionDetailView.vue";
const api = vi.hoisted(() => ({ request: vi.fn(), catalogs: vi.fn(), roles: ["ASSET_ADMIN"] as string[] }));
vi.mock("@/requisition/api", async importOriginal => ({ ...await importOriginal<typeof import("@/requisition/api")>(),
  requisitionRequest: api.request, loadRequisitionCatalogs: api.catalogs }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { id: "1", roles: api.roles } }) }));
const catalog = { catalogs: { category: [{ id: "7", version: 2, name: "笔记本电脑" }],
  organization: [{ id: "o1", version: 3, name: "研发部" }], employee: [{ id: "e1", version: 4, name: "张三", organizationId: "o1" }],
  location: [{ id: "l1", version: 5, name: "三层工位" }] }, canShared: true, employeeId: "e1", organizationId: "o1" };
const demand = { id: "501", requestId: "9007199254740993", categoryId: "7", categoryVersionSnapshot: 2, categoryNameSnapshot: "笔记本电脑",
  specification: "十四寸", purpose: "研发测试", expectedDate: "2026-11-02", custodyType: "PERSONAL", targetOrganizationId: "o1",
  organizationVersionSnapshot: 3, targetEmployeeId: "e1", employeeVersionSnapshot: 4, targetLocationId: "l1", locationVersionSnapshot: 5,
  requestedQuantity: 2, pendingAllocationQuantity: 2, pendingHandoverQuantity: 0, completedQuantity: 0, unfulfillableQuantity: 0,
  cancelledQuantity: 0, version: 1 };
const draft = { id: "9007199254740993", requestNo: "LY-2026-0001", creatorAccountId: "1", creatorUsernameSnapshot: "zhang.san",
  requestStatus: "DRAFT", requestVersion: 1, version: 5, demands: [demand], allocations: [], decisions: [], unfulfillable: [],
  ownership: [], followups: [] };
const inFlight = { ...draft, requestStatus: "WAREHOUSE_PROCESSING" };
const mountView = (id: string) => mount(RequisitionDetailView, { props: { id }, global: { stubs: {
  AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
  RequisitionReassignment: true,
  RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
const click = async (wrapper: ReturnType<typeof mountView>, text: string) => {
  await wrapper.findAll("button").find((button) => button.text() === text)?.trigger("click"); await flushPromises(); };
beforeEach(() => { api.catalogs.mockResolvedValue(catalog); });
afterEach(() => { window.history.replaceState(null, "", "/"); vi.clearAllMocks();
  api.roles = ["ASSET_ADMIN"]; });

describe("领用与借用申请详情", () => {
  it("只按合法且原样的字符串单号取单，草稿给出修订入口与提交按钮", async () => {
    api.request.mockResolvedValue(draft);
    const wrapper = mountView("9007199254740993"); await flushPromises();
    expect(api.request).toHaveBeenCalledWith("/9007199254740993");
    expect(wrapper.text()).toContain("LY-2026-0001 · 草稿");
    expect(wrapper.text()).not.toContain("新建数量申请");
    expect(wrapper.findAll("a[data-destination]").map((link) => [link.text(), link.attributes("data-destination")]))
      .toContainEqual(["修改草稿", "/requisitions/9007199254740993/edit"]);
    wrapper.unmount();
  });
  it("非法单号不向服务端发出", async () => {
    api.request.mockResolvedValue(draft);
    const wrapper = mountView("../other"); await flushPromises();
    expect(api.request).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("没有此申请或操作的当前权限。");
    expect(wrapper.text()).not.toContain("LY-2026-0001");
    wrapper.unmount();
  });
  it("提交管理员确认复用同一条写请求并刷新状态", async () => {
    api.request.mockImplementation(async (_path: string, _method?: string, mutation?: unknown) => {
      if (mutation) return { ...draft, requestStatus: "PENDING_ADMIN_DECISION", version: 6 };
      return draft;
    });
    const wrapper = mountView("9007199254740993"); await flushPromises();
    await click(wrapper, "提交管理员确认");
    expect(api.request).toHaveBeenLastCalledWith("/9007199254740993/submit", "POST",
      expect.objectContaining({ path: "/9007199254740993/submit", version: 5 }));
    expect(wrapper.text()).toContain("LY-2026-0001 · 待管理员确认");
    expect(wrapper.text()).toContain("操作已保存。");
    wrapper.unmount();
  });
  it("提请范围变更在详情页展开需求表单并提交到 changes", async () => {
    const writes: unknown[] = [];
    api.request.mockImplementation(async (_path: string, _method?: string, mutation?: { path: string }) => {
      if (mutation) { writes.push(JSON.parse(JSON.stringify(mutation))); return inFlight; }
      return inFlight;
    });
    const wrapper = mountView("9007199254740993"); await flushPromises();
    await click(wrapper, "提请范围变更");
    expect(wrapper.text()).toContain("提请批准范围变更");
    expect(wrapper.findAll("input")[0].element.value).toBe("十四寸");
    await wrapper.find("form").trigger("submit"); await flushPromises();
    expect(writes[0]).toMatchObject({ path: "/9007199254740993/changes", method: "POST", version: 5,
      body: { requestType: "REQUISITION", demands: [{ quantity: 2, specification: "十四寸" }] } });
    expect(wrapper.text()).not.toContain("提请批准范围变更");
    wrapper.unmount();
  });
  it("保存草稿跳转来的地址栏标记只播报一次", async () => {
    window.history.replaceState(null, "", "/requisitions/9007199254740993?saved=1");
    api.request.mockResolvedValue(draft);
    const wrapper = mountView("9007199254740993"); await flushPromises();
    expect(wrapper.text()).toContain("操作已保存。");
    expect(window.location.search).toBe("");
    wrapper.unmount();
  });
});
