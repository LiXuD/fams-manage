import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AccountAdminView from "./AccountAdminView.vue";

const state = vi.hoisted(() => ({
  request: vi.fn(),
  session: { user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null },
}));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
vi.mock("@/api/business", async original => ({
  ...await original<typeof import("@/api/business")>(), businessRequest: state.request,
}));
const account = { id: "9007199254740993", username: "operator", employeeId: "44",
  status: "ENABLED", version: 7, requiresInitialPasswordChange: false };

describe("account administration", () => {
  beforeEach(() => {
    state.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] } });
    state.request.mockReset();
    state.request.mockImplementation(async (path: string) => path.startsWith("/role-configurations")
      ? { items: [{ role: "ASSET_ADMIN", permissions: ["BUSINESS_APPROVE"], businessScope: "ENTERPRISE_WIDE" }] }
      : { items: [account], total: 1 });
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  it("denies business-only accounts without requesting administrative data", async () => {
    state.session.user = { id: "2", roles: ["ASSET_ADMIN"] };
    const wrapper = mount(AccountAdminView);
    await flushPromises();
    expect(state.request).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("只有系统管理员");
  });

  it("preserves the original key and account version after an unknown write result", async () => {
    const writes: unknown[] = [];
    state.request.mockImplementation(async (path: string, mutation?: unknown) => {
      if (mutation) {
        writes.push(JSON.parse(JSON.stringify(mutation)));
        if (writes.length === 1) throw new TypeError("lost reply");
        return { ...account, status: "DISABLED", version: 8 };
      }
      return path.startsWith("/role-configurations") ? { items: [] } : { items: [account], total: 1 };
    });
    const wrapper = mount(AccountAdminView);
    await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "管理")!.trigger("click");
    await wrapper.find("textarea").setValue("岗位变更");
    await wrapper.findAll("button").find(button => button.text() === "停用")!.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("原请求重试停用账号");
    await wrapper.findAll("button").find(button => button.text() === "原请求重试停用账号")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2);
    expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]).toMatchObject({ path: "/system-accounts/9007199254740993/status", version: 7,
      body: { action: "DISABLE", reason: "岗位变更" } });
  });

  it("confirms the target and sends the selected role with the account version", async () => {
    const wrapper = mount(AccountAdminView);
    await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "管理")!.trigger("click");
    await wrapper.find("textarea").setValue("恢复业务资管");
    await wrapper.find("select").setValue("ASSET_ADMIN");
    state.request.mockResolvedValueOnce({ ...account, version: 8 });
    await wrapper.findAll("button").find(button => button.text() === "授予角色")!.trigger("click");
    await flushPromises();
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining("operator"));
    expect(state.request).toHaveBeenCalledWith("/system-accounts/9007199254740993/roles",
      expect.objectContaining({ version: 7, body: { role: "ASSET_ADMIN", reason: "恢复业务资管" } }));
  });

  it("discards a former administrator's late response on identity change", async () => {
    let finish!: (reply: unknown) => void;
    state.request.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = mount(AccountAdminView);
    state.session.user = { id: "2", roles: ["EMPLOYEE"] };
    await flushPromises();
    finish({ items: [account], total: 1 });
    await flushPromises();
    expect(wrapper.text()).not.toContain("operator");
    expect(wrapper.find("table").exists()).toBe(false);
  });
});
