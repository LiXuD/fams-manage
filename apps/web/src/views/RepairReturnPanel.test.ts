import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import RepairReturnPanel from "./RepairReturnPanel.vue";
import type { Repair, RepairReturnTarget } from "@/repair/api";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const target: RepairReturnTarget = { custodyType: "PERSONAL", employeeId: "2", employeeName: "实际接收人", employeeVersion: 4,
  organizationId: "3", organizationName: "原使用部门", organizationVersion: 5, locationId: "6", locationName: "原使用地点", locationVersion: 7, receiverAccountId: "2" };
const location = "9007199254740995";
const row = (): Repair => ({ id: "19", assetId: "20", creatorAccountId: "2", source: { id: "20", assetCode: "RETURN-1", name: "实物设备", version: 0, lifecycle: "BORROWED", custodyType: "PERSONAL", locationId: "6", locationName: "原地点" },
  fault: "实际故障", impact: "HIGH", occurredAt: "2026-10-22T00:00:00Z", status: "WAREHOUSE_PROCESSING", version: 4, assetVersion: 1, ownershipVersion: 1, loanVersion: 8, processorAccountId: "1", acceptedAt: "2026-10-22T01:00:00Z", completedAt: null, lockVersion: 0,
  returnHandovers: [{ id: "9007199254740993", handover_status: "WAITING_RECEIPT", target_snapshot_json: target, delivered_at: "2026-10-22T01:00:00Z", delivery_evidence: "实际交出设备等待本人接收", receipt_at: null, receipt_evidence: null }],
  actions: { approve: false, claim: false, acceptInPlace: false, completeInPlace: false, addFaultPhoto: false, cancel: false, deliverReturn: true, receiveReturn: true, refuseReturn: true, recollectReturn: true } });
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const page = () => mount(RepairReturnPanel, { props: { repair: row() } });
const button = (wrapper: ReturnType<typeof page>, name: string) => wrapper.findAll("button").find(item => item.text() === name)!;
function wire(write: (input: RequestInit) => Promise<Response>) { vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
  if (input.method === "POST") return await write(input);
  if (url.endsWith("return-target")) return response(target);
  if (url.includes("physical-catalog")) return response({ items: [{ id: location, name: "实际收回地点", version: 11 }], total: 1 });
  return response(row());
})); }
async function fill(wrapper: ReturnType<typeof page>, mode = "核实原目标并记录实际交出") {
  await button(wrapper, mode).trigger("click"); await flushPromises();
  await wrapper.find("input[maxlength='2048']").setValue("RETURN-1");
  await wrapper.find("input[type='datetime-local']").setValue("2026-10-22T09:00:00.000");
  await wrapper.find("textarea").setValue("本人已经实际核实设备身份与当前去向，记录本次真实交接依据。");
}
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("delivery freezes approved original target and loan versions behind inline physical confirmation", async () => {
  const writes: RequestInit[] = []; wire(async input => { writes.push(input); return response({ id: "19" }); }); const wrapper = page();
  await fill(wrapper); expect(wrapper.text()).toContain("实际接收人"); await button(wrapper, "核对本次返还办理").trigger("click"); expect(writes).toHaveLength(0);
  await button(wrapper, "确认保存实际返还办理").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ employeeVersion: 4, organizationVersion: 5, locationVersion: 7, loanVersion: 8, ownershipVersion: 1, assetVersion: 1 });
  expect(wrapper.emitted("changed")).toHaveLength(1); wrapper.unmount();
});
it("actual receiver must explicitly check physical facts and retains exact large handover identity", async () => {
  const writes: RequestInit[] = []; wire(async input => { writes.push(input); return response({ id: "19" }); }); const wrapper = page();
  await fill(wrapper, "我已实际接收修复设备"); await button(wrapper, "核对本次返还办理").trigger("click"); expect(wrapper.findAll("button").some(row => row.text() === "确认保存实际返还办理")).toBe(false);
  await wrapper.find("input[type='checkbox']").setValue(true); await button(wrapper, "核对本次返还办理").trigger("click"); await button(wrapper, "确认保存实际返还办理").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ handoverId: "9007199254740993", physicalChecked: true, loanVersion: 8 }); wrapper.unmount();
});
it("lost committed receipt survives refreshed terminal props and retries original immutable request", async () => {
  const writes: RequestInit[] = []; let lost = true; wire(async input => { writes.push(input); if (lost) { lost = false; throw new Error("committed reply lost"); } return response({ id: "19" }); }); const wrapper = page();
  await fill(wrapper, "我已实际接收修复设备"); await wrapper.find("input[type='checkbox']").setValue(true); await button(wrapper, "核对本次返还办理").trigger("click"); await button(wrapper, "确认保存实际返还办理").trigger("click"); await flushPromises();
  await wrapper.setProps({ repair: { ...row(), version: 9, status: "COMPLETED", actions: undefined } });
  await button(wrapper, "重试原返还办理").trigger("click"); await flushPromises(); expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
});
it("actual recollection requires a real selected location and its current version", async () => {
  const writes: RequestInit[] = []; wire(async input => { writes.push(input); return response({ id: "19" }); }); const wrapper = page();
  await fill(wrapper, "核实库管已实际收回"); await button(wrapper, "核对本次返还办理").trigger("click"); expect(writes).toHaveLength(0);
  await button(wrapper, "查询实际收回位置").trigger("click"); await flushPromises(); await wrapper.find("select").setValue(location);
  await button(wrapper, "核对本次返还办理").trigger("click"); await button(wrapper, "确认保存实际返还办理").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ actualLocationId: location, locationVersion: 11, handoverId: "9007199254740993" }); wrapper.unmount();
});
it("identity replacement discards prepared target and private evidence before submission", async () => {
  const writes: RequestInit[] = []; wire(async input => { writes.push(input); return response({ id: "19" }); }); const wrapper = page();
  await fill(wrapper); await button(wrapper, "核对本次返还办理").trigger("click"); state.session.user.id = "9"; await flushPromises();
  expect(wrapper.findAll("button").some(row => row.text() === "确认保存实际返还办理")).toBe(false); expect(wrapper.find("textarea").element.value).toBe(""); expect(writes).toHaveLength(0); wrapper.unmount();
});
