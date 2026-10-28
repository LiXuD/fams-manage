import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import InboundDraftView from "./InboundDraftView.vue";
const push = vi.hoisted(() => vi.fn());
const api = vi.hoisted(() => ({ createReceipt: vi.fn(), updateReceipt: vi.fn(), getReceipt: vi.fn() }));
const assetApi = vi.hoisted(() => ({ listAssets: vi.fn(), activeReferences: vi.fn(), getAsset: vi.fn() }));
vi.mock("vue-router", () => ({ useRouter: () => ({ push }) }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: ["WAREHOUSE_OPERATOR"], id: "1" } }) }));
vi.mock("@/inbound/api", () => ({ ...api, acceptItem: vi.fn(), cancelReceipt: vi.fn(), claimReceipt: vi.fn(),
  decideReceipt: vi.fn(), listReceipts: vi.fn(), submitReceipt: vi.fn() }));
vi.mock("@/asset/api", () => assetApi);
const today = new Date().toISOString().slice(0, 10);
const asset = (id: string, version: number) => ({ id, assetCode: `FA-${id}`, name: `资产 ${id}`, cardStage: "DRAFT", version });
const item = { id: "IT-1", executionStatus: "PENDING", asset: { id: "101", version: 3 },
  assetSnapshot: { assetCode: "FA-101", assetName: "资产 101" },
  targetWarehouse: { id: "W-1", version: 2 }, targetLocation: { id: "L-1", version: 4 } };
const receipt = { id: "9007199254740993", receiptNumber: "RK-FIND-001", status: "DRAFT", businessDate: "2026-10-19",
  version: 5, items: [item], createdBy: { id: "1", username: "warehouse" } };
const payload = (assetIds: string[]) => ({ businessDate: today,
  items: assetIds.map((id) => ({ asset: { id, version: id === "101" ? 3 : 7 },
    targetWarehouse: { id: "W-1", version: 2 }, targetLocation: { id: "L-1", version: 4 } })) });
async function panel(props: { id?: string } = {}) {
  assetApi.listAssets.mockResolvedValue({ items: [asset("101", 3), asset("102", 7)], total: 2 });
  assetApi.activeReferences.mockImplementation((path: string) => Promise.resolve(
    path === "/warehouses" ? { items: [{ id: "W-1", version: 2, name: "中心库" }], total: 1 }
      : { items: [{ id: "L-1", version: 4, name: "301", warehouse: { id: "W-1", version: 2 } }], total: 1 }));
  if (props.id) api.getReceipt.mockResolvedValue(receipt);
  const wrapper = mount(InboundDraftView, { props, global: { stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return wrapper;
}
const choose = async (wrapper: Awaited<ReturnType<typeof panel>>, index: number) => {
  await wrapper.findAll('input[type="checkbox"]')[index]!.setValue(true);
  await wrapper.findAll("select")[0]!.setValue("W-1");
  await wrapper.findAll("select")[1]!.setValue("L-1");
};
afterEach(() => { vi.clearAllMocks(); });

describe("入库草稿", () => {
  it("新建保存后跳到详情路由，并带上一次性保存标记", async () => {
    const wrapper = await panel();
    api.createReceipt.mockResolvedValue({ ...receipt, id: "9007199254740994" });
    expect(wrapper.text()).not.toContain("修订草稿 RK-");
    await choose(wrapper, 0);
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(api.createReceipt).toHaveBeenCalledExactlyOnceWith(payload(["101"]));
    expect(api.updateReceipt).not.toHaveBeenCalled();
    expect(push).toHaveBeenCalledExactlyOnceWith("/inbound/9007199254740994?saved=1");
    wrapper.unmount();
  });
  it("缺资产或目标位置时不向服务端写入", async () => {
    const wrapper = await panel();
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("请选择资产、仓库和位置");
    expect(api.createReceipt).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it("修订按单号回填资产与目标位置，保存走带版本的 PATCH", async () => {
    const wrapper = await panel({ id: "9007199254740993" });
    expect(api.getReceipt).toHaveBeenCalledExactlyOnceWith("9007199254740993");
    expect(wrapper.find<HTMLInputElement>('input[type="date"]').element.value).toBe("2026-10-19");
    expect(wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')[0]!.element.checked).toBe(true);
    expect(wrapper.findAll<HTMLSelectElement>("select")[0]!.element.value).toBe("W-1");
    expect(wrapper.findAll<HTMLSelectElement>("select")[1]!.element.value).toBe("L-1");
    expect(wrapper.text()).toContain("修订草稿 RK-FIND-001");
    expect(wrapper.text()).toContain("修改下方草稿内容后保存；所选资产使用同一目标仓库和位置");
    const revise = wrapper.findAll("button").find((button) => button.text() === "保存修订");
    expect(revise).toBeTruthy();
    expect(wrapper.findAll("a[data-destination]").find((link) => link.text() === "退出修订")
      ?.attributes("data-destination")).toBe("/inbound/9007199254740993");
    api.updateReceipt.mockResolvedValue(receipt);
    await choose(wrapper, 1);
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(api.updateReceipt).toHaveBeenCalledExactlyOnceWith(receipt, { ...payload(["101", "102"]), businessDate: "2026-10-19" });
    expect(push).toHaveBeenCalledExactlyOnceWith("/inbound/9007199254740993?saved=1");
    wrapper.unmount();
  });
});
