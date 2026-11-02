import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import InventoryLateDamagePanel from "./InventoryLateDamagePanel.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const fixture = () => ({ assetId: "9007199254740993", currentAsset: { version: 8, assetCode: "PRIVATE-DAMAGE" }, damage: { observation_id: "30", version: 3, damage_status: "OPEN", repair_id: null as string | null, repair_status: null as string | null }, originalEvidence: { rawObservedAt: "2099-01-01T00:00:00Z" }, recheckResult: "REVIEWED", todoOpen: true, history: { items: [], page: 1, total: 0 } });
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const panel = () => mount(InventoryLateDamagePanel, { props: { taskId: "9", observationId: "30" }, global: { stubs: { RouterLink: true } } });
const button = (wrapper: ReturnType<typeof panel>, label: string) => wrapper.findAll("button").find(item => item.text() === label)!;
async function prepare(wrapper: ReturnType<typeof panel>, method = "FORMAL_REPAIR") {
  await wrapper.find("select").setValue(method);
  await wrapper.find("input[type=datetime-local]").setValue("2026-01-01T10:00:00.123");
  for (const checkbox of wrapper.findAll("input[type=checkbox]")) await checkbox.setValue(true);
  const fields = wrapper.findAll("textarea");
  if (method === "FORMAL_REPAIR") await fields.shift()!.setValue("Actual independent physical fault");
  await fields[0]!.setValue("Independent physical damage check");
  await fields[1]!.setValue("Original photos and independently verified discovery time");
}
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); vi.spyOn(window, "confirm"); });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("confirms inline with independent damage and asset versions and verified discovery time", async () => {
  const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => { if (input.method === "POST") writes.push(structuredClone(input)); return response(fixture()); }));
  const wrapper = panel(); await flushPromises();
  expect(wrapper.find<HTMLInputElement>("input[type=datetime-local]").element.value).toBe("");
  await prepare(wrapper); await button(wrapper, "核对独立损坏处理").trigger("click");
  expect(writes).toHaveLength(0); expect(window.confirm).not.toHaveBeenCalled();
  await button(wrapper, "确认保存独立损坏处理").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(1); expect(writes[0]!.headers).toMatchObject({ "If-Match-Version": "3", "X-CSRF-Token": "csrf" });
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ method: "FORMAL_REPAIR", assetVersion: 8, repair: { physicalDamageChecked: true, discoveryTimeVerified: true, fault: "Actual independent physical fault" } });
  expect(wrapper.text()).toContain("仍有开放待办"); expect(wrapper.emitted("changed")).toHaveLength(1);
  expect(wrapper.emitted("busyChange")!.at(-1)).toEqual([false]); wrapper.unmount();
});
it("keeps the exact unknown request across a refresh with a newer independent damage version", async () => {
  const writes: RequestInit[] = []; let lost = true;
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(structuredClone(input)); if (lost) { lost = false; throw Error("reply lost"); } }
    const data = fixture(); if (!lost) data.damage.version = 12; return response(data);
  }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper);
  await button(wrapper, "核对独立损坏处理").trigger("click"); await button(wrapper, "确认保存独立损坏处理").trigger("click"); await flushPromises();
  await button(wrapper, "刷新独立损坏与当前资产").trigger("click"); await flushPromises();
  expect(wrapper.text()).toContain("独立损坏第 12 版"); expect(wrapper.find("fieldset").element.matches(":disabled")).toBe(true);
  await button(wrapper, "原键重试独立损坏处理").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); wrapper.unmount();
});
it("requires inspection of a same-asset active repair and captures its actual version", async () => {
  const writes: RequestInit[] = []; let wrongAsset = true;
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") writes.push(structuredClone(input));
    return response(url.includes("/repairs/") ? { id: "88", assetId: wrongAsset ? "other" : fixture().assetId, version: 6, status: "WAREHOUSE_PROCESSING" } : fixture());
  }));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper, "LINK_REPAIR");
  await wrapper.find("input[inputmode=numeric]").setValue("88"); await button(wrapper, "核对同资产维修").trigger("click"); await flushPromises();
  await button(wrapper, "核对独立损坏处理").trigger("click"); expect(wrapper.find('[aria-label="确认独立损坏处理"]').exists()).toBe(false);
  wrongAsset = false; await button(wrapper, "核对同资产维修").trigger("click"); await flushPromises();
  await button(wrapper, "核对独立损坏处理").trigger("click"); await button(wrapper, "确认保存独立损坏处理").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string).repair).toMatchObject({ repairId: "88", repairVersion: 6 }); wrapper.unmount();
});
it("refreshes a linked real repair without fabricating new physical or discovery evidence", async () => {
  const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => {
    if (input.method === "POST") writes.push(structuredClone(input));
    const data = fixture(); data.damage.damage_status = "PROCESSING"; data.damage.repair_id = "88"; data.damage.repair_status = "WAREHOUSE_PROCESSING";
    return response({ ...data, currentAsset: null });
  }));
  const wrapper = panel(); await flushPromises(); expect(wrapper.find("input[type=datetime-local]").exists()).toBe(false);
  await wrapper.findAll("textarea")[0]!.setValue("Actual repair progress verified"); await wrapper.findAll("textarea")[1]!.setValue("Signed formal repair completion and acceptance records");
  await button(wrapper, "核对独立损坏处理").trigger("click"); await button(wrapper, "确认保存独立损坏处理").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string)).toEqual({ method: "REFRESH_REPAIR", assetVersion: 0, reason: "Actual repair progress verified", evidence: "Signed formal repair completion and acceptance records" }); wrapper.unmount();
});
it("clears private evidence and the pending payload when current permission is denied", async () => {
  vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => input.method === "POST" ? response({ code: "FORBIDDEN" }, 403) : response(fixture())));
  const wrapper = panel(); await flushPromises(); await prepare(wrapper);
  await button(wrapper, "核对独立损坏处理").trigger("click"); await button(wrapper, "确认保存独立损坏处理").trigger("click"); await flushPromises();
  expect(wrapper.text()).not.toContain("PRIVATE-DAMAGE"); expect(wrapper.emitted("invalidated")).toHaveLength(1);
  expect(wrapper.text()).not.toContain("原键重试独立损坏处理"); wrapper.unmount();
});
it("ignores an old identity read after the session is replaced", async () => {
  const reads: ((value: Response) => void)[] = [];
  vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => reads.push(resolve))));
  const wrapper = panel(); state.session.user.id = "2"; state.session.csrfToken = "new-csrf";
  reads[0]!(response(fixture())); await flushPromises();
  expect(wrapper.text()).not.toContain("PRIVATE-DAMAGE"); expect(wrapper.find("fieldset").exists()).toBe(false); wrapper.unmount();
});
