import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import RepairTargetChangePanel from "./RepairTargetChangePanel.vue";
import type { Repair } from "@/repair/api";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const large = "9007199254740995";
const repair = (): Repair => ({ id: "19", assetId: "20", creatorAccountId: "2",
  source: { id: "20", assetCode: "TARGET-1", name: "维修设备", version: 0, lifecycle: "IN_USE", custodyType: "PERSONAL", locationId: "6", locationName: "原地点" },
  fault: "实际故障", impact: "HIGH", occurredAt: "2026-10-23T00:00:00Z", status: "WAREHOUSE_PROCESSING",
  version: 4, assetVersion: 1, ownershipVersion: 1, processorAccountId: "1", acceptedAt: "2026-10-23T01:00:00Z",
  completedAt: null, lockVersion: 0, actions: { approve: false, claim: false, acceptInPlace: false, completeInPlace: false, addFaultPhoto: false, cancel: false, changeReturnTarget: true } });
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const page = () => mount(RepairTargetChangePanel, { props: { repair: repair() } });
const button = (wrapper: ReturnType<typeof page>, name: string) => wrapper.findAll("button").find(item => item.text() === name)!;
function wire(write: (input: RequestInit) => Promise<Response>, proposal = false) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") return write(input);
    if (url.includes("/context?")) return response({ businessId: "19", itemId: "20", version: 4, assetCode: "TARGET-1", target: { employeeId: "2" }, ownershipVersion: 1, loanVersion: null });
    if (url.includes("/catalog/")) return response({ items: [{ id: large, name: "有效新目标", version: 11, organizationId: large, locationType: "ROOM", sharedEligible: true }], page: 1, pageSize: 20 });
    return response({ items: proposal ? [{ id: large, business_id: "19", version: 2, change_status: "PENDING", reason: "失效目标转为新责任人", physical_verification: "已核实设备与目标", proposed_target_json: { employeeId: large } }] : [], total: proposal ? 1 : 0 });
  }));
}
async function fill(wrapper: ReturnType<typeof page>) {
  await button(wrapper, "核实本单并提请新返还目标").trigger("click"); await flushPromises();
  for (const label of ["查询新使用组织", "查询新责任人员", "查询新固定位置"]) {
    await button(wrapper, label).trigger("click"); await flushPromises();
    await wrapper.findAll("select")[1]!.setValue(large);
  }
  await wrapper.findAll("textarea")[0]!.setValue("原目标已失效，提请经批准后向新责任人实际返还");
  await wrapper.findAll("textarea")[1]!.setValue("已实际核对设备和新责任人及使用地点，保留原责任历史");
  await wrapper.find("input[maxlength='128']").setValue("TARGET-1");
}
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("requires explicit physical verification and sends selected string identities with current versions", async () => {
  const writes: RequestInit[] = []; wire(async input => { writes.push(input); return response({ id: "4" }); });
  const wrapper = page(); await fill(wrapper);
  await button(wrapper, "核对提请目标变更").trigger("click"); expect(writes).toHaveLength(0);
  expect(wrapper.text()).toContain("请选择有效新组织");
  await wrapper.find("input[type='checkbox']").setValue(true);
  await button(wrapper, "核对提请目标变更").trigger("click"); expect(writes).toHaveLength(0);
  await button(wrapper, "确认保存目标变更操作").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ businessId: "19", itemId: "20", physicalChecked: true,
    snapshot: { version: 4, ownershipVersion: 1 }, target: { employeeId: large, organizationId: large, locationId: large, employeeVersion: 11, organizationVersion: 11, locationVersion: 11 } });
  expect(wrapper.emitted("changed")).toHaveLength(1); wrapper.unmount();
});
it("retains an unknown committed result and retries the original request after refreshed props", async () => {
  const writes: RequestInit[] = []; wire(async input => { writes.push(input); if (writes.length === 1) throw new Error("committed response lost"); return response({ id: "4" }); });
  const wrapper = page(); await fill(wrapper); await wrapper.find("input[type='checkbox']").setValue(true);
  await button(wrapper, "核对提请目标变更").trigger("click"); await button(wrapper, "确认保存目标变更操作").trigger("click"); await flushPromises();
  await wrapper.setProps({ repair: { ...repair(), version: 5, actions: undefined } });
  await button(wrapper, "重试原目标变更操作").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
});
it("admin approves only this repair through inline confirmation with exact proposal identity", async () => {
  state.session.user.roles = ["ASSET_ADMIN"]; const writes: RequestInit[] = [];
  wire(async input => { writes.push(input); return response({ id: large }); }, true);
  const wrapper = page(); await button(wrapper, "查询维修目标变更申请").trigger("click"); await flushPromises();
  await wrapper.find("textarea").setValue("经核实批准本单新目标，仍须实际交出接收");
  await button(wrapper, "核对批准本单新目标").trigger("click"); expect(writes).toHaveLength(0);
  await button(wrapper, "确认保存目标变更操作").trigger("click"); await flushPromises();
  const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === "POST")!;
  expect(call[0]).toBe("/api/v1/item-target-changes/" + large + "/decision?businessType=REPAIR");
  expect((writes[0]!.headers as Record<string, string>)["If-Match-Version"]).toBe("2");
  expect(JSON.parse(writes[0]!.body as string).result).toBe("APPROVED"); wrapper.unmount();
});
it("identity changes discard prepared private targets and evidence", async () => {
  const writes: RequestInit[] = []; wire(async input => { writes.push(input); return response({}); });
  const wrapper = page(); await fill(wrapper); await wrapper.find("input[type='checkbox']").setValue(true);
  await button(wrapper, "核对提请目标变更").trigger("click"); state.session.user.id = "9"; await flushPromises();
  expect(wrapper.text()).not.toContain("确认保存目标变更操作"); expect(wrapper.text()).not.toContain("原目标已失效，提请经批准后向新责任人实际返还");
  expect(writes).toHaveLength(0); wrapper.unmount();
});
it("uses actual catalog pages without totals, scopes people to the selected organization and excludes bins", async () => {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    if (url.includes("/context?")) return response({ businessId: "19", itemId: "20", version: 4, assetCode: "TARGET-1", target: {} });
    if (url.includes("/catalog/organization")) return response({ items: url.includes("page=2&")
      ? [{ id: large, name: "后页组织", version: 12 }]
      : Array.from({ length: 20 }, (_, i) => ({ id: String(i + 1), name: "组织" + i, version: 0 })) });
    if (url.includes("/catalog/employee")) return response({ items: [{ id: "44", name: "所选组织人员", version: 3 }] });
    if (url.includes("/catalog/location")) return response({ items: [
      { id: "55", name: "应排除的仓库库位", locationType: "BIN", version: 1 },
      { id: "66", name: "实际使用地点", locationType: "ROOM", version: 2 }] });
    return response({ items: [], total: 0 });
  }));
  const wrapper = page(); await button(wrapper, "核实本单并提请新返还目标").trigger("click"); await flushPromises();
  await button(wrapper, "查询新使用组织").trigger("click"); await flushPromises();
  expect(button(wrapper, "下一页目标选项").attributes("disabled")).toBeUndefined();
  await button(wrapper, "下一页目标选项").trigger("click"); await flushPromises();
  expect(button(wrapper, "下一页目标选项").attributes("disabled")).toBeDefined();
  await wrapper.findAll("select")[1]!.setValue(large);
  await button(wrapper, "查询新责任人员").trigger("click"); await flushPromises();
  expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes("/transfers/catalog/employee?page=1&pageSize=20&organizationId=" + large))).toBe(true);
  await button(wrapper, "查询新固定位置").trigger("click"); await flushPromises();
  expect(wrapper.findAll("select")[1]!.text()).toContain("实际使用地点");
  expect(wrapper.findAll("select")[1]!.text()).not.toContain("应排除的仓库库位");
  wrapper.unmount();
});
