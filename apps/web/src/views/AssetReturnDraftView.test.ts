import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import AssetReturnDraftView from "./AssetReturnDraftView.vue";

const api = vi.hoisted(() => ({ request: vi.fn(), push: vi.fn() }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(), loanRequest: api.request }));
vi.mock("vue-router", () => ({ useRouter: () => ({ push: api.push }) }));

const asset = { id: "21", assetCode: "A21", name: "Computer", version: 7, lifecycleStatus: "IN_USE" };

async function fixture(assets: unknown[] = [asset]) {
  const writes: unknown[] = [];
  api.request.mockImplementation(async (_path: string, mutation?: unknown) => {
    if (mutation) { writes.push(structuredClone(mutation)); return { id: "51", version: 0 }; }
    return { items: assets, total: assets.length, page: 1, pageSize: 20 };
  });
  const wrapper = mount(AssetReturnDraftView, { global: { stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, writes, button: (text: string) => wrapper.findAll("button").find(item => item.text() === text)! };
}

describe("提请退库", () => {
  afterEach(() => { vi.clearAllMocks(); });

  it("只列当前可退库资产并提交其实物版本", async () => {
    const f = await fixture();
    expect(api.request).toHaveBeenCalledWith("/asset-returns/eligible-assets?page=1&pageSize=20");
    expect(f.wrapper.find("option").text()).toBe("请选择当前可退库资产");
    expect(f.wrapper.findAll("option")[1].text()).toBe("A21 · Computer");
    expect(f.wrapper.findAll("a[data-destination]").map(item => [item.text(), item.attributes("data-destination")]))
      .toEqual([["返回退还列表", "/asset-returns"]]);
    f.wrapper.unmount();
  });

  it("未选资产或未填原因时零写请求", async () => {
    const f = await fixture();
    f.button("提请退库").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    expect(f.wrapper.text()).toContain("请选择借用或填写退库资产及原因。");
    f.wrapper.unmount();
  });

  it("提交后退库资产选择与原因并跳转到新建退还单", async () => {
    const f = await fixture();
    await f.wrapper.findAll("select")[0].setValue("21");
    await f.wrapper.find("textarea").setValue("return unused device");
    f.button("提请退库").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(1);
    expect(f.writes[0]).toMatchObject({ path: "/asset-returns", version: 0,
      body: { assetId: "21", assetVersion: 7, reason: "return unused device" } });
    expect(api.push).toHaveBeenCalledWith("/asset-returns/51?saved=1");
    f.wrapper.unmount();
  });
});
