import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, expect, it, vi } from "vitest";
import { RepairApiError } from "@/repair/api";
import RepairReturnSourcePanel from "./RepairReturnSourcePanel.vue";
const state = vi.hoisted(() => ({ request: vi.fn(), session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
vi.mock("@/repair/api", async original => ({ ...await original<typeof import("@/repair/api")>(), repairRequest: state.request }));
const source = { followupId: "9007199254740993", version: 3, returnId: "8", closedLoanId: "7", processorAccountId: "4", inspectedAt: "2026-10-24T00:00:00Z", inspectionReason: "Actual damaged receipt", lockVersion: 2, asset: { id: "9", assetCode: "PRIVATE-DAMAGED", name: "Actual asset", version: 6 } };
const page = () => mount(RepairReturnSourcePanel, { global: { stubs: { RouterLink: true } } });
const button = (wrapper: ReturnType<typeof page>, text: string) => wrapper.findAll("button").find(item => item.text() === text)!;
beforeEach(() => { state.request.mockReset(); state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); });
async function select(wrapper: ReturnType<typeof page>) {
  await button(wrapper, "查询损坏归还后续").trigger("click"); await flushPromises();
  await button(wrapper, "核对损坏来源 9007199254740993").trigger("click"); await flushPromises();
}
it("keeps exact source versions and original request after lost reply and authorized refresh", async () => {
  const writes: unknown[] = []; let lose = true;
  state.request.mockImplementation(async (path: string, mutation?: unknown) => {
    if (mutation) { writes.push(JSON.parse(JSON.stringify(mutation))); if (lose) { lose = false; throw Error("lost committed reply"); } return { id: "10" }; }
    return path.includes("?") ? { items: [{ id: source.followupId, followup_status: "PENDING" }], total: 1 } : structuredClone(source);
  });
  const wrapper = page(); await select(wrapper); expect(wrapper.text()).toContain("原借用 7 不会重新打开");
  await button(wrapper, "核对接续申请").trigger("click"); expect(writes).toHaveLength(0);
  await button(wrapper, "确认提交损坏接续").trigger("click"); await flushPromises();
  await button(wrapper, "查询损坏归还后续").trigger("click"); await flushPromises();
  await button(wrapper, "重试原接续申请").trigger("click"); await flushPromises();
  expect(writes[1]).toEqual(writes[0]); expect(writes[0]).toMatchObject({ version: 3, path: "/return-sources/9007199254740993", body: { assetVersion: 6, lockVersion: 2 } });
  expect(wrapper.emitted("created")).toEqual([[{ id: "10" }]]); wrapper.unmount();
});
it("clears source and pending authority after forbidden refresh", async () => {
  state.request.mockImplementation(async (path: string) => path.includes("?") ? { items: [{ id: source.followupId, followup_status: "PENDING" }], total: 1 } : source);
  const wrapper = page(); await select(wrapper); expect(wrapper.text()).toContain("PRIVATE-DAMAGED");
  state.request.mockRejectedValue(new RepairApiError(403, "AUTH_FORBIDDEN", {}));
  await button(wrapper, "查询损坏归还后续").trigger("click"); await flushPromises();
  expect(wrapper.text()).not.toContain("PRIVATE-DAMAGED"); expect(wrapper.find("fieldset").exists()).toBe(false); wrapper.unmount();
});
it("discards a private source returned after account switch", async () => {
  let complete!: (value: unknown) => void;
  state.request.mockResolvedValueOnce({ items: [{ id: source.followupId, followup_status: "PENDING" }], total: 1 }).mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
  const wrapper = page(); await button(wrapper, "查询损坏归还后续").trigger("click"); await flushPromises();
  await button(wrapper, "核对损坏来源 9007199254740993").trigger("click"); await flushPromises();
  state.session.user = { id: "8", roles: ["ASSET_ADMIN"] }; await flushPromises(); complete(source); await flushPromises();
  expect(wrapper.text()).not.toContain("PRIVATE-DAMAGED"); expect(wrapper.find("fieldset").exists()).toBe(false); wrapper.unmount();
});
