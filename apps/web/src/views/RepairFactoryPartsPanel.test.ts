import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import RepairFactoryPartsPanel from "./RepairFactoryPartsPanel.vue";
import type { Repair } from "@/repair/api";
import type { FactoryDetail, FactoryPartsPage, FactoryRoundDetail } from "@/repair/factory-types";

const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const repair = () => ({ id: "19", assetId: "20", processorAccountId: "1", version: 5, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, status: "WAREHOUSE_PROCESSING" }) as Repair;
const original = { assetId: "20", assetCode: "ORIGINAL", serialNumber: "SERIAL-OLD", name: "原机", version: 7, lifecycle: "UNDER_REPAIR" };
const round = () => ({ id: "9007199254740997", repairId: "19", assetId: "20", roundStatus: "RECEIVED", version: 10, originalIdentity: original,
  evidence: [{ object_id: "parts-1", purpose: "PARTS_CHANGE", content_sha256: "a".repeat(64), uploader_account_id: 1, recorded_at: "2026-10-27T01:00:00Z" }, { object_id: "quote-1", purpose: "QUOTE", content_sha256: "b".repeat(64), uploader_account_id: 1, recorded_at: "2026-10-27T01:00:00Z" }],
  files: ["parts-1", "quote-1"].map(objectId => ({ objectId, filename: objectId + ".png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN", businessVersion: 5, createdAt: "2026-10-27T01:00:00Z" })) }) as FactoryRoundDetail;
const header = (): FactoryDetail => ({ repairId: "19", version: 5, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, status: "WAREHOUSE_PROCESSING", processorAccountId: "1", pendingScope: null, approvedScope: null, activeRound: null, summary: null, photosAvailable: true, actions: { requestScope: false, decideScope: false, withdrawScope: false, createRound: false, dispatch: false, cancelRound: false, receive: false, uploadEvidence: false } });
const parts = (): FactoryPartsPage => ({ page: 1, pageSize: 20, total: 1, canRecord: true, items: [{ id: "901", roundId: "9007199254740997", assetId: "20", partName: "原始部件事实", beforeInfo: "原始旧件", afterInfo: "原始新件", observedAt: "2026-10-27T01:00:00Z", recordedAt: "2026-10-27T01:01:00Z", evidenceIds: ["parts-1"], recordedBy: "1" }] });
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const page = () => mount(RepairFactoryPartsPanel, { props: { repair: repair(), round: round(), blocked: false } });
type Page = ReturnType<typeof page>;
const mounted: Page[] = [];
function create() { const wrapper = page(); mounted.push(wrapper); return wrapper; }
const button = (wrapper: Page, name: string) => wrapper.findAll("button").find(item => item.text() === name)!;
async function click(wrapper: Page, name: string) { await button(wrapper, name).trigger("click"); await flushPromises(); }
async function field(wrapper: Page, name: string, value: string | boolean) { await wrapper.findAll("label").find(item => item.text().includes(name))!.find("input,textarea").setValue(value); }
function wire(write: (input: RequestInit) => Promise<Response> = async () => response({})) {
  const remote = { header: header(), round: round(), parts: parts() }, writes: RequestInit[] = [], reads: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(input); return write(input); }
    reads.push(url); return response(url.includes("/parts?") ? remote.parts : url.includes("/rounds/") ? remote.round : remote.header);
  }));
  return { remote, writes, reads };
}
async function draft(wrapper: Page) {
  for (const [name, value] of [["核实的部件名称", "内存"], ["变更前部件或身份资料", "原内存 8GB"], ["变更后部件或身份资料", "新内存 16GB"], ["部件核实的原机资产编码", "ORIGINAL"], ["部件核实的原机序列号", "SERIAL-OLD"], ["部件核实日期和时刻", "2026-10-27T12:30:11"], ["部件变更核实依据", "现场核实同一原机的内存和所选原件一致。"]]) await field(wrapper, name!, value!);
  await field(wrapper, "选用部件变更原件", true); await field(wrapper, "已亲自核实仍为原实物", true);
}
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" }); });
afterEach(() => { for (const wrapper of mounted.splice(0)) wrapper.unmount(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("loads parts only on explicit read and keeps financial evidence out of the fact", async () => {
  const { reads, writes } = wire(); const wrapper = create(); expect(reads).toHaveLength(0);
  await click(wrapper, "核对部件变更记录"); expect(reads).toHaveLength(3); expect(wrapper.text()).toContain("原始部件事实"); expect(wrapper.text()).not.toContain("quote-1.png");
  await draft(wrapper); await click(wrapper, "核对保存部件变更"); expect(writes).toHaveLength(0);
  expect(wrapper.find('[aria-label="确认本次部件办理"]').text()).toContain("原内存 8GB");
  await click(wrapper, "确认保存本次部件办理");
  expect(JSON.parse(writes[0]!.body as string)).toEqual({ roundVersion: 10, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, partName: "内存", beforeInfo: "原内存 8GB", afterInfo: "新内存 16GB", observedCode: "ORIGINAL", observedSerial: "SERIAL-OLD", observedAt: new Date("2026-10-27T12:30:11").toISOString(), evidenceIds: ["parts-1"], reason: "现场核实同一原机的内存和所选原件一致。", originalPhysicalIdentityChecked: true });
  expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "5", "X-CSRF-Token": "csrf" });
});
it("lost committed reply keeps original key, body and versions across read and parent refresh", async () => {
  const state = wire(async () => { state.remote.header.version = 6; state.remote.header.assetVersion = 8; state.remote.round.version = 11; if (state.writes.length === 1) throw new Error("committed reply lost"); return response({}); });
  const wrapper = create(); await click(wrapper, "核对部件变更记录"); await draft(wrapper); await click(wrapper, "核对保存部件变更"); await click(wrapper, "确认保存本次部件办理");
  await click(wrapper, "核对部件变更记录"); await wrapper.setProps({ repair: { ...repair(), version: 6, assetVersion: 8 }, round: { ...round(), version: 11 } });
  await click(wrapper, "重试原部件办理"); expect(state.writes).toHaveLength(2); expect(state.writes[1]).toEqual(state.writes[0]); expect(wrapper.emitted("changed")).toBeTruthy();
});
it("definitive stale rejection clears retry and requires fresh read before a new decision", async () => {
  const { writes } = wire(async () => response({ code: "REPAIR_VERSION_MISMATCH" }, 409)); const wrapper = create();
  await click(wrapper, "核对部件变更记录"); await draft(wrapper); await click(wrapper, "核对保存部件变更"); await click(wrapper, "确认保存本次部件办理");
  expect(button(wrapper, "重试原部件办理")).toBeUndefined(); expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") !== undefined)).toBe(true); expect(writes).toHaveLength(1);
});
it("admin reads immutable facts without a parts submission form", async () => {
  state.session.user = { id: "9", roles: ["ASSET_ADMIN"] }; const { remote, writes } = wire(); remote.parts.canRecord = false;
  const wrapper = create(); await click(wrapper, "核对部件变更记录"); expect(wrapper.text()).toContain("原始旧件"); expect(button(wrapper, "核对保存部件变更")).toBeUndefined(); expect(writes).toHaveLength(0);
});
it("identity change discards pending private fact and prevents another submission", async () => {
  const { writes } = wire(async () => { throw new Error("unknown result"); }); const wrapper = create();
  await click(wrapper, "核对部件变更记录"); await draft(wrapper); await click(wrapper, "核对保存部件变更"); await click(wrapper, "确认保存本次部件办理");
  state.session.user.id = "9"; await flushPromises(); expect(wrapper.text()).not.toContain("原始旧件"); expect(wrapper.text()).not.toContain("重试原部件办理"); expect(writes).toHaveLength(1); expect(wrapper.emitted("busy-change")?.at(-1)).toEqual([false]);
});
it("reselects the same file path for a distinct purpose-bound original upload", async () => {
  const { writes } = wire(); const wrapper = create(); await click(wrapper, "核对部件变更记录"); const input = wrapper.find('input[type="file"]');
  const chosen = new File(["abc"], "same.png", { type: "image/png" }); Object.defineProperty(input.element, "files", { value: [chosen] });
  for (let attempt = 0; attempt < 2; attempt++) { await input.trigger("change"); expect((input.element as HTMLInputElement).value).toBe(""); await click(wrapper, "核对追加部件变更原件"); await click(wrapper, "确认保存本次部件办理"); }
  expect(writes).toHaveLength(2); expect((writes[0]!.body as FormData).get("purpose")).toBe("PARTS_CHANGE"); expect(writes[0]!.headers).not.toEqual(writes[1]!.headers);
});
it.each(["round-first", "repair-first"])("unlocks after successful upload and staggered parent refresh: %s", async order => {
  const state = wire(async () => {
    state.remote.header.version = 6; state.remote.round.version = 11;
    return response({});
  });
  const wrapper = create(); await click(wrapper, "核对部件变更记录");
  const input = wrapper.find('input[type="file"]');
  Object.defineProperty(input.element, "files", { value: [new File(["abc"], "parts.png", { type: "image/png" })] });
  await input.trigger("change"); await click(wrapper, "核对追加部件变更原件"); await click(wrapper, "确认保存本次部件办理");
  const reads = state.reads.length;
  const repairProps = { repair: { ...repair(), version: 6 } }, roundProps = { round: { ...round(), version: 11 } };
  await wrapper.setProps(order === "round-first" ? roundProps : repairProps);
  expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") !== undefined)).toBe(true);
  await wrapper.setProps(order === "round-first" ? repairProps : roundProps);
  expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") === undefined)).toBe(true);
  expect(state.reads).toHaveLength(reads);
  await draft(wrapper); await click(wrapper, "核对保存部件变更"); await click(wrapper, "确认保存本次部件办理");
  expect(state.writes).toHaveLength(2);
  expect(state.writes[1]!.headers).toMatchObject({ "If-Match-Version": "6" });
  expect(JSON.parse(state.writes[1]!.body as string).roundVersion).toBe(11);
});
it.each([400, 409])("parent versions realigning cannot clear a definitive %s rejection", async status => {
  const state = wire(async () => response({ code: "REPAIR_VERSION_MISMATCH" }, status));
  const wrapper = create(); await click(wrapper, "核对部件变更记录"); await draft(wrapper);
  await click(wrapper, "核对保存部件变更"); await click(wrapper, "确认保存本次部件办理");
  await wrapper.setProps({ round: { ...round(), version: 11 } });
  await wrapper.setProps({ round: round() });
  expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") !== undefined)).toBe(true);
  expect(button(wrapper, "重试原部件办理")).toBeUndefined();
  await click(wrapper, "核对部件变更记录");
  expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") === undefined)).toBe(true);
  expect(state.writes).toHaveLength(1);
});
it("rejects calendar rollover and identical before and after facts before confirmation", async () => {
  const { writes } = wire(); const wrapper = create(); await click(wrapper, "核对部件变更记录"); await draft(wrapper);
  await field(wrapper, "部件核实日期和时刻", "2026-02-31T12:30:11"); await click(wrapper, "核对保存部件变更"); expect(wrapper.find('[aria-label="确认本次部件办理"]').exists()).toBe(false);
  await field(wrapper, "部件核实日期和时刻", "2026-10-27T12:30:11"); await field(wrapper, "变更后部件或身份资料", "原内存 8GB"); await click(wrapper, "核对保存部件变更"); expect(wrapper.find('[aria-label="确认本次部件办理"]').exists()).toBe(false); expect(writes).toHaveLength(0);
});
it("permission loss clears private data and emits invalidation", async () => {
  wire(async () => response({ code: "REPAIR_NOT_PROCESSOR" }, 403)); const wrapper = create(); await click(wrapper, "核对部件变更记录"); await draft(wrapper); await click(wrapper, "核对保存部件变更"); await click(wrapper, "确认保存本次部件办理");
  expect(wrapper.emitted("invalidated")).toHaveLength(1); expect(wrapper.text()).not.toContain("原始旧件"); expect(button(wrapper, "重试原部件办理")).toBeUndefined();
});
