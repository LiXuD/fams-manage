import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Role } from "@/auth/types";
import { useSessionStore } from "@/auth/sessionStore";
import AssetReturnDetailView from "./AssetReturnDetailView.vue";

const api = vi.hoisted(() => ({ request: vi.fn(), roles: ["ASSET_ADMIN"] as Role[] }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(), loanRequest: api.request }));

const asset = { id: "21", assetCode: "A21", name: "Computer", version: 7, lifecycleStatus: "IN_USE", exceptionStatus: "NONE" };
const returnRow = (over: Record<string, unknown> = {}): Record<string, unknown> => ({ id: "51", assetId: "21", loanId: "11",
  assetVersion: 7, version: 3, proposedBy: "2", responsibleEmployeeId: "9", reason: "设备退库", returnStatus: "PENDING_APPROVAL",
  processorAccountId: null, inspectionResult: null, inspectionReason: null, lockVersion: 4, asset, followups: [], ...over });
const warehouses = [{ id: "w1", name: "中心仓", version: 2 }];
const locations = [{ id: "l1", name: "A-01", version: 5, warehouseId: "w1" }];

async function fixture(row: Record<string, unknown> = returnRow(), id = "51") {
  const writes: unknown[] = [];
  api.request.mockImplementation(async (path: string, mutation?: unknown) => {
    if (mutation) { writes.push(structuredClone(mutation)); return { id: "51", version: 4 }; }
    if (path.startsWith("/asset-returns/catalog/warehouse")) return { items: warehouses };
    if (path.startsWith("/asset-returns/catalog/location")) return { items: locations };
    if (path === "/asset-returns/51") return structuredClone(row);
    throw new Error("unexpected read " + path);
  });
  const pinia = createPinia(); const session = useSessionStore(pinia);
  session.state = "authenticated";
  session.user = { id: "1", username: "operator", roles: api.roles, status: "ENABLED", requiresInitialPasswordChange: false };
  const wrapper = mount(AssetReturnDetailView, { props: { id }, global: { plugins: [pinia], stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    AssetReturnReassignment: true,
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, writes, button: (text: string) => wrapper.findAll("button").find(item => item.text() === text)! };
}

describe("退还单详情", () => {
  afterEach(() => { vi.clearAllMocks(); api.roles = ["ASSET_ADMIN"]; window.history.replaceState(null, "", "/"); });

  it("非法单号不向服务端发出", async () => {
    const f = await fixture(returnRow(), "../other");
    expect(api.request).not.toHaveBeenCalled();
    expect(f.wrapper.text()).toContain("没有当前记录或操作的权限。");
    f.wrapper.unmount();
  });

  it("管理员批准退还带原因提交到当前版本，缺原因则零写请求", async () => {
    const f = await fixture();
    f.button("批准退还").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    expect(f.wrapper.text()).toContain("请填写决定原因。");
    await f.wrapper.find("textarea").setValue("同意退还");
    f.button("批准退还").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/asset-returns/51/decision", version: 3, body: { approved: true, reason: "同意退还" } });
    expect(f.wrapper.text()).toContain("操作已保存。");
    f.wrapper.unmount();
  });

  it("库管领取整单验收只提交版本不提交资料", async () => {
    api.roles = ["WAREHOUSE_OPERATOR"];
    const f = await fixture(returnRow({ returnStatus: "WAITING_WAREHOUSE_CLAIM" }));
    f.button("领取整单验收").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/asset-returns/51/claim", version: 3 });
    expect((f.writes[0] as { body?: unknown }).body).toBeUndefined();
    f.wrapper.unmount();
  });

  it("实物验收未核对编码与仓位时零写请求，齐备后提交资产与锁版本", async () => {
    api.roles = ["WAREHOUSE_OPERATOR"];
    const f = await fixture(returnRow({ returnStatus: "WAREHOUSE_PROCESSING", processorAccountId: "1" }));
    await f.wrapper.find("textarea").setValue("实物完好");
    f.button("提交实物验收").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    expect(f.wrapper.text()).toContain("请核对实物编码并选择仓库、仓位。");
    await f.wrapper.find("input").setValue("A21");
    await f.wrapper.findAll("select")[1].setValue("w1");
    await f.wrapper.findAll("select")[2].setValue("l1");
    f.button("提交实物验收").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/asset-returns/51/inspect", version: 3,
      body: { result: "NORMAL", reason: "实物完好", assetVersion: 7, lockVersion: 4, observedCode: "A21",
        warehouseId: "w1", warehouseVersion: 2, locationId: "l1", locationVersion: 5 } });
    f.wrapper.unmount();
  });

  it("缺失实物只提交验收结果并保留责任说明", async () => {
    api.roles = ["WAREHOUSE_OPERATOR"];
    const f = await fixture(returnRow({ returnStatus: "WAREHOUSE_PROCESSING", processorAccountId: "1" }));
    await f.wrapper.find("textarea").setValue("实物未找到");
    await f.wrapper.findAll("select")[0].setValue("MISSING");
    expect(f.wrapper.text()).toContain("实物缺失将保留借用责任，进入报失处理。");
    f.button("提交实物验收").trigger("click"); await flushPromises();
    const body = (f.writes[0] as { body: Record<string, unknown> }).body;
    expect(body).toMatchObject({ result: "MISSING", reason: "实物未找到", assetVersion: 7, lockVersion: 4 });
    expect(Object.keys(body)).not.toContain("warehouseId");
    f.wrapper.unmount();
  });

  it("提报跳转来的地址栏标记只播报一次", async () => {
    window.history.replaceState(null, "", "/asset-returns/51?saved=1");
    const f = await fixture();
    expect(f.wrapper.text()).toContain("操作已保存。");
    expect(window.location.search).toBe("");
    f.wrapper.unmount();
  });
});
