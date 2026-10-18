import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import InventoryEvidenceDecisionPanel from "./InventoryEvidenceDecisionPanel.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
function selection() { return { taskVersion: 8, recheckGeneration: 2, contextDigest: "b".repeat(64), review: { version: 3, observation_id: "31", review_status: "SELECTED" } }; }
function board() { return { task: { task_status: "IN_PROGRESS" }, items: [{ item: { id: "10" }, current: { version: 7 } }] }; }
function subjects() { return { items: [{ dimension: "LOCATION", version: 4, subject_status: "OPEN" }, { dimension: "CONFLICT", version: 5, subject_status: "OPEN" }] }; }
function response(body: unknown, status = 200): Response { return { ok: status >= 200 && status < 300, status, json: async () => structuredClone(body) } as Response; }
function panel() { return mount(InventoryEvidenceDecisionPanel, { props: { taskId: "9", itemId: "10", itemPage: 1,
  observations: ["30", "31"].map(id => ({ id, client_event_id: "event", recheck_generation: 2, outcome: "CONFLICT_REVIEW", received_at: "2026-10-18T00:00:00Z", facts_json: { actual: { locationId: "20" } } })) } }); }
function read(path: string) { return path.endsWith("/selection") ? selection() : path.includes("evidence-dispositions") ? { items: [], total: 0 } : path.includes("evidence-subjects") ? subjects() : board(); }
const action = (wrapper: ReturnType<typeof panel>, label: string) => wrapper.findAll("button").find(button => button.text() === label)!;
async function explain(wrapper: ReturnType<typeof panel>) {
  await wrapper.findAll("textarea")[0]!.setValue("明确核实后逐项处置证据");
  await wrapper.findAll("textarea")[1]!.setValue("独立签字表与实物编号一致，保留全部反证和原始事实");
}
describe("inventory evidence disposition and final acceptance", () => {
  beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); vi.spyOn(window, "confirm").mockReturnValue(true); });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  it("binds all rejected dimensions and counterevidence review to the current asset and task versions", async () => {
    const writes: RequestInit[] = [];
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") { writes.push(structuredClone(input)); return response({}); } return response(read(path));
    }));
    const wrapper = panel(); await flushPromises(); await wrapper.find("select").setValue("30"); await flushPromises(); await explain(wrapper);
    await action(wrapper, "共同提交证据处置").trigger("click"); await flushPromises();
    expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ observationId: "30", reviewVersion: 3, recheckGeneration: 2, assetVersion: 7,
      contextDigest: "b".repeat(64), method: "REJECT_EVIDENCE", subjects: [{ dimension: "LOCATION", version: 4 }, { dimension: "CONFLICT", version: 5 }] });
    expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "8" }); expect(wrapper.emitted("changed")).toHaveLength(1); wrapper.unmount();
  });
  it("verification of the selected observation never submits its physical dimension as an automatic closure", async () => {
    let body: unknown;
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") { body = JSON.parse(input.body as string); return response({}); } return response(read(path));
    }));
    const wrapper = panel(); await flushPromises(); await wrapper.find("select").setValue("31"); await flushPromises(); await explain(wrapper);
    await action(wrapper, "共同提交证据处置").trigger("click"); await flushPromises();
    expect(body).toMatchObject({ method: "VERIFY_SELECTED", subjects: [{ dimension: "CONFLICT", version: 5 }] }); wrapper.unmount();
  });
  it("a lost final acceptance reply retries the exact original acceptance request", async () => {
    const writes: { path: string; input: RequestInit }[] = []; let lost = true;
    vi.stubGlobal("fetch", vi.fn(async (path: string, input: RequestInit) => {
      if (input.method === "POST") { writes.push({ path, input: structuredClone(input) }); if (lost) { lost = false; throw Error("lost"); } return response({}); } return response(read(path));
    }));
    const wrapper = panel(); await flushPromises(); await explain(wrapper);
    await action(wrapper, "最终采信所选证据").trigger("click"); await flushPromises();
    expect(writes[0]!.path).toContain("/acceptance"); expect(wrapper.find("fieldset").element.matches(":disabled")).toBe(true);
    await action(wrapper, "重试原证据处置").trigger("click"); await flushPromises(); expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
  });
  it("permission denial clears selection, counterevidence and parent raw evidence", async () => {
    let denied = false;
    vi.stubGlobal("fetch", vi.fn(async (path: string) => denied ? response({ code: "DENIED" }, 403) : response(read(path))));
    const wrapper = panel(); await flushPromises(); expect(wrapper.text()).toContain("证据 31"); denied = true;
    await action(wrapper, "刷新证据处置").trigger("click"); await flushPromises(); expect(wrapper.text()).not.toContain("证据 31"); expect(wrapper.emitted("invalid")).toHaveLength(1); wrapper.unmount();
  });
  it("stale identity reads cannot restore previous review facts", async () => {
    const pending: ((value: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => pending.push(resolve))));
    const wrapper = panel(); state.session.user.id = "2"; state.session.csrfToken = "next";
    pending[0]!(response(selection())); pending[1]!(response(board())); pending[2]!(response({ items: [], total: 0 })); await flushPromises();
    expect(wrapper.text()).not.toContain("证据 31"); expect(wrapper.findAll("textarea")).toHaveLength(0); wrapper.unmount();
  });
});
