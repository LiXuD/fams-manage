import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ReviewApiError } from "@/inventory-review/api";
import RepairInventoryPhoto from "./RepairInventoryPhoto.vue";
const state = vi.hoisted(() => ({ request: vi.fn(), session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
vi.mock("@/inventory-review/api", async original => ({ ...await original<typeof import("@/inventory-review/api")>(), reviewRequest: state.request }));
const object = "00000000-0000-4000-8000-000000000001";
const path = `/api/v1/inventory-repair-evidence/9/observations/8/photos/${object}/content?grant=signed`;
const panel = () => mount(RepairInventoryPhoto, { props: { repairId: "9", observationId: "8", objectId: object } });
beforeEach(() => {
  state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" });
  state.request.mockReset();
  state.request.mockResolvedValueOnce({ items: [{ objectId: object, filename: "original.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" }] }).mockResolvedValueOnce({ url: path });
  vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, blob: async () => new Blob(["png"], { type: "image/png" }) })));
  vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:private-original");
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
});
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("uses only the exact private repair-source route and clears bytes on an identity change", async () => {
  const wrapper = panel(); await wrapper.find("button").trigger("click"); await flushPromises();
  expect(wrapper.find("img").attributes("src")).toBe("blob:private-original");
  expect(fetch).toHaveBeenCalledWith(expect.stringContaining(path), expect.objectContaining({ credentials: "include", cache: "no-store" }));
  state.session.user.id = "2"; await flushPromises();
  expect(wrapper.find("img").exists()).toBe(false); expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:private-original"); wrapper.unmount();
});
it("rejects a grant for a different object before reading bytes", async () => {
  state.request.mockReset().mockResolvedValueOnce({ items: [{ objectId: object, contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" }] }).mockResolvedValueOnce({ url: path.replace("/9/", "/99/") });
  const wrapper = panel(); await wrapper.find("button").trigger("click"); await flushPromises();
  expect(fetch).not.toHaveBeenCalled(); expect(wrapper.find("img").exists()).toBe(false); wrapper.unmount();
});
it("discards bytes that arrive after the repair selection changes", async () => {
  let resolve!: (blob: Blob) => void;
  vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, blob: () => new Promise<Blob>(done => { resolve = done; }) })));
  const wrapper = panel(); await wrapper.find("button").trigger("click"); await flushPromises();
  await wrapper.setProps({ repairId: "10" }); resolve(new Blob(["png"], { type: "image/png" })); await flushPromises();
  expect(URL.createObjectURL).not.toHaveBeenCalled(); expect(wrapper.find("img").exists()).toBe(false); wrapper.unmount();
});
it("invalidates the private repair view when live source authority is lost", async () => {
  state.request.mockReset().mockRejectedValueOnce(new ReviewApiError(403, "INVENTORY_REPAIR_EVIDENCE_FORBIDDEN", {}));
  const wrapper = panel(); await wrapper.find("button").trigger("click"); await flushPromises();
  expect(wrapper.emitted("invalidated")).toHaveLength(1); expect(fetch).not.toHaveBeenCalled(); wrapper.unmount();
});
