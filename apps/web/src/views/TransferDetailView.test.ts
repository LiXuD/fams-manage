import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Role } from "@/auth/types";
import { useSessionStore } from "@/auth/sessionStore";
import TransferDetailView from "./TransferDetailView.vue";

const api = vi.hoisted(() => ({ request: vi.fn(), query: {} as Record<string, string>, roles: ["ASSET_ADMIN"] as Role[] }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(), loanRequest: api.request }));
vi.mock("vue-router", () => ({ useRoute: () => ({ query: api.query }) }));

const item = { id: "i1", version: 5, itemStatus: "READY", receiptRequired: true, destination: null, deliveryEvidence: null,
  sourceSnapshot: { asset_code: "A21", custody_type: "PERSONAL", using_organization_id: "o1", responsible_employee_id: "e1",
    department_responsible_employee_id: null, current_location_id: "l1" },
  targetSnapshot: { custodyType: "PERSONAL", usingOrganizationId: "o2", employeeId: "e2", locationId: "l2" } };
const row = { id: "8", version: 3, requestStatus: "PENDING_APPROVAL", creatorAccountId: "1", processorAccountId: null,
  reason: "跨部门调剂", totalQuantity: 1, completedQuantity: 0, cancelledQuantity: 0, pendingQuantity: 1, sourceGroups: 1,
  targetGroups: 1, items: [item] };

async function fixture(over: Record<string, unknown> = {}, id = "8") {
  const writes: unknown[] = [];
  api.request.mockImplementation(async (path: string, mutation?: unknown) => {
    if (mutation) { writes.push(structuredClone(mutation)); return { id: "8", version: 4 }; }
    if (path.startsWith("/transfers/catalog/organization")) return { items: [{ id: "o1", name: "使用部", version: 4 }, { id: "o2", name: "目标部", version: 5 }] };
    if (path.startsWith("/transfers/catalog/location")) return { items: [{ id: "l1", name: "301", version: 6 }, { id: "l2", name: "402", version: 7 }] };
    if (path.startsWith("/transfers/catalog/employee")) return { items: [{ id: "e2", name: "李四", version: 9 }] };
    if (path === "/transfers/8") return structuredClone({ ...row, ...over });
    throw new Error("unexpected read " + path);
  });
  const pinia = createPinia(); const session = useSessionStore(pinia);
  session.state = "authenticated";
  session.user = { id: "1", username: "admin", roles: api.roles, status: "ENABLED", requiresInitialPasswordChange: false };
  const wrapper = mount(TransferDetailView, { props: { id }, global: { plugins: [pinia], stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    TransferReassignment: true,
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, writes, button: (text: string) => wrapper.findAll("button").find(b => b.text() === text)!,
    control: (label: string) => wrapper.findAll("label").find(l => l.text().startsWith(label))! };
}

describe("调拨详情", () => {
  afterEach(() => { vi.clearAllMocks(); api.roles = ["ASSET_ADMIN"]; api.query = {}; window.history.replaceState(null, "", "/"); });

  it("非法单号不向服务端发出", async () => {
    const f = await fixture({}, "../other");
    expect(api.request).not.toHaveBeenCalled();
    expect(f.wrapper.text()).toContain("没有当前记录或操作的权限。");
    f.wrapper.unmount();
  });

  it("详情只取本单调拨与两类目录，返回入口指向列表", async () => {
    const f = await fixture();
    expect(api.request).toHaveBeenCalledWith("/transfers/8");
    expect(api.request).toHaveBeenCalledWith("/transfers/catalog/organization?page=1&pageSize=200");
    expect(api.request).not.toHaveBeenCalledWith("/transfers/catalog/warehouse?page=1&pageSize=200");
    expect(f.wrapper.text()).toContain("调拨 8 · 待管理员确认");
    expect(f.wrapper.text()).toContain("A21 · 未交出");
    expect(f.wrapper.text()).toContain("目标：个人 / 目标部 / 李四 / 402");
    expect(f.wrapper.findAll("a[data-destination]").map(item2 => [item2.text(), item2.attributes("data-destination")]))
      .toEqual([["返回调拨列表", "/transfers"]]);
    f.wrapper.unmount();
  });

  it("批准调拨缺原因时零写请求，带原因提交到当前版本", async () => {
    const f = await fixture();
    f.button("批准调拨").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    await f.control("申请 / 决定原因").find("textarea").setValue("同意跨部门调剂");
    f.button("批准调拨").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/transfers/8/decision", version: 3, body: { approved: true, reason: "同意跨部门调剂" } });
    expect(f.wrapper.text()).toContain("操作已保存。");
    f.wrapper.unmount();
  });

  it("库管领取整单调拨只提交版本不提交资料", async () => {
    api.roles = ["WAREHOUSE_OPERATOR"];
    const f = await fixture({ requestStatus: "WAITING_WAREHOUSE_CLAIM" });
    f.button("领取整单调拨").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/transfers/8/claim", version: 3 });
    expect((f.writes[0] as { body?: unknown }).body).toBeUndefined();
    f.wrapper.unmount();
  });

  it("实物交接缺编码、去向或依据时零写请求，齐备后提交明细动作", async () => {
    api.roles = ["WAREHOUSE_OPERATOR"];
    const f = await fixture({ requestStatus: "PROCESSING", processorAccountId: "1" });
    f.button("记录交出，等待目标人接收").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    expect(f.wrapper.text()).toContain("请填写实物编码、实际去向和交接/收回依据。");
    await f.control("实物编码 / 当前二维码").find("input").setValue("A21");
    await f.control("实际去向").find("input").setValue("四楼机房");
    await f.control("现场交接 / 收回依据").find("textarea").setValue("双方现场核对实物一致");
    f.button("记录交出，等待目标人接收").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/transfers/8/items/i1/deliver", version: 3,
      body: { itemVersion: 5, observedCode: "A21", destination: "四楼机房", evidence: "双方现场核对实物一致",
        actualReceiver: null, receivedAt: null, physicalReturned: null } });
    expect(f.wrapper.findAll("a[data-destination]").map(item2 => item2.text()))
      .toEqual(["申请目标变更", "返回调拨列表"]);
    f.wrapper.unmount();
  });

  it("建单跳转来的地址栏标记只播报一次", async () => {
    window.history.replaceState(null, "", "/transfers/8?saved=1");
    api.query = { saved: "1" };
    const f = await fixture();
    expect(f.wrapper.text()).toContain("操作已保存。");
    expect(window.location.search).toBe("");
    f.wrapper.unmount();
  });
});
