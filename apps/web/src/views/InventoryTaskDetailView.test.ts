import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import InventoryTaskDetailView from "./InventoryTaskDetailView.vue";
const state = vi.hoisted(() => ({ request: vi.fn(), push: vi.fn(), roles: ["ASSET_ADMIN"] }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot name=\"actions\" /><slot /></main>" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: state.roles } }) }));
vi.mock("@/loan/api", () => ({ loanRequest: state.request, loanMessage: () => "结果尚未确认", LoanApiError: class extends Error {} }));
vi.mock("vue-router", () => ({ useRouter: () => ({ push: state.push }) }));
const task = (status: string, version: number) => ({ id: "9007199254740993", name: "固定范围", task_status: status, version,
 assignment_version: 0, starts_at: "2026-12-01T00:00:00Z", due_at: "2026-12-02T00:00:00Z", range_json: { categoryId: "7" } });
function detailWrapper() {
  return mount(InventoryTaskDetailView, { props: { id: "9007199254740993" },
   global: { stubs: { RouterLink: { props: ["to"], template: '<a :data-destination="to"><slot /></a>' } } } });
}
describe("inventory task detail", () => {
 beforeEach(() => { state.request.mockReset(); state.push.mockReset(); state.roles = ["ASSET_ADMIN"]; vi.spyOn(window, "confirm").mockReturnValue(true); });
 it("publishes only a previewed range and replays exact lost reply without changing captured count", async () => {
  let status = "DRAFT"; let lose = true; const writes: unknown[] = [];
  state.request.mockImplementation(async (path: string, mutation?: { path: string; body?: unknown }) => {
   if (path.includes("/catalog/") || path.includes("/active-references")) return { items: [] };
   if (path.includes("/executors")) return [];
   if (path.endsWith("/preview")) return { count: 2, digest: "a".repeat(64) };
   if (mutation) { writes.push(JSON.parse(JSON.stringify(mutation))); status = "READY"; if (lose) { lose = false; throw Error("lost reply"); } return task(status, 1); }
   return { task: task(status, status === "DRAFT" ? 0 : 1), items: [], total: 0, canManage: true };
  });
  const wrapper = detailWrapper(); await flushPromises();
  const button = (text: string) => wrapper.findAll("button").find(row => row.text() === text)!;
  expect(button("发布并冻结集合").attributes("disabled")).toBeDefined();
  await button("预览应盘数量").trigger("click"); await flushPromises();
  expect(wrapper.text()).toContain("预计 2 项");
  await button("发布并冻结集合").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(1); await button("重试原操作").trigger("click"); await flushPromises();
  expect(writes[1]).toEqual(writes[0]); expect(writes[0]).toMatchObject({ path: "/inventory-tasks/9007199254740993/publication", version: 0,
   body: { expectedCount: 2, previewDigest: "a".repeat(64) } }); expect(wrapper.text()).toContain("待开始");
  expect(state.push).not.toHaveBeenCalled(); wrapper.unmount();
 });
 it("copies the frozen range onto the new draft page instead of staying on the source task", async () => {
  state.request.mockImplementation(async (path: string, mutation?: unknown) => {
   if (path.includes("/executors")) return [];
   if (mutation) return { ...task("DRAFT", 0), id: "9007199254740994", name: "固定范围 副本" };
   return { task: task("DRAFT", 0), items: [], total: 0, canManage: true };
  });
  const wrapper = detailWrapper(); await flushPromises();
  await wrapper.findAll("button").find(row => row.text() === "复制范围创建新草稿")!.trigger("click"); await flushPromises();
  expect(state.push).toHaveBeenCalledWith("/inventory-tasks/9007199254740994"); wrapper.unmount();
 });
 it("management actions and edit entry stay off the executor detail page", async () => {
  state.roles = ["EMPLOYEE"];
  state.request.mockResolvedValue({ task: task("IN_PROGRESS", 2), items: [{ id: "1", asset_id: "7", assignment_version: 0,
   executor_account_id: "3", result_count: 0, snapshot_json: { assetCode: "FA-1", name: "扫码枪", locationName: "一号仓", custodyType: "DEPARTMENT" } }],
   total: 1, canManage: false });
  const wrapper = detailWrapper(); await flushPromises();
  expect(wrapper.text()).toContain("FA-1 · 扫码枪"); expect(wrapper.text()).not.toContain("任务操作");
  expect(wrapper.text()).not.toContain("编辑草稿"); expect(state.request.mock.calls).toHaveLength(1); wrapper.unmount();
 });
});
