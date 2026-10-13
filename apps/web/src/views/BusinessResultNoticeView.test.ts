import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import BusinessResultNoticeView from "./BusinessResultNoticeView.vue";

const api = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
vi.mock("@/loan/api", () => ({ loanRequest: api.request, loanMessage: () => "结果尚未确认" }));

describe("recipient result notice", () => {
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
    await wrapper.findAll("button").find(b => b.text() === "原请求重试核查登记")!.trigger("click");
    await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
    expect(writes[0]).toMatchObject({ path: "/business-result-notices/7/objection",
      body: { reason: "请核查签收凭证与实际交付记录" } });
    expect(wrapper.text()).toContain("已登记管理员核查，资产责任保持。");
    expect(wrapper.text()).toContain("未读 1 条"); wrapper.unmount();
  });
});
