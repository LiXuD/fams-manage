import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ItemTargetChangeView from "./ItemTargetChangeView.vue";
const state = vi.hoisted(() => ({ request: vi.fn(), roles: ["ASSET_ADMIN"] }));
vi.mock("@/components/AppChrome.vue", async () => {
  const { useRoute } = await import("vue-router");
  return { default: { template: '<main><header><h1>{{ m.uiTitle }}</h1><p>{{ m.uiDesc }}</p><slot name="actions" /></header><slot /></main>',
    setup: () => ({ m: useRoute().meta }) } };
});
vi.mock("vue-router", () => ({ useRoute: () => ({ query: {}, meta: { uiTitle: "领用与借用目标变更",
  uiDesc: "仅变更未完成明细。已交出的实物保持原交出记录和占用锁，批准不会提前转移资产责任。" } }) }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: state.roles } }) }));
vi.mock("@/loan/api", () => ({ loanRequest: state.request, loanMessage: () => "结果尚未确认" }));
vi.mock("@/requisition/api", () => ({ requisitionRequest: async () => ({ items: [] }) }));
describe("approved allocation target change", () => {
  beforeEach(() => { state.request.mockReset(); vi.spyOn(window, "confirm").mockReturnValue(true); });
  it("shows original physical destination and retries the exact single-admin decision after lost reply", async () => {
    const writes: unknown[] = []; let lose = true; let status = "PENDING";
    const target = { custodyType: "PERSONAL", organizationId: "1", organizationVersion: 0,
      employeeId: "3", employeeVersion: 0, locationId: "7", locationVersion: 0 };
    const record = () => ({ id: "99", business_id: "88", item_id: "77", version: status === "PENDING" ? 0 : 1,
      change_status: status, source_snapshot_json: { assetCode: "FA-1", target,
        handover: [{ destinationText: "原交出房间", deliveredAt: "2026-10-13T01:00:00Z" }] },
      proposed_target_json: { ...target, employeeId: "4" }, reason: "原目标停用", physical_verification: "实物仍在原地点" });
    state.request.mockImplementation(async (_path: string, mutation?: unknown) => {
      if (!mutation) return { items: [record()], total: 1 };
      writes.push(JSON.parse(JSON.stringify(mutation))); status = "APPROVED";
      if (lose) { lose = false; throw new Error("lost committed reply"); }
      return record();
    });
    const wrapper = mount(ItemTargetChangeView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const button = (text: string) => wrapper.findAll("button").find(row => row.text() === text)!;
    await button("FA-1 · 待管理员批准").trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain("原交出房间");
    expect(wrapper.text()).toContain("批准不会提前转移资产责任");
    expect(button("批准目标变更").attributes("disabled")).toBeDefined();
    await wrapper.find("textarea").setValue("已核对实物和新目标");
    await button("批准目标变更").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(1); expect(wrapper.find("fieldset").attributes("disabled")).toBeDefined();
    await button("重试原操作").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
    expect(writes[0]).toMatchObject({ path: "/requisition-target-changes/99/decision", version: 0,
      body: { result: "APPROVED", reason: "已核对实物和新目标" } });
    expect(wrapper.text()).toContain("已批准"); wrapper.unmount();
  });
});
