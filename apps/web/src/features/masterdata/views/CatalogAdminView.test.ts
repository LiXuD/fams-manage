import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import CatalogAdminView from "./CatalogAdminView.vue";
const mock = vi.hoisted(() => ({ request: vi.fn(),
  session: { user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/features/masterdata/api", async original => ({
  ...await original<typeof import("@/features/masterdata/api")>(), masterDataRequest: mock.request,
}));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
describe("catalog maintenance", () => {
  beforeEach(() => {
    mock.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] } });
    mock.request.mockReset(); mock.request.mockResolvedValue({ items: [], total: 0 });
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });
  it("preserves category fields and the original request when the response is lost", async () => {
    const category = { id: "9007199254740993", version: 4, status: "ACTIVE", code: "IT", name: "设备",
      suggestedUnit: "台", numberingPrefix: "IT", description: "原说明",
      extensionFieldTemplate: [{ key: "warranty", label: "保修年数", type: "NUMBER", required: true }] };
    const writes: unknown[] = [];
    mock.request.mockImplementation(async (_path: string, operation?: unknown) => {
      if (operation) {
        writes.push(JSON.parse(JSON.stringify(operation)));
        if (writes.length === 1) throw new TypeError("lost committed reply");
        return { ...category, version: 5 };
      }
      return { items: [category], total: 1 };
    });
    const wrapper = mount(CatalogAdminView); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "维护")!.trigger("click");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "原请求重试保存")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]).toMatchObject({ method: "PATCH", version: 4, body: {
      suggestedUnit: "台", numberingPrefix: "IT", extensionFieldTemplate: category.extensionFieldTemplate,
    } });
  });
  it("resolves both warehouse and parent location versions for a location create", async () => {
    mock.request.mockImplementation(async (path: string, mutation?: unknown) => {
      if (mutation) return { id: "88", version: 0, status: "ACTIVE", name: "库位" };
      if (path === "/locations/9007199254740993") return { id: "9007199254740993", version: 6, status: "ACTIVE" };
      if (path === "/warehouses/9007199254740994") return { id: "9007199254740994", version: 8, status: "ACTIVE" };
      return { items: [], total: 0 };
    });
    const wrapper = mount(CatalogAdminView); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "位置")!.trigger("click"); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "新建位置")!.trigger("click");
    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("BIN1"); await inputs[1].setValue("库位");
    await inputs[2].setValue("9007199254740993"); await inputs[3].setValue("9007199254740994");
    await wrapper.get("select").setValue("BIN");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(mock.request).toHaveBeenCalledWith("/locations", expect.objectContaining({ method: "POST", body: {
      code: "BIN1", name: "库位", description: "", type: "BIN",
      parentLocation: { id: "9007199254740993", version: 6 },
      warehouse: { id: "9007199254740994", version: 8 },
    } }));
  });
  it("discards a former administrator's late catalog response", async () => {
    let finish!: (result: unknown) => void;
    mock.request.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = mount(CatalogAdminView);
    mock.session.user = { id: "2", roles: ["EMPLOYEE"] }; await flushPromises();
    finish({ items: [{ id: "1", code: "PRIVATE", name: "目录", status: "ACTIVE", version: 0 }], total: 1 });
    await flushPromises();
    expect(wrapper.find("table").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("PRIVATE");
  });
});
