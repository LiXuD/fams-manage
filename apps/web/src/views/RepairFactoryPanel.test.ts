import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import RepairFactoryPanel from "./RepairFactoryPanel.vue";
import type { Repair } from "@/repair/api";
import type { FactoryDetail, FactoryRoundDetail, FactoryScope } from "@/repair/factory-types";

const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const id = "9007199254740997", location = "9007199254740999";
const repair = (): Repair => ({ id: "19", assetId: "20", creatorAccountId: "2", source: { id: "20", assetCode: "FACTORY-ORIGINAL", name: "原机", version: 0, lifecycle: "UNDER_REPAIR", custodyType: "PERSONAL", locationId: "21", locationName: "原地点" }, fault: "实际故障", impact: "HIGH", occurredAt: "2026-10-01T00:00:00Z", status: "WAREHOUSE_PROCESSING", version: 5, assetVersion: 7, ownershipVersion: 2, processorAccountId: "1", acceptedAt: "2026-10-01T01:00:00Z", completedAt: null, lockVersion: 3 });
const scope = (): FactoryScope => ({ id: "27", repairId: "19", scopeStatus: "APPROVED", allowedMethod: "FACTORY_NO_COST", requestReason: "内部无法处理，需要原机返厂", requestedBy: "1", requestedAt: "2026-10-01T02:00:00Z", decisionReason: "同意必要无费用返厂", decidedBy: "3", decidedAt: "2026-10-01T03:00:00Z", version: 1, requestBasis: {}, decisionBasis: {} });
const round = (received = false): FactoryRoundDetail => ({ id, repairId: "19", assetId: "20", scopeId: "27", roundNumber: 2, roundStatus: received ? "DISPATCHED" : "PENDING_DISPATCH", providerName: "私有厂家", serviceContact: "私有联系人", servicePhone: "13800001111", expectedReturnDate: "2026-11-01", fault: "厂家检查无法启动", createdBy: "1", createdAt: "2026-10-01T04:00:00Z", dispatchedAt: received ? "2026-10-02T04:00:00Z" : null, dispatchedBy: received ? "1" : null, dispatchEvidence: received ? "实际寄出依据" : null, receivedAt: null, receivedBy: null, receiveLocationId: null, receiveLocationVersion: null, conditionResult: null, treatmentResult: null, actualCost: null, cancelReason: null, version: 10, originalIdentity: { assetId: "20", assetCode: "FACTORY-ORIGINAL", name: "原机", serialNumber: "SERIAL-OLD", version: 7, lifecycle: "UNDER_REPAIR" }, evidence: [{ object_id: "file-1", purpose: received ? "RECEIPT" : "FACTORY_ACK", content_sha256: "a".repeat(64), uploader_account_id: 1, recorded_at: "2026-10-02T00:00:00Z" }], files: [{ objectId: "file-1", filename: "厂家原件.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN", businessVersion: 5, createdAt: "2026-10-02T00:00:00Z" }] });
const detail = (actions: Partial<FactoryDetail["actions"]> = {}, activeRound: FactoryRoundDetail | null = null): FactoryDetail => ({ repairId: "19", version: 5, assetVersion: 7, ownershipVersion: 2, status: "WAREHOUSE_PROCESSING", processorAccountId: "1", lockVersion: 3, pendingScope: null, approvedScope: activeRound ? scope() : null, activeRound, summary: null, photosAvailable: true, actions: { requestScope: false, decideScope: false, withdrawScope: false, createRound: false, dispatch: false, cancelRound: false, receive: false, uploadEvidence: false, ...actions } });
const response = (body: unknown, status = 200): Response => ({ ok: status === 200, status, json: async () => structuredClone(body), blob: async () => new Blob([new Uint8Array([1, 2, 3])], { type: "image/png" }) }) as Response;
const page = () => mount(RepairFactoryPanel, { props: { repair: repair(), blocked: false } });
type Page = ReturnType<typeof page>;
const button = (wrapper: Page, name: string) => wrapper.findAll("button").find(item => item.text() === name)!;
async function field(wrapper: Page, label: string, value: string | boolean) { await wrapper.findAll("label").find(item => item.text().includes(label))!.find("input,textarea,select").setValue(value); }
function wire(getDetail: () => FactoryDetail, getRound: () => FactoryRoundDetail | null, write: (input: RequestInit) => Promise<Response>) {
  const requests: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    requests.push(url);
    if (input.method === "POST") return write(input);
    if (url.includes("/physical-catalog/location")) return response({ items: [{ id: location, name: "实际收回房间", version: 11 }], total: 1 });
    if (url.endsWith("/grant")) return response({ token: "private-grant", expiresAtEpochSecond: 1999999999 });
    if (url.includes("/content?")) return response(null);
    if (url.includes("/history?")) return response({ items: [], total: 21 });
    if (url.includes("/rounds?")) return response({ items: getRound() ? [getRound()] : [], total: 21 });
    if (url.includes("/rounds/")) return response(getRound());
    return response(getDetail());
  }));
  return requests;
}
async function open(wrapper: Page) { await button(wrapper, "核对返厂范围与轮次").trigger("click"); await flushPromises(); }
async function physical(wrapper: Page, receive = false) {
  await field(wrapper, "现场核对的资产编码", "FACTORY-ORIGINAL"); await field(wrapper, "现场核对的原机序列号", "SERIAL-OLD");
  await field(wrapper, "实际办理日期", "2026-10-22"); await field(wrapper, "实际办理时刻", "15:16:17");
  await field(wrapper, "已核实是本轮原实物", true);
  await field(wrapper, "选用本轮", true);
  if (receive) { await button(wrapper, "查询有效收回地点").trigger("click"); await flushPromises(); await field(wrapper, "实际收回房间", true); await field(wrapper, "已核实本轮实际没有费用", true); }
  await field(wrapper, receive ? "实际收回状况与处理结果" : "实物寄出与厂家接收依据", "已现场核对本轮原实物编码、序列号以及真实凭证和实际办理情况。");
}
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("completed repair retains rounds without asking users to finish the repair again", async () => {
  const closed = { ...detail(), status: "COMPLETED", summary: { scope: { id: "27", scopeStatus: "APPROVED" as const, version: 1 }, round: null, actualDispatchCount: 2, followupOpen: false } };
  wire(() => closed, () => null, async () => response({}));
  const wrapper = page(); await open(wrapper);
  expect(wrapper.text()).toContain("维修已实际结束，轮次原记录保留");
  expect(wrapper.text()).not.toContain("维修尚需核实实际结束");
  expect(wrapper.find('[aria-label="确认本次返厂办理"]').exists()).toBe(false);
  wrapper.unmount();
});

it("scope changes require inline confirmation and freeze all independent versions", async () => {
  const writes: RequestInit[] = []; wire(() => detail({ requestScope: true }), () => null, async input => { writes.push(input); return response({}); });
  const wrapper = page(); await open(wrapper); await field(wrapper, "本次范围或取消原因", "实际接修后仍无法内部修复，申请必要返厂。"); await button(wrapper, "申请必要的无费用返厂").trigger("click");
  expect(writes).toHaveLength(0); expect(wrapper.emitted("busy-change")?.at(-1)).toEqual([true]);
  await button(wrapper, "确认保存本次返厂办理").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ assetVersion: 7, ownershipVersion: 2, lockVersion: 3 });
  expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "5", "X-CSRF-Token": "csrf" }); expect(wrapper.emitted("changed")).toHaveLength(1); wrapper.unmount();
});
it("lost committed reply retains identical key and body through explicit result refresh and parent refresh", async () => {
  let remote = detail({ requestScope: true }); const writes: RequestInit[] = [];
  wire(() => remote, () => null, async input => { writes.push(input); remote = { ...remote, version: 6, assetVersion: 8, pendingScope: { ...scope(), scopeStatus: "PENDING" } }; if (writes.length === 1) throw new Error("committed response lost"); return response({}); });
  const wrapper = page(); await open(wrapper); await field(wrapper, "本次范围或取消原因", "需要实际原机返厂检查。"); await button(wrapper, "申请必要的无费用返厂").trigger("click"); await button(wrapper, "确认保存本次返厂办理").trigger("click"); await flushPromises();
  await open(wrapper); await wrapper.setProps({ repair: { ...repair(), version: 6, assetVersion: 8 } });
  await button(wrapper, "重试原返厂办理").trigger("click"); await flushPromises(); expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
});
it("actual original receipt selects the real versioned catalog and this round's receipt evidence", async () => {
  const current = round(true), writes: RequestInit[] = []; const requests = wire(() => detail({ receive: true }, current), () => current, async input => { writes.push(input); return response({}); });
  const wrapper = page(); await open(wrapper); await physical(wrapper, true); await button(wrapper, "核对原实物已实际收回").trigger("click"); expect(writes).toHaveLength(0);
  await button(wrapper, "确认保存本次返厂办理").trigger("click"); await flushPromises();
  expect(requests).toContain("/api/v1/repairs/physical-catalog/location?page=1&pageSize=20");
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ roundVersion: 10, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, originalPhysicalIdentityChecked: true, observedSerial: "SERIAL-OLD", actualLocationId: location, locationVersion: 11, evidenceIds: ["file-1"], actualCost: "0.00", conditionResult: "RESTORED", occurredAt: new Date("2026-10-22T15:16:17").toISOString() }); wrapper.unmount();
});
it("wrong-purpose evidence cannot prepare physical dispatch", async () => {
  const current = round(); current.evidence[0]!.purpose = "RECEIPT"; const writes: RequestInit[] = []; wire(() => detail({ dispatch: true }, current), () => current, async input => { writes.push(input); return response({}); });
  const wrapper = page(); await open(wrapper); await field(wrapper, "现场核对的资产编码", "FACTORY-ORIGINAL"); await field(wrapper, "现场核对的原机序列号", "SERIAL-OLD"); await field(wrapper, "实际办理日期", "2026-10-22"); await field(wrapper, "实际办理时刻", "15:16:17"); await field(wrapper, "已核实是本轮原实物", true); await field(wrapper, "实物寄出与厂家接收依据", "已现场核对本轮原实物编码和实际寄出。"); await button(wrapper, "核对实际寄出原实物").trigger("click");
  expect(wrapper.find('[aria-label="确认本次返厂办理"]').exists()).toBe(false); expect(writes).toHaveLength(0); wrapper.unmount();
});
it("round and private history pagination remain independent", async () => {
  const current = round(); const requests = wire(() => detail({}, current), () => current, async () => response({})); const wrapper = page(); await open(wrapper);
  await button(wrapper, "下一页返厂轮次").trigger("click"); await flushPromises(); expect(requests).toContain("/api/v1/repairs/19/factory/rounds?page=2&pageSize=20");
  await button(wrapper, "下一页返厂历史").trigger("click"); await flushPromises(); expect(requests.at(-2)).toContain("/history?page=2&pageSize=20"); expect(requests).toContain("/api/v1/repairs/19/factory/rounds?page=2&pageSize=20"); wrapper.unmount();
});
it("identity change revokes private original preview and discards unsent private details", async () => {
  const current = round(); wire(() => detail({}, current), () => current, async () => response({})); const revoke = vi.fn(); vi.stubGlobal("URL", { createObjectURL: vi.fn(() => "blob:factory-private"), revokeObjectURL: revoke });
  const wrapper = page(); await open(wrapper); expect(wrapper.text()).toContain("13800001111"); await button(wrapper, "读取厂家凭证 厂家原件.png").trigger("click"); await flushPromises(); expect(wrapper.find("img").attributes("src")).toBe("blob:factory-private");
  state.session.user.id = "9"; await flushPromises(); expect(wrapper.text()).not.toContain("13800001111"); expect(wrapper.find("img").exists()).toBe(false); expect(revoke).toHaveBeenCalledWith("blob:factory-private"); wrapper.unmount();
});
it("permission loss invalidates the parent and removes retry credentials", async () => {
  wire(() => detail({ requestScope: true }), () => null, async () => response({ code: "REPAIR_NOT_PROCESSOR" }, 403)); const wrapper = page(); await open(wrapper); await field(wrapper, "本次范围或取消原因", "申请无费用原机返厂。"); await button(wrapper, "申请必要的无费用返厂").trigger("click"); await button(wrapper, "确认保存本次返厂办理").trigger("click"); await flushPromises(); expect(wrapper.emitted("invalidated")).toHaveLength(1); expect(wrapper.text()).not.toContain("重试原返厂办理"); wrapper.unmount();
});
it("multipart retry freezes original file purpose and four version headers or fields", async () => {
  const current = round(), remote = detail({ uploadEvidence: true }, current), writes: RequestInit[] = []; wire(() => remote, () => current, async input => { writes.push(input); remote.version = 6; current.version = 11; if (writes.length === 1) throw new Error("upload reply lost"); return response({}); });
  const wrapper = page(); await open(wrapper); const input = wrapper.find('input[type="file"]'); Object.defineProperty(input.element, "files", { value: [new File(["abc"], "厂家原件.png", { type: "image/png" })] }); await input.trigger("change"); await button(wrapper, "核对追加本轮凭证").trigger("click"); await button(wrapper, "确认保存本次返厂办理").trigger("click"); await flushPromises(); await open(wrapper);
  await wrapper.setProps({ repair: { ...repair(), version: 6, assetVersion: 9 } }); await button(wrapper, "重试原返厂办理").trigger("click"); await flushPromises(); expect(writes).toHaveLength(2); expect(writes[1]!.headers).toEqual(writes[0]!.headers); expect([...((writes[1]!.body) as FormData).entries()]).toEqual([...((writes[0]!.body) as FormData).entries()]); expect((writes[0]!.body as FormData).get("roundVersion")).toBe("10"); wrapper.unmount();
});

it("a returned round final cost remains exact after committed reply loss and newer round refresh", async () => {
 const current=round(true);current.roundStatus="RECEIVED";current.evidence[0]!.purpose="COST_RECEIPT";current.actions={recordCost:true,uploadFinancialEvidence:true};
 const remote=detail();const writes:RequestInit[]=[];wire(()=>remote,()=>current,async input=>{writes.push(input);if(writes.length===1){remote.version=91;current.version=92;current.actualCost="10.01";current.actions!.recordCost=false;throw new Error("committed cost reply lost");}return response({});});
 const wrapper=page();await open(wrapper);await field(wrapper,"本次金额", "10.01");await field(wrapper,"本次费用核对依据","亲自核对本轮实际费用和原始凭证");await field(wrapper,"费用原件：",true);await field(wrapper,"已亲自核对本次金额",true);
 await button(wrapper,"核对确认本轮最终费用").trigger("click");expect(writes).toHaveLength(0);expect(wrapper.find('[aria-label="确认本次返厂办理"]').text()).toContain("10.01");await button(wrapper,"确认保存本次返厂办理").trigger("click");await flushPromises();await open(wrapper);await button(wrapper,"重试原返厂办理").trigger("click");await flushPromises();
 expect(writes).toHaveLength(2);expect(writes[1]).toEqual(writes[0]);expect(JSON.parse(writes[0]!.body as string)).toEqual({assetVersion:7,ownershipVersion:2,lockVersion:3,roundVersion:10,amount:"10.01",evidenceIds:["file-1"],reason:"亲自核对本轮实际费用和原始凭证",finalAmountChecked:true});wrapper.unmount();
});
it("money cannot use wrong-purpose originals or omit explicit amount confirmation", async () => {
 const current=round();current.actions={recordCost:true};current.evidence[0]!.purpose="QUOTE";const writes:RequestInit[]=[];wire(()=>detail({},current),()=>current,async input=>{writes.push(input);return response({});});const wrapper=page();await open(wrapper);
 await field(wrapper,"本次金额","9.99");await field(wrapper,"本次费用核对依据","核对本轮实际费用凭证");await field(wrapper,"报价原件：",true);await button(wrapper,"核对确认本轮最终费用").trigger("click");expect(wrapper.find('[aria-label="确认本次返厂办理"]').exists()).toBe(false);await field(wrapper,"已亲自核对本次金额",true);await button(wrapper,"核对确认本轮最终费用").trigger("click");expect(wrapper.find('[aria-label="确认本次返厂办理"]').exists()).toBe(false);expect(writes).toHaveLength(0);wrapper.unmount();
});
it("returned round financial upload preserves the selected purpose and original round version", async () => {
 const current=round(true);current.roundStatus="RECEIVED";current.actions={uploadFinancialEvidence:true};const writes:RequestInit[]=[];wire(()=>detail(),()=>current,async input=>{writes.push(input);return response({});});const wrapper=page();await open(wrapper);await field(wrapper,"凭证用途","COST_RECEIPT");const input=wrapper.find('input[type="file"]');Object.defineProperty(input.element,"files",{value:[new File(["abc"],"actual-cost.png",{type:"image/png"})]});await input.trigger("change");await button(wrapper,"核对追加本轮凭证").trigger("click");await button(wrapper,"确认保存本次返厂办理").trigger("click");await flushPromises();expect((writes[0]!.body as FormData).get("purpose")).toBe("COST_RECEIPT");expect((writes[0]!.body as FormData).get("roundVersion")).toBe("10");wrapper.unmount();
});
it("limited scope request retains an exact cumulative amount", async () => {
 const writes:RequestInit[]=[];wire(()=>detail({requestCostScope:true}),()=>null,async input=>{writes.push(input);return response({});});const wrapper=page();await open(wrapper);await field(wrapper,"本次范围或取消原因","明确所有轮次累计费用授权");await field(wrapper,"本维修所有返厂轮次费用约束","LIMITED");await field(wrapper,"所有轮次累计限额（元）","105.01");await button(wrapper,"申请返厂费用范围变更").trigger("click");expect(wrapper.text()).toContain("105.01");await button(wrapper,"确认保存本次返厂办理").trigger("click");await flushPromises();expect(JSON.parse(writes[0]!.body as string)).toMatchObject({costPolicy:"LIMITED",costLimit:"105.01"});wrapper.unmount();
});

it("actual original return explicitly retains an unknown amount instead of inventing zero",async()=>{
 const current=round(true),writes:RequestInit[]=[];wire(()=>detail({receive:true},current),()=>current,async input=>{writes.push(input);return response({});});const wrapper=page();await open(wrapper);await physical(wrapper,true);await field(wrapper,"已核实本轮实际没有费用",false);await field(wrapper,"费用尚待核实",true);await button(wrapper,"核对原实物已实际收回").trigger("click");await button(wrapper,"确认保存本次返厂办理").trigger("click");await flushPromises();expect(JSON.parse(writes[0]!.body as string)).toMatchObject({actualCost:null,costUnassessed:true,roundVersion:10});wrapper.unmount();
});
it("identity replacement removes an unsent final amount and selected financial originals",async()=>{
 const current=round();current.actions={recordCost:true};current.evidence[0]!.purpose="COST_RECEIPT";wire(()=>detail({},current),()=>current,async()=>response({}));const wrapper=page();await open(wrapper);await field(wrapper,"本次金额","67.89");await field(wrapper,"本次费用核对依据","私有费用核实记录");await field(wrapper,"费用原件：",true);state.session.csrfToken="renewed";await flushPromises();expect(wrapper.text()).not.toContain("私有费用核实记录");expect(wrapper.find('input[inputmode="decimal"]').exists()).toBe(false);expect(wrapper.text()).not.toContain("确认保存本次返厂办理");wrapper.unmount();
});
