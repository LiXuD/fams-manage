import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import RepairFactoryReplacementPanel from "./RepairFactoryReplacementPanel.vue";
import type { Repair } from "@/repair/api";
import type { FactoryDetail, FactoryRoundDetail } from "@/repair/factory-types";

const session = vi.hoisted(() => ({ value: { user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => session.value }));
const repair = () => ({ id: "19", assetId: "20", processorAccountId: "1", version: 5, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, status: "WAREHOUSE_PROCESSING" }) as Repair;
const round = () => ({ id: "9007199254740997", repairId: "19", assetId: "20", roundStatus: "DISPATCHED", version: 10,
  evidence: [{ object_id: "replacement-1", purpose: "REPLACEMENT" }, { object_id: "quote-1", purpose: "QUOTE" }],
  files: ["replacement-1", "quote-1"].map(objectId => ({ objectId, filename: objectId + ".png", contentType: "image/png", scanStatus: "CLEAN" })) }) as FactoryRoundDetail;
const header = () => ({ repairId: "19", version: 5, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, photosAvailable: true }) as FactoryDetail;
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const page = () => mount(RepairFactoryReplacementPanel, { props: { repair: repair(), round: round(), blocked: false } });
type Page = ReturnType<typeof page>;
const mounted: Page[] = [];
const create = () => { const wrapper = page(); mounted.push(wrapper); return wrapper; };
const button = (wrapper: Page, name: string) => wrapper.findAll("button").find(item => item.text() === name)!;
async function click(wrapper: Page, name: string) { await button(wrapper, name).trigger("click"); await flushPromises(); }
async function field(wrapper: Page, name: string, value: string | boolean) { await wrapper.findAll("label").find(item => item.text().includes(name))!.find("input,textarea,select").setValue(value); }
function wire(write: (input: RequestInit) => Promise<Response> = async () => response({})) {
  const remote = { header: header(), round: round(), records: { items: [], canRecord: true } }, writes: RequestInit[] = [], reads: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit = {}) => {
    if (input.method === "POST") { writes.push(input); return write(input); }
    reads.push(url);
    if (url.includes("active-references")) return response({ page: 1, pageSize: 50, total: 1, items: [{ id: "101", code: "REF", name: "有效资料", version: 3, warehouse: { id: "101" } }] });
    return response(url.endsWith("/replacements") ? remote.records : url.includes("/rounds/") ? remote.round : remote.header);
  }));
  return { remote, writes, reads };
}
async function draft(wrapper: Page) {
  for (const [name, value] of [["新机资产名称", "独立新机"], ["新机独立序列号", "NEW-SERIAL"], ["新机型号", "New Model"], ["新机计量单位", "台"], ["资产分类", "101"], ["归属组织", "101"], ["计划入库仓库", "101"], ["计划入库库位", "101"], ["新机入库业务日期", "2026-10-27"], ["实际换机核实日期和时刻", "2026-10-27T12:30:11"], ["旧机实际去向", "FACTORY_RETAINED"], ["厂家换机及旧机去向依据", "核实厂家换机原件及新实物序列号，旧机留在厂家。"]]) await field(wrapper, name!, value!);
  await field(wrapper, "选用厂家换机原件", true); await field(wrapper, "已核实新实物", true);
}
beforeEach(() => { session.value = reactive({ user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" }); });
afterEach(() => { for (const wrapper of mounted.splice(0)) wrapper.unmount(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("freezes a complete independently sourced new-card draft with four guards and dedicated evidence", async () => {
  const { writes, reads } = wire(); const wrapper = create(); expect(reads).toHaveLength(0);
  await click(wrapper, "核对整机换新记录"); expect(reads).toHaveLength(7); expect(wrapper.text()).not.toContain("quote-1.png");
  await draft(wrapper); await click(wrapper, "核对建立新卡及入库草稿"); expect(writes).toHaveLength(0);
  expect(wrapper.find('[aria-label="确认本次换机办理"]').text()).toContain("草稿还须单独提交、批准和实际验收入库");
  await click(wrapper, "确认保存本次换机办理");
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ roundVersion: 10, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, replacementPhysicalIdentityChecked: true, originalDisposition: "FACTORY_RETAINED", evidenceIds: ["replacement-1"], newAsset: { serialNumber: "NEW-SERIAL", category: { id: "101", version: 3 }, organization: { id: "101", version: 3 } }, warehouseId: "101", locationId: "101" });
  expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "5", "X-CSRF-Token": "csrf" });
});
it("preserves the original unknown-result decision across readonly refresh and parent versions", async () => {
  const state = wire(async () => { state.remote.header.version = 6; state.remote.round.version = 11; if (state.writes.length === 1) throw new Error("committed reply lost"); return response({}); });
  const wrapper = create(); await click(wrapper, "核对整机换新记录"); await draft(wrapper); await click(wrapper, "核对建立新卡及入库草稿"); await click(wrapper, "确认保存本次换机办理");
  await click(wrapper, "核对整机换新记录"); await wrapper.setProps({ repair: { ...repair(), version: 6 }, round: { ...round(), version: 11 } });
  await click(wrapper, "重试原换机办理"); expect(state.writes).toHaveLength(2); expect(state.writes[1]).toEqual(state.writes[0]);
});
it.each(["round-first", "repair-first"])("unlocks after dedicated upload and staggered parent refresh: %s", async order => {
  const state = wire(async () => { state.remote.header.version = 6; state.remote.round.version = 11; return response({}); });
  const wrapper = create(); await click(wrapper, "核对整机换新记录"); const input = wrapper.find('input[type="file"]');
  Object.defineProperty(input.element, "files", { value: [new File(["abc"], "replacement.png", { type: "image/png" })] });
  await input.trigger("change"); await click(wrapper, "核对追加厂家换机原件"); await click(wrapper, "确认保存本次换机办理");
  const first = order === "round-first" ? { round: { ...round(), version: 11 } } : { repair: { ...repair(), version: 6 } };
  await wrapper.setProps(first); expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") !== undefined)).toBe(true);
  await wrapper.setProps({ repair: { ...repair(), version: 6 }, round: { ...round(), version: 11 } }); expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") === undefined)).toBe(true);
  expect((state.writes[0]!.body as FormData).get("purpose")).toBe("REPLACEMENT");
  await draft(wrapper); await click(wrapper, "核对建立新卡及入库草稿"); await click(wrapper, "确认保存本次换机办理"); expect(state.writes).toHaveLength(2);
});
it.each([400, 409])("requires explicit fresh read after a definitive %s rejection", async status => {
  const { writes } = wire(async () => response({ code: "REPAIR_VERSION_MISMATCH" }, status)); const wrapper = create();
  await click(wrapper, "核对整机换新记录"); await draft(wrapper); await click(wrapper, "核对建立新卡及入库草稿"); await click(wrapper, "确认保存本次换机办理");
  await wrapper.setProps({ round: { ...round(), version: 11 } }); await wrapper.setProps({ round: round() });
  expect(button(wrapper, "重试原换机办理")).toBeUndefined(); expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") !== undefined)).toBe(true); expect(writes).toHaveLength(1);
  await click(wrapper, "核对整机换新记录"); expect(wrapper.findAll("fieldset").every(item => item.attributes("disabled") === undefined)).toBe(true);
});
it("does not prepare a card without physical confirmation or valid evidence", async () => {
  const { writes } = wire(); const wrapper = create(); await click(wrapper, "核对整机换新记录"); await draft(wrapper); await field(wrapper, "已核实新实物", false); await click(wrapper, "核对建立新卡及入库草稿"); expect(wrapper.find('[aria-label="确认本次换机办理"]').exists()).toBe(false); expect(writes).toHaveLength(0);
});
it("identity loss discards unknown-result private data and prevents another submission", async () => {
  const { writes } = wire(async () => { throw new Error("unknown"); }); const wrapper = create(); await click(wrapper, "核对整机换新记录"); await draft(wrapper); await click(wrapper, "核对建立新卡及入库草稿"); await click(wrapper, "确认保存本次换机办理"); session.value.user.id = "9"; await flushPromises(); expect(button(wrapper, "重试原换机办理")).toBeUndefined(); expect(wrapper.text()).toBe(""); expect(writes).toHaveLength(1);
});
