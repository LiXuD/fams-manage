import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import ScrapView from "./ScrapView.vue";
import type { ScrapRequest } from "@/scrap/api";

const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["EMPLOYEE"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
vi.mock("vue-router", () => ({ useRoute: () => ({ params: {} }) }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot/></main>" } }));
const response = (body: unknown) => ({ ok: true, json: async () => structuredClone(body) }) as Response;
const report = (): ScrapRequest => ({
  id: "9007199254740997", assetId: "9007199254740999", version: 2, assetVersion: 3, ownershipVersion: 4,
  lockVersion: 5, processorAccountId: "1", status: "PROCESSING", reason: "原机损坏", observedCondition: "无法继续使用",
  proposedDisposition: "等待处置", source: { assetCode: "ORIGINAL-1", name: "原机", serialNumber: "SERIAL-1" },
  actions: { decide: false, claim: false, execute: true },
  decision: { decision: { approvedDisposition: "核对后待处置" } }, disposition: null,
});
const button = (wrapper: ReturnType<typeof mount>, name: string) =>
  wrapper.findAll("button").find(item => item.text() === name)!;
async function field(wrapper: ReturnType<typeof mount>, text: string, value: string | boolean) {
  await wrapper.findAll("label").find(item => item.text().startsWith(text))!.find("input,textarea,select").setValue(value);
}
beforeEach(() => {
  state.session = reactive({ user: { id: "1", roles: ["EMPLOYEE"] }, csrfToken: "csrf" });
});
afterEach(() => vi.unstubAllGlobals());

it("submission awaits confirmation and retains its exact key and body after a lost committed reply", async () => {
  const writes: RequestInit[] = [];
  let lost = true;
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") {
      writes.push(structuredClone(input));
      if (lost) { lost = false; throw Error("committed reply lost"); }
      return response({ id: "9007199254740997" });
    }
    if (url.includes("/eligible")) return response({
      items: [{ id: "9007199254740999", assetCode: "ORIGINAL-1", name: "原机", version: 7 }], total: 1,
    });
    if (url.includes("?")) return response({ items: [], total: 0 });
    return response({ ...report(), actions: {}, status: "PENDING_ADMIN_DECISION" });
  }));
  const wrapper = mount(ScrapView);
  await flushPromises();
  await button(wrapper, "查询可报废资产").trigger("click"); await flushPromises();
  await field(wrapper, "资产", "9007199254740999");
  await field(wrapper, "报废原因", "无法继续使用");
  await field(wrapper, "资产现状", "已核对原机损坏");
  await field(wrapper, "建议处置方式", "等待处置");
  await button(wrapper, "核对提交报废申请").trigger("click");
  expect(writes).toHaveLength(0);
  await button(wrapper, "确认提交本次办理").trigger("click"); await flushPromises();
  expect(button(wrapper, "重试原报废操作").exists()).toBe(true);
  await button(wrapper, "刷新报废进度").trigger("click"); await flushPromises();
  await button(wrapper, "重试原报废操作").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
  expect(JSON.parse(String(writes[0]?.body))).toMatchObject({ assetId: "9007199254740999", assetVersion: 7 });
  wrapper.unmount();
});

it("execution freezes independent versions and selected real custody before writing", async () => {
  const row = report(), writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") { writes.push(input); row.actions.execute = false; return response({ id: row.id }); }
    if (url.includes("/execution-catalog/location")) return response({ items: [{ id: "11", name: "核实地点", version: 8 }], total: 1 });
    if (url.includes("/execution-catalog/employee")) return response({ items: [{ id: "12", name: "实物保管人", version: 9 }], total: 1 });
    return response(url.includes("?") ? { items: [row], total: 1 } : row);
  }));
  const wrapper = mount(ScrapView); await flushPromises();
  await button(wrapper, "ORIGINAL-1 · 待实物核验与执行").trigger("click"); await flushPromises();
  await button(wrapper, "加载更多实际位置").trigger("click"); await flushPromises();
  await button(wrapper, "加载更多处置保管人").trigger("click"); await flushPromises();
  await field(wrapper, "实际原机编码", "ORIGINAL-1"); await field(wrapper, "实际原机序列号", "SERIAL-1");
  await field(wrapper, "实际核验时间", "2026-11-05T10:20:30");
  await field(wrapper, "待处置位置", "11"); await field(wrapper, "处置保管人", "12");
  await field(wrapper, "实际核验依据", "已核对原机身份和真实位置并明确处置保管责任");
  await button(wrapper, "核对执行报废").trigger("click");
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
  await field(wrapper, "已亲见并核对", true);
  await button(wrapper, "核对执行报废").trigger("click");
  expect(writes).toHaveLength(0);
  await button(wrapper, "确认提交本次办理").trigger("click"); await flushPromises();
  expect(JSON.parse(String(writes[0]?.body))).toMatchObject({
    assetVersion: 3, ownershipVersion: 4, lockVersion: 5, locationId: "11", locationVersion: 8,
    custodianEmployeeId: "12", custodianVersion: 9, dispositionMethod: "核对后待处置",
    physicalIdentityVerified: true,
  });
  expect((writes[0]?.headers as Record<string, string>)["If-Match-Version"]).toBe("2");
  state.session.user.id = "2"; await flushPromises();
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
  wrapper.unmount();
});
