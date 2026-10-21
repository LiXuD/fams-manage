import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import InventorySealPanel from "./InventorySealPanel.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const preflight = () => ({ task: { id: "9", version: 7, task_status: "WAITING_REVIEW" }, sealed: false,
  blockers: [], blockerCount: 0, synchronization: [{ accountId: "2", name: "执行人", current: false, reason: "BINDING_MISSING", devices: [] }],
  synchronizationDigest: "a".repeat(64), missingSynchronizationCount: 1 });
const frozen = () => ({ task: { id: "9", version: 8, task_status: "COMPLETED" }, sealed: true,
  report: { report_sha256: "b".repeat(64), accepted_count: 1, not_observed_count: 1, captured_count: 1,
    actor_username: "复核人", created_at: "2026-10-20T01:00:00Z", missing_sync_reason: "待补证名单保持冻结", snapshot_json: { sealed: true } } });
const response = (value: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(value) }) as Response;
const panel = () => mount(InventorySealPanel, { props: { taskId: "9" } });
const button = (wrapper: ReturnType<typeof panel>, name: string) => wrapper.findAll("button").find(item => item.text() === name)!;
async function prepare(wrapper: ReturnType<typeof panel>) {
  const fields = wrapper.findAll("textarea"); await fields[0]!.setValue("逐项核对封存依据并冻结报告");
  await fields[1]!.setValue("执行人声明暂缺，明确保留名单及补证复盘义务");
  await button(wrapper, "核对本次封存").trigger("click"); await flushPromises();
}
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("keeps physical blockers nonwaivable and submits only after the inline final action", async () => {
  const view = { ...preflight(), blockerCount: 1, blockers: [{ code: "INVENTORY_SEAL_PHYSICAL_MISSING_OPEN" }] }; const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => { if (input.method === "POST") writes.push(input); return response(view); }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper); expect(writes).toHaveLength(0);
  expect(button(wrapper, "核对本次封存").attributes("disabled")).toBeDefined();
  view.blockerCount = 0; view.blockers = []; await button(wrapper, "刷新封存检查与报告").trigger("click"); await flushPromises();
  await prepare(wrapper); expect(writes).toHaveLength(0); expect(wrapper.text()).toContain("未盘不计正常");
  await button(wrapper, "确认封存本任务").trigger("click"); await flushPromises(); expect(writes).toHaveLength(1);
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ synchronizationDigest: "a".repeat(64) }); wrapper.unmount();
});
it("preserves the original UUID version payload and roster digest after a lost committed reply", async () => {
  const view = preflight(), writes: RequestInit[] = []; let lost = true;
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(input); if (lost) { lost = false; view.task.version = 99; view.synchronizationDigest = "c".repeat(64); throw new Error("lost committed seal reply"); } return response({ status: "COMPLETED" }); }
    return response(view);
  }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper); await button(wrapper, "确认封存本任务").trigger("click"); await flushPromises();
  expect(wrapper.text()).toContain("封存结果尚未确认"); await button(wrapper, "刷新封存检查与报告").trigger("click"); await flushPromises();
  await button(wrapper, "原键重试未确认封存").trigger("click"); await flushPromises(); expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
});
it("exports all three frozen sections with the frozen header and no current asset lookup", async () => {
  const paths: string[] = [], payloads: unknown[][] = []; const clicked = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  class ReportBlob { constructor(parts: unknown[]) { payloads.push(parts); } }
  vi.stubGlobal("Blob", ReportBlob); vi.stubGlobal("URL", class extends URL { static createObjectURL() { return "blob:owned-report"; } static revokeObjectURL() {} });
  vi.stubGlobal("fetch", vi.fn(async (url: string) => { paths.push(url); return response(url.includes("/report/") ? { items: [{ snapshot_json: { frozen: true }, snapshot_sha256: "saved" }], total: 1 } : frozen()); }));
  const wrapper = panel(); await flushPromises(); await button(wrapper, "导出完整冻结报告 JSON").trigger("click"); await flushPromises();
  expect(clicked).toHaveBeenCalledOnce(); expect(paths.filter(path => path.includes("/report/"))).toHaveLength(3);
  const saved = JSON.parse(payloads[0]![0] as string); expect(saved.header.report_sha256).toBe("b".repeat(64));
  expect(Object.keys(saved.sections)).toEqual(["items", "evidence", "synchronization"]); expect(paths.some(path => path.includes("/assets"))).toBe(false); wrapper.unmount();
});
it("does not export old report contents after identity changes during a page request", async () => {
  const clicked = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    if (url.includes("/report/")) { state.session.user.id = "2"; return response({ items: [{ snapshot_json: { private: true } }], total: 1 }); }
    return response(frozen());
  }));
  const wrapper = panel(); await flushPromises(); await button(wrapper, "导出完整冻结报告 JSON").trigger("click"); await flushPromises();
  expect(clicked).not.toHaveBeenCalled(); wrapper.unmount();
});
