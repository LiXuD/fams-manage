import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import LoanDetailView from "./LoanDetailView.vue";

const api = vi.hoisted(() => ({ request: vi.fn(), push: vi.fn() }));
vi.mock("@/loan/api", async importOriginal => ({ ...await importOriginal<typeof import("@/loan/api")>(), loanRequest: api.request }));
vi.mock("vue-router", () => ({ useRouter: () => ({ push: api.push }) }));

const loan = { id: "11", assetId: "21", assetCode: "A21", name: "Computer", relationStatus: "OPEN", version: 4,
  currentAssetVersion: 7, borrowedDate: "2098-01-01", originalReturnDate: "2099-01-01", expectedReturnDate: "2099-01-01",
  overdue: false, extensions: [] };
const borrowRequest = { id: "41", version: 3, expectedReturnDate: "2099-03-01", requestStatus: "WAITING_RECEIPT" };

async function fixture(id = "11", lose = false) {
  const writes: unknown[] = [];
  let lost = lose;
  api.request.mockImplementation(async (path: string, mutation?: unknown) => {
    if (mutation) {
      writes.push(structuredClone(mutation));
      if (lost) { lost = false; throw new Error("lost response"); }
      return { id: "31", version: 0 };
    }
    if (path === "/loans/11") return structuredClone(loan);
    return { items: [borrowRequest], total: 1, page: 1, pageSize: 20 };
  });
  const wrapper = mount(LoanDetailView, { props: { id }, global: { stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, writes, button: (text: string) => wrapper.findAll("button").find(item => item.text() === text)! };
}

describe("借用详情", () => {
  afterEach(() => { vi.clearAllMocks(); });

  it("非法单号不向服务端发出", async () => {
    const f = await fixture("../other");
    expect(api.request).not.toHaveBeenCalled();
    expect(f.wrapper.text()).toContain("没有当前记录或操作的权限。");
    f.wrapper.unmount();
  });

  it("详情只取本条借用与其待交接申请，返回入口指向列表", async () => {
    const f = await fixture();
    expect(api.request).toHaveBeenCalledWith("/loans/11");
    expect(api.request).toHaveBeenCalledWith("/loans/borrow-requests?page=1&pageSize=20");
    expect(f.wrapper.text()).toContain("A21 · Computer");
    expect(f.wrapper.text()).toContain("2098-01-01");
    expect(f.wrapper.findAll("a[data-destination]").map(item => [item.text(), item.attributes("data-destination")]))
      .toEqual([["返回借用列表", "/loans"]]);
    f.wrapper.unmount();
  });

  it("延期响应丢失后重试沿用原日期、原因、版本与幂等键", async () => {
    const f = await fixture("11", true);
    await f.wrapper.find("textarea").setValue("continue using");
    await f.wrapper.find('input[type="date"]').setValue("2099-02-01");
    f.button("提请延期").trigger("click"); await flushPromises();
    expect(f.wrapper.text()).toContain("重试上次操作");
    f.button("重试上次操作").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(2); expect(f.writes[1]).toEqual(f.writes[0]);
    expect(f.writes[1]).toMatchObject({ path: "/loans/11/extensions", version: 4,
      body: { expectedReturnDate: "2099-02-01", reason: "continue using" } });
    f.wrapper.unmount();
  });

  it("申请归还提交当前资产版本而非借用版本并跳转退还单", async () => {
    const f = await fixture();
    await f.wrapper.find("textarea").setValue("return unused device");
    f.button("申请归还").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/asset-returns", body: { assetId: "21", assetVersion: 7, reason: "return unused device" } });
    expect(api.push).toHaveBeenCalledWith("/asset-returns/31?saved=1");
    f.wrapper.unmount();
  });

  it("接收前延期取所选借用申请的版本与归还日", async () => {
    const f = await fixture();
    await f.wrapper.find("textarea").setValue("出差占用");
    await f.wrapper.findAll("select")[0].setValue("41");
    f.button("提请接收前延期").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/loans/borrow-requests/41/extensions", version: 3,
      body: { expectedReturnDate: "2099-03-01", reason: "出差占用" } });
    f.wrapper.unmount();
  });

  it("缺日期或原因时零写请求", async () => {
    const f = await fixture();
    f.button("提请延期").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    expect(f.wrapper.text()).toContain("请填写归还日期及延期原因。");
    f.wrapper.unmount();
  });
});
