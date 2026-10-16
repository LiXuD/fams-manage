import assert from "node:assert/strict";
import test from "node:test";
import { createInventoryCollectionPage } from "../src/inventory-collection-pages.ts";
import { InventoryQueue } from "../src/inventory-queue.ts";
import { MiniRequestError } from "../src/session.ts";
const device = "11111111-1111-4111-8111-111111111111";
const calibrationId = "44444444-4444-4444-8444-444444444444";
function fixture() {
    const disk = new Map(); let serial = 1; let loseReply = false; const uploads = [];
    const storage = { read: key => disk.get(key), write: (key, value) => disk.set(key, structuredClone(value)), photoExists: async () => true, removePhoto: async () => {} };
    const queue = new InventoryQueue({ accountId: "1", bindingId: "2" }, device, storage, { now: () => Date.now() });
    const item = { id: "9007199254740993", assignment_version: 1, recheck_generation: 0, asset_version: 0,
        executor_account_id: "1", snapshot_json: { assetCode: "A-1", name: "Desk", locationId: "4", locationName: "Room A", custodyType: "STORAGE" } };
    const services = { sessions: { identity: { accountId: "1", bindingId: "2" }, request: async (method, path, body) => {
        if (method === "GET") return { task: { id: "9", name: "Inventory", task_status: "IN_PROGRESS", version: 3, assignment_version: 1 }, items: [item], total: 1 };
        if (path.endsWith("/calibrations")) return { calibrationId, clientTime: body.clientTime, serverTime: body.clientTime };
        uploads.push(structuredClone(body)); if (loseReply) { loseReply = false; throw new Error("Reply lost after commit"); }
        return { eventId: body.eventId, outcome: "ACCEPTED" };
    } }, inventory: () => ({ queue, files: { storage, prune: () => 0 }, deviceId: device }), createKey: () => `${String(serial++).padStart(8,"0")}-2222-4222-8222-222222222222`,
        confirm: async () => true, navigate() {} };
    const create = () => { const page = createInventoryCollectionPage(() => services); page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); page.onLoad({ taskId: "9" }); return page; };
    return { create, queue, services, uploads, lose: () => { loseReply = true; } };
}
const input = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value } });
async function record(f) {
    const page = f.create(); await page.onShow(); await page.onRoom({ detail: { value: "0" } });
    page.onInput(input("query", "Desk")); page.onInput(input("manualReason", "Label damaged"));
    await page.onSearch(); page.onSelect({ currentTarget: { dataset: { id: "9007199254740993" } } });
    await page.onSave({ currentTarget: { dataset: { field: "normal" } } }); return page;
}
test("actual handlers require field confirmation, durably save, and retry original event after reconstruction", async () => {
    const f = fixture(); const page = await record(f);
    assert.equal(page.data.pending.length, 1); assert.equal(f.queue.list().length, 1);
    const original = f.queue.list()[0]; f.lose(); await page.onSync(); assert.equal(f.queue.list().length, 1);
    page.onUnload(); const recreated = f.create(); await recreated.onShow(); await recreated.onSync();
    assert.equal(f.uploads[0].eventId, original.eventId); assert.deepEqual(f.uploads[1], f.uploads[0]);
    assert.deepEqual(f.queue.list(), []); assert.match(recreated.data.message, /待管理员复核/);
    assert.match(recreated.data.receipts[0].message, /待管理员复核/);
});
test("task known ended stops new collection while keeping pending observations uploadable", async () => {
    const f = fixture(); const page = await record(f); const original = f.queue.list()[0];
    page.data.task.task_status = "COMPLETED"; await page.onSave({ currentTarget: { dataset: { field: "normal" } } });
    assert.match(page.data.error, /已结束/); assert.deepEqual(f.queue.list(), [original]);
});
test("unconfirmed room and manual reason cannot be silently copied from book values", async () => {
    const f = fixture(); const page = f.create(); await page.onShow(); page.onInput(input("query", "Desk")); await page.onSearch();
    page.onSelect({ currentTarget: { dataset: { id: "9007199254740993" } } });
    await page.onSave({ currentTarget: { dataset: { field: "normal" } } }); assert.equal(f.queue.list().length, 0);
    await page.onRoom({ detail: { value: "0" } }); await page.onSave({ currentTarget: { dataset: { field: "normal" } } });
    assert.match(page.data.error, /手工原因/); assert.equal(f.queue.list().length, 0);
});
test("binding change during upload does not clear new identity's queue or render old receipt", async () => {
    const f = fixture(); const page = await record(f); let release;
    f.services.sessions.request = async () => new Promise(resolve => { release = resolve; });
    const syncing = page.onSync(); await new Promise(resolve => setImmediate(resolve));
    page.onHide(); f.services.sessions.identity = { accountId: "3", bindingId: "4" };
    release({ eventId: f.queue.list()[0].eventId, outcome: "ACCEPTED" }); await syncing;
    assert.equal(f.queue.list().length, 1); assert.doesNotMatch(page.data.message, /待管理员复核/);
});
test("foreground 401 identity invalidation immediately hides previous task and pending details", async () => {
    const f = fixture(); const page = await record(f);
    f.services.sessions.request = async () => { f.services.sessions.identity = null; throw new MiniRequestError(401, "AUTH_SESSION_INVALID"); };
    await page.onSync(); assert.equal(page.data.task, null); assert.equal(page.data.selected, null); assert.deepEqual(page.data.pending, []);
    assert.match(page.data.error, /已隐藏原任务/); assert.equal(page.data.busy, false);
});
test("manual unknown physical object retains its actual source and never invents a registered item", async () => {
    const f = fixture(); const page = f.create(); await page.onShow(); await page.onRoom({ detail: { value: "0" } });
    page.onManual(); page.onMode({ detail: { value: "0" } }); page.onCondition({ detail: { value: "0" } });
    page.onInput(input("manualReason", "No readable registered label")); page.onInput(input("description", "Object awaiting verification"));
    page.data.photos = [{ path: "owned/scene.png", name: "scene.png", size: 10 }];
    await page.onSave({ currentTarget: { dataset: { field: "difference" } } });
    const saved = f.queue.list()[0]; assert.ok(saved, page.data.error); assert.equal(saved.source, "MANUAL");
    assert.equal(saved.itemId, null); assert.equal(saved.rawScannedAt, null);
});
test("scan completion time is retained separately from later field confirmation and persistence", async () => {
    const f = fixture(); const original = f.services.sessions.request;
    f.services.sessions.request = async (method, path, body) => path.endsWith("/identification")
        ? { kind: "ASSIGNED", itemId: "9007199254740993" } : original(method, path, body);
    f.services.scan = async () => "A".repeat(43);
    const page = f.create(); await page.onShow(); await page.onRoom({ detail: { value: "0" } }); await page.onScan();
    await new Promise(resolve => setTimeout(resolve, 5)); await page.onSave({ currentTarget: { dataset: { field: "normal" } } });
    const saved = f.queue.list()[0]; assert.ok(saved, page.data.error); assert.equal(saved.source, "SCAN");
    assert.ok(Date.parse(saved.rawScannedAt) < Date.parse(saved.rawObservedAt));
    assert.ok(Date.parse(saved.firstPersistedAt) >= Date.parse(saved.rawObservedAt));
});
test("observation counter changes do not block coherent paged snapshots but reassignment does", async () => {
    for (const reassign of [false, true]) {
        const f = fixture(); const original = f.services.sessions.request;
        f.services.sessions.request = async (method, path, body) => {
            if (method !== "GET") return original(method, path, body);
            const second = path.includes("page=2&");
            return { task: { id: "9", name: "Large task", task_status: "IN_PROGRESS", version: second ? 4 : 3,
                assignment_version: second && reassign ? 2 : 1 }, total: 101,
                items: Array.from({ length: second ? 1 : 100 }, (_, i) => ({ id: String((second ? 100 : 0) + i + 1),
                    executor_account_id: "1", assignment_version: 1, recheck_generation: 0, asset_version: 0,
                    snapshot_json: { assetCode: `A-${i}`, locationId: "4", locationName: "Room A", custodyType: "STORAGE" } })) };
        };
        const page = f.create(); await page.onShow();
        if (reassign) { assert.equal(f.queue.cached("9"), null); assert.equal(page.data.task, null); }
        else assert.equal(f.queue.cached("9").items.length, 101);
    }
});
test("a scanner callback after hiding the page cannot issue an old-task request", async () => {
    const f = fixture(); const original = f.services.sessions.request; let release; let lookups = 0;
    f.services.scan = async () => new Promise(resolve => { release = resolve; });
    f.services.sessions.request = async (method, path, body) => { if (path.endsWith("/identification")) lookups++; return original(method, path, body); };
    const page = f.create(); await page.onShow(); const scan = page.onScan(); page.onHide();
    release("A".repeat(43)); await scan; assert.equal(lookups, 0); assert.equal(f.queue.list().length, 0);
});
test("unreadable local photo preview does not claim a saved observation", async () => {
    const f = fixture(); f.services.openEvidence = async () => { throw new Error("Missing file"); };
    const page = f.create(); await page.onShow(); page.data.photos = [{ path: "owned/missing.png", name: "scene.png", size: 1 }];
    await page.onPreviewPhoto({ currentTarget: { dataset: { id: "owned/missing.png" } } });
    assert.match(page.data.error, /可能已丢失/); assert.equal(f.queue.list().length, 0);
});
