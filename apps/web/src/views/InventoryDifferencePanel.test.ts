import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import InventoryDifferencePanel from "./InventoryDifferencePanel.vue";
import type { Board, ReviewItem } from "@/inventory-review/api";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf-a" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const item: ReviewItem = { item: { id: "10", asset_id: "100", recheck_generation: 0, snapshot_json: { assetCode: "PRIVATE-100", name: "Private equipment", version: 0, locationId: "20", custodyType: "STORAGE" } }, current: { assetCode: "PRIVATE-100", name: "Current private equipment", version: 5, locationId: "20", custodyType: "STORAGE" }, candidate: null, review: { version: 0 }, canAcceptNormal: false, blockedReason: "INVENTORY_REVIEW_EVIDENCE_CONFLICT", movementEvents: [] };
function board(): Board { return { task: { id: "9", name: "Private review", task_status: "IN_PROGRESS", version: 7 }, items: [structuredClone(item)], total: 1, page: 1, pageSize: 20, reviewedCount: 0 }; }
function subjects() { return { items: ["LOCATION", "RESPONSIBILITY", "CONFLICT"].map((dimension, index) => ({ observation_id: "30", item_id: "10", dimension, version: index + 2, subject_status: "OPEN", method: null, reason: null, evidence: null, reviewer_username: null, reviewed_at: null, decisions: [], links: [] })), total: 3 }; }
function response(body: unknown, status = 200): Response { return { ok: status >= 200 && status < 300, status, json: async () => structuredClone(body) } as Response; }
function panel() { return mount(InventoryDifferencePanel, { props: { taskId: "9", item: structuredClone(item), itemPage: 1, observationIds: ["30"] }, global: { stubs: { RouterLink: true } } }); }
const action = (wrapper: ReturnType<typeof panel>, label: string) => wrapper.findAll("button").find(button => button.text() === label)!;
async function choose(wrapper: ReturnType<typeof panel>) {
  await wrapper.findAll("input[type=checkbox]")[0]!.setValue(true);
  await wrapper.findAll("input[type=checkbox]")[1]!.setValue(true);
  await wrapper.findAll("textarea")[0]!.setValue("independent register confirmed");
  await wrapper.findAll("textarea")[1]!.setValue("signed original physical evidence document");
}
describe("independent inventory difference panel", () => {
  beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf-a" }); vi.spyOn(window, "confirm").mockReturnValue(true); });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  it("captures both independent versions and the current asset information version in one decision", async () => {
    const writes: RequestInit[] = [];
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") { writes.push(structuredClone(input)); return response({ taskId: "9", version: 8 }); }
      return response(path.includes("/differences?") ? subjects() : board());
    }));
    const wrapper = panel(); await flushPromises(); await choose(wrapper);
    await action(wrapper, "共同提交所选差异").trigger("click"); await flushPromises();
    await action(wrapper, "确认保存本次操作").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(1);
    expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ subjects: [{ observationId: "30", dimension: "LOCATION", version: 2 }, { observationId: "30", dimension: "RESPONSIBILITY", version: 3 }], assetVersion: 5, method: "BOOK_CORRECT" });
    expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "7", "X-CSRF-Token": "csrf-a" });
    expect(wrapper.text()).toContain("本次处理已保存"); wrapper.unmount();
  });
  it("a lost mutation response keeps the complete original request while refresh changes visible versions", async () => {
    const writes: RequestInit[] = []; let lost = true, changed = false;
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") { writes.push(structuredClone(input)); if (lost) { lost = false; changed = true; throw Error("lost reply"); } return response({ taskId: "9", version: 8 }); }
      const value = board(); if (changed) value.task.version = 11;
      return response(path.includes("/differences?") ? subjects() : value);
    }));
    const wrapper = panel(); await flushPromises(); await choose(wrapper);
    await action(wrapper, "共同提交所选差异").trigger("click"); await flushPromises();
    await action(wrapper, "确认保存本次操作").trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain("结果尚未确认"); expect(wrapper.find("fieldset").element.matches(":disabled")).toBe(true);
    await action(wrapper, "重试原差异操作").trigger("click"); await flushPromises();
    expect(writes[1]).toEqual(writes[0]); expect(wrapper.text()).toContain("本次处理已保存"); wrapper.unmount();
  });
  it("permission loss clears private state and invalidates the containing evidence view", async () => {
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => input.method === "POST" ? response({ code: "INVENTORY_REVIEW_ADMIN_REQUIRED" }, 403) : response(path.includes("/differences?") ? subjects() : board())));
    const wrapper = panel(); await flushPromises(); expect(wrapper.text()).toContain("PRIVATE-100");
    await action(wrapper, "列入当前页证据的差异").trigger("click"); await flushPromises();
    await action(wrapper, "确认保存本次操作").trigger("click"); await flushPromises();
    expect(wrapper.emitted("invalid")).toHaveLength(1); expect(wrapper.text()).not.toContain("PRIVATE-100"); expect(wrapper.text()).toContain("原资料已隐藏"); wrapper.unmount();
  });
  it("cancels an inline confirmation without sending or opening a native dialog", async () => {
    const writes: RequestInit[] = [];
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => { if (input.method === "POST") writes.push(input); return response(path.includes("/differences?") ? subjects() : board()); }));
    const wrapper = panel(); await flushPromises();
    await action(wrapper, "列入当前页证据的差异").trigger("click");
    expect(writes).toHaveLength(0); expect(window.confirm).not.toHaveBeenCalled();
    await action(wrapper, "取消本次操作").trigger("click");
    expect(writes).toHaveLength(0); expect(wrapper.find('[aria-label="确认差异操作"]').exists()).toBe(false); wrapper.unmount();
  });
  it("captures one damage dimension with independently verified time and preserves an unknown original reply", async () => {
    const writes: RequestInit[] = []; let lost = true;
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") { writes.push(structuredClone(input)); if (lost) { lost = false; throw Error("lost"); } return response({ version: 8 }); }
      const data = subjects(); data.items = [{ ...data.items[0]!, dimension: "CONDITION" }]; data.total = 1;
      return response(path.includes("/differences?") ? data : board());
    }));
    const wrapper = panel(); await flushPromises();
    await wrapper.find('input[type=checkbox]').setValue(true);
    await wrapper.find('select').setValue("FORMAL_REPAIR");
    await wrapper.find('input[type=datetime-local]').setValue("2026-01-01T10:00:00.123");
    await wrapper.findAll('input[type=checkbox]')[1]!.setValue(true);
    await wrapper.findAll('input[type=checkbox]')[2]!.setValue(true);
    await wrapper.findAll('textarea')[0]!.setValue("Actual verified physical fault");
    await wrapper.findAll('textarea')[1]!.setValue("Signed independent damage check");
    await wrapper.findAll('textarea')[2]!.setValue("Signed photo and independent discovery timestamp");
    await action(wrapper, "共同提交所选差异").trigger("click");
    expect(writes).toHaveLength(0);
    await action(wrapper, "确认保存本次操作").trigger("click"); await flushPromises();
    expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ method: "FORMAL_REPAIR", subjects: [{ dimension: "CONDITION", version: 2 }], repair: { physicalDamageChecked: true, discoveryTimeVerified: true, fault: "Actual verified physical fault" } });
    await action(wrapper, "重试原差异操作").trigger("click"); await flushPromises();
    expect(writes[1]).toEqual(writes[0]); expect(window.confirm).not.toHaveBeenCalled(); wrapper.unmount();
  });
  it("an old identity read cannot restore previous evidence after the session changes", async () => {
    const reads: ((value: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => reads.push(resolve))));
    const wrapper = panel(); state.session.user.id = "2"; state.session.csrfToken = "csrf-b";
    reads[0]!(response(board())); reads[1]!(response(subjects())); await flushPromises();
    expect(wrapper.text()).not.toContain("PRIVATE-100"); expect(wrapper.findAll("input[type=checkbox]")).toHaveLength(0); wrapper.unmount();
  });
});
