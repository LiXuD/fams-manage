import assert from "node:assert/strict";
import test from "node:test";
import { MiniRequestError } from "../src/session.ts";
import { listReviewPhotos, readReviewPhoto, ReviewPhotoUnavailable } from "../src/inventory-review-photos.ts";
const event = "22222222-2222-4222-8222-222222222222", object = "33333333-3333-4333-8333-333333333333";
const missing = "44444444-4444-4444-8444-444444444444";
const photo = { objectId: object, filename: "现场.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" };
const base = `/api/v1/mini/inventory-observations/tasks/9/events/${event}/photos`;
function fixture() {
    const calls = [], reads = [];
    const sessions = { identity: { accountId: "1", bindingId: "2" },
        async request(method, path, body, mutation) { calls.push({ method, path, body, mutation }); return method === "GET" ? { items: [photo] } : { url: `${base}/${object}/content?grant=owned.signature` }; },
        async downloadInventoryPhoto(path, type) { reads.push({ path, type }); return "/owned/photo.png"; } };
    return { sessions, calls, reads };
}
test("original photo references exclude unused uploads and retain missing evidence", async () => {
    const f = fixture(); const rows = await listReviewPhotos(f.sessions, "9", event, [object, missing]);
    assert.equal(rows.length, 2); assert.equal(rows[0].readable, true); assert.equal(rows[1].photo, null); assert.equal(rows[1].objectId, missing);
    assert.equal(f.calls[0].path, base);
});
test("a missing photo scope is shown only after a fresh administrator task read", async () => {
    const f = fixture(); let authorized = true;
    f.sessions.request = async (_method, path) => {
        f.calls.push(path);
        if (path.includes("inventory-reviews")) { if (!authorized) throw new MiniRequestError(403, "AUTH_FORBIDDEN"); return {}; }
        throw new MiniRequestError(404, "INVENTORY_PHOTO_NOT_FOUND");
    };
    assert.equal((await listReviewPhotos(f.sessions, "9", event, [object]))[0].photo, null);
    assert.equal(f.calls[1], "/api/v1/mini/inventory-reviews/tasks/9?page=1&pageSize=1");
    authorized = false; await assert.rejects(listReviewPhotos(f.sessions, "9", event, [object]), { status: 403 });
});
test("a photo grant uses the mini owner route and scoped image transport", async () => {
    const f = fixture(); assert.equal(await readReviewPhoto(f.sessions, "9", event, photo, () => "original-grant-key"), "/owned/photo.png");
    assert.equal(f.calls[0].method, "POST"); assert.equal(f.calls[0].path, `${base}/${object}/download-grant`);
    assert.deepEqual(f.calls[0].mutation, { key: "original-grant-key", version: 0 });
    assert.deepEqual(f.reads, [{ path: `${base}/${object}/content?grant=owned.signature`, type: "image/png" }]);
});
test("foreign sibling PC or extra-query grants cannot reach the image transport", async () => {
    for (const url of [`https://foreign.invalid${base}/${object}/content?grant=one`, `${base}/${missing}/content?grant=one`,
        `${base.replace("/mini/", "/")}/${object}/content?grant=one`, `${base}/${object}/content?grant=one&extra=1`]) {
        const f = fixture(); f.sessions.request = async () => ({ url });
        await assert.rejects(readReviewPhoto(f.sessions, "9", event, photo, () => "key"), /地址无效/); assert.equal(f.reads.length, 0);
    }
});
test("an unscanned reference does not issue a grant", async () => {
    const f = fixture(); await assert.rejects(readReviewPhoto(f.sessions, "9", event, { ...photo, scanStatus: "UNAVAILABLE" }, () => "key"), /未通过扫描/);
    assert.equal(f.calls.length, 0);
});
test("binding change after grant prevents an old interaction from downloading with the new identity", async () => {
    const f = fixture(); f.sessions.request = async () => { f.sessions.identity = { accountId: "2", bindingId: "3" }; return { url: `${base}/${object}/content?grant=one` }; };
    await assert.rejects(readReviewPhoto(f.sessions, "9", event, photo, () => "key"), /身份已变化/); assert.equal(f.reads.length, 0);
});
test("removed content preserves its reference after current task authorization succeeds", async () => {
    const f = fixture(); f.sessions.downloadInventoryPhoto = async () => { throw new MiniRequestError(404, "MINI_REQUEST_FAILED"); };
    await assert.rejects(readReviewPhoto(f.sessions, "9", event, photo, () => "key"), ReviewPhotoUnavailable);
    assert.equal(f.calls.at(-1).path, "/api/v1/mini/inventory-reviews/tasks/9?page=1&pageSize=1");
});
test("an identity change during missing-photo reauthorization cannot return old references", async () => {
    const f = fixture(); let finish;
    f.sessions.request = async (_method, path) => { if (!path.includes("inventory-reviews")) throw new MiniRequestError(404, "INVENTORY_PHOTO_NOT_FOUND");
        return new Promise(resolve => { finish = resolve; }); };
    const pending = listReviewPhotos(f.sessions, "9", event, [object]); await new Promise(resolve => setImmediate(resolve));
    f.sessions.identity = { accountId: "2", bindingId: "3" }; finish({}); await assert.rejects(pending, /身份已变化/);
});

test("a hidden page does not download an old grant or reauthorize a missing scope", async () => {
    for (const missingScope of [false, true]) {
        const f = fixture(); let finish, current = true;
        f.sessions.request = () => new Promise((resolve, reject) => { finish = () => missingScope
            ? reject(new MiniRequestError(404, "INVENTORY_PHOTO_NOT_FOUND"))
            : resolve({ url: `${base}/${object}/content?grant=old` }); });
        const pending = missingScope ? listReviewPhotos(f.sessions, "9", event, [object], () => current)
            : readReviewPhoto(f.sessions, "9", event, photo, () => "key", () => current);
        current = false; finish(); await assert.rejects(pending, /身份已变化/);
        assert.equal(f.reads.length, 0);
    }
});
