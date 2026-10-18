import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import InventorySelectionPanel from "./InventorySelectionPanel.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
function selection() { return { taskVersion: 5, recheckGeneration: 0, contextDigest: "a".repeat(64), review: { version: 0 }, history: [], total: 0 }; }
function board() { return { task: { task_status: "IN_PROGRESS" }, items: [{ item: { id: "10" }, current: { version: 7 } }] }; }
function response(body: unknown, status = 200): Response { return { ok: status >= 200 && status < 300, status, json: async () => structuredClone(body) } as Response; }
function panel() { return mount(InventorySelectionPanel, { props: { taskId: "9", itemId: "10", itemPage: 1,
  observations: [{ id: "30", client_event_id: "event", recheck_generation: 0, outcome: "TIME_REVIEW", received_at: "2026-10-18T00:00:00Z", facts_json: {} }] } }); }
const action = (wrapper: ReturnType<typeof panel>, label: string) => wrapper.findAll("button").find(button => button.text() === label)!;
async function fill(wrapper: ReturnType<typeof panel>) {
  await wrapper.find("select").setValue("30");
  await wrapper.find("input[type=datetime-local]").setValue("2026-10-17T09:30:00.123");
  await wrapper.findAll("textarea")[0]!.setValue("核对后选择异常时间证据");
  await wrapper.findAll("textarea")[1]!.setValue("签字记录确认的实际现场观察时间和证据编号");
}
describe("inventory evidence selection", () => {
  beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); vi.spyOn(window, "confirm").mockReturnValue(true); });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  it("submits the explicit evidence with time, joint versions and physical context", async () => {
    const writes: RequestInit[] = [];
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") { writes.push(structuredClone(input)); return response({}); }
      return response(path.includes("/selection?") ? selection() : board());
    }));
    const wrapper = panel(); await flushPromises(); await fill(wrapper);
    await action(wrapper, "保存待采信选择").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(1);
    expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ observationId: "30", recheckGeneration: 0, reviewVersion: 0,
      assetVersion: 7, contextDigest: "a".repeat(64), verifiedObservedAt: new Date("2026-10-17T09:30:00.123").toISOString() });
    expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "5", "X-CSRF-Token": "csrf" });
    expect(wrapper.text()).toContain("不替代差异处理或最终采信"); expect(wrapper.emitted("changed")).toHaveLength(1); wrapper.unmount();
  });
  it("an ambiguous reply retains the exact original key, time and all versions", async () => {
    const writes: RequestInit[] = []; let lost = true;
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") { writes.push(structuredClone(input)); if (lost) { lost = false; throw Error("lost"); } return response({}); }
      return response(path.includes("/selection?") ? selection() : board());
    }));
    const wrapper = panel(); await flushPromises(); await fill(wrapper);
    await action(wrapper, "保存待采信选择").trigger("click"); await flushPromises();
    expect(wrapper.find("fieldset").element.matches(":disabled")).toBe(true);
    await action(wrapper, "重试原证据选择").trigger("click"); await flushPromises();
    expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
  });
  it("a current permission denial hides previous evidence and invalidates its parent", async () => {
    let denied = false;
    vi.stubGlobal("fetch", vi.fn(async (path: string) => denied ? response({ code: "DENIED" }, 403) : response(path.includes("/selection?") ? selection() : board())));
    const wrapper = panel(); await flushPromises(); await fill(wrapper); denied = true;
    await action(wrapper, "刷新选择与流转依据").trigger("click"); await flushPromises();
    expect(wrapper.findAll("textarea")).toHaveLength(0); expect(wrapper.emitted("invalid")).toHaveLength(1); wrapper.unmount();
  });
  it("old session replies cannot restore evidence or a pending mutation", async () => {
    const reads: ((value: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => reads.push(resolve))));
    const wrapper = panel(); state.session.user.id = "2"; state.session.csrfToken = "new";
    reads[0]!(response(selection())); reads[1]!(response(board())); await flushPromises();
    expect(wrapper.findAll("textarea")).toHaveLength(0); expect(wrapper.text()).not.toContain("证据 30"); wrapper.unmount();
  });
});
