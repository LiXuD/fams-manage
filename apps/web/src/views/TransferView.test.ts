import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "@/auth/sessionStore";
import TransferView from "./TransferView.vue";

const api = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock("@/loan/api", () => ({ loanRequest: api.request, loanMessage: () => "操作结果尚未确认" }));

async function fixture() {
  let lose = true;
  const writes: unknown[] = [];
  api.request.mockImplementation(async (path: string, mutation?: { path: string }) => {
    if (path.endsWith("/grant")) return { grant: "x".repeat(43), requestVersion: 7 };
    if (mutation) {
      writes.push(structuredClone(mutation));
      if (lose) { lose = false; throw new Error("lost after commit"); }
      return { id: "2" };
    }
    return { items: path.includes("/receipts/pending") ? [{ id: "2", assetCode: "A2", name: "电脑", destination: "二楼", requestVersion: 6 }] : [], total: 1 };
  });
  const pinia = createPinia(); const session = useSessionStore(pinia);
  session.state = "authenticated";
  session.user = { id: "1", username: "employee", roles: ["EMPLOYEE"], status: "ENABLED", requiresInitialPasswordChange: false };
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/transfers", component: TransferView }] });
  await router.push("/transfers");
  const wrapper = mount(TransferView, { global: { plugins: [pinia, router], stubs: { AppChrome: { template: "<main><slot /></main>" } } } });
  await flushPromises();
  const button = (text: string) => wrapper.findAll("button").find(b => b.text() === text)!;
  await button("核对并接收").trigger("click"); await flushPromises();
  return { wrapper, writes, button };
}

describe("Transfer actual receipt", () => {
  it("requires physical evidence and retries the same grant, current version and key after a lost reply", async () => {
    const f = await fixture();
    await f.button("确认调拨接收").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(0);
    await f.wrapper.find('input[type="checkbox"]').setValue(true);
    await f.wrapper.findAll("input").find(i => i.attributes("type") !== "checkbox")!.setValue("A2");
    await f.button("确认调拨接收").trigger("click"); await flushPromises();
    await f.button("重试上次操作").trigger("click"); await flushPromises();
    expect(f.writes).toHaveLength(2); expect(f.writes[1]).toEqual(f.writes[0]);
    expect(f.writes[1]).toMatchObject({ path: "/transfers/receipts/2/receive", version: 7,
      body: { grant: "x".repeat(43), observedCode: "A2", physicalChecked: true } });
    f.wrapper.unmount();
  });
});
