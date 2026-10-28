import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import RequisitionDraftView from "./RequisitionDraftView.vue";
const api = vi.hoisted(() => ({ request: vi.fn(), catalogs: vi.fn(), push: vi.fn() }));
vi.mock("@/requisition/api", async importOriginal => ({ ...await importOriginal<typeof import("@/requisition/api")>(),
  requisitionRequest: api.request, loadRequisitionCatalogs: api.catalogs }));
vi.mock("vue-router", () => ({ useRouter: () => ({ push: api.push }) }));
const catalog = { catalogs: { category: [{ id: "7", version: 2, name: "笔记本电脑" }],
  organization: [{ id: "o1", version: 3, name: "研发部" }], employee: [{ id: "e1", version: 4, name: "张三", organizationId: "o1" }],
  location: [{ id: "l1", version: 5, name: "三层工位" }] }, canShared: true, employeeId: "e1", organizationId: "o1" };
const demand = { id: "501", requestId: "9007199254740993", categoryId: "7", categoryVersionSnapshot: 2, categoryNameSnapshot: "笔记本电脑",
  specification: "十四寸", purpose: "研发测试", expectedDate: "2026-11-02", custodyType: "PERSONAL", targetOrganizationId: "o1",
  organizationVersionSnapshot: 3, targetEmployeeId: "e1", employeeVersionSnapshot: 4, targetLocationId: "l1", locationVersionSnapshot: 5,
  requestedQuantity: 2, pendingAllocationQuantity: 2, pendingHandoverQuantity: 0, completedQuantity: 0, unfulfillableQuantity: 0,
  cancelledQuantity: 0, version: 1 };
const requisition = { id: "9007199254740993", requestNo: "LY-2026-0001", creatorAccountId: "1", creatorUsernameSnapshot: "zhang.san",
  requestStatus: "DRAFT", requestVersion: 1, version: 5, demands: [demand], allocations: [], decisions: [], unfulfillable: [],
  ownership: [], followups: [] };
const mountView = (id?: string) => mount(RequisitionDraftView, { props: { id }, global: { stubs: {
  AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
  RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
beforeEach(() => { api.catalogs.mockResolvedValue(catalog); });
afterEach(() => { vi.clearAllMocks(); });

describe("领用与借用申请草稿", () => {
  it("新建按空行提交 POST 并跳到新单详情", async () => {
    const writes: unknown[] = [];
    api.request.mockImplementation(async (_path: string, _method?: string, mutation?: { path: string }) => {
      if (mutation) { writes.push(JSON.parse(JSON.stringify(mutation))); return { ...requisition, id: "9007199254740994" }; }
      return { items: [], total: 0 };
    });
    const wrapper = mountView(); await flushPromises();
    expect(wrapper.text()).toContain("新建数量申请");
    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("十四寸"); await inputs[2].setValue("研发测试");
    await wrapper.find("form").trigger("submit"); await flushPromises();
    expect(writes[0]).toMatchObject({ path: "", method: "POST", version: 0,
      body: { requestType: "REQUISITION", expectedReturnDate: null,
        demands: [{ category: { id: "7", version: 2 }, specification: "十四寸", purpose: "研发测试", quantity: 1,
          organization: { id: "o1", version: 3 }, employee: { id: "e1", version: 4 }, location: { id: "l1", version: 5 } }] } });
    expect(api.push).toHaveBeenCalledExactlyOnceWith("/requisitions/9007199254740994?saved=1");
    wrapper.unmount();
  });
  it("修订回填需求行并按当前版本提交 PATCH", async () => {
    const writes: unknown[] = [];
    api.request.mockImplementation(async (_path: string, _method?: string, mutation?: { path: string }) => {
      if (mutation) { writes.push(JSON.parse(JSON.stringify(mutation))); return requisition; }
      return requisition;
    });
    const wrapper = mountView("9007199254740993"); await flushPromises();
    expect(api.request).toHaveBeenCalledWith("/9007199254740993");
    expect(wrapper.text()).toContain("修改草稿");
    expect(wrapper.findAll("input")[0].element.value).toBe("十四寸");
    await wrapper.find("form").trigger("submit"); await flushPromises();
    expect(writes[0]).toMatchObject({ path: "/9007199254740993", method: "PATCH", version: 5,
      body: { demands: [{ quantity: 2, specification: "十四寸" }] } });
    expect(api.push).toHaveBeenCalledExactlyOnceWith("/requisitions/9007199254740993?saved=1");
    wrapper.unmount();
  });
  it("非法单号既不拉目录也不请求单据", async () => {
    const wrapper = mountView("../other"); await flushPromises();
    expect(api.catalogs).not.toHaveBeenCalled();
    expect(api.request).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("没有此申请或操作的当前权限。");
    expect(wrapper.find("form").exists()).toBe(false);
    wrapper.unmount();
  });
});
