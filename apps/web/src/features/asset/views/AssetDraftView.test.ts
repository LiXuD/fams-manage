import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import AssetDraftView from "./AssetDraftView.vue";

const api = vi.hoisted(() => ({ refs: vi.fn(), create: vi.fn(), push: vi.fn() }));
vi.mock("@/features/asset/api", async importOriginal => ({
  ...await importOriginal<typeof import("@/features/asset/api")>(), activeReferences: api.refs, createAsset: api.create }));
vi.mock("vue-router", () => ({ useRouter: () => ({ push: api.push }) }));

const catalogs: Record<string, unknown[]> = {
  "/asset-categories": [{ id: "c1", code: "PC", name: "笔记本电脑", version: 2 }],
  "/organizations": [{ id: "o1", code: "ORG-1", name: "研发中心", version: 4 }],
  "/warehouses": [{ id: "w1", code: "WH-1", name: "中心仓", version: 3, warehouse: { id: "w1", version: 3 } }],
  "/locations": [{ id: "l1", code: "LOC-1", name: "301", version: 6 }],
};

async function fixture() {
  api.refs.mockImplementation(async (path: string) => ({ page: 1, pageSize: 200, total: 1, items: catalogs[path] }));
  api.create.mockResolvedValue({ id: "107", assetCode: "FA-2026-000201", version: 1 });
  const wrapper = mount(AssetDraftView, { global: { stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, field: (id: string) => wrapper.find(`#${id}`),
    submit: async () => { await wrapper.find("form").trigger("submit"); } };
}

describe("单个建卡草稿", () => {
  afterEach(() => { vi.clearAllMocks(); });

  it("只取四张基础资料目录，不读台账也不写资产", async () => {
    const f = await fixture();
    expect(api.refs).toHaveBeenCalledWith("/asset-categories");
    expect(api.refs).toHaveBeenCalledWith("/organizations");
    expect(api.refs).toHaveBeenCalledWith("/warehouses");
    expect(api.refs).toHaveBeenCalledWith("/locations");
    expect(f.wrapper.findAll("select")).toHaveLength(4);
    expect(f.wrapper.text()).toContain("笔记本电脑");
    expect(f.wrapper.findAll("a[data-destination]").map(a => [a.text(), a.attributes("data-destination")]))
      .toEqual([["返回台账列表", "/assets"]]);
    f.wrapper.unmount();
  });

  it("缺资产名称时零建卡请求并给出本域原句", async () => {
    const f = await fixture();
    await f.submit();
    expect(api.create).not.toHaveBeenCalled();
    expect(f.wrapper.text()).toContain("请填写资产名称");
    f.wrapper.unmount();
  });

  it("只填名称时载荷不含可选字段，成功后跳转详情并播报", async () => {
    const f = await fixture();
    await f.field("create-name").setValue("办公笔记本");
    await f.submit();
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(api.create).toHaveBeenCalledWith({ name: "办公笔记本" });
    expect(api.push).toHaveBeenCalledWith("/assets/107?saved=1");
    f.wrapper.unmount();
  });

  it("选定分类、组织、仓库与位置后各带引用版本提交", async () => {
    const f = await fixture();
    await f.field("create-name").setValue("  办公笔记本 ThinkPad T14  ");
    await f.field("create-model").setValue("T14 Gen 4");
    await f.field("create-unit").setValue("台");
    await f.wrapper.findAll("select")[0]!.setValue("c1");
    await f.wrapper.findAll("select")[1]!.setValue("o1");
    await f.wrapper.findAll("select")[2]!.setValue("w1");
    await f.wrapper.findAll("select")[3]!.setValue("l1");
    await f.submit();
    expect(api.create).toHaveBeenCalledWith({ name: "办公笔记本 ThinkPad T14", model: "T14 Gen 4", unit: "台",
      category: { id: "c1", version: 2 }, organization: { id: "o1", version: 4 },
      plannedInboundWarehouse: { id: "w1", version: 3 }, plannedInboundLocation: { id: "l1", version: 6 } });
    expect(api.push).toHaveBeenCalledWith("/assets/107?saved=1");
    f.wrapper.unmount();
  });
});
