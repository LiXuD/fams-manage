import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { reactive } from "vue";
import MessageCenterView from "./MessageCenterView.vue";

const api = vi.hoisted(() => ({ request: vi.fn(), duty: vi.fn(),
  session: { user: { id: "1", roles: ["ASSET_ADMIN"] } as { id: string; roles: string[] } | null,
    csrfToken: "csrf" as string | null } }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => api.session }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(),
  loanRequest: api.request, loanMessage: () => "结果尚未确认" }));
vi.mock("@/reminder/api", async importOriginal => ({ ...await importOriginal<typeof import("@/reminder/api")>(),
  dutyRequest: api.duty, reminderMessage: () => "登记结果尚未确认" }));

const dutyRow = { roleCode: "ASSET_ADMIN", version: "1",
  primary: { id: "7", name: "管理员甲", active: true },
  backup: { id: "8", name: "管理员乙", active: false }, effectiveAt: "2026-11-10T01:00:00Z" };

const reminder = { kind: "REMINDER", id: "41", businessType: "INBOUND", businessId: "7",
  stage: "UNRESOLVED_AFTER_1_WORKDAY", objectStage: "ADMIN_DECISION", poolType: "ASSET_ADMIN",
  assigneeAccountId: "", openedAt: "2026-11-09T01:00:00Z", deadlineAt: "2026-11-10T04:00:00Z", read: false };
const todo = { kind: "TODO", id: "42", businessType: "SCRAP", businessId: "8", stage: "DISPOSITION",
  objectStage: "", poolType: "WAREHOUSE_OPERATOR", assigneeAccountId: "", openedAt: "2026-11-08T01:00:00Z",
  deadlineAt: "", read: false };

describe("message centre", () => {
  beforeEach(() => {
    api.request.mockReset(); api.duty.mockReset();
    api.duty.mockResolvedValue({ items: [] });
    api.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" });
  });
  it("labels both sources, keeps one authoritative unread count and retries the same read identity", async () => {
    const writes: unknown[] = []; let lose = true;
    api.request.mockImplementation(async (path: string, mutation?: unknown) => {
      if (mutation) {
        writes.push(JSON.parse(JSON.stringify(mutation)));
        if (lose) { lose = false; throw new Error("lost committed reply"); }
        return { kind: "TODO", id: "42", read: true };
      }
      return { items: [todo, { ...reminder, read: true }], total: 2, unread: lose ? 2 : 1,
        view: String(path).includes("EXCEPTION") ? "EXCEPTION" : "INBOX" };
    });
    const wrapper = mount(MessageCenterView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    expect(wrapper.text()).toContain("提醒 · 管理员确认 1个工作日未处理");
    expect(wrapper.text()).toContain("待办 · 待实际处置");
    expect(wrapper.text()).toContain("未读 2 条");
    expect(wrapper.findAll("article")).toHaveLength(2);
    const buttons = wrapper.findAll("button");
    await buttons.find(button => button.text() === "异常清单")!.trigger("click");
    await flushPromises();
    expect(api.request).toHaveBeenLastCalledWith("/messages?view=EXCEPTION&page=1&pageSize=20");
    await wrapper.findAll("button").find(button => button.text() === "标记已读")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(1);
    await wrapper.findAll("button").find(button => button.text() === "原请求重试已读登记")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2);
    expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]).toMatchObject({ path: "/messages/TODO/42/read" });
    wrapper.unmount();
  });
  it("hides the exception tab from employees and clears a late inbox after the account switches", async () => {
    api.session.user = { id: "9", roles: ["EMPLOYEE"] };
    let complete!: (value: unknown) => void;
    api.request.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }))
      .mockResolvedValue({ items: [], total: 0, unread: 0, view: "INBOX" });
    const wrapper = mount(MessageCenterView, { global: { stubs: { RouterLink: true } } });
    expect(wrapper.text()).not.toContain("异常清单");
    api.session.user = { id: "10", roles: ["EMPLOYEE"] };
    await flushPromises();
    complete({ items: [{ ...todo, businessId: "PRIVATE-8" }], total: 1, unread: 1, view: "INBOX" });
    await flushPromises();
    expect(wrapper.text()).not.toContain("PRIVATE-8");
    expect(wrapper.findAll("article")).toHaveLength(0);
    wrapper.unmount();
  });
  it("shows the duty chain to an administrator without an editing form", async () => {
    api.duty.mockResolvedValue({ items: [dutyRow] });
    const wrapper = mount(MessageCenterView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    expect(api.duty).toHaveBeenCalledWith("/duty-assignments");
    expect(wrapper.text()).toContain("值班 管理员甲");
    expect(wrapper.text()).toContain("管理员乙（已失效）");
    expect(wrapper.find("form").exists()).toBe(false);
    wrapper.unmount();
  });
  it("rotates the duty contact once and keeps the same request identity after a lost response", async () => {
    api.session.user = { id: "1", roles: ["SYSTEM_ADMIN"] };
    api.duty.mockResolvedValue({ items: [dutyRow] });
    const writes: unknown[] = []; let lose = true;
    api.request.mockResolvedValue({ items: [], total: 0, unread: 0, view: "INBOX" });
    api.duty.mockImplementation(async (_path: string, mutation?: unknown) => {
      if (!mutation) return { items: [dutyRow] };
      writes.push(JSON.parse(JSON.stringify(mutation)));
      if (lose) { lose = false; throw new Error("lost committed reply"); }
      return { ...dutyRow, version: "2" };
    });
    const wrapper = mount(MessageCenterView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find("select").setValue("ASSET_ADMIN");
    await wrapper.findAll("input")[0]!.setValue("9");
    await wrapper.findAll("input")[1]!.setValue("");
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(writes).toHaveLength(1);
    expect(wrapper.text()).toContain("原请求重试值班登记");
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(writes).toHaveLength(2);
    expect(writes[1]).toEqual(writes[0]);
    expect(writes[0]).toMatchObject({ version: 1,
      body: { primaryAccountId: "9", backupAccountId: null } });
    expect(wrapper.text()).toContain("版本 2");
    wrapper.unmount();
  });
  it("links scrap rows to the scrap page by business identity", async () => {
    api.request.mockResolvedValue({ items: [todo], total: 1, unread: 1, view: "INBOX" });
    const wrapper = mount(MessageCenterView, {
      global: { stubs: { RouterLink: { props: ["to"], template: '<a :data-destination="JSON.stringify(to)"><slot /></a>' } } } });
    await flushPromises();
    expect(wrapper.find("a").attributes("data-destination")).toContain("/scrap-requests");
    expect(wrapper.find("a").attributes("data-destination")).toContain("8");
    wrapper.unmount();
  });
});
