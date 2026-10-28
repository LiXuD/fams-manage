import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import AssetReturnView from "./AssetReturnView.vue";

const api = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(), loanRequest: api.request }));

const row = { id: "51", assetId: "21", loanId: "11", assetVersion: 7, version: 3, proposedBy: "1",
  responsibleEmployeeId: "9", reason: "设备退库", returnStatus: "WAITING_WAREHOUSE_CLAIM", processorAccountId: null,
  inspectionResult: null, inspectionReason: null, lockVersion: 4 };

async function fixture(items: unknown[] = [row]) {
  api.request.mockImplementation((path: string) => Promise.resolve(
    path === "/asset-returns?page=1&pageSize=20" ? { items, total: items.length, page: 1, pageSize: 20 }
      : { items: [], total: 0, page: 1, pageSize: 20 }));
  const wrapper = mount(AssetReturnView, { global: { stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, link: (text: string) => wrapper.findAll("a[data-destination]").find(item => item.text() === text)! };
}

describe("退还与退库列表", () => {
  afterEach(() => { vi.clearAllMocks(); });

  it("只取退还单一张表，行操作指向详情页", async () => {
    const f = await fixture();
    expect(api.request).toHaveBeenCalledTimes(1);
    expect(api.request).toHaveBeenCalledWith("/asset-returns?page=1&pageSize=20");
    expect(f.link("查看 / 审批 / 验收").attributes("data-destination")).toBe("/asset-returns/51");
    expect(f.link("提请退库").attributes("data-destination")).toBe("/asset-returns/new");
    expect(f.wrapper.text()).toContain("51");
    expect(f.wrapper.text()).toContain("待库管领取");
    f.wrapper.unmount();
  });

  it("没有退还单时给出下一步说明且不提供整单操作", async () => {
    const f = await fixture([]);
    expect(f.wrapper.text()).toContain("当前没有退还单");
    expect(f.wrapper.text()).toContain("借用归还或在用资产退库提交后会出现在这里。");
    expect(f.wrapper.findAll("a[data-destination]").map(item => item.text())).toEqual(["提请退库"]);
    expect(f.wrapper.findAll("button").map(item => item.text())).toEqual(["刷新", "上一页", "下一页"]);
    f.wrapper.unmount();
  });
});
