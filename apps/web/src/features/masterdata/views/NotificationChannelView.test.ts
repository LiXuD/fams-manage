import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import NotificationChannelView from "./NotificationChannelView.vue";
import { MasterDataApiError } from "@/features/masterdata/api";
const mock = vi.hoisted(() => ({ request: vi.fn(),
  session: { user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/features/masterdata/api", async original => ({
  ...await original<typeof import("@/features/masterdata/api")>(), masterDataRequest: mock.request,
}));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
const unconfigured = { smsTemplate: "", wechatTemplate: "", version: 0 };
const configured = { smsTemplate: "SMS_REMINDER_01", wechatTemplate: "WX_REMINDER_01", version: 1,
  updatedAt: "2026-11-20T02:20:00Z", actorAccountId: "7001", actorUsername: "sysadmin" };
describe("notification channel configuration", () => {
  beforeEach(() => {
    mock.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] } });
    mock.request.mockReset(); mock.request.mockResolvedValue(unconfigured);
  });
  it("replaces both templates as one version-guarded write and reloads", async () => {
    const writes: unknown[] = [];
    mock.request.mockImplementation(async (_path: string, operation?: unknown) => {
      if (operation) { writes.push(JSON.parse(JSON.stringify(operation))); return configured; }
      return writes.length ? configured : unconfigured;
    });
    const wrapper = mount(NotificationChannelView); await flushPromises();
    expect(wrapper.text()).toContain("未配置");
    await wrapper.get("#sms-template").setValue("SMS_REMINDER_01");
    await wrapper.get("#wechat-template").setValue("WX_REMINDER_01");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ path: "/notification-channels", method: "POST", version: 0,
      body: { smsTemplate: "SMS_REMINDER_01", wechatTemplate: "WX_REMINDER_01" } });
    expect(wrapper.text()).toContain("版本 1 已生效");
    expect(wrapper.text()).toContain("SMS_REMINDER_01");
  });
  it("replays the original request after a lost committed reply", async () => {
    const writes: unknown[] = [];
    mock.request.mockImplementation(async (_path: string, operation?: unknown) => {
      if (operation) {
        writes.push(JSON.parse(JSON.stringify(operation)));
        if (writes.length === 1) throw new TypeError("lost committed reply");
        return configured;
      }
      return unconfigured;
    });
    const wrapper = mount(NotificationChannelView); await flushPromises();
    await wrapper.get("#sms-template").setValue("SMS_REMINDER_01");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "原请求重试保存")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1]);
  });
  it("rejects an invalid template id on the client without writing", async () => {
    const wrapper = mount(NotificationChannelView); await flushPromises();
    await wrapper.get("#sms-template").setValue("模板 有空格");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(mock.request).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("模板 ID 只能包含字母、数字、下划线和连字符");
  });
  it("asks for a refresh when the configuration version moved", async () => {
    mock.request.mockImplementation(async (_path: string, operation?: unknown) => {
      if (operation) throw new MasterDataApiError(409, "NOTIFICATION_CHANNEL_VERSION_CONFLICT");
      return unconfigured;
    });
    const wrapper = mount(NotificationChannelView); await flushPromises();
    await wrapper.get("#sms-template").setValue("SMS_REMINDER_02");
    await wrapper.get("form").trigger("submit"); await flushPromises();
    expect(wrapper.text()).toContain("通道配置版本已变化");
  });
  it("denies non administrators before reading the configuration", async () => {
    mock.session = reactive({ user: { id: "2", roles: ["AUDIT_READ_ONLY"] } });
    const wrapper = mount(NotificationChannelView); await flushPromises();
    expect(mock.request).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("只有系统管理员可以维护通知通道");
  });
});
