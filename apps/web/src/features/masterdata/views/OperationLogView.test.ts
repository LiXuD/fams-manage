import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import OperationLogView from "./OperationLogView.vue";
import { MasterDataApiError } from "@/features/masterdata/api";
const mock = vi.hoisted(() => ({ request: vi.fn(),
  session: { user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/features/masterdata/api", async original => ({
  ...await original<typeof import("@/features/masterdata/api")>(), masterDataRequest: mock.request,
}));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
const entry = { source: "SECURITY", eventId: "12", eventType: "MASTER_DATA_CHANGED",
  requestId: "98982a10-f4c2-45ca-a186-cad4625ad2d1", occurredAt: "2026-11-20T02:00:00Z",
  detail: { contactPhone: "138****5678", initialPassword: "***" }, actorAccountId: "7001" };
const page = { page: 1, pageSize: 20, total: 25, items: [entry] };
describe("operation log read-only query", () => {
  beforeEach(() => {
    mock.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] } });
    mock.request.mockReset(); mock.request.mockResolvedValue(page);
  });
  it("lists merged audit entries and shows the masked detail without any write", async () => {
    const wrapper = mount(OperationLogView); await flushPromises();
    expect(mock.request).toHaveBeenCalledTimes(1);
    expect(mock.request).toHaveBeenCalledWith("/operation-logs?page=1&pageSize=20");
    expect(wrapper.text()).toContain("安全审计");
    expect(wrapper.text()).toContain("MASTER_DATA_CHANGED");
    await wrapper.findAll("button").find(button => button.text() === "查看详情")!.trigger("click");
    expect(wrapper.text()).toContain("138****5678");
    expect(wrapper.text()).toContain("98982a10-f4c2-45ca-a186-cad4625ad2d1");
    expect(mock.request).toHaveBeenCalledTimes(1);
  });
  it("applies the source filter from page one and paginates forward", async () => {
    const wrapper = mount(OperationLogView); await flushPromises();
    await wrapper.get("#log-source").setValue("SECURITY");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(mock.request).toHaveBeenLastCalledWith("/operation-logs?page=1&pageSize=20&source=SECURITY");
    await wrapper.findAll("button").find(button => button.text() === "下一页")!.trigger("click");
    await flushPromises();
    expect(mock.request).toHaveBeenLastCalledWith("/operation-logs?page=2&pageSize=20&source=SECURITY");
  });
  it("shows the authorization message when the audit role lost its field grant", async () => {
    mock.session = reactive({ user: { id: "2", roles: ["AUDIT_READ_ONLY"] } });
    mock.request.mockRejectedValue(new MasterDataApiError(403, "AUTH_FORBIDDEN"));
    const wrapper = mount(OperationLogView); await flushPromises();
    expect(mock.request).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("当前账号没有系统日志读取授权");
    expect(wrapper.find("table").exists()).toBe(false);
  });
  it("denies unrelated roles before any request", async () => {
    mock.session = reactive({ user: { id: "3", roles: ["ASSET_ADMIN"] } });
    const wrapper = mount(OperationLogView); await flushPromises();
    expect(mock.request).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("只有系统管理员或获得授权的审计只读账号");
  });
});
