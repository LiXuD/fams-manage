import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import RequisitionView from "./RequisitionView.vue";
const api = vi.hoisted(() => ({ request: vi.fn(), catalogs: vi.fn() }));
vi.mock("@/requisition/api", async importOriginal => ({ ...await importOriginal<typeof import("@/requisition/api")>(),
  requisitionRequest: api.request, loadRequisitionCatalogs: api.catalogs }));
const page = (total = 1) => ({ items: total ? [{ id: "9007199254740993", requestNo: "LY-2026-0001", creatorAccountId: "1",
  creatorUsernameSnapshot: "zhang.san", requestStatus: "DRAFT", requestVersion: 1, version: 1 }] : [], total, page: 1, pageSize: 20 });
const links = (wrapper: ReturnType<typeof mount>) => wrapper.findAll("a[data-destination]").map((link) =>
  [link.text(), String(link.attributes("data-destination"))]);
const mountView = () => mount(RequisitionView, { global: { stubs: {
  AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
  RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
afterEach(() => { vi.clearAllMocks(); });

describe("领用与借用申请列表", () => {
  it("列表只发一次查询、不拉目录，行链接指向详情路由", async () => {
    api.request.mockResolvedValue(page());
    const wrapper = mountView(); await flushPromises();
    expect(api.request).toHaveBeenCalledExactlyOnceWith("?page=1&pageSize=20");
    expect(api.catalogs).not.toHaveBeenCalled();
    expect(links(wrapper)).toEqual([["查看申请", "/requisitions/9007199254740993"], ["新建申请", "/requisitions/new"]]);
    expect(wrapper.text()).toContain("LY-2026-0001");
    expect(wrapper.text()).toContain("草稿");
    wrapper.unmount();
  });
  it("状态筛选与翻页都把条件带回服务端", async () => {
    api.request.mockResolvedValue(page(0));
    const wrapper = mountView(); await flushPromises();
    api.request.mockResolvedValue(page(25));
    await wrapper.find("select").setValue("DRAFT"); await flushPromises();
    expect(api.request).toHaveBeenLastCalledWith("?page=1&pageSize=20&status=DRAFT");
    await wrapper.findAll("button").find((button) => button.text() === "下一页")?.trigger("click"); await flushPromises();
    expect(api.request).toHaveBeenLastCalledWith("?page=2&pageSize=20&status=DRAFT");
    expect(wrapper.text()).toContain("共 25 条");
    wrapper.unmount();
  });
});
