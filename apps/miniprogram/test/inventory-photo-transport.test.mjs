import assert from "node:assert/strict";
import test from "node:test";
import { MiniSessionClient } from "../src/session.ts";
const saved = { sessionToken: "a".repeat(43), accountId: "9007199254740993", bindingId: "4" };
const photoPath = "/api/v1/mini/inventory-observations/tasks/9/events/22222222-2222-4222-8222-222222222222/photos/33333333-3333-4333-8333-333333333333/content?grant=owned.signature";
function fixture() {
    const calls = [], discarded = [], cleared = []; let current = saved;
    const runtime = { readSession: () => current, writeSession: value => { current = value; }, removeSession: () => { current = null; }, clearIdentityData: value => cleared.push(value),
        request: async () => ({ statusCode: 200, data: { bindingStatus: "BOUND", sessionToken: "b".repeat(43), accountId: "2", bindingId: "3", idleExpiresAt: "2026-10-20T00:00:00Z", absoluteExpiresAt: "2026-11-20T00:00:00Z" } }),
        downloadInventoryPhoto: async input => { calls.push(input); return { statusCode: 200, filePath: "/owned/photo.png" }; }, discardInventoryPhoto: value => discarded.push(value) };
    return { runtime, calls, discarded, cleared, client: new MiniSessionClient("https://api.example.test", runtime) };
}
test("scoped photo transport sends only its origin opaque credential and explicit file owner", async () => {
    const f = fixture(); assert.equal(await f.client.downloadInventoryPhoto(photoPath, "image/png"), "/owned/photo.png");
    assert.equal(f.calls[0].url, "https://api.example.test" + photoPath);
    assert.deepEqual(f.calls[0].identity, { accountId: saved.accountId, bindingId: saved.bindingId });
    assert.deepEqual(f.calls[0].header, { "X-FAMS-Mini-Session": saved.sessionToken });
    f.client.discardInventoryPhoto("/owned/photo.png"); assert.deepEqual(f.discarded, ["/owned/photo.png"]);
});
test("foreign PC unrelated or malformed photo URLs and unsafe MIME never receive credentials", async () => {
    const f = fixture();
    for (const path of ["https://foreign.invalid" + photoPath, photoPath.replace("/mini/", "/"), photoPath + "&extra=1", photoPath.replace("/content?", "/../content?"), "/api/v1/mini/attachments/33333333-3333-4333-8333-333333333333/content?grant=one"]) {
        await assert.rejects(f.client.downloadInventoryPhoto(path, "image/png"));
    }
    await assert.rejects(f.client.downloadInventoryPhoto(photoPath, "image/svg+xml")); assert.equal(f.calls.length, 0);
});
test("a late successful photo is discarded after a cleared session", async () => {
    const f = fixture(); let finish; f.runtime.downloadInventoryPhoto = () => new Promise(resolve => { finish = resolve; });
    const pending = f.client.downloadInventoryPhoto(photoPath, "image/png"); f.client.clear();
    finish({ statusCode: 200, filePath: "/owned/old.png" }); await assert.rejects(pending, /Session changed/);
    assert.deepEqual(f.discarded, ["/owned/old.png"]); assert.equal(f.client.identity, null);
});
test("old photo 401 cannot clear a new binding and still discards its old owned file", async () => {
    const f = fixture(); let finish; f.runtime.downloadInventoryPhoto = () => new Promise(resolve => { finish = resolve; });
    const pending = f.client.downloadInventoryPhoto(photoPath, "image/png"); f.client.clear(); await f.client.login("owned-new-login");
    finish({ statusCode: 401, filePath: "/owned/old.png" }); await assert.rejects(pending, /Session changed/);
    assert.deepEqual(f.client.identity, { accountId: "2", bindingId: "3" }); assert.equal(f.cleared.length, 1); assert.deepEqual(f.discarded, ["/owned/old.png"]);
});
test("current photo 401 clears identity and a missing cleanup capability prevents a download", async () => {
    const f = fixture(); f.runtime.downloadInventoryPhoto = async () => ({ statusCode: 401, filePath: "/owned/denied.png" });
    await assert.rejects(f.client.downloadInventoryPhoto(photoPath, "image/png"), { status: 401 });
    assert.equal(f.client.identity, null); assert.equal(f.cleared.length, 1); assert.deepEqual(f.discarded, ["/owned/denied.png"]);
    const other = fixture(); delete other.runtime.discardInventoryPhoto;
    await assert.rejects(other.client.downloadInventoryPhoto(photoPath, "image/png"), /安全读取/); assert.equal(other.calls.length, 0);
});
