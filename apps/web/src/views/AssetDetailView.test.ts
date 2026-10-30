import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Role } from "@/auth/types";
import { useSessionStore } from "@/auth/sessionStore";
import AssetDetailView from "./AssetDetailView.vue";

const api = vi.hoisted(() => ({ asset: vi.fn(), tag: vi.fn(), update: vi.fn(), copy: vi.fn(), cancel: vi.fn(),
  reprint: vi.fn(), qr: vi.fn(), query: {} as Record<string, string>, roles: ["WAREHOUSE_OPERATOR"] as Role[] }));
vi.mock("@/asset/api", async importOriginal => ({
  ...await importOriginal<typeof import("@/asset/api")>(),
  getAsset: api.asset, getTag: api.tag, updateAsset: api.update, copyAsset: api.copy,
  cancelAsset: api.cancel, reprintTag: api.reprint }));
vi.mock("qrcode", () => ({ default: { toDataURL: api.qr } }));
vi.mock("vue-router", () => ({ useRoute: () => ({ query: api.query }) }));

const card = (over: Record<string, unknown> = {}) => ({ id: "21", assetCode: "FA-2026-000101",
  name: "办公笔记本 ThinkPad T14", model: "T14 Gen 4", lifecycle: "IN_USE", exception: "NORMAL",
  cardStage: "ACTIVE", version: 7, category: { id: "c1", version: 2 }, organization: { id: "o1", version: 4 },
  currentCustody: { custodyType: "PERSONAL", responsibleEmployee: { id: "9" } },
  physicalDestination: { destination: "四楼机房", status: "DELIVERED_WAITING_RECEIPT", temporaryEmployeeId: "9",
    deliveredAt: "2026-10-01T02:00:00Z", receiptDeadlineAt: "2026-10-08T02:00:00Z" }, ...over });
const tag = { enterpriseName: "示例公司", assetName: "办公笔记本 ThinkPad T14", assetCode: "FA-2026-000101",
  qrToken: "token", qrCode: "https://tag.example/FA-2026-000101" };

async function fixture(over: Record<string, unknown> = {}, id = "21") {
  api.asset.mockResolvedValue(structuredClone(card(over)));
  api.tag.mockResolvedValue(structuredClone(tag));
  api.qr.mockResolvedValue("data:image/png;base64,qr");
  api.update.mockImplementation(async (_id: string, _version: number, payload: Record<string, unknown>) =>
    structuredClone(card({ ...over, ...payload, version: 8 })));
  api.copy.mockResolvedValue({ assets: [card(), card()] });
  api.cancel.mockResolvedValue(structuredClone(card({ ...over, cardStage: "CANCELLED", version: 8 })));
  api.reprint.mockResolvedValue({ jobId: "job-9", tag: { ...tag, assetCode: "FA-2026-000101" } });
  vi.spyOn(window, "print").mockImplementation(() => undefined);
  const pinia = createPinia(); const session = useSessionStore(pinia);
  session.state = "authenticated";
  session.user = { id: "1", username: "wh", roles: api.roles, status: "ENABLED", requiresInitialPasswordChange: false };
  const wrapper = mount(AssetDetailView, { props: { id }, global: { plugins: [pinia], stubs: {
    AppChrome: { template: "<main><slot /><slot name=\"actions\" /></main>" },
    RouterLink: { props: ["to"], template: "<a :data-destination=\"to\"><slot /></a>" } } } });
  await flushPromises();
  return { wrapper, button: (text: string) => wrapper.findAll("button").find(b => b.text() === text)!,
    field: () => wrapper.find("div[role=\"group\"] input"),
    labels: () => wrapper.findAll("button").map(b => b.text()) };
}

describe("资产详情", () => {
  afterEach(() => {
    vi.clearAllMocks(); vi.restoreAllMocks();
    api.roles = ["WAREHOUSE_OPERATOR"]; api.query = {};
    window.history.replaceState(null, "", "/");
  });

  it("非法单号不向服务端发出", async () => {
    const f = await fixture({}, "../other");
    expect(api.asset).not.toHaveBeenCalled();
    expect(f.wrapper.text()).toContain("资产详情加载失败");
    expect(f.labels()).toEqual([]);
    f.wrapper.unmount();
  });

  it("只取本条资产与标签，阶段与去向用中文，返回入口指向台账列表", async () => {
    const f = await fixture();
    expect(api.asset).toHaveBeenCalledWith("21");
    expect(api.tag).toHaveBeenCalledWith("21");
    expect(api.qr).toHaveBeenCalledWith("https://tag.example/FA-2026-000101", { width: 160, margin: 1 });
    expect(f.wrapper.text()).toContain("FA-2026-000101 · 已生效 · 版本 7");
    expect(f.wrapper.text()).not.toContain("ACTIVE");
    expect(f.wrapper.text()).toContain("个人保管 · 责任人 9");
    expect(f.wrapper.text()).toContain("实物去向：四楼机房 · 已交出待接收；正式保管仍以上方资产事实为准。");
    expect(f.wrapper.find("img[alt=\"资产二维码\"]").attributes("src")).toBe("data:image/png;base64,qr");
    expect(f.wrapper.findAll("a[data-destination]").map(a => [a.text(), a.attributes("data-destination")]))
      .toEqual([["返回台账列表", "/assets"]]);
    f.wrapper.unmount();
  });

  it("非库管只有返回入口，不出现任何办理动作", async () => {
    api.roles = ["EMPLOYEE"];
    const f = await fixture();
    expect(f.labels()).toEqual([]);
    expect(f.wrapper.findAll("a[data-destination]").map(a => a.text())).toEqual(["返回台账列表"]);
    f.wrapper.unmount();
  });

  it("在用资产给修正备注与打印标签，取消草稿只属于草稿卡", async () => {
    const active = await fixture();
    expect(active.labels()).toEqual(["复制建卡", "修正备注", "打印标签"]);
    active.wrapper.unmount();
    const draft = await fixture({ cardStage: "DRAFT", version: 1 });
    expect(draft.labels()).toEqual(["复制建卡", "修正备注", "取消草稿"]);
    draft.wrapper.unmount();
    const cancelled = await fixture({ cardStage: "CANCELLED" });
    expect(cancelled.labels()).toEqual(["复制建卡"]);
    cancelled.wrapper.unmount();
  });

  it("动作只在页内确认块里给出输入，返回修改时零写请求", async () => {
    const f = await fixture();
    f.button("修正备注").trigger("click"); await flushPromises();
    expect(f.wrapper.find("div[role=\"group\"]").attributes("aria-label")).toBe("确认修正备注");
    expect(f.labels()).toEqual(["复制建卡", "修正备注", "打印标签", "确认修正备注", "返回修改"]);
    f.button("返回修改").trigger("click"); await flushPromises();
    f.button("复制建卡").trigger("click"); await flushPromises();
    f.button("返回修改").trigger("click"); await flushPromises();
    f.button("打印标签").trigger("click"); await flushPromises();
    expect(api.update).not.toHaveBeenCalled();
    expect(api.copy).not.toHaveBeenCalled();
    expect(api.reprint).toHaveBeenCalledWith("21", 7);
    f.wrapper.unmount();
  });

  it("确认修正备注提交当前版本并播报", async () => {
    const f = await fixture();
    f.button("修正备注").trigger("click"); await flushPromises();
    await f.field().setValue("同意修正");
    f.button("确认修正备注").trigger("click"); await flushPromises();
    expect(api.update).toHaveBeenCalledWith("21", 7, { notes: "同意修正" });
    expect(f.wrapper.text()).toContain("备注已更新");
    expect(f.wrapper.text()).toContain("FA-2026-000101 · 已生效 · 版本 8");
    expect(f.labels()).toEqual(["复制建卡", "修正备注", "打印标签"]);
    f.wrapper.unmount();
  });

  it("空备注仍是有效修正", async () => {
    const f = await fixture();
    f.button("修正备注").trigger("click"); await flushPromises();
    f.button("确认修正备注").trigger("click"); await flushPromises();
    expect(api.update).toHaveBeenCalledWith("21", 7, { notes: "" });
    expect(f.wrapper.text()).toContain("备注已更新");
    f.wrapper.unmount();
  });

  it("复制数量越界时零请求，合法时按当前版本提交", async () => {
    const f = await fixture();
    f.button("复制建卡").trigger("click"); await flushPromises();
    for (const value of ["0", "101", ""]) {
      await f.field().setValue(value);
      f.button("确认复制建卡").trigger("click"); await flushPromises();
    }
    expect(api.copy).not.toHaveBeenCalled();
    await f.field().setValue("2");
    f.button("确认复制建卡").trigger("click"); await flushPromises();
    expect(api.copy).toHaveBeenCalledWith("21", 7, 2);
    expect(f.wrapper.text()).toContain("已创建 2 张独立草稿");
    f.wrapper.unmount();
  });

  it("取消草稿缺原因时零请求，带原因提交当前版本", async () => {
    const empty = await fixture({ cardStage: "DRAFT", version: 1 });
    empty.button("取消草稿").trigger("click"); await flushPromises();
    await empty.field().setValue("   ");
    empty.button("确认取消草稿").trigger("click"); await flushPromises();
    expect(api.cancel).not.toHaveBeenCalled();
    expect(empty.wrapper.find("div[role=\"group\"]").exists()).toBe(true);
    empty.wrapper.unmount();
    const f = await fixture({ cardStage: "DRAFT", version: 1 });
    f.button("取消草稿").trigger("click"); await flushPromises();
    await f.field().setValue("重复建卡");
    f.button("确认取消草稿").trigger("click"); await flushPromises();
    expect(api.cancel).toHaveBeenCalledWith("21", 1, "重复建卡");
    expect(f.wrapper.text()).toContain("草稿已取消，编码永久保留");
    expect(f.labels()).toEqual(["复制建卡"]);
    f.wrapper.unmount();
  });

  it("打印标签换回新凭证并触发打印", async () => {
    const f = await fixture();
    f.button("打印标签").trigger("click"); await flushPromises();
    expect(api.reprint).toHaveBeenCalledWith("21", 7);
    expect(f.wrapper.text()).toContain("打印任务 job-9 已完成；标签身份保持不变");
    expect(window.print).toHaveBeenCalled();
    f.wrapper.unmount();
  });

  it("建卡跳转来的地址栏标记只播报一次", async () => {
    window.history.replaceState(null, "", "/assets/21?saved=1");
    api.query = { saved: "1" };
    const f = await fixture({ physicalDestination: null });
    expect(f.wrapper.text()).toContain("草稿 FA-2026-000101 已保存");
    expect(window.location.search).toBe("");
    f.wrapper.unmount();
  });
});
