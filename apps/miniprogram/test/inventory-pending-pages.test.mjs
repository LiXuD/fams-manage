import assert from "node:assert/strict";
import test from "node:test";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { InventoryQueue } from "../src/inventory-queue.ts";
import { createInventoryFiles } from "../src/inventory-files.ts";
import { createInventoryPendingPage } from "../src/inventory-pending-pages.ts";
import { createMinePage } from "../src/pages.ts";
const device = "00000000-0000-4000-8000-000000000001";
const eventId = "00000000-0000-4000-8000-000000000002";
const photoId = "00000000-0000-4000-8000-000000000003";
function mount(page) { page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); return page; }
async function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "fams-inventory-pending-"));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const values = new Map(); let now = Date.parse("2026-10-18T01:00:00Z");
    const wx = { env: { USER_DATA_PATH: root }, getFileSystemManager: () => fs,
        getStorageSync: key => values.get(key), setStorageSync: (key, value) => values.set(key, structuredClone(value)),
        removeStorageSync: key => values.delete(key) };
    const owner = { accountId: "9007199254740993", bindingId: "2" };
    const files = createInventoryFiles(wx, owner);
    const restore = () => new InventoryQueue(owner, device, files.storage, { now: () => now });
    const queue = restore();
    queue.cache({ id: "9", name: "Private task", task_status: "IN_PROGRESS", items: [] });
    const temporary = path.join(root, "temporary.png"); fs.writeFileSync(temporary, "picture");
    const photo = files.persist({ path: temporary, name: "photo.png", size: 7 }, eventId, photoId);
    await queue.save({ eventId, taskId: "9", itemId: "10", assignmentVersion: 1, recheckGeneration: 0,
        assetVersion: 0, source: "SCAN", assetCode: "secret-code", manualReason: null,
        actual: { description: "private-fact", condition: "GOOD" },
        rawObservedAt: new Date(now).toISOString(), calibrationId: null, photos: [photo] });
    fs.unlinkSync(temporary);
    const navigations = []; let logoutCount = 0, unbindCount = 0;
    const services = { sessions: { identity: owner, async request() { throw new Error("offline"); },
        async logout() { logoutCount++; files.clear(); this.identity = null; } },
        inventory: () => ({ queue: restore(), files, deviceId: device }), navigate: url => navigations.push(url),
        confirm: async () => true, createKey: () => "unbind-stable-key-0001",
        binding: { async unbind() { unbindCount++; files.clear(); services.sessions.identity = null; } } };
    return { services, files, queue, restore, navigations, photo, values, setNow: value => { now = value; },
        counts: () => ({ logoutCount, unbindCount }) };
}

test("pending entry reconstructs real persistent photos offline and exposes only minimal summary", async t => {
    const f = await fixture(t); const page = mount(createInventoryPendingPage(() => f.services));
    await page.onShow();
    assert.equal(page.data.tasks[0].pending, 1);
    assert.equal(page.data.tasks[0].name, "Private task");
    assert.equal(fs.readFileSync(f.photo.path, "utf8"), "picture");
    const display = JSON.stringify(page.data);
    for (const secret of ["secret-code", "private-fact", f.photo.path]) assert.equal(display.includes(secret), false);
    page.onOpen({ currentTarget: { dataset: { id: "9" } } });
    assert.deepEqual(f.navigations, ["/pages/inventory-collection/index?taskId=9"]);
    page.onHide(); assert.deepEqual(page.data.tasks, []);
    f.services.sessions.identity = { accountId: "3", bindingId: "4" };
    page.onOpen({ currentTarget: { dataset: { id: "9" } } });
    assert.equal(f.navigations.length, 1);
});

test("pending refresh clears expired real files and retains minimal failure instead of uploaded receipt", async t => {
    const f = await fixture(t); f.setNow(Date.parse("2026-10-21T01:00:00Z"));
    const page = mount(createInventoryPendingPage(() => f.services)); await page.onShow();
    assert.equal(fs.existsSync(f.photo.path), false);
    assert.equal(page.data.tasks[0].pending, 0);
    assert.equal(page.data.tasks[0].statuses[0].outcome, "EXPIRED_RECOLLECT");
    assert.match(page.data.tasks[0].statuses[0].message, /本机已到期/);
    assert.equal(f.restore().receipt(eventId), null);
});

test("confirmed cleanup differs from unreceived events and revoked summaries reveal no task", async t => {
    const f = await fixture(t); const failing = { ...f.files.storage, async removePhoto() { throw new Error("disk busy"); } };
    const queue = new InventoryQueue(f.services.sessions.identity, device, failing, { now: () => Date.parse("2026-10-18T01:00:00Z") });
    await assert.rejects(queue.acknowledge(eventId, "ACCEPTED"), /disk busy/);
    assert.equal(queue.summaries()[0].pending, 0); assert.equal(queue.summaries()[0].cleanup, 1);
    await assert.rejects(queue.rejectTask("9"), /disk busy/);
    assert.deepEqual(queue.summaries(), []);
    assert.equal(queue.cached("9"), null);
});

test("pending page hides late old identity after expiry cleanup completion", async t => {
    const f = await fixture(t); let finish;
    const queue = { expire: () => new Promise(resolve => { finish = resolve; }), summaries: () => [{ taskId: "9", name: "private" }] };
    f.services.inventory = () => ({ queue });
    const page = mount(createInventoryPendingPage(() => f.services)); const showing = page.onShow();
    f.services.sessions.identity = { accountId: "3", bindingId: "4" }; finish(); await showing;
    assert.deepEqual(page.data.tasks, []); assert.match(page.data.error, /身份已变化/);
});

test("mine shows offline pending count and cancel preserves actual local evidence", async t => {
    const f = await fixture(t); const confirmations = [];
    f.services.confirm = async (_title, text) => { confirmations.push(text); return false; };
    const page = mount(createMinePage(() => f.services)); await page.onShow();
    assert.equal(page.data.pendingCount, 1);
    await page.onLogout(); await page.onUnbind();
    assert.deepEqual(f.counts(), { logoutCount: 0, unbindCount: 0 });
    assert.ok(confirmations.every(value => value.includes("1 条")));
    assert.equal(fs.existsSync(f.photo.path), true); assert.equal(f.restore().list().length, 1);
    f.services.confirm = async () => true; await page.onLogout();
    assert.equal(f.counts().logoutCount, 1); assert.equal(f.services.sessions.identity, null);
    assert.equal(fs.existsSync(f.photo.path), false); assert.equal(f.values.size, 0);
    assert.deepEqual(f.navigations, ["/pages/login/index"]);
});

test("old logout and unbind confirmations cannot clear a new identity", async t => {
    for (const action of ["onLogout", "onUnbind"]) {
        const f = await fixture(t); let finish;
        f.services.confirm = () => new Promise(resolve => { finish = resolve; });
        const page = mount(createMinePage(() => f.services)); await page.onShow(); const pending = page[action]();
        f.services.sessions.identity = { accountId: "3", bindingId: "4" }; finish(true); await pending;
        assert.deepEqual(f.counts(), { logoutCount: 0, unbindCount: 0 });
        assert.equal(f.services.sessions.identity.accountId, "3"); assert.equal(fs.existsSync(f.photo.path), true);
        assert.equal(page.data.accountId, ""); assert.equal(page.data.pendingCount, 0);
    }
});
