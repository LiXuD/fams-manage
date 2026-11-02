import { afterEach, describe, expect, it, vi } from "vitest";
import { listInventoryPhotos, readInventoryPhoto, type InventoryPhoto } from "./photos";
const state = vi.hoisted(() => ({ session: { csrfToken: "photo-csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const event = "22222222-2222-4222-8222-222222222222";
const object = "33333333-3333-4333-8333-333333333333";
const unused = "44444444-4444-4444-8444-444444444444";
const missing = "55555555-5555-4555-8555-555555555555";
const path = `/api/v1/inventory-observations/tasks/9/events/${event}/photos/${object}`;
function photo(): InventoryPhoto { return { objectId: object, filename: "现场.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" }; }
const reply = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => body }) as Response;
afterEach(() => { vi.unstubAllGlobals(); state.session.csrfToken = "photo-csrf"; });
describe("inventory-owned photo reading", () => {
  it("preserves original references and missing metadata while excluding unused uploads", async () => {
    const fetcher = vi.fn(async () => reply({ items: [photo(), { ...photo(), objectId: unused }] })); vi.stubGlobal("fetch", fetcher);
    const rows = await listInventoryPhotos("9", event, [object, missing]);
    expect(rows.map(row => row.objectId)).toEqual([object, missing]); expect(rows[1]!.photo).toBeNull();
    expect(fetcher).toHaveBeenCalledWith(path.slice(0, -37), expect.objectContaining({ method: "GET", credentials: "include" }));
  });
  it("uses current csrf for the owned grant and reads exact signed content without caching", async () => {
    const fetcher = vi.fn(async (_url: string, input: RequestInit) => input.method === "POST"
      ? reply({ url: path + "/content?grant=owned.signature" })
      : ({ ok: true, headers: new Headers({ "Content-Type": "image/png" }), blob: async () => new Blob(["png"], { type: "image/png" }) }) as Response);
    vi.stubGlobal("fetch", fetcher); expect((await readInventoryPhoto("9", event, photo())).size).toBe(3);
    expect(fetcher.mock.calls[0]![0]).toBe(path + "/download-grant");
    expect(fetcher.mock.calls[0]![1].headers).toMatchObject({ "X-CSRF-Token": "photo-csrf" });
    expect(fetcher.mock.calls[1]![1]).toMatchObject({ credentials: "include", cache: "no-store" });
  });
  it.each([
    `https://foreign.invalid${path}/content?grant=owned`,
    `${path.replace(object, unused)}/content?grant=owned`,
    `${path}/content?grant=owned&extra=1`,
    `${path}/content?grant=one&grant=two`,
    `${path}/content?grant=bad%2Ftoken`,
    `${path}/content?grant=owned#fragment`,
  ])("rejects a foreign, sibling or malformed grant before any content request: %s", async url => {
    const fetcher = vi.fn(async () => reply({ url })); vi.stubGlobal("fetch", fetcher);
    await expect(readInventoryPhoto("9", event, photo())).rejects.toThrow("地址无效"); expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("refuses unscanned content and absent csrf before transmitting a read grant", async () => {
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
    await expect(readInventoryPhoto("9", event, { ...photo(), scanStatus: "UNAVAILABLE" })).rejects.toThrow("未通过扫描");
    state.session.csrfToken = ""; await expect(readInventoryPhoto("9", event, photo())).rejects.toMatchObject({ status: 401 });
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects content that differs from the bound image type or byte size", async () => {
    let type = "image/svg+xml";
    vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => input.method === "POST"
      ? reply({ url: path + "/content?grant=owned" })
      : ({ ok: true, headers: new Headers({ "Content-Type": type }), blob: async () => new Blob(["wrong-size"]) }) as Response));
    await expect(readInventoryPhoto("9", event, photo())).rejects.toThrow("类型不一致");
    type = "image/png"; await expect(readInventoryPhoto("9", event, photo())).rejects.toThrow("容量不一致");
  });
  it("missing scope preserves references only after the current review role is revalidated", async () => {
    let authorized = true; const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      calls.push(url); return url.includes("/inventory-reviews/") ? reply({}, authorized ? 200 : 403)
        : reply({ code: "INVENTORY_PHOTO_NOT_FOUND" }, 404);
    }));
    expect(await listInventoryPhotos("9", event, [object])).toEqual([{ objectId: object, photo: null }]);
    expect(calls[1]).toBe("/api/v1/inventory-reviews/tasks/9?page=1&pageSize=1");
    authorized = false; await expect(listInventoryPhotos("9", event, [object])).rejects.toMatchObject({ status: 403 });
  });
  it("a removed object grant revalidates the task before reporting an unreadable original", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => url.includes("/inventory-reviews/") ? reply({}) : reply({ code: "ATTACHMENT_NOT_FOUND" }, 404)));
    await expect(readInventoryPhoto("9", event, photo())).rejects.toThrow("原始照片暂不可读取");
  });
  it.each(["identity", "scope"])("does not fetch content after %s changes during a grant", async change => {
    let release!: (value: Response) => void; let current = true;
    const fetcher = vi.fn(() => new Promise<Response>(resolve => { release = resolve; }));
    vi.stubGlobal("fetch", fetcher);
    const pending = readInventoryPhoto("9", event, photo(), () => current);
    const rejected = expect(pending).rejects.toThrow("读取已取消");
    if (change === "identity") state.session.csrfToken = "new-identity"; else current = false;
    release(reply({ url: path + "/content?grant=old" })); await rejected;
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("does not revalidate missing metadata under a replacement identity", async () => {
    let release!: (value: Response) => void;
    const fetcher = vi.fn(() => new Promise<Response>(resolve => { release = resolve; }));
    vi.stubGlobal("fetch", fetcher);
    const pending = listInventoryPhotos("9", event, [object]);
    const rejected = expect(pending).rejects.toThrow("读取已取消");
    state.session.csrfToken = "new-identity";
    release(reply({ code: "INVENTORY_PHOTO_NOT_FOUND" }, 404)); await rejected;
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

});
