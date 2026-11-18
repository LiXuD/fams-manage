import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import NumberingRuleView from "./NumberingRuleView.vue";
const mock = vi.hoisted(() => ({ request: vi.fn(),
  session: { user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/masterdata/api", async original => ({
  ...await original<typeof import("@/masterdata/api")>(), masterDataRequest: mock.request,
}));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
const rule = { id: "9007199254740993", code: "ASSET", target: "ASSET", mode: "ENTERPRISE", fixedPrefix: "FA",
  datePart: "NONE", sequenceLength: 6, status: "ACTIVE", version: 4 };
describe("numbering rule maintenance", () => {
  beforeEach(() => {
    mock.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] } });
    mock.request.mockReset(); mock.request.mockResolvedValue({ items: [rule], total: 1 });
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });
  it("omits the fixed prefix when changing to category mode and replays the original request", async () => {
    const writes: unknown[] = [];
    mock.request.mockImplementation(async (_path: string, operation?: unknown) => {
      if (operation) {
        writes.push(JSON.parse(JSON.stringify(operation)));
        if (writes.length === 1) throw new TypeError("lost committed reply");
        return { ...rule, mode: "CATEGORY_PREFIX", fixedPrefix: undefined, version: 5 };
      }
      return { items: [rule], total: 1 };
    });
    const wrapper = mount(NumberingRuleView); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "维护")!.trigger("click");
    await wrapper.get("#numbering-mode").setValue("CATEGORY_PREFIX");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "原请求重试保存")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]).toMatchObject({ path: "/numbering-rules/9007199254740993", method: "PATCH", version: 4,
      body: { target: "ASSET", mode: "CATEGORY_PREFIX", datePart: "NONE", sequenceLength: 6 } });
    expect((writes[0] as { body: object }).body).not.toHaveProperty("fixedPrefix");
    expect((writes[0] as { body: object }).body).not.toHaveProperty("code");
  });
  it("does not request or reserve a number while showing the format", async () => {
    const wrapper = mount(NumberingRuleView); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "新建规则")!.trigger("click");
    await wrapper.findAll("input")[0].setValue("NEW");
    await wrapper.findAll("input")[1].setValue("TEST");
    expect(wrapper.text()).toContain("TEST");
    expect(mock.request).toHaveBeenCalledTimes(1);
    expect(mock.request).toHaveBeenCalledWith("/numbering-rules?page=1&pageSize=20");
  });
  it("denies business administrators before reading the rule list", async () => {
    mock.session.user = { id: "2", roles: ["ASSET_ADMIN"] };
    const wrapper = mount(NumberingRuleView); await flushPromises();
    expect(mock.request).not.toHaveBeenCalled();
    expect(wrapper.find("table").exists()).toBe(false);
  });
});
