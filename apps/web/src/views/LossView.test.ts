import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import LossView from "./LossView.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
vi.mock("vue-router", () => ({ useRoute: () => ({ params: { id: "9" } }) }));
const record = () => ({ id: "9", assetId: "9007199254740993", source: { id: "9007199254740993", assetCode: "LOSS-001", name: "私有报失资产", version: 6, lifecycle: "BORROWED" }, status: "PENDING_ADMIN_DECISION", version: 3, assetVersion: 6, ownershipVersion: 1, lockVersion: 2, lastSeenAt: "2026-10-20T00:00:00Z", lastLocation: "原保管位置", circumstances: "离开原保管位置后未找到实物", processorAccountId: null, nextReviewDate: null, actions: { decide: true, addPhoto: true }, photos: [], history: [] });
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const mountPage = () => mount(LossView, { global: { stubs: { AppChrome: { template: "<main><slot /></main>" } } } });
const button = (wrapper: ReturnType<typeof mountPage>, text: string) => wrapper.findAll("button").find(item => item.text() === text)!;
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("freezes approval key and version through committed response loss and readonly refresh", async () => {
  const writes: RequestInit[] = [], row = record(); let lost = true;
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(input); if (lost) { lost = false; row.version = 71; throw new Error("committed reply lost"); } return response(row); }
    return response(url.endsWith("/9") ? row : { items: [row], total: 1 });
  }));
  const wrapper = mountPage(); await flushPromises();
  await wrapper.findAll("textarea")[1]!.setValue("批准核实，尚不确认资产遗失");
  await button(wrapper, "批准核实").trigger("click"); expect(writes).toHaveLength(0);
  await button(wrapper, "确认提交").trigger("click"); await flushPromises();
  await button(wrapper, "只读核对最新结果").trigger("click"); await flushPromises();
  await button(wrapper, "使用原请求重试").trigger("click"); await flushPromises();
  expect(writes[1]).toEqual(writes[0]); expect((writes[0]!.headers as Record<string,string>)["If-Match-Version"]).toBe("3");
  expect(JSON.parse(writes[0]!.body as string).result).toBe("APPROVE"); wrapper.unmount();
});
it("successful upload refreshes version and leaves form immediately usable", async () => {
  const row = record(); const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(input); row.version = 4; return response({ id: "9", version: 4, objectId: "stored" }); }
    return response(url.endsWith("/9") ? row : { items: [row], total: 1 });
  }));
  const wrapper = mountPage(); await flushPromises();
  const file = new File(["actual bytes"], "original.png", { type: "image/png" });
  Object.defineProperty(wrapper.find('input[type="file"]').element, "files", { value: [file] });
  await wrapper.find('input[type="file"]').trigger("change"); await button(wrapper,"上传情况图片").trigger("click");
  await button(wrapper,"确认提交").trigger("click"); await flushPromises();
  expect(wrapper.findAll("fieldset").every(field => !field.element.disabled)).toBe(true);
  await wrapper.findAll("textarea")[1]!.setValue("核对当前原件后批准查找核实"); await button(wrapper,"批准核实").trigger("click");
  await button(wrapper,"确认提交").trigger("click"); await flushPromises();
  expect((writes[1]!.headers as Record<string,string>)["If-Match-Version"]).toBe("4"); wrapper.unmount();
});
it("identity replacement clears prepared write and previous private fields", async () => {
  const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") writes.push(input);
    if (state.session.user.id !== "1") return response({ code: "FORBIDDEN" },403);
    return response(url.endsWith("/9") ? record() : { items: [record()], total: 1 });
  }));
  const wrapper = mountPage(); await flushPromises();
  await wrapper.findAll("textarea")[1]!.setValue("先核对，再批准正式查找"); await button(wrapper,"批准核实").trigger("click");
  state.session.user.id = "2"; state.session.csrfToken = "replacement"; await flushPromises();
  expect(wrapper.text()).not.toContain("私有报失资产"); expect(wrapper.find('[role="dialog"]').exists()).toBe(false); expect(writes).toHaveLength(0); wrapper.unmount();
});
it("verification requires complete custodian evidence and freezes all four versions", async () => {
  const row = { ...record(), status: "VERIFYING", processorAccountId: "1", actions: { search: true } }; const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => { if (input.method === "POST") writes.push(input); return response(url.endsWith("/9") || input.method === "POST" ? row : { items: [row], total: 1 }); }));
  const wrapper = mountPage(); await flushPromises();
  await button(wrapper,"记录核实并进入查找").trigger("click"); expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
  await wrapper.findAll("textarea")[1]!.setValue("已经联系最后保管人核对完整交接过程"); await wrapper.findAll("textarea")[2]!.setValue("已查找原办公室抽屉并记录所有维修线索");
  await wrapper.findAll('input[type="datetime-local"]')[1]!.setValue("2026-10-28T10:00:00"); await wrapper.find('input[type="date"]').setValue("2026-11-04");
  await button(wrapper,"记录核实并进入查找").trigger("click"); await button(wrapper,"确认提交").trigger("click"); await flushPromises();
  expect(JSON.parse(writes[0]!.body as string)).toMatchObject({ assetVersion: 6, ownershipVersion: 1, lockVersion: 2, nextReviewDate: "2026-11-04" }); wrapper.unmount();
});
it("late response for previous identity never repopulates private report", async () => {
  let release: (value: Response) => void = () => {}; let slow = true;
  vi.stubGlobal("fetch", vi.fn(async () => { if (state.session.user.id !== "1") return response({ code: "FORBIDDEN" },403); if (slow) { slow = false; return await new Promise<Response>(resolve => { release = resolve; }); } return response(record()); }));
  const wrapper = mountPage(); await flushPromises(); state.session.user.id = "2"; await flushPromises(); release(response({ items: [record()], total: 1 })); await flushPromises();
  expect(wrapper.text()).not.toContain("私有报失资产"); wrapper.unmount();
});

it("switching reports discards the previous original preview and verification inputs", async () => {
  const first = { ...record(), status: "VERIFYING", processorAccountId: "1", actions: { search: true }, photos: [{ objectId: "original", filename: "previous-original.png" }] };
  const second = { ...first, id: "10", source: { ...first.source, assetCode: "LOSS-002", name: "另一个独立资产" }, photos: [] };
  const revoke = vi.fn(); vi.stubGlobal("URL", { createObjectURL: vi.fn(() => "blob:owned-original"), revokeObjectURL: revoke });
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    if (url.includes("content?grant=")) return { ok: true, status: 200, blob: async () => new Blob(["original bytes"], { type: "image/png" }) } as Response;
    if (url.endsWith("download-grant")) return response({ url: "/api/v1/loss-reports/9/photos/original/content?grant=owned" });
    return response(url.endsWith("/9") ? first : url.endsWith("/10") ? second : { items: [first, second], total: 2 });
  }));
  const wrapper = mountPage(); await flushPromises();
  await wrapper.findAll("textarea")[1]!.setValue("上一单的保管人核实结果必须清除"); await wrapper.findAll("textarea")[2]!.setValue("上一单的查找范围与原件不能带入新单");
  await button(wrapper,"查看原件").trigger("click"); await flushPromises(); expect(wrapper.find("img").exists()).toBe(true);
  await wrapper.findAll("button").filter(item => item.text() === "查看")[1]!.trigger("click"); await flushPromises();
  expect(wrapper.find("img").exists()).toBe(false); expect(revoke).toHaveBeenCalledWith("blob:owned-original");
  expect((wrapper.findAll("textarea")[1]!.element as HTMLTextAreaElement).value).toBe("");
  expect((wrapper.findAll("textarea")[2]!.element as HTMLTextAreaElement).value).toBe(""); wrapper.unmount();
});

it("recovered loss shows historical loss and found state without offering liability follow-up",async()=>{const row={...record(),status:"CONFIRMED_LOST",recoveredAt:"2026-10-30T02:20:00Z",actions:{}};vi.stubGlobal("fetch",vi.fn(async(url:string)=>response(url.endsWith("/9")?row:{items:[row],total:1})));const w=mountPage();await flushPromises();expect(w.find('tbody').text()).toContain('遗失事实保留·已找回');expect(w.find('[aria-label="报失详情"] h2').text()).toContain('遗失事实保留·已找回');expect(w.text()).not.toContain('已确认遗失·持续跟进');expect(w.findAll('button').some(item=>item.text()==='记录持续跟进')).toBe(false);expect(row.status).toBe('CONFIRMED_LOST');w.unmount();});
