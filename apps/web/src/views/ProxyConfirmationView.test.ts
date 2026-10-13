import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ProxyConfirmationView from "./ProxyConfirmationView.vue";

const state = vi.hoisted(() => ({ request: vi.fn(), roles: ["ASSET_ADMIN"] }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
vi.mock("vue-router", () => ({ useRoute: () => ({ query: { id: "41" } }),
  useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => ({ user: { roles: state.roles } }) }));
vi.mock("@/loan/api", () => ({ loanRequest: state.request, loanMessage: () => "结果尚未确认" }));
vi.mock("@/attachment/api", () => ({ downloadAttachment: vi.fn() }));
vi.mock("@/proxy/api", () => ({ uploadProxyEvidence: vi.fn() }));

describe("proxy confirmation approval", () => {
  beforeEach(() => { state.roles = ["ASSET_ADMIN"]; state.request.mockReset(); });
  it("requires evidence checks and retries the original approved snapshot after a lost reply", async () => {
    const writes: unknown[] = []; let lose = true; let status = "SUBMITTED";
    const record = () => ({ id: "41", version: 3, businessId: "8", itemId: "9",
      confirmationStatus: status, creatorAccountId: "2", actualSigner: "张三",
      businessSnapshotJson: { assetCode: "FA-1", targetEmployeeId: "3" } });
    state.request.mockImplementation(async (path: string, mutation?: unknown) => {
      if (mutation) {
        writes.push(JSON.parse(JSON.stringify(mutation))); status = "APPROVED";
        if (lose) { lose = false; throw new Error("lost after commit"); }
        return record();
      }
      if (path.endsWith("/attachments")) return { items: [{ objectId: "proof", filename: "receipt.pdf", scanStatus: "CLEAN" }] };
      return path.includes("?page=") ? { items: [record()], total: 1 } : record();
    });
    const wrapper = mount(ProxyConfirmationView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const button = (text: string) => wrapper.findAll("button").find(b => b.text() === text)!;
    expect(button("批准本次代确认").attributes("disabled")).toBeDefined();
    await wrapper.find("textarea").setValue("已核验签收单、人员、编码与授权");
    for (const check of wrapper.findAll('input[type="checkbox"]')) await check.setValue(true);
    await button("批准本次代确认").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(1);
    expect(wrapper.find("fieldset").attributes("disabled")).toBeDefined();
    await button("重试上次操作").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
    expect(writes[0]).toMatchObject({ path: "/proxy-confirmations/41/decision", version: 3,
      body: { approved: true, physicalEvidenceChecked: true, noDisputeChecked: true } });
    expect(wrapper.text()).toContain("已批准，待库管执行");
    expect(wrapper.text()).not.toContain("执行已批准代确认并通知责任人员");
    wrapper.unmount();
  });
});
