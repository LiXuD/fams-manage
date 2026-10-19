import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import InventoryReviewPanel from "./InventoryReviewPanel.vue";
import type { Board } from "@/inventory-review/api";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf-one" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
function board(): Board {
  const snapshot = { assetCode: "A-9007199254740993", name: "Private asset", version: 0,
    locationId: "9007199254740993", locationName: "Original room", custodyType: "STORAGE" };
  return { task: { id: "9", name: "Own task", task_status: "IN_PROGRESS", version: 5 }, total: 2, page: 1, pageSize: 20, reviewedCount: 0,
    items: ["10", "11"].map((id, index) => ({ item: { id, asset_id: String(100 + index), recheck_generation: 0, snapshot_json: snapshot },
      current: snapshot, candidate: { id: String(30 + index), client_event_id: "00000000-0000-4000-8000-000000000001",
        received_at: "2026-10-18T00:00:00Z", recheck_generation: 0, outcome: "ACCEPTED",
        facts_json: { rawObservedAt: "2026-10-18T00:00:00Z", actual: { locationId: snapshot.locationId, custodyType: "STORAGE", condition: "GOOD" } } },
      review: { version: 0 }, canAcceptNormal: true, blockedReason: null, movementEvents: [] })) };
}
function reply(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => structuredClone(body) } as Response;
}
const action = (wrapper: ReturnType<typeof mount>, label: string) => wrapper.findAll("button").find(button => button.text() === label)!;
describe("inventory review panel", () => {
  beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf-one" }); vi.spyOn(window, "confirm").mockReturnValue(true); });
  afterEach(() => { vi.unstubAllGlobals(); });
  it("sends explicit per-item versions and reports mixed conflicts with the actual reviewer", async () => {
    const writes: { path: string; headers: HeadersInit | undefined; body: unknown }[] = [];
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") {
        writes.push({ path, headers: input.headers, body: JSON.parse(input.body as string) });
        return reply({ accepted: 1, conflicts: 1, items: [{ itemId: "10", status: "ACCEPTED" },
          { itemId: "11", status: "CONFLICT", code: "INVENTORY_REVIEW_VERSION_CONFLICT", current: { reviewer_username: "Actual admin", reviewed_at: "2026-10-18T01:00:00Z", version: 1 } }] });
      }
      return reply(board());
    }));
    const wrapper = mount(InventoryReviewPanel, { props: { taskId: "9" } }); await flushPromises();
    expect(wrapper.text()).toContain("Original room");
    for (const checkbox of wrapper.findAll("input[type=checkbox]")) await checkbox.setValue(true);
    await action(wrapper, "逐项采信所选正常观察").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(1);
    expect(writes[0]?.body).toMatchObject({ items: [{ itemId: "10", recheckGeneration: 0, reviewVersion: 0, assetVersion: 0 }, { itemId: "11" }] });
    expect(writes[0]?.headers).toMatchObject({ "If-Match-Version": "5", "X-CSRF-Token": "csrf-one" });
    expect(wrapper.text()).toContain("本次已采信 1 项，冲突或待核实 1 项"); expect(wrapper.text()).toContain("Actual admin"); wrapper.unmount();
  });
  it("ambiguous reply keeps the original request key, task version and captured body", async () => {
    const writes: RequestInit[] = []; let lost = true;
    vi.stubGlobal("fetch", vi.fn(async (_path: string, input: RequestInit) => {
      if (input.method === "POST") {
        writes.push(structuredClone(input)); if (lost) { lost = false; throw Error("reply lost"); }
        return reply({ accepted: 1, conflicts: 0, items: [{ itemId: "10", status: "ACCEPTED" }] });
      }
      return reply(board());
    }));
    const wrapper = mount(InventoryReviewPanel, { props: { taskId: "9" } }); await flushPromises();
    await wrapper.find("input[type=checkbox]").setValue(true); await action(wrapper, "逐项采信所选正常观察").trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain("结果尚未确认"); expect(wrapper.find("textarea").element.matches(":disabled")).toBe(true);
    await action(wrapper, "重试原采信操作").trigger("click"); await flushPromises();
    expect(writes[1]).toEqual(writes[0]); expect(wrapper.text()).toContain("本次已采信 1 项"); wrapper.unmount();
  });
  it("stale version feedback names the committed reviewer and a permission denial hides old facts", async () => {
    let denied = false;
    vi.stubGlobal("fetch", vi.fn(async (_path: string, input: RequestInit) => {
      if (input.method === "POST") return reply({ code: "INVENTORY_REVIEW_TASK_CHANGED", fieldErrors: {
        processedBy: "Winner", processedAt: "2026-10-18T01:00:00Z", result: "ACCEPTED" } }, 409);
      return denied ? reply({ code: "INVENTORY_REVIEW_ADMIN_REQUIRED" }, 403) : reply(board());
    }));
    const wrapper = mount(InventoryReviewPanel, { props: { taskId: "9" } }); await flushPromises();
    await wrapper.find("input[type=checkbox]").setValue(true); await action(wrapper, "逐项采信所选正常观察").trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain("Winner"); expect(wrapper.text()).not.toContain("重试原采信操作");
    denied = true; await action(wrapper, "刷新复核信息").trigger("click"); await flushPromises();
    expect(wrapper.text()).not.toContain("Private asset"); expect(wrapper.text()).toContain("已隐藏原资料"); wrapper.unmount();
  });
  it("a late old-identity response cannot repopulate a new session", async () => {
    let complete: (value: Response) => void = () => undefined;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => { complete = resolve; })));
    const wrapper = mount(InventoryReviewPanel, { props: { taskId: "9" } });
    state.session.user.id = "2"; state.session.csrfToken = "csrf-two";
    complete(reply(board())); await flushPromises();
    expect(wrapper.text()).not.toContain("Private asset"); expect(wrapper.text()).toContain("身份或权限已变化"); wrapper.unmount();
  });
  it("a photo authorization denial invalidates an already pending parent refresh", async () => {
    let waiting = false, finish!: (value: Response) => void;
    const raw = board().items[0]!.candidate!;
    raw.facts_json.photoIds = ["33333333-3333-4333-8333-333333333333"];
    vi.stubGlobal("fetch", vi.fn(async (path: string) => {
      if (path.endsWith("/photos")) return reply({ code: "INVENTORY_PHOTO_NOT_AUTHORIZED" }, 403);
      if (path.includes("/observations?")) return reply({ items: [raw], total: 1 });
      if (waiting) return new Promise<Response>(resolve => { finish = resolve; });
      return reply(board());
    }));
    const wrapper = mount(InventoryReviewPanel, { props: { taskId: "9" }, global: { stubs: {
      InventorySelectionPanel: true, InventoryEvidenceDecisionPanel: true, InventoryDifferencePanel: true,
    } } }); await flushPromises();
    await action(wrapper, "查看全部原始证据").trigger("click"); await flushPromises();
    waiting = true; await action(wrapper, "刷新复核信息").trigger("click");
    await action(wrapper, "查看原始照片资料").trigger("click"); await flushPromises();
    expect(wrapper.text()).not.toContain("Private asset"); finish(reply(board())); await flushPromises();
    expect(wrapper.text()).not.toContain("Private asset"); expect(wrapper.find("table").exists()).toBe(false); wrapper.unmount();
  });
});
