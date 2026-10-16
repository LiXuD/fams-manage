import assert from "node:assert/strict";
import test from "node:test";
import { InventoryQueue } from "../src/inventory-queue.ts";
const device = "11111111-1111-4111-8111-111111111111";
const eventId = "22222222-2222-4222-8222-222222222222";
const identity = { accountId: "9007199254740993", bindingId: "2" };
function fixture() {
    let now = Date.parse("2026-10-14T00:00:00.000Z"); let failWrite = false;
    const disk = new Map(); const photos = new Set(["owned/photo.jpg"]);
    const storage = { read: key => structuredClone(disk.get(key)),
        write: (key, value) => { if (failWrite) throw new Error("Storage full"); disk.set(key, structuredClone(value)); },
        photoExists: async path => photos.has(path), removePhoto: async path => { photos.delete(path); } };
    const clock = { now: () => now };
    const create = (who = identity) => new InventoryQueue(who, device, storage, clock);
    const input = { eventId, taskId: "9", itemId: "3", assignmentVersion: 1, recheckGeneration: 0,
        assetVersion: 0, source: "SCAN", assetCode: "A-1", manualReason: null,
        actual: { locationId: "4", locationHint: null, custodyType: "STORAGE", responsibleEmployeeId: null,
            responsibilityHint: null, condition: "GOOD", unknownReason: null, description: null },
        rawObservedAt: new Date(now).toISOString(), calibrationId: "7", photos: [{ path: "owned/photo.jpg", name: "photo.jpg", size: 10 }] };
    return { disk, photos, create, input, setNow: value => { now = value; }, fail: () => { failWrite = true; } };
}
test("T-17-01 process reconstruction and original retry preserve event ID and exact expiry", async () => {
    const f = fixture(); const first = await f.create().save(f.input);
    f.setNow(Date.parse(first.firstPersistedAt) + 71 * 3600000);
    const afterCrash = f.create(); assert.deepEqual(await afterCrash.uploadable(eventId), first);
    assert.equal(Date.parse(first.expiresAt) - Date.parse(first.firstPersistedAt), 72 * 3600000);
    await assert.rejects(afterCrash.save(f.input), /duplicate/);
    assert.deepEqual(afterCrash.list(), [first]);
});
test("failed durable save and missing photo never return a saved event", async () => {
    const f = fixture(); f.fail(); await assert.rejects(f.create().save(f.input), /Storage full/);
    assert.deepEqual(f.create().list(), []);
    const g = fixture(); g.photos.clear(); await assert.rejects(g.create().save(g.input), /not durable/);
    assert.deepEqual(g.create().list(), []);
});
test("clock rollback keeps original retries stable and marks only new observations anomalous", async () => {
    const f = fixture(); const saved = await f.create().save(f.input);
    f.setNow(Date.parse(saved.firstPersistedAt) - 10000);
    assert.deepEqual(await f.create().uploadable(eventId), saved);
    assert.deepEqual(f.create().list(), [saved]);
    const newEvent = await f.create().save({ ...f.input, eventId: "33333333-3333-4333-8333-333333333333" });
    assert.equal(newEvent.clockAnomaly, true);
});
test("exact deadline clears own sensitive event/photo and retains only minimal failure", async () => {
    const f = fixture(); const saved = await f.create().save(f.input);
    f.setNow(Date.parse(saved.expiresAt) - 1); await f.create().expire(); assert.equal(f.create().list().length, 1);
    f.setNow(Date.parse(saved.expiresAt)); await f.create().expire(); assert.deepEqual(f.create().list(), []);
    assert.deepEqual(f.create().failures(), [{ eventId, code: "EXPIRED_RECOLLECT", taskId: "9", deviceId: device }]);
    assert.equal(f.photos.size, 0);
});
test("binding namespace isolation and explicit receipt cleanup", async () => {
    const f = fixture(); await f.create().save(f.input);
    const other = f.create({ accountId: identity.accountId, bindingId: "3" });
    assert.deepEqual(other.list(), []); await other.acknowledge(eventId, "ACCEPTED");
    assert.equal(f.create().list().length, 1);
    await assert.rejects(f.create().acknowledge(eventId, "TRANSPORT_OK"), /Explicit receipt/);
    assert.equal(f.create().list().length, 1);
    await f.create().acknowledge(eventId, "CONFLICT_REVIEW"); assert.deepEqual(f.create().list(), []);
});
test("photo lost after process restart blocks upload and retains evidence-loss recovery reference", async () => {
    const f = fixture(); const saved = await f.create().save(f.input); f.photos.clear();
    await assert.rejects(f.create().uploadable(eventId), /evidence lost/);
    assert.deepEqual(f.create().list(), [saved]);
});
test("durable remote photo receipt permits exact original retry after only local bytes disappear", async () => {
    const f = fixture(); const saved = await f.create().save(f.input);
    f.create().recordPhotoReceipt(eventId, saved.photos[0].path, "44444444-4444-4444-8444-444444444444");
    f.photos.clear(); assert.deepEqual(await f.create().uploadable(eventId), saved);
});
test("revocation survives cleanup failure and process reconstruction before any cached view or upload", async () => {
    const f = fixture(); const queue = f.create(); await queue.save(f.input);
    queue.cache({ id: "9", name: "Private task", task_status: "IN_PROGRESS", items: [] });
    const key = `fams.identity.${identity.accountId}.${identity.bindingId}`;
    const saved = f.disk.get(key); saved.events[0].photos[0].path = "missing-cleanup-permission";
    f.disk.set(key, saved);
    // Persisted safety tombstone is committed before any fallible file cleanup.
    const storage = { read: k => f.disk.get(k), write: (k, value) => f.disk.set(k, structuredClone(value)),
        photoExists: async () => true, removePhoto: async () => { throw new Error("Cleanup denied"); } };
    const revoked = new InventoryQueue(identity, device, storage, { now: () => Date.now() });
    await assert.rejects(revoked.rejectTask("9"), /Cleanup denied/);
    const restarted = new InventoryQueue(identity, device, storage, { now: () => Date.now() });
    assert.equal(restarted.cached("9"), null);
    await assert.rejects(restarted.uploadable(eventId), /authorization revoked/);
    await assert.rejects(restarted.save({ ...f.input, eventId: "33333333-3333-4333-8333-333333333333" }), /authorization revoked/);
});
test("minimal original receipt survives cleanup failure without storing observation facts in history", async () => {
    const f = fixture(); await f.create().save(f.input);
    let blocked = true;
    const storage = { read: key => f.disk.get(key), write: (key, value) => f.disk.set(key, structuredClone(value)),
        photoExists: async () => true, removePhoto: async () => { if (blocked) throw new Error("Cleanup denied"); } };
    const create = () => new InventoryQueue(identity, device, storage, { now: () => Date.now() });
    await assert.rejects(create().acknowledge(eventId, "LATE_REVIEW"), /Cleanup denied/);
    assert.equal(create().receipt(eventId), "LATE_REVIEW");
    assert.deepEqual(create().statuses("9"), [{ eventId, outcome: "LATE_REVIEW" }]);
    await assert.rejects(create().acknowledge(eventId, "ACCEPTED"), /Original receipt changed/);
    blocked = false; await create().acknowledge(eventId, "LATE_REVIEW");
    assert.deepEqual(create().list(), []);
    const history = f.disk.get(`fams.identity.${identity.accountId}.${identity.bindingId}`).receipts;
    assert.deepEqual(Object.keys(history[0]).sort(), ["deviceId", "eventId", "outcome", "taskId"]);
});
