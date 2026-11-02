import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import AssetFactoryReplacementTrace from "./AssetFactoryReplacementTrace.vue";
const session = vi.hoisted(() => ({ value: { user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => session.value }));
const link = { sourceId: "source", originalAssetId: "9007199254740993", replacementAssetId: "9007199254740995", originalAssetCode: "OLD-CODE", replacementAssetCode: "NEW-CODE", originalSerialNumber: "OLD-SERIAL", replacementSerialNumber: "NEW-SERIAL" };
const response = (items = [link], status = 200) => ({ ok: status === 200, status, json: async () => ({ items }) }) as Response;
const mounted: ReturnType<typeof mount>[] = [];
const create = () => { const wrapper = mount(AssetFactoryReplacementTrace, { props: { assetId: link.originalAssetId } }); mounted.push(wrapper); return wrapper; };
beforeEach(() => { session.value = reactive({ user: { id: "1", roles: ["WAREHOUSE_OPERATOR"] }, csrfToken: "csrf" }); });
afterEach(() => { for (const wrapper of mounted.splice(0)) wrapper.unmount(); vi.unstubAllGlobals(); });
it("reads only on request and opens either exact string card through the owner", async () => {
  const fetch = vi.fn(async () => response()); vi.stubGlobal("fetch", fetch); const wrapper = create(); expect(fetch).not.toHaveBeenCalled(); await wrapper.find("button").trigger("click"); await flushPromises(); expect(wrapper.text()).toContain("OLD-CODE"); expect(wrapper.text()).toContain("NEW-SERIAL"); await wrapper.findAll("button")[2]!.trigger("click"); expect(wrapper.emitted("open-card")).toEqual([[link.replacementAssetId]]);
});
it("identity change ignores a late old private response", async () => {
  let finish!: (result: Response) => void; vi.stubGlobal("fetch", () => new Promise<Response>(resolve => { finish = resolve; })); const wrapper = create(); await wrapper.find("button").trigger("click"); session.value.user.id = "9"; await flushPromises(); finish(response()); await flushPromises(); expect(wrapper.text()).not.toContain("NEW-CODE"); expect(wrapper.findAll("article")).toHaveLength(0);
});
it("permission rejection clears previously read trace", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response([], 404)); vi.stubGlobal("fetch", fetch); const wrapper = create(); await wrapper.find("button").trigger("click"); await flushPromises(); await wrapper.find("button").trigger("click"); await flushPromises(); expect(wrapper.text()).not.toContain("NEW-CODE"); expect(wrapper.find('[role="alert"]').text()).toContain("权限");
});
