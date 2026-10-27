import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { reactive } from "vue";
import BusinessResultNoticeView from "./BusinessResultNoticeView.vue";

const api = vi.hoisted(() => ({ request: vi.fn(), session: { user: { id: "5", roles: ["EMPLOYEE"] } as { id: string; roles: string[] } | null, csrfToken: "csrf" } }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot name=\"actions\" /><slot /></main>" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => api.session }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(), loanRequest: api.request, loanMessage: () => "结果尚未确认" }));

describe("recipient result notice", () => {
  beforeEach(() => { api.request.mockReset(); api.session = reactive({ user: { id: "5", roles: ["EMPLOYEE"] }, csrfToken: "csrf" }); });
  it("keeps the same objection identity after a lost response and displays persisted review status", async () => {
    const writes: unknown[] = []; let lose = true; let reported = false;
    api.request.mockImplementation(async (_path: string, mutation?: unknown) => {
      if (mutation) {
        writes.push(JSON.parse(JSON.stringify(mutation))); reported = true;
        if (lose) { lose = false; throw new Error("lost committed reply"); }
        return { status: "ADMIN_REVIEW_REQUESTED" };
      }
      return { items: [{ id: 7, businessId: 3, businessType: "REQUISITION", itemId: 9,
        content: { result: { assetCode: "FA-1" } }, createdAt: "2026-10-13", read: false,
        reviewRequested: reported }], total: 1, unread: 1 };
    });
    const wrapper = mount(BusinessResultNoticeView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find("textarea").setValue("请核查签收凭证与实际交付记录");
    await wrapper.find("form").trigger("submit"); await flushPromises();
    expect(writes).toHaveLength(1); expect(wrapper.find("textarea").attributes("disabled")).toBeDefined();
    await wrapper.findAll("button").find(b => b.text() === "刷新通知")!.trigger("click"); await flushPromises();
    await wrapper.findAll("button").find(b => b.text() === "原请求重试核查登记")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
    expect(writes[0]).toMatchObject({ path: "/business-result-notices/7/objection",
      body: { reason: "请核查签收凭证与实际交付记录" } });
    expect(wrapper.text()).toContain("已登记管理员核查，资产责任保持。");
    expect(wrapper.text()).toContain("未读 1 条"); wrapper.unmount();
  });
  it("clears a late recipient inbox after account switch without painting old contact details", async () => {
    let complete!: (value: unknown) => void;
    api.request.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; })).mockResolvedValue({ items: [], total: 0, unread: 0 });
    const wrapper = mount(BusinessResultNoticeView, { global: { stubs: { RouterLink: true } } }); await flushPromises();
    api.session.user = { id: "6", roles: ["EMPLOYEE"] }; await flushPromises();
    complete({ items: [{ id: "7", businessType: "REPAIR", businessId: "8", content: { result: { assetCode: "PRIVATE-REPAIR" } } }], total: 1, unread: 1 }); await flushPromises();
    expect(wrapper.text()).not.toContain("PRIVATE-REPAIR"); expect(wrapper.findAll("article")).toHaveLength(0); wrapper.unmount();
  });
  it("links repair recipients to the repair while transfer recipients retain the minimal result", async () => {
    api.request.mockResolvedValue({ items: ["REPAIR", "TRANSFER"].map((businessType, index) => ({ id: String(index + 1), businessType, businessId: "8", itemId: "9", content: { result: { assetCode: "FA-1" }, metadata: {} }, read: true, reviewRequested: true })), total: 2, unread: 0 });
    const wrapper = mount(BusinessResultNoticeView, { global: { stubs: { RouterLink: { props: ["to"], template: '<a :data-destination="JSON.stringify(to)"><slot /></a>' } } } }); await flushPromises();
    expect(wrapper.find("a").attributes("data-destination")).toContain("/repairs/8");
    expect(wrapper.findAll("a")).toHaveLength(1); expect(wrapper.find("details").text()).toContain("本次调拨签收结果"); wrapper.unmount();
  });

});
