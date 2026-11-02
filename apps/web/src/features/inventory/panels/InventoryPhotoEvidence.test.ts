import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import InventoryPhotoEvidence from "./InventoryPhotoEvidence.vue";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const event = "22222222-2222-4222-8222-222222222222", object = "33333333-3333-4333-8333-333333333333";
const path = `/api/v1/inventory-observations/tasks/9/events/${event}/photos/${object}`;
const photo = { objectId: object, filename: "原始现场.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" };
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
function panel() { return mount(InventoryPhotoEvidence, { props: { taskId: "9", eventId: event, photoIds: [object] } }); }
const action = (wrapper: ReturnType<typeof panel>, label: string) => wrapper.findAll("button").find(row => row.text() === label)!;
const create = vi.fn(() => "blob:owned-inventory-photo"), revoke = vi.fn();
const OriginalURL = URL;
describe("raw observation photo lifecycle", () => {
  beforeEach(() => {
    state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" });
    create.mockClear(); revoke.mockClear();
    vi.stubGlobal("URL", class extends OriginalURL { static createObjectURL = create; static revokeObjectURL = revoke; });
  });
  afterEach(() => { vi.unstubAllGlobals(); });
  it("reads only on explicit action and revokes the preview when closed and unmounted", async () => {
    const fetcher = vi.fn(async (url: string) => url.endsWith("/download-grant") ? response({ url: path + "/content?grant=owned" })
      : url.includes("/content?") ? ({ ok: true, headers: new Headers({ "Content-Type": "image/png" }), blob: async () => new Blob(["png"]) }) as Response
        : response({ items: [photo] })); vi.stubGlobal("fetch", fetcher);
    const wrapper = panel(); expect(fetcher).not.toHaveBeenCalled();
    await action(wrapper, "查看原始照片资料").trigger("click"); await flushPromises();
    await action(wrapper, "读取照片 1").trigger("click"); await flushPromises(); expect(wrapper.find("img").attributes("src")).toBe("blob:owned-inventory-photo");
    await action(wrapper, "关闭照片").trigger("click"); expect(revoke).toHaveBeenCalledExactlyOnceWith("blob:owned-inventory-photo");
    wrapper.unmount(); expect(revoke).toHaveBeenCalledTimes(1);
  });
  it("an identity switch during content reading cannot create a late preview", async () => {
    let release!: (value: Blob) => void;
    vi.stubGlobal("fetch", vi.fn(async (url: string) => url.endsWith("/download-grant") ? response({ url: path + "/content?grant=owned" })
      : url.includes("/content?") ? ({ ok: true, headers: new Headers({ "Content-Type": "image/png" }), blob: () => new Promise<Blob>(resolve => { release = resolve; }) }) as Response
        : response({ items: [photo] })));
    const wrapper = panel(); await action(wrapper, "查看原始照片资料").trigger("click"); await flushPromises();
    await action(wrapper, "读取照片 1").trigger("click"); await flushPromises(); state.session.user.id = "2";
    release(new Blob(["png"])); await flushPromises(); expect(create).not.toHaveBeenCalled(); expect(wrapper.find("img").exists()).toBe(false);
    expect(wrapper.text()).not.toContain(photo.filename); wrapper.unmount();
  });
  it("a current permission denial hides metadata and invalidates its parent", async () => {
    let denied = false; vi.stubGlobal("fetch", vi.fn(async () => denied ? response({ code: "AUTH_FORBIDDEN" }, 403) : response({ items: [photo] })));
    const wrapper = panel(); await action(wrapper, "查看原始照片资料").trigger("click"); await flushPromises(); denied = true;
    await action(wrapper, "刷新原始照片资料").trigger("click"); await flushPromises();
    expect(wrapper.text()).not.toContain(photo.filename); expect(wrapper.emitted("invalid")).toHaveLength(1); wrapper.unmount();
  });
  it("missing and unscanned original references remain visible without a read action", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => response({ items: [{ ...photo, scanStatus: "UNAVAILABLE" }] })));
    const wrapper = panel(); await action(wrapper, "查看原始照片资料").trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain("尚未通过扫描"); expect(wrapper.findAll("button").some(row => row.text().startsWith("读取照片"))).toBe(false);
    vi.stubGlobal("fetch", vi.fn(async () => response({ items: [] }))); await action(wrapper, "刷新原始照片资料").trigger("click"); await flushPromises();
    expect(wrapper.text()).toContain(object); expect(wrapper.text()).toContain("原始引用"); wrapper.unmount();
  });
  it.each(["scope", "unmount"])("does not fetch photo bytes after %s while the grant is pending", async change => {
    let release!: (value: Response) => void;
    const fetcher = vi.fn(async (url: string) => url.endsWith("/download-grant")
      ? new Promise<Response>(resolve => { release = resolve; }) : response({ items: [photo] }));
    vi.stubGlobal("fetch", fetcher);
    const wrapper = panel(); await action(wrapper, "查看原始照片资料").trigger("click"); await flushPromises();
    await action(wrapper, "读取照片 1").trigger("click"); await flushPromises();
    if (change === "scope") await wrapper.setProps({ taskId: "10" }); else wrapper.unmount();
    release(response({ url: path + "/content?grant=old" })); await flushPromises();
    expect(fetcher).toHaveBeenCalledTimes(2); expect(create).not.toHaveBeenCalled();
    if (change === "scope") wrapper.unmount();
  });

});
