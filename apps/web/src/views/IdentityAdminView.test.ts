import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import IdentityAdminView from "./IdentityAdminView.vue";
const mock = vi.hoisted(() => ({ request: vi.fn(),
  session: { user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/masterdata/api", async original => ({
  ...await original<typeof import("@/masterdata/api")>(), masterDataRequest: mock.request,
}));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
const organization = { id: "9007199254740993", version: 4, status: "ACTIVE", code: "ORG", name: "原组织" };
describe("identity administration", () => {
  beforeEach(() => {
    mock.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] } });
    mock.request.mockReset();
    mock.request.mockResolvedValue({ items: [organization], total: 1 });
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });
  it("denies business-only administrators before loading identity data", async () => {
    mock.session.user = { id: "2", roles: ["ASSET_ADMIN"] };
    const wrapper = mount(IdentityAdminView);
    await flushPromises();
    expect(mock.request).not.toHaveBeenCalled();
    expect(wrapper.find("table").exists()).toBe(false);
  });
  it("replays a lost update with the same payload and version", async () => {
    const writes: unknown[] = [];
    mock.request.mockImplementation(async (_path: string, operation?: unknown) => {
      if (operation) {
        writes.push(JSON.parse(JSON.stringify(operation)));
        if (writes.length === 1) throw new TypeError("lost result");
        return { ...organization, name: "新组织", version: 5 };
      }
      return { items: [organization], total: 1 };
    });
    const wrapper = mount(IdentityAdminView); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "维护")!.trigger("click");
    await wrapper.findAll('input')[1].setValue("新组织");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "原请求重试保存")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2);
    expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]).toMatchObject({ path: "/organizations/9007199254740993", method: "PATCH", version: 4,
      body: { code: "ORG", name: "新组织" } });
  });
  it("uses a current organization reference for a new employee and includes the entered registration phone", async () => {
    mock.request.mockImplementation(async (path: string, operation?: unknown) => {
      if (operation) return { id: "44", version: 0, status: "ACTIVE", displayName: "新员工" };
      if (path === "/organizations/9007199254740993") return organization;
      return { items: [], total: 0 };
    });
    const wrapper = mount(IdentityAdminView); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "人员")!.trigger("click");
    await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "新建员工")!.trigger("click");
    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("N1"); await inputs[1].setValue("新员工");
    await inputs[2].setValue("9007199254740993"); await inputs[3].setValue("13800000000");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(mock.request).toHaveBeenCalledWith("/employees", expect.objectContaining({
      method: "POST", body: { employeeNo: "N1", displayName: "新员工", phone: "13800000000",
        organization: { id: "9007199254740993", version: 4 } },
    }));
  });
});
