import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import InventoryTaskView from "./InventoryTaskView.vue";
const state = vi.hoisted(() => ({ request: vi.fn(), roles: ["ASSET_ADMIN"] }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: state.roles } }) }));
vi.mock("@/loan/api", () => ({ loanRequest: state.request, loanMessage: () => "结果尚未确认", LoanApiError: class extends Error {} }));
describe("inventory task management", () => {
 beforeEach(() => { state.request.mockReset(); state.roles = ["ASSET_ADMIN"]; vi.spyOn(window, "confirm").mockReturnValue(true); });
 it("publishes only a previewed range and replays exact lost publication without changing captured count", async () => {
  let status = "DRAFT"; let lose = true; const writes: unknown[] = [];
  const task = () => ({ id: "9007199254740993", name: "固定范围", task_status: status, version: status === "DRAFT" ? 0 : 1,
   assignment_version: 0, starts_at: "2026-12-01T00:00:00Z", due_at: "2026-12-02T00:00:00Z", range_json: { categoryId: "7" } });
  state.request.mockImplementation(async (path: string, mutation?: { path: string; body?: unknown }) => {
   if (path.includes("/catalog/") || path.includes("/active-references")) return { items: [] };
   if (path.includes("/executors")) return [];
   if (path.endsWith("/preview")) return { count: 2, digest: "a".repeat(64) };
   if (mutation) { writes.push(JSON.parse(JSON.stringify(mutation))); status = "READY"; if (lose) { lose = false; throw Error("lost reply"); } return task(); }
   if (path.includes("/9007199254740993?")) return { task: task(), items: [], total: 0, canManage: true };
   return { items: [task()], total: 1, canManage: true };
  });
  const wrapper = mount(InventoryTaskView); await flushPromises();
  const button = (text: string) => wrapper.findAll("button").find(row => row.text() === text)!;
  await button("固定范围 · 草稿").trigger("click"); await flushPromises();
  expect(button("发布并冻结集合").attributes("disabled")).toBeDefined();
  await button("预览应盘数量").trigger("click"); await flushPromises();
  expect(wrapper.text()).toContain("预计 2 项");
  await button("发布并冻结集合").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(1); await button("重试原操作").trigger("click"); await flushPromises();
  expect(writes[1]).toEqual(writes[0]); expect(writes[0]).toMatchObject({ path: "/inventory-tasks/9007199254740993/publication", version: 0,
   body: { expectedCount: 2, previewDigest: "a".repeat(64) } }); expect(wrapper.text()).toContain("待开始"); wrapper.unmount();
 });
 it("ordinary executor view has no draft, publication or all-person choices", async () => {
  state.roles = ["EMPLOYEE"]; state.request.mockResolvedValue({ items: [], total: 0, canManage: false });
  const wrapper = mount(InventoryTaskView); await flushPromises();
  expect(wrapper.text()).toContain("我的任务"); expect(wrapper.text()).not.toContain("创建盘点草稿");
  expect(state.request.mock.calls).toHaveLength(1); wrapper.unmount();
 });
});
