import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import IdentityImportView from "./IdentityImportView.vue";
const mock = vi.hoisted(() => ({ session: {
  user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null, csrfToken: "csrf",
} }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
const csv = 'recordType,action,code,displayName,parentCode,organizationCode,phone,expectedVersion\r\n'
  + 'ORGANIZATION,CREATE,NEW,"部门\n二",,,,\r\n';
describe("identity CSV import", () => {
  beforeEach(() => {
    mock.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] }, csrfToken: "csrf" });
    vi.stubGlobal("fetch", vi.fn());
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });
  it("submits the full CSV and replays the same key after a lost result", async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError("lost reply")).mockResolvedValueOnce(
      new Response(JSON.stringify({ processedRows: 1, createdRows: 1, updatedRows: 0, disabledRows: 0 })));
    const wrapper = mount(IdentityImportView, { global: { stubs: { RouterLink: true } } });
    const input = wrapper.get('input[type="file"]');
    Object.defineProperty(input.element, "files", { value: [{ name: "identity.csv",
      arrayBuffer: async () => new TextEncoder().encode("\uFEFF" + csv).buffer }] });
    await input.trigger("change"); await flushPromises();
    expect(wrapper.get("textarea").element.value).toBe(csv.replace(/\r\n/g, "\n"));
    await wrapper.findAll("button").find(button => button.text() === "确认导入")!.trigger("click");
    await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "原请求重试导入")!.trigger("click");
    await flushPromises();
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(vi.mocked(fetch).mock.calls[0]).toEqual(vi.mocked(fetch).mock.calls[1]);
    expect(vi.mocked(fetch).mock.calls[0]).toEqual(["/api/v1/identity-imports", expect.objectContaining({
      method: "POST", body: csv, credentials: "include",
      headers: { "Content-Type": "text/csv;charset=utf-8", "X-CSRF-Token": "csrf",
        "Idempotency-Key": expect.any(String) },
    })]);
    expect(wrapper.get('[aria-label="导入结果"]').text()).toContain("新增 1 条");
    expect(wrapper.find("textarea").exists()).toBe(false);
  });
  it("rejects a non-UTF8 file before any request", async () => {
    const wrapper = mount(IdentityImportView, { global: { stubs: { RouterLink: true } } });
    const input = wrapper.get('input[type="file"]');
    Object.defineProperty(input.element, "files", { value: [{ name: "bad.csv",
      arrayBuffer: async () => Uint8Array.from([0xff, 0xfe]).buffer }] });
    await input.trigger("change"); await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain("UTF-8");
    expect(fetch).not.toHaveBeenCalled();
  });
  it("drops a former administrator's late file contents and removes the old file control", async () => {
    let finish!: (value: ArrayBuffer) => void;
    const wrapper = mount(IdentityImportView, { global: { stubs: { RouterLink: true } } });
    const input = wrapper.get('input[type="file"]');
    Object.defineProperty(input.element, "files", { value: [{ name: "private.csv",
      arrayBuffer: () => new Promise<ArrayBuffer>(resolve => { finish = resolve; }) }] });
    await input.trigger("change");
    mock.session.user = { id: "2", roles: ["ASSET_ADMIN"] }; await flushPromises();
    finish(new TextEncoder().encode(csv).buffer); await flushPromises();
    expect(wrapper.find('input[type="file"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain("private.csv");
    expect(fetch).not.toHaveBeenCalled();
  });
});
