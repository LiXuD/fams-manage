import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import BusinessParameterView from "./BusinessParameterView.vue";

const mock = vi.hoisted(() => ({
  request: vi.fn(), session: { user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null },
}));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/loan/api", async original => ({
  ...await original<typeof import("@/loan/api")>(), loanRequest: mock.request,
}));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
const snapshot = { timezone: "Asia/Shanghai", workWeek: ["MONDAY"], holidays: [], adjustedWorkdays: [],
  adminDecisionDeadlineWorkdays: 2, warehouseClaimDeadlineWorkdays: 1,
  warehouseFirstExecutionDeadlineWorkdays: 2, handoverAcceptanceDeadlineNaturalDays: 3,
  notificationEscalationThresholds: { administratorUnreadHours: 4,
    onDutyWarehouseOperatorUnreadHours: 4, firstExecutionOverdueNaturalDays: 1, receiptDueUnreadHours: 24 } };
const version = { id: "9007199254740993", version: 7, status: "ACTIVE", effectiveAt: "2026-11-18T00:00:00Z",
  actor: { id: "1", username: "system" }, snapshot };

describe("business parameter maintenance", () => {
  beforeEach(() => {
    mock.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] } });
    mock.request.mockReset();
    mock.request.mockImplementation(async (path: string) => path.endsWith("/current")
      ? structuredClone(version) : { items: [structuredClone(version)], total: 1 });
  });

  it("hides system configuration from business administrators and makes no read", async () => {
    mock.session.user = { id: "2", roles: ["ASSET_ADMIN"] };
    const wrapper = mount(BusinessParameterView);
    await flushPromises();
    expect(mock.request).not.toHaveBeenCalled();
    expect(wrapper.find("form").exists()).toBe(false);
  });

  it("blocks invalid and overlapping calendar dates before proposing a version", async () => {
    const wrapper = mount(BusinessParameterView);
    await flushPromises();
    await wrapper.findAll("textarea")[0].setValue("2026-02-30");
    await wrapper.get("form").trigger("submit");
    expect(wrapper.get('[role="alert"]').text()).toContain("有效");
    await wrapper.findAll("textarea")[0].setValue("2026-12-01");
    await wrapper.findAll("textarea")[1].setValue("2026-12-01");
    await wrapper.get("form").trigger("submit");
    expect(wrapper.get('[role="alert"]').text()).toContain("不能重叠");
    expect(wrapper.find('[aria-label="新版本预览"]').exists()).toBe(false);
  });

  it("starts with version zero when the installation has no parameter history", async () => {
    mock.request.mockResolvedValue({ items: [], total: 0 });
    const wrapper = mount(BusinessParameterView);
    await flushPromises();
    expect(wrapper.text()).toContain("设置初始参数");
    expect(mock.request).not.toHaveBeenCalledWith("/business-parameters/current");
    await wrapper.get("form").trigger("submit");
    mock.request.mockResolvedValueOnce({ ...version, version: 1 });
    await wrapper.findAll("button").find(button => button.text() === "确认立即生效")!.trigger("click");
    await flushPromises();
    expect(mock.request).toHaveBeenCalledWith("/business-parameters",
      expect.objectContaining({ version: 0, body: { snapshot: expect.objectContaining({
        timezone: "Asia/Shanghai", workWeek: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
      }) } }));
  });

  it("previews all values and replays the complete original snapshot when the save reply is lost", async () => {
    const writes: unknown[] = [];
    mock.request.mockImplementation(async (path: string, mutation?: unknown) => {
      if (mutation) {
        writes.push(JSON.parse(JSON.stringify(mutation)));
        if (writes.length === 1) throw new TypeError("lost committed reply");
        return { ...version, version: 8 };
      }
      return path.endsWith("/current") ? structuredClone(version) : { items: [structuredClone(version)], total: 1 };
    });
    const wrapper = mount(BusinessParameterView);
    await flushPromises();
    await wrapper.findAll('input[type="number"]')[0].setValue(5);
    await wrapper.get("form").trigger("submit");
    expect(wrapper.get('[aria-label="新版本预览"]').text()).toContain("2 → 5");
    await wrapper.findAll("button").find(button => button.text() === "确认立即生效")!.trigger("click");
    await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "原请求重试保存")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2);
    expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]).toMatchObject({ version: 7, body: { snapshot: { ...snapshot, adminDecisionDeadlineWorkdays: 5 } } });
    expect(wrapper.text()).toContain("版本 8 已生效");
  });
});
