import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "@/auth/sessionStore";
import LoanView from "./LoanView.vue";

const api = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock("@/loan/api", () => ({ loanRequest: api.request, loanMessage: () => "操作结果尚未确认" }));

async function fixture() {
  const loan = { id: "11", assetId: "21", assetCode: "A21", name: "Computer", relationStatus: "OPEN",
    version: 4, currentAssetVersion: 7, borrowedDate: "2098-01-01", originalReturnDate: "2099-01-01",
    expectedReturnDate: "2099-01-01", overdue: false, extensions: [] };
  let lose = false;
  const writes: unknown[] = [];
  api.request.mockImplementation(async (path: string, mutation?: unknown) => {
    if (mutation) {
      writes.push(structuredClone(mutation));
      if (lose) { lose = false; throw new Error("lost response"); }
      return { id: "31", version: 0 };
    }
    if (path === "/loans/11") return structuredClone(loan);
    return { items: path.startsWith("/loans?") ? [loan] : [], total: 1, page: 1, pageSize: 20 };
  });
  const pinia = createPinia(); const session = useSessionStore(pinia);
  session.state = "authenticated";
  session.user = { id: "1", username: "employee", roles: ["EMPLOYEE"], status: "ENABLED", requiresInitialPasswordChange: false };
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/loans", component: LoanView }] });
  await router.push("/loans");
  const wrapper = mount(LoanView, { global: { plugins: [pinia, router], stubs: { AppChrome: { template: "<main><slot /></main>" } } } });
  await flushPromises();
  const button = (text: string) => wrapper.findAll("button").find(b => b.text() === text)!;
  await button("查看 / 延期 / 归还").trigger("click"); await flushPromises();
  return { wrapper, writes, button, loseNext() { lose = true; } };
}

describe("LoanView business writes", () => {
  it("retries the original extension date, reason, version and key after a lost response", async () => {
    const f = await fixture();
    await f.wrapper.find("textarea").setValue("continue using");
    await f.wrapper.find('input[type="date"]').setValue("2099-02-01");
    f.loseNext(); await f.button("提请延期").trigger("click"); await flushPromises();
    expect(f.wrapper.text()).toContain("重试上次操作");
    await f.button("重试上次操作").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(2); expect(f.writes[1]).toEqual(f.writes[0]);
    expect(f.writes[1]).toMatchObject({ path: "/loans/11/extensions", version: 4,
      body: { expectedReturnDate: "2099-02-01", reason: "continue using" } });
    f.wrapper.unmount();
  });
  it("submits a return with current asset identity and version rather than the loan version", async () => {
    const f = await fixture(); await f.wrapper.find("textarea").setValue("return unused device");
    await f.button("申请归还").trigger("click"); await flushPromises();
    expect(f.writes[0]).toMatchObject({ path: "/asset-returns", body: { assetId: "21", assetVersion: 7, reason: "return unused device" } });
    f.wrapper.unmount();
  });
});
