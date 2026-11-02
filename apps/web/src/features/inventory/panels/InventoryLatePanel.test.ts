import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import InventoryLatePanel from "./InventoryLatePanel.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const entry = () => ({ evidence: { observation_id: "9007199254740993", comparison_kind: "FROZEN_CONFLICT", report_sha256: "frozen", snapshot_json: { rawObservedAt: "2026-10-21T00:00:00Z", calibratedObservedAt: null, receivedAt: "2026-10-21T01:00:00Z", assetId: "9007199254740994", acceptedObservationId: "8", facts: { actual: { condition: "UNKNOWN" } } } }, review: { decision_version: 0, result: "OPEN" }, todoOpen: true });
const fixture = () => ({ task: { version: 7, name: "原冻结任务", range_json: { usingOrganizationId: "9007199254740995" } }, items: [entry()], total: 1 });
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const panel = () => mount(InventoryLatePanel, { props: { taskId: "9" } });
const button = (wrapper: ReturnType<typeof panel>, label: string) => wrapper.findAll("button").find(item => item.text() === label)!;
async function prepare(wrapper: ReturnType<typeof panel>) { await button(wrapper, "核对原证据与补充历史").trigger("click"); await flushPromises();
  const fields = wrapper.findAll("textarea"); await fields[0]!.setValue("逐项核实不同观察时点的补充结论"); await fields[1]!.setValue("核对冻结采信与原始线索，不改原报告及资产日期"); }
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("prepares historical evidence only after the explicit inline action", async () => {
  const writes: RequestInit[] = []; vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => { if (input.method === "POST") writes.push(input); return response(fixture()); }));
  const wrapper = panel(); await flushPromises(); await button(wrapper, "准备既有补充证据").trigger("click"); expect(writes).toHaveLength(0);
  expect(wrapper.text()).toContain("保留原始分类与回执"); await button(wrapper, "确认追加补充处理").trigger("click"); await flushPromises(); expect(writes).toHaveLength(1); wrapper.unmount();
});
it("keeps exact original key body and decision version after a committed response is lost", async () => {
  const writes: RequestInit[] = []; let lost = true; const board = fixture();
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(input); if (lost) { lost = false; board.items[0]!.review.decision_version = 9; throw new Error("actual reply unknown"); } return response({ result: "REVIEWED" }); }
    return response(url.includes("/observations/") ? { case: board.items[0], history: { items: [], page: 1, pageSize: 20, total: 0 } } : board);
  }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper); await button(wrapper, "核对补充处理").trigger("click"); expect(writes).toHaveLength(0);
  await button(wrapper, "确认追加补充处理").trigger("click"); await flushPromises(); await button(wrapper, "刷新补充证据").trigger("click"); await flushPromises();
  await button(wrapper, "原键重试未确认补充处理").trigger("click"); await flushPromises(); expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
});
it("does not send or retain private evidence after identity replacement during inline confirmation", async () => {
  const writes: RequestInit[] = []; vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => { if (input.method === "POST") writes.push(input); if (state.session.user.id !== "1") return response({ code: "FORBIDDEN" }, 403); return response(url.includes("/observations/") ? { case: entry(), history: { items: [], page: 1, pageSize: 20, total: 0 } } : fixture()); }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper); await button(wrapper, "核对补充处理").trigger("click"); state.session.user.id = "2"; await flushPromises();
  expect(writes).toHaveLength(0); expect(wrapper.text()).not.toContain("9007199254740993"); expect(wrapper.text()).not.toContain("确认追加补充处理"); wrapper.unmount();
});
it("freezes the original source range and date inputs before creating a linked draft", async () => {
  const writes: RequestInit[] = []; vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => { if (input.method === "POST") { writes.push(input); return response({ result: "FOLLOWUP_OPEN" }); } return response(url.includes("/observations/") ? { case: entry(), history: { items: [], page: 1, pageSize: 20, total: 0 } } : fixture()); }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper); await wrapper.find("select").setValue("LINK_RECHECK");
  const inputs = wrapper.findAll("input"); await inputs[0]!.setValue("关联实盘任务"); await inputs[1]!.setValue("2026-10-22T00:00:00Z"); await inputs[2]!.setValue("2026-10-23T00:00:00Z");
  await button(wrapper, "核对补充处理").trigger("click"); await inputs[0]!.setValue("确认后不能替换冻结载荷"); await button(wrapper, "确认追加补充处理").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string).recheck).toMatchObject({ name: "关联实盘任务", range: { usingOrganizationId: "9007199254740995" }, startsAt: "2026-10-22T00:00:00.000Z" }); wrapper.unmount();
});

it("reads bounded supplemental history pages without changing a draft or writing a decision", async () => {
  const paths: string[] = [], writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => { paths.push(url); if(input.method === "POST") writes.push(input);
    return response(url.includes("/observations/") ? { case: entry(), history: { items: [{ decision_version: url.includes("page=2") ? 21 : 1, actor_username: url.includes("page=2") ? "第二页核实人" : "首条核实人", method: "NOTE", reason: "原结论保留", evidence: "独立依据", result: "REVIEWED" }], page: url.includes("page=2") ? 2 : 1, pageSize: 20, total: 21 } } : fixture()); }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper); await button(wrapper, "下页补充决定").trigger("click"); await flushPromises();
  expect(wrapper.text()).toContain("第二页核实人"); expect(wrapper.findAll("textarea")[0]!.element.value).toBe("逐项核实不同观察时点的补充结论"); expect(paths.some(path => path.endsWith("?page=2&pageSize=20"))).toBe(true); expect(writes).toHaveLength(0); wrapper.unmount();
});

it("keeps an unknown independent damage write mounted while blocking parent refresh and recheck", async () => {
  const damage = { assetId: "9007199254740994", currentAsset: { version: 4, assetCode: "DAMAGE-PRIVATE" }, damage: { observation_id: entry().evidence.observation_id, version: 2, damage_status: "PROCESSING", repair_id: "88", repair_status: "WAREHOUSE_PROCESSING" }, originalEvidence: { rawObservedAt: "2099-01-01T00:00:00Z" }, recheckResult: "FOLLOWUP_OPEN", todoOpen: true, history: { items: [], page: 1, total: 0 } };
  const source = { ...entry(), damageFollowup: { observation_id: entry().evidence.observation_id, damage_status: "PROCESSING" } };
  const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(structuredClone(input)); throw Error("committed reply lost"); }
    if (url.includes("/damage-repair")) return response(damage);
    return response(url.includes("/observations/") ? { case: source, history: { items: [], page: 1, total: 0 } } : { ...fixture(), items: [source] });
  }));
  const wrapper = panel(); await flushPromises(); await button(wrapper, "核对原证据与补充历史").trigger("click"); await flushPromises();
  const child = wrapper.find('[aria-label="迟到损坏独立维修"]'); expect(child.text()).toContain("DAMAGE-PRIVATE");
  await child.findAll("textarea")[0]!.setValue("Actual independent repair progress"); await child.findAll("textarea")[1]!.setValue("Signed actual repair and physical acceptance records");
  await button(wrapper, "核对独立损坏处理").trigger("click");
  expect(button(wrapper, "刷新补充证据").attributes("disabled")).toBeDefined();
  expect(button(wrapper, "确认保存独立损坏处理").attributes("disabled")).toBeUndefined();
  await button(wrapper, "确认保存独立损坏处理").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(1); expect(wrapper.text()).toContain("原键重试独立损坏处理");
  expect(button(wrapper, "刷新补充证据").attributes("disabled")).toBeDefined();
  expect(button(wrapper, "核对补充处理").element.matches(":disabled")).toBe(true); wrapper.unmount();
});
