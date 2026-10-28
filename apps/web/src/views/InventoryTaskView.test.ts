import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import InventoryTaskView from "./InventoryTaskView.vue";
const state = vi.hoisted(() => ({ request: vi.fn(), roles: ["ASSET_ADMIN"] }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot name=\"actions\" /><slot /></main>" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: state.roles } }) }));
vi.mock("@/loan/api", () => ({ loanRequest: state.request, loanMessage: () => "结果尚未确认", LoanApiError: class extends Error {} }));
const links = (wrapper: ReturnType<typeof mount>) => wrapper.findAll("a").map(a => [a.text(), a.attributes("data-destination")]);
function listWrapper() {
  return mount(InventoryTaskView, { global: { stubs: { RouterLink: { props: ["to"], template: '<a :data-destination="to"><slot /></a>' } } } });
}
describe("inventory task list", () => {
 beforeEach(() => { state.request.mockReset(); state.roles = ["ASSET_ADMIN"]; });
 it("keeps the draft form off the list and links every row to its own detail page", async () => {
  state.request.mockResolvedValue({ items: [{ id: "9007199254740993", name: "固定范围", task_status: "DRAFT",
   starts_at: "2026-12-01T00:00:00Z", due_at: "2026-12-02T00:00:00Z" }], total: 1 });
  const wrapper = listWrapper(); await flushPromises();
  expect(wrapper.text()).toContain("全部任务"); expect(wrapper.text()).not.toContain("保存草稿");
  expect(links(wrapper)).toEqual(expect.arrayContaining([["新建盘点草稿", "/inventory-tasks/new"], ["打开", "/inventory-tasks/9007199254740993"]]));
  expect(state.request.mock.calls).toHaveLength(1); expect(wrapper.text()).toContain("草稿"); wrapper.unmount();
 });
 it("ordinary executor list exposes neither the draft entry nor management wording", async () => {
  state.roles = ["EMPLOYEE"]; state.request.mockResolvedValue({ items: [], total: 0 });
  const wrapper = listWrapper(); await flushPromises();
  expect(wrapper.text()).toContain("我的任务"); expect(wrapper.text()).not.toContain("新建盘点草稿");
  expect(links(wrapper)).toEqual([]); expect(state.request.mock.calls).toHaveLength(1); wrapper.unmount();
 });
});
