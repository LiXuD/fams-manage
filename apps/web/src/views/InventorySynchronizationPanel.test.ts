import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import InventorySynchronizationPanel from "./InventorySynchronizationPanel.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => body }) as Response;
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); });
it("shows missing and revoked participants without offering administrator declarations", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => response({ total: 2, items: [
    { accountId: "2", name: "待补证甲", current: false, reason: "PARTICIPATION_REVOKED", devices: [] },
    { accountId: "3", name: "现场乙", current: false, reason: "DEVICE_CONFIRMATION_MISSING", devices: [
      { deviceId: "device", bindingId: "4", current: false, reason: "NEW_OBSERVATION" } ] } ] })));
  const wrapper = mount(InventorySynchronizationPanel, { props: { taskId: "9" } }); await flushPromises();
  expect(wrapper.text()).toContain("参与关系已撤销，仍待补证"); expect(wrapper.text()).toContain("声明后有新证据");
  expect(wrapper.findAll("button").map(button => button.text())).toEqual(["刷新同步声明", "上页执行人", "下页执行人"]); wrapper.unmount();
});
it("suppresses an old identity response and clears the previous roster", async () => {
  let finish!: (value: Response) => void;
  vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
  const wrapper = mount(InventorySynchronizationPanel, { props: { taskId: "9" } });
  const old = finish; state.session.user.id = "5"; await flushPromises();
  old(response({ total: 1, items: [{ accountId: "2", name: "旧身份私有姓名", reason: "DECLARED", devices: [] }] }));
  await flushPromises(); expect(wrapper.text()).not.toContain("旧身份私有姓名"); wrapper.unmount();
});
