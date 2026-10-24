import assert from "node:assert/strict";
import test from "node:test";
import { MiniSessionClient, MiniRequestError } from "../src/session.ts";
import { readRepairInventoryPhoto } from "../src/inventory-repair-photos.ts";
import { createInventoryRepairPhotoPage } from "../src/inventory-repair-photo-pages.ts";
const object = "33333333-3333-4333-8333-333333333333";
const unused = "44444444-4444-4444-8444-444444444444";
const photo = { objectId: object, filename: "原始损坏.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" };
const base = "/api/v1/mini/inventory-repair-evidence/9/observations/8/photos";
const url = `${base}/${object}/content?grant=owned.signature`;
const settle = () => new Promise(resolve => setImmediate(resolve));
function fixture() {
    const calls = [], discarded = [], navigations = [];
    let source = [{ taskId: "7", observationId: "8", photoIds: [object] }], removeFails = false;
    const sessions = { identity: { accountId: "1", bindingId: "2" },
        async request(method, path) {
            calls.push({ method, path });
            if (path === "/api/v1/mini/repairs/9") return { inventoryEvidence: source };
            if (path === base) return { items: [photo, { ...photo, objectId: unused }] };
            if (path === `${base}/${object}/download-grant`) return { url };
            throw Error("Unexpected route " + path);
        },
        async downloadInventoryPhoto() { return "/private/owned-photo.png"; },
        discardInventoryPhoto(path) { if (removeFails) throw Error("disk busy"); discarded.push(path); }
    };
    const services = { sessions, createKey: () => "grant-key", navigate: path => navigations.push(path) };
    const page = createInventoryRepairPhotoPage(() => services); page.data = structuredClone(page.data);
    page.setData = value => Object.assign(page.data, value);
    const open = async () => { page.onLoad({ repairId: "9", observationId: "8" }); await page.onShow(); };
    const read = () => page.onRead({ currentTarget: { dataset: { id: object } } });
    return { page, services, sessions, calls, discarded, navigations, open, read, removeFail(value) { removeFails = value; }, source(value) { source = value; } };
}
test("repair photo page shows only immutable source references and clears private bytes on hide", async () => {
    const f = fixture(); await f.open();
    assert.deepEqual(f.page.data.photos.map(row => row.objectId), [object]);
    await f.read(); assert.equal(f.page.data.photoPath, "/private/owned-photo.png");
    f.page.onHide(); assert.equal(f.page.data.photoPath, ""); assert.deepEqual(f.page.data.photos, []);
    assert.deepEqual(f.discarded, ["/private/owned-photo.png"]);
});
test("lost current repair source authority hides all previous references", async () => {
    const f = fixture(); await f.open(); await f.read(); f.source([]); await f.page.onRefresh();
    assert.deepEqual(f.page.data.photos, []); assert.equal(f.page.data.taskId, ""); assert.equal(f.page.data.photoPath, "");
    assert.match(f.page.data.error, /已隐藏/);
});
test("binding change after source grant prevents download and clears old route selection", async () => {
    const f = fixture(); await f.open(); let release, downloads = 0;
    f.sessions.request = () => new Promise(resolve => { release = resolve; });
    f.sessions.downloadInventoryPhoto = async () => { downloads++; return "/should-not-exist"; };
    const reading = f.read(); f.sessions.identity = { accountId: "1", bindingId: "3" }; release({ url }); await reading;
    assert.equal(downloads, 0); assert.equal(f.page.data.repairId, ""); assert.deepEqual(f.page.data.photos, []);
});
test("a late hidden-page download is deleted without hiding a newer authorized preview", async () => {
    const f = fixture(); await f.open(); let release, number = 0;
    f.sessions.downloadInventoryPhoto = async () => ++number === 1 ? new Promise(resolve => { release = resolve; }) : "/private/new.png";
    const first = f.read(); await settle(); f.page.onHide(); await f.page.onShow(); await f.read();
    assert.equal(f.page.data.photoPath, "/private/new.png"); release("/private/old.png"); await first;
    assert.equal(f.page.data.photoPath, "/private/new.png"); assert.deepEqual(f.discarded, ["/private/old.png"]);
    f.page.onUnload(); assert.deepEqual(f.discarded, ["/private/old.png", "/private/new.png"]);
});
test("failed local deletion retains a cleanup obligation before another read or navigation", async () => {
    const f = fixture(); await f.open(); await f.read(); f.removeFail(true); f.page.onHide();
    assert.equal(f.page.data.photoPath, ""); assert.equal(f.page.data.cleanupPending, true);
    await f.page.onShow(); assert.deepEqual(f.page.data.photos, []); f.page.onBack(); assert.equal(f.navigations.length, 0);
    f.removeFail(false); f.page.onClose(); assert.equal(f.page.data.cleanupPending, false);
    await f.page.onRefresh(); assert.equal(f.page.data.photos.length, 1); f.page.onUnload();
});
test("source-photo grant rejects PC foreign sibling and extra-query addresses", async () => {
    for (const bad of [url.replace("/mini/", "/"), "https://foreign.invalid" + url, url.replace("/9/", "/99/"), url + "&extra=1"]) {
        const f = fixture(); f.sessions.request = async () => ({ url: bad }); let downloaded = false;
        f.sessions.downloadInventoryPhoto = async () => { downloaded = true; return "/private/image"; };
        await assert.rejects(readRepairInventoryPhoto(f.sessions, "9", "8", photo, () => "key", () => true), /地址无效/);
        assert.equal(downloaded, false);
    }
});
test("current source-photo role denial cannot leave a visible preview", async () => {
    const f = fixture(); await f.open(); f.sessions.downloadInventoryPhoto = async () => { throw new MiniRequestError(403, "INVENTORY_REPAIR_EVIDENCE_FORBIDDEN"); };
    await f.read(); assert.equal(f.page.data.photoPath, ""); assert.deepEqual(f.page.data.photos, []); assert.match(f.page.data.error, /已隐藏/);
});
test("opaque image transport accepts only exact bounded inventory owner routes", async () => {
    const saved = { sessionToken: "a".repeat(43), accountId: "1", bindingId: "2" }, reads = [];
    const runtime = { readSession: () => saved, writeSession() {}, removeSession() {}, clearIdentityData() {},
        async request() { return { statusCode: 200, data: {} }; },
        async downloadInventoryPhoto(input) { reads.push(input); return { statusCode: 200, filePath: "/private/owned.png" }; }, discardInventoryPhoto() {} };
    const client = new MiniSessionClient("https://api.example.test", runtime);
    assert.equal(await client.downloadInventoryPhoto(url, "image/png"), "/private/owned.png");
    assert.deepEqual(reads[0].header, { "X-FAMS-Mini-Session": saved.sessionToken });
    for (const bad of [url.replace("/mini/", "/"), url + "&x=1", url.replace("/observations/8", "/observations/0"), url.replace("/9/", "/90071992547409930000/"), url.replace("inventory-repair-evidence", "repairs")]) await assert.rejects(client.downloadInventoryPhoto(bad, "image/png"));
    assert.equal(reads.length, 1);
});
