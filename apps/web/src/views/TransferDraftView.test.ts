import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import TransferDraftView from "./TransferDraftView.vue";

const api = vi.hoisted(() => ({ request: vi.fn(), push: vi.fn() }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(), loanRequest: api.request }));
vi.mock("vue-router", () => ({ useRouter: () => ({ push: api.push }) }));

const asset = { id: "a21", assetCode: "A21", name: "笔记本电脑", version: 7, lifecycleStatus: "IN_USE", custodyType: "PERSONAL",
  owningOrganizationId: null, usingOrganizationId: "o1", responsibleEmployeeId: "e1", storageWarehouseId: null, currentLocationId: "l1" };
const organizations = [{ id: "o1", name: "使用部", version: 4 }, { id: "o2", name: "所属部", version: 5 }];
const locations = [{ id: "l1", name: "301", version: 6, locationType: "ROOM", warehouseId: "w1" }];
const employees = [{ id: "e1", name: "张三", version: 2, sharedEligible: false }];

async function fixture(lose = false) {
  const writes: unknown[] = [];
  let lost = lose;
  api.request.mockImplementation(async (path: string, mutation?: unknown) => {
    if (mutation) {
      writes.push(structuredClone(mutation));
      if (lost) { lost = false; throw new Error("lost response"); }
      return { id: "88" };
    }
    if (path.startsWith("/transfers/eligible-assets")) return { items: [asset], total: 1 };
    if (path.startsWith("/transfers/catalog/organization")) return { items: organizations };
    if (path.startsWith("/transfers/catalog/location")) return { items: locations };
    if (path.startsWith("/transfers/catalog/employee")) return { items: employees };
    if (path.startsWith("/transfers/catalog/warehouse")) return { items: [{ id: "w1", name: "中心仓", version: 3 }] };
    throw new Error("unexpected read " + path);
  });
  const wrapper = mount(TransferDraftView, { global: { stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, writes, button: (text: string) => wrapper.findAll("button").find(item => item.text() === text)! };
}

describe("新建调拨建单", () => {
  afterEach(() => { vi.clearAllMocks(); });

  it("只取可调拨资产与四类目录，返回入口指向列表", async () => {
    const f = await fixture();
    expect(f.wrapper.findAll("select")).toHaveLength(6);
    expect(api.request).toHaveBeenCalledWith("/transfers/eligible-assets?page=1&pageSize=20");
    expect(api.request).toHaveBeenCalledWith("/transfers/catalog/organization?page=1&pageSize=200");
    expect(api.request).toHaveBeenCalledWith("/transfers/catalog/warehouse?page=1&pageSize=200");
    expect(api.request).toHaveBeenCalledWith("/transfers/catalog/location?page=1&pageSize=200");
    expect(f.wrapper.text()).toContain("A21 · 笔记本电脑");
    expect(f.wrapper.findAll("a[data-destination]").map(item => [item.text(), item.attributes("data-destination")]))
      .toEqual([["返回调拨列表", "/transfers"]]);
    f.wrapper.unmount();
  });

  it("没有明细时保存草稿按钮禁用且零写请求", async () => {
    const f = await fixture();
    expect(f.button("保存调拨草稿").attributes("disabled")).toBeDefined();
    f.button("保存调拨草稿").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    f.wrapper.unmount();
  });

  it("选定来源资产后添加明细并提交草稿，成功后跳转详情页", async () => {
    const f = await fixture();
    await f.wrapper.findAll("select")[0]!.setValue("a21"); await flushPromises();
    expect(f.wrapper.text()).toContain("原责任模式 个人 · 原使用组织 使用部 · 原位置 301");
    f.button("添加调拨明细").trigger("click"); await flushPromises();
    expect(f.wrapper.text()).toContain("A21：301 → 301；个人 → 个人；使用部");
    f.button("保存调拨草稿").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    expect(f.wrapper.text()).toContain("请添加调拨明细并填写原因。");
    await f.wrapper.find("textarea").setValue("跨部门调剂");
    f.button("保存调拨草稿").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/transfers", version: 0,
      body: { reason: "跨部门调剂", items: [{ assetId: "a21", assetVersion: 7,
        target: { owningOrganizationId: null, custodyType: "PERSONAL", usingOrganizationId: "o1", employeeId: "e1",
          warehouseId: null, locationId: "l1", referenceVersions: { location: 6, usingOrganization: 4, employee: 2 } } }] } });
    expect(api.push).toHaveBeenCalledWith("/transfers/88?saved=1");
    f.wrapper.unmount();
  });

  it("响应丢失后重试沿用同一明细、版本与幂等键", async () => {
    const f = await fixture(true);
    await f.wrapper.findAll("select")[0]!.setValue("a21"); await flushPromises();
    f.button("添加调拨明细").trigger("click"); await flushPromises();
    await f.wrapper.find("textarea").setValue("跨部门调剂");
    f.button("保存调拨草稿").trigger("click"); await flushPromises();
    expect(f.wrapper.text()).toContain("操作结果尚未确认，请重试上次操作或刷新查询。");
    f.button("重试上次操作").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(2);
    expect(f.writes[1]).toEqual(f.writes[0]);
    expect(api.push).toHaveBeenCalledWith("/transfers/88?saved=1");
    f.wrapper.unmount();
  });
});
