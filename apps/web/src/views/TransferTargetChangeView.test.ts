import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import TransferTargetChangeView from "./TransferTargetChangeView.vue";
const state = vi.hoisted(() => ({ request: vi.fn(), roles: ["ASSET_ADMIN"] }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
vi.mock("vue-router", () => ({ useRoute: () => ({ query: {} }) }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: state.roles } }) }));
vi.mock("@/loan/api", () => ({ loanRequest: state.request, loanMessage: () => "结果尚未确认" }));
vi.mock("@/requisition/api", () => ({ requisitionRequest: async () => ({ items: [] }) }));
describe("approved transfer target change", () => {
  beforeEach(() => { state.request.mockReset(); vi.spyOn(window, "confirm").mockReturnValue(true); });
  it("shows original physical destination and retries the exact single-admin decision after lost reply", async () => {
    const writes: unknown[] = []; let lose = true; let status = "PENDING";
    const target = { custodyType: "PERSONAL", owningOrganizationId: "1", usingOrganizationId: "1",
      employeeId: "3", warehouseId: null, locationId: "7", referenceVersions: { employee: 0, location: 0, usingOrganization: 0, owningOrganization: 0 } };
    const record = () => ({ id: "99", business_id: "88", item_id: "77", version: status === "PENDING" ? 0 : 1,
      change_status: status, source_snapshot_json: { assetCode: "FA-1", target,
        physical: { destination: "原交出房间", deliveredAt: "2026-10-13T01:00:00Z" } },
      proposed_target_json: { ...target, employeeId: "4" }, reason: "原目标停用", physical_verification: "实物仍在原地点" });
    state.request.mockImplementation(async (_path: string, mutation?: unknown) => {
      if (!mutation) return _path.includes("/catalog/") ? { items: [] } : { items: [record()], total: 1 };
      writes.push(JSON.parse(JSON.stringify(mutation))); status = "APPROVED";
      if (lose) { lose = false; throw new Error("lost committed reply"); }
      return record();
    });
    const wrapper = mount(TransferTargetChangeView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const button = (text: string) => wrapper.findAll("button").find(row => row.text() === text)!;
    await button("FA-1 · 待管理员批准").trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain("原交出房间");
    expect(wrapper.text()).toContain("原责任、已交出地点及物理锁保持");
    expect(button("批准调拨目标变更").attributes("disabled")).toBeDefined();
    await wrapper.find("textarea").setValue("已核对实物和新目标");
    await button("批准调拨目标变更").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(1); expect(wrapper.find("fieldset").attributes("disabled")).toBeDefined();
    await button("重试原操作").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
    expect(writes[0]).toMatchObject({ path: "/item-target-changes/99/decision?businessType=TRANSFER", version: 0,
      body: { result: "APPROVED", reason: "已核对实物和新目标" } });
    expect(wrapper.text()).toContain("已批准"); wrapper.unmount();
  });
});
