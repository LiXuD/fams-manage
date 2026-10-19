import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import InventoryFindingPanel from "./InventoryFindingPanel.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const raw = { id: "8", client_event_id: "22222222-2222-4222-8222-222222222222", outcome: "FINDING_REVIEW",
  received_at: "2026-10-19T00:00:00Z", facts_json: { actual: { description: "Private finding" }, photoIds: [] } };
const detail = () => ({ taskVersion: 7, observation: raw, subject: { version: 0, subject_status: "OPEN" }, history: [], historyTotal: 0 });
const reply = (value: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(value) }) as Response;
const panel = () => mount(InventoryFindingPanel, { props: { taskId: "9" } });
const action = (wrapper: ReturnType<typeof panel>, name: string) => wrapper.findAll("button").find(row => row.text() === name)!;
async function open(wrapper: ReturnType<typeof panel>) {
  await action(wrapper, "读取盘盈与任务外线索").trigger("click"); await flushPromises();
  await action(wrapper, "核对线索 8").trigger("click"); await flushPromises();
}
describe("finding owner review", () => {
  beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); vi.spyOn(window, "confirm").mockReturnValue(true); });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  it("retains the original request after an unknown reply and retries the same decision exactly", async () => {
    let failed = true; const writes: RequestInit[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
      if (input.method === "POST") { writes.push(input); if (failed) { failed = false; throw new Error("lost reply"); } return reply({ status: "CLOSED" }); }
      return reply(url.endsWith("/findings/8") ? detail() : { items: [raw], total: 1 });
    }));
    const wrapper = panel(); await open(wrapper);
    await wrapper.findAll("textarea")[0]!.setValue("核对为施工单位自有设备");
    await wrapper.findAll("textarea")[1]!.setValue("设备清单及现场负责人证明为外部施工单位资产");
    await action(wrapper, "保存盘盈复核与关联").trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain("操作结果尚未确认");
    await action(wrapper, "重试原盘盈复核操作").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
    expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ subjectVersion: 0, method: "NON_ENTERPRISE" });
    expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "7", "X-CSRF-Token": "csrf" }); wrapper.unmount();
  });
  it("identity replacement during a read clears private findings and suppresses its late response", async () => {
    let finish!: (value: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
    const wrapper = panel(); await action(wrapper, "读取盘盈与任务外线索").trigger("click");
    state.session.user.id = "2"; finish(reply({ items: [raw], total: 1 })); await flushPromises();
    expect(wrapper.text()).not.toContain("Private finding"); wrapper.unmount();
  });
  it("finds a submitted receipt by business number and captures its exact selected item and owner version", async () => {
    const writes: RequestInit[] = [], paths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
      paths.push(url); if (input.method === "POST") { writes.push(input); return reply({ status: "PROCESSING" }); }
      if (url.includes("/inbound-receipts?")) return reply({ items: [{ id: "9007199254740993", receiptNumber: "RK-001", version: 4, status: "PENDING_ADMIN_DECISION", items: [
        { id: "3", asset: { id: "9007199254740995", version: 1 }, assetSnapshot: { assetCode: "A-1", assetName: "Device" }, executionStatus: "PENDING" } ] }] });
      return reply(url.endsWith("/findings/8") ? detail() : { items: [raw], total: 1 });
    }));
    const wrapper = panel(); await open(wrapper); await wrapper.find("select").setValue("LINK_INBOUND");
    await wrapper.find("input[type='text'], input:not([type])").setValue("RK-001");
    await action(wrapper, "查找正式依据").trigger("click"); await flushPromises();
    await wrapper.find("input[type=radio]").setValue(true);
    await wrapper.findAll("textarea")[0]!.setValue("核实为该入库项目所对应实物");
    await wrapper.findAll("textarea")[1]!.setValue("正式入库单与现场照片设备特征一致，等待库管实际验收");
    await action(wrapper, "保存盘盈复核与关联").trigger("click"); await flushPromises();
    expect(paths).toContain("/api/v1/inbound-receipts?page=1&pageSize=20&receiptNumber=RK-001");
    expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ assetId: "9007199254740995", inboundReceiptId: "9007199254740993", ownerVersion: 4 }); wrapper.unmount();
  });
});
