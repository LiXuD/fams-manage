import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import InventoryTaskDraftView from "./InventoryTaskDraftView.vue";
const state = vi.hoisted(() => ({ request: vi.fn(), push: vi.fn() }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot name=\"actions\" /><slot /></main>" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: ["ASSET_ADMIN"] } }) }));
vi.mock("@/loan/api", () => ({ loanRequest: state.request, loanMessage: () => "结果尚未确认", LoanApiError: class extends Error {} }));
vi.mock("vue-router", () => ({ useRouter: () => ({ push: state.push }) }));
function draftWrapper(id?: string) {
  return mount(InventoryTaskDraftView, { props: { id }, global: { stubs: { RouterLink: { props: ["to"], template: '<a :data-destination="to"><slot /></a>' } } } });
}
describe("inventory task draft", () => {
 beforeEach(() => { state.request.mockReset(); state.push.mockReset(); });
 it("creates a draft from a standalone page and opens the new task detail", async () => {
  const writes: unknown[] = [];
  state.request.mockImplementation(async (path: string, mutation?: { path: string; body?: unknown }) => {
   if (path.includes("/catalog/")) return { items: [] };
   if (mutation) { writes.push(JSON.parse(JSON.stringify(mutation))); return { id: "9007199254740993", name: "固定范围", version: 0,
    starts_at: "2026-12-01T00:00:00Z", due_at: "2026-12-02T00:00:00Z" }; }
   return { task: { id: "9007199254740993", name: "", version: 0, starts_at: "", due_at: "" }, items: [], total: 0 };
  });
  const wrapper = draftWrapper(); await flushPromises();
  const inputs = wrapper.findAll("input");
  await inputs[0].setValue("固定范围"); await inputs[1].setValue("2026-12-01T00:00"); await inputs[2].setValue("2026-12-02T00:00");
  await wrapper.find("form").trigger("submit"); await flushPromises();
  expect(writes[0]).toMatchObject({ path: "/inventory-tasks", version: 0, body: { name: "固定范围" } });
  expect(state.push).toHaveBeenCalledWith("/inventory-tasks/9007199254740993");
  expect(wrapper.find('a[data-destination="/inventory-tasks"]').text()).toBe("返回任务列表"); wrapper.unmount();
 });
 it("refills the saved range and posts the revision with the loaded version", async () => {
  const writes: unknown[] = [];
  state.request.mockImplementation(async (path: string, mutation?: { path: string; body?: unknown }) => {
   if (path.includes("/catalog/")) return { items: [{ id: "7", name: "办公设备" }] };
   if (mutation) { writes.push(JSON.parse(JSON.stringify(mutation))); return { id: "9007199254740993", name: "固定范围", version: 4,
    starts_at: "2026-12-01T00:00:00Z", due_at: "2026-12-02T00:00:00Z" }; }
   return { task: { id: "9007199254740993", name: "固定范围", version: 3, starts_at: "2026-12-01T00:00:00Z",
    due_at: "2026-12-02T00:00:00Z", range_json: { categoryId: "7" } }, items: [], total: 0 };
  });
  const wrapper = draftWrapper("9007199254740993"); await flushPromises();
  expect(wrapper.findAll("input")[0].element.value).toBe("固定范围");
  expect((wrapper.findAll("select")[2].element as HTMLSelectElement).value).toBe("7");
  await wrapper.find("form").trigger("submit"); await flushPromises();
  expect(writes[0]).toMatchObject({ path: "/inventory-tasks/9007199254740993/revision", version: 3,
   body: { name: "固定范围", range: { categoryId: "7" } } }); wrapper.unmount();
 });
});
