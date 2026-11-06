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


it("retries the original multipart file and request version after a committed response is lost", async () => {
  const row = { ...report(), status: "PENDING_ADMIN_DECISION", actions: { decide: false, claim: false, execute: false } };
  const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith("/attachments/request") && init.method === "POST") {
      writes.push(init);
      if (writes.length === 1) { row.version++; throw Error("committed upload reply lost"); }
      return response({ id: row.id, version: row.version });
    }
    if (url.endsWith("attachment-capabilities")) return response({ canRead: false, canUploadRequest: true,
      canUploadDisposition: false, requestVersion: row.version, dispositionVersion: null });
    return response(url.includes("?") ? { items: [row], total: 1 } : row);
  }));
  const wrapper = mount(ScrapView); await flushPromises();
  await button(wrapper, "ORIGINAL-1 · 待管理员确认").trigger("click"); await flushPromises();
  const input = wrapper.get('input[type="file"]');
  const file = new File(["original"], "报废原件.png", { type: "image/png" });
  Object.defineProperty(input.element, "files", { value: [file] });
  await input.trigger("change");
  expect(writes).toHaveLength(0);
  expect(wrapper.get('[role="dialog"]').text()).toContain(file.name);
  await button(wrapper, "确认提交本次办理").trigger("click"); await flushPromises();
  await button(wrapper, "刷新报废进度").trigger("click"); await flushPromises();
  await button(wrapper, "重试原报废操作").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(2);
  expect(writes[1]?.headers).toEqual(writes[0]?.headers);
  expect(writes[0]?.headers).toMatchObject({ "If-Match-Version": "2" });
  expect((writes[0]?.body as FormData).get("file")).toBe(file);
  expect((writes[1]?.body as FormData).get("file")).toBe(file);
  wrapper.unmount();
});

it("discards a late attachment response after identity changes and revokes an opened original", async () => {
  const row = report();
  const objectId = "913f4814-383f-4a40-aa23-96691cf5166d";
  const file = { objectId, filename: "敏感原件.png", contentType: "image/png", sizeBytes: 4 };
  const path = "/api/v1/scrap-requests/" + row.id + "/attachments/request/" + objectId;
  let release: ((value: Response) => void) | undefined;
  let delayed = false;
  const bytes = { ok: true, blob: async () => new Blob(["data"], { type: file.contentType }) } as Response;
  const create = vi.fn(() => "blob:protected-scrap"), revoke = vi.fn();
  vi.stubGlobal("URL", class extends URL { static createObjectURL = create; static revokeObjectURL = revoke; });
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    if (url.endsWith("/attachment-capabilities")) return response({ canRead: true });
    if (url.endsWith("/attachments/request")) return response([file]);
    if (url.endsWith("/download-grant")) return response({ url: path + "/content?grant=private" });
    if (url.startsWith(path + "/content?")) {
      if (delayed) return new Promise<Response>(resolve => { release = resolve; });
      return bytes;
    }
    return response(url.includes("?") ? { items: [row], total: 1 } : row);
  }));
  const wrapper = mount(ScrapView); await flushPromises();
  await button(wrapper, "ORIGINAL-1 · 待实物核验与执行").trigger("click"); await flushPromises();
  await button(wrapper, file.filename).trigger("click"); await flushPromises();
  expect(wrapper.get("img").attributes("src")).toBe("blob:protected-scrap");
  delayed = true;
  await button(wrapper, file.filename).trigger("click"); await flushPromises();
  expect(revoke).toHaveBeenCalledWith("blob:protected-scrap");
  state.session.user.id = "2";
  expect(release).toBeDefined(); release?.(bytes); await flushPromises();
  expect(create).toHaveBeenCalledTimes(1);
  expect(wrapper.find("img").exists()).toBe(false);
  expect(wrapper.text()).not.toContain(file.filename);
  wrapper.unmount();
});


it("closes actual disposition with its independent version and selected clean original", async () => {
  const row: ScrapRequest = { ...report(), version: 4, status: "EXECUTED",
    actions: { decide: false, claim: false, execute: false, dispose: true },
    disposition: { disposition_status: "PENDING_DISPOSITION", due_date: "2026-12-06", version: 8 } };
  const objectId = "913f4814-383f-4a40-aa23-96691cf5166d";
  const writes: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (url.endsWith("/disposition/complete")) {
      writes.push(input);
      row.actions.dispose = false;
      row.disposition = { ...row.disposition!, disposition_status: "DISPOSED", version: 9,
        completion: { occurredAt: "2026-11-06T02:00:00Z", method: "核对后待处置", recipient: "接收单位", operatorName: "现场经办人" } };
      return response({ id: row.id, version: 9, status: "DISPOSED" });
    }
    if (url.endsWith("attachment-capabilities")) return response({ canRead: true, dispositionVersion: row.disposition?.version });
    if (url.endsWith("/attachments/request")) return response([]);
    if (url.endsWith("/attachments/disposition")) return response([{ objectId, filename: "实际凭证.png", contentType: "image/png", sizeBytes: 4 }]);
    if (url.includes("/execution-catalog/employee")) return response({ items: [{ id: "9007199254740991", name: "现场经办人", version: 6 }], total: 1 });
    return response(url.includes("?") ? { items: [row], total: 1 } : row);
  }));
  const wrapper = mount(ScrapView); await flushPromises();
  await button(wrapper, "ORIGINAL-1 · 已执行报废").trigger("click"); await flushPromises();
  await button(wrapper, "加载更多实际经办人").trigger("click"); await flushPromises();
  await field(wrapper, "处置原机编码", "ORIGINAL-1");
  await field(wrapper, "处置原机序列号", "SERIAL-1");
  await field(wrapper, "实际处置时间", "2026-11-06T10:00:00");
  await field(wrapper, "接收或处置对象", "接收单位");
  await field(wrapper, "实际经办人", "9007199254740991");
  await button(wrapper, "核对实际处置结案").trigger("click");
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
  await field(wrapper, "使用凭证 实际凭证.png", true);
  await button(wrapper, "核对实际处置结案").trigger("click");
  expect(writes).toHaveLength(0);
  await button(wrapper, "确认提交本次办理").trigger("click"); await flushPromises();
  expect(writes).toHaveLength(1);
  expect(writes[0]?.headers).toMatchObject({ "If-Match-Version": "8" });
  expect(JSON.parse(String(writes[0]?.body))).toMatchObject({ requestVersion: 4, ownershipVersion: 4,
    operatorEmployeeId: "9007199254740991", operatorVersion: 6, evidenceObjectIds: [objectId], recipient: "接收单位" });
  expect(wrapper.text()).toContain("处置进度 已处置");
  expect(wrapper.text()).toContain("经办人 现场经办人");
  wrapper.unmount();
});


it("opens an overdue disposition from the scoped server list and returns to all requests", async () => {
  const row: ScrapRequest = { ...report(), status: "EXECUTED",
    disposition: { disposition_status: "PENDING_DISPOSITION", due_date: "2026-11-05" } };
  const paths: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    paths.push(url);
    if (url.includes("/dispositions/overdue?")) return response({ items: [row], total: 1,
      canListOverdueDispositions: true, businessDate: "2026-11-06" });
    if (url.includes("?")) return response({ items: [row], total: 1, canListOverdueDispositions: true });
    if (url.endsWith("attachment-capabilities")) return response({ canRead: false });
    return response(row);
  }));
  const wrapper = mount(ScrapView); await flushPromises();
  await button(wrapper, "查看超期待处置").trigger("click"); await flushPromises();
  expect(wrapper.text()).toContain("业务日期 2026-11-06，已超期 1 单");
  expect(wrapper.text()).toContain("计划处置 2026-11-05");
  await button(wrapper, "ORIGINAL-1 · 已执行报废").trigger("click"); await flushPromises();
  expect(paths).toContain("/api/v1/scrap-requests/" + row.id);
  expect(wrapper.text()).toContain("处置进度 待实际处置");
  await button(wrapper, "返回全部申请").trigger("click"); await flushPromises();
  expect(wrapper.text()).not.toContain("已超期 1 单");
  expect(wrapper.find('[aria-label="报废详情"]').exists()).toBe(false);
  wrapper.unmount();
});

it("writes off a lost asset using the approved loss action and the original versions", async () => {
  const row: ScrapRequest = { ...report(), sourceType: "LOSS",
    actions: { decide: false, claim: false, execute: false, executeLoss: true } };
  const writes: { url: string; input: RequestInit }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, input: RequestInit) => {
    if (input.method === "POST") {
      writes.push({ url, input }); row.actions.executeLoss = false;
      row.disposition = { disposition_status: "LOSS_WRITTEN_OFF", due_date: null };
      return response({ id: row.id });
    }
    if (url.endsWith("attachment-capabilities")) return response({ canRead: false });
    return response(url.includes("?") ? { items: [row], total: 1 } : row);
  }));
  const wrapper = mount(ScrapView); await flushPromises();
  await button(wrapper, "ORIGINAL-1 · 待遗失核销").trigger("click"); await flushPromises();
  expect(wrapper.text()).not.toContain("实际原机编码");
  const evidence = "核对原报失证据与原借用后办理遗失核销";
  await field(wrapper, "核销复核依据", evidence);
  await button(wrapper, "核对执行遗失核销").trigger("click");
  expect(writes).toHaveLength(0);
  await button(wrapper, "确认提交本次办理").trigger("click"); await flushPromises();
  expect(writes[0]?.url).toBe("/api/v1/scrap-requests/" + row.id + "/execute-loss");
  expect(writes[0]?.input.headers).toMatchObject({ "If-Match-Version": "2" });
  expect(JSON.parse(String(writes[0]?.input.body))).toEqual({ assetVersion: 3, lockVersion: 5, ownershipVersion: 4, evidence });
  expect(wrapper.text()).toContain("处置进度 遗失核销");
  expect(wrapper.text()).not.toContain("计划日期");
  wrapper.unmount();
});
