import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import InventoryReadinessPanel from "./InventoryReadinessPanel.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const detail = () => ({ task: { id: "9", version: 7, task_status: "IN_PROGRESS" }, total: 1, missingCount: 1, notObservedCount: 0,
  items: [{ id: "9007199254740993", item: { id: "9007199254740993", recheck_generation: 2, snapshot_json: { assetCode: "A-1", name: "现场甲" } }, current: { version: 4 }, canRecord: true, physicalMissingOpen: false }] });
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const panel = () => mount(InventoryReadinessPanel, { props: { taskId: "9" } });
const action = (wrapper: ReturnType<typeof panel>, name: string) => wrapper.findAll("button").find(button => button.text() === name)!;
async function prepare(wrapper: ReturnType<typeof panel>) {
  await action(wrapper, "记录 A-1 未盘原因").trigger("click");
  await wrapper.findAll("textarea")[0]!.setValue("现场暂时封闭无法取得观察");
  await wrapper.findAll("textarea")[1]!.setValue("现场负责人员说明及受限环境记录，明确保留未盘依据");
  await action(wrapper, "核对未盘记录").trigger("click"); await flushPromises();
}
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); });
it("uses explicit inline confirmation and sends no mutation before the final reviewable action", async () => {
  const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => { if (input.method === "POST") writes.push(input); return response(input.method === "POST" ? { status: "NOT_OBSERVED" } : detail()); }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper);
  expect(writes).toHaveLength(0); expect(wrapper.find('[aria-label="确认本次未盘或待复核处理"]').text()).toContain("未盘不计正常");
  await action(wrapper, "确认保存本次处理").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(1); expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ recheckGeneration: 2, assetVersion: 4, kind: "UNABLE_TO_OBSERVE" });
  expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "7", "X-CSRF-Token": "csrf" }); wrapper.unmount();
});
it("retains the exact original request across an unknown reply and a fresh displayed task version", async () => {
  const writes: RequestInit[] = []; let lost = true; const view = detail();
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(input); if (lost) { lost = false; view.task.version = 99; throw new Error("lost committed reply"); } return response({ status: "NOT_OBSERVED" }); }
    return response(view);
  }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper); await action(wrapper, "确认保存本次处理").trigger("click"); await flushPromises();
  expect(wrapper.text()).toContain("提交结果尚未确认"); await action(wrapper, "刷新未盘与就绪情况").trigger("click"); await flushPromises();
  await action(wrapper, "重试原未盘或待复核操作").trigger("click"); await flushPromises(); expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
});
it("requires every item result or reason before the explicit waiting-review confirmation", async () => {
  const view = detail(); let writes = 0;
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => { if (input.method === "POST") writes++; return response(view); }));
  const wrapper = panel(); await flushPromises(); await wrapper.find("textarea").setValue("全部项目已有结果或明确原因");
  expect(action(wrapper, "核对进入待复核").attributes("disabled")).toBeDefined();
  view.missingCount = 0; view.notObservedCount = 1; await action(wrapper, "刷新未盘与就绪情况").trigger("click"); await flushPromises();
  await action(wrapper, "核对进入待复核").trigger("click"); expect(writes).toBe(0); expect(wrapper.text()).toContain("未盘 1 项不计正常"); wrapper.unmount();
});
it("hides private readiness records when the identity changes during a read", async () => {
  let finish!: (value: Response) => void;
  vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
  const wrapper = panel(); const old = finish; state.session.user.id = "2"; await flushPromises(); old(response(detail()));
  await flushPromises(); expect(wrapper.text()).not.toContain("现场甲"); wrapper.unmount();
});
