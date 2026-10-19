import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import InboundView from "./InboundView.vue";
const api = vi.hoisted(() => ({ listReceipts: vi.fn(), getReceipt: vi.fn() }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: ["ASSET_ADMIN"], id: "1" } }) }));
vi.mock("@/inbound/api", () => ({ ...api, acceptItem: vi.fn(), cancelReceipt: vi.fn(), claimReceipt: vi.fn(), createReceipt: vi.fn(),
  decideReceipt: vi.fn(), submitReceipt: vi.fn(), updateReceipt: vi.fn() }));
const panel = () => mount(InboundView, { global: { stubs: { AppChrome: { template: "<main><slot /></main>" }, InboundReassignment: true, AttachmentPanel: true } } });
afterEach(() => { window.history.replaceState(null, "", "/"); vi.clearAllMocks(); });
describe("formal inbound finding link", () => {
  it("reads a valid linked receipt using the current server permission while preserving its string identity", async () => {
    window.history.replaceState(null, "", "/inbound?receiptId=9007199254740993");
    api.listReceipts.mockResolvedValue({ items: [], total: 0 });
    api.getReceipt.mockResolvedValue({ id: "9007199254740993", receiptNumber: "RK-FIND-001", status: "PENDING_ADMIN_DECISION", version: 1,
      businessDate: "2026-10-19", items: [], createdBy: { id: "2", username: "warehouse" } });
    const wrapper = panel(); await flushPromises();
    expect(api.getReceipt).toHaveBeenCalledExactlyOnceWith("9007199254740993");
    expect(wrapper.text()).toContain("RK-FIND-001"); wrapper.unmount();
  });
  it("does not request malformed receipt identifiers", async () => {
    window.history.replaceState(null, "", "/inbound?receiptId=../other");
    api.listReceipts.mockResolvedValue({ items: [], total: 0 });
    const wrapper = panel(); await flushPromises(); expect(api.getReceipt).not.toHaveBeenCalled(); wrapper.unmount();
  });
});
