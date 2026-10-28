import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Role } from "@/auth/types";
import { useSessionStore } from "@/auth/sessionStore";
import LoanView from "./LoanView.vue";

const api = vi.hoisted(() => ({ request: vi.fn(), roles: ["ASSET_ADMIN"] as Role[] }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(), loanRequest: api.request }));

const loan = { id: "11", assetId: "21", assetCode: "A21", name: "Computer", relationStatus: "OPEN", version: 4,
  currentAssetVersion: 7, borrowedDate: "2098-01-01", originalReturnDate: "2099-01-01", expectedReturnDate: "2099-01-01",
  overdue: false, extensions: [] };
const extension = { id: "31", loanId: "11", requestId: null, targetVersion: 4, originalDueDate: "2099-01-01",
  requestedDueDate: "2099-02-01", reason: "出差占用", proposedBy: "1", extensionStatus: "PENDING", version: 2,
  decisionReason: null, overdueFrom: null, overdueThrough: null };
const page = (items: unknown[]) => ({ items, total: items.length, page: 1, pageSize: 20 });

function mountView() {
  const pinia = createPinia(); const session = useSessionStore(pinia);
  session.state = "authenticated";
  session.user = { id: "1", username: "employee", roles: api.roles, status: "ENABLED", requiresInitialPasswordChange: false };
  return mount(LoanView, { global: { plugins: [pinia], stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
}
const button = (wrapper: ReturnType<typeof mountView>, text: string) =>
  wrapper.findAll("button").find(item => item.text() === text)!;

describe("借用记录列表", () => {
  afterEach(() => { vi.clearAllMocks(); api.roles = ["ASSET_ADMIN"]; });

  it("只取借用与延期两张表，行操作指向详情页", async () => {
    api.request.mockImplementation((path: string) => Promise.resolve(
      path.startsWith("/loans/extensions") ? page([extension]) : page([loan])));
    const wrapper = mountView(); await flushPromises();
    expect(api.request).toHaveBeenCalledTimes(2);
    expect(api.request).toHaveBeenCalledWith("/loans?page=1&pageSize=20");
    expect(api.request).toHaveBeenCalledWith("/loans/extensions?page=1&pageSize=20");
    expect(wrapper.findAll("a[data-destination]").map(item => [item.text(), item.attributes("data-destination")]))
      .toEqual([["查看 / 延期 / 归还", "/loans/11"]]);
    expect(wrapper.text()).toContain("A21 · Computer");
    expect(wrapper.text()).toContain("借用中");
    wrapper.unmount();
  });

  it("管理员批准延期带原因提交到当前版本，缺原因则零写请求", async () => {
    const writes: unknown[] = [];
    api.request.mockImplementation(async (path: string, mutation?: unknown) => {
      if (mutation) { writes.push(structuredClone(mutation)); return { id: "31", version: 3 }; }
      return path.startsWith("/loans/extensions") ? page([extension]) : page([loan]);
    });
    const wrapper = mountView(); await flushPromises();
    await button(wrapper, "批准延期").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(0);
    expect(wrapper.text()).toContain("请填写决定原因。");
    await wrapper.find("textarea").setValue("同意延期一周");
    await button(wrapper, "批准延期").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ path: "/loans/extensions/31/decision", version: 2, body: { approved: true, reason: "同意延期一周" } });
    expect(wrapper.text()).toContain("操作已保存。");
    wrapper.unmount();
  });

  it("非管理员只读延期历史，不给审批按钮", async () => {
    api.roles = ["EMPLOYEE"];
    api.request.mockImplementation((path: string) => Promise.resolve(
      path.startsWith("/loans/extensions") ? page([extension]) : page([loan])));
    const wrapper = mountView(); await flushPromises();
    expect(wrapper.findAll("button").map(item => item.text())).toEqual(["刷新", "上一页", "下一页"]);
    expect(wrapper.text()).toContain("31 · 2099-01-01 → 2099-02-01 · 待审批 · 出差占用");
    wrapper.unmount();
  });
});
