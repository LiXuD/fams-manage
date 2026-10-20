import test from "node:test";
import assert from "node:assert/strict";
import { createInventorySynchronizationPage } from "../src/inventory-synchronization-pages.ts";
import { MiniRequestError } from "../src/session.ts";
function fixture() {
    let events = [], statuses = [], failures = [], unreadable = false, number = 0;
    const calls = [], deviceId = "11111111-1111-4111-8111-111111111111";
    const sessions = { identity: { accountId: "1", bindingId: "2" }, async request(method, path, body, mutation) {
        calls.push(structuredClone({ method, path, body, mutation }));
        return method === "POST" ? { declarationOnly: true } : { taskVersion: 7, assignmentVersion: 2,
            observationWatermark: "9007199254740993", taskStatus: "IN_PROGRESS", device: { current: false, reason: "NO_DECLARATION" } };
    } };
    const services = { sessions, confirm: async () => true, createKey: () => `00000000-0000-4000-8000-${String(++number).padStart(12, "0")}`,
        inventory: () => ({ deviceId, queue: { list() { if (unreadable) throw new Error("Unreadable durable queue"); return events; },
            receipt: id => statuses.find(row => row.eventId === id)?.outcome ?? null, statuses: () => statuses, failures: () => failures } }) };
    const page = createInventorySynchronizationPage(() => services);
    page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); page.onLoad({ taskId: "9" });
    return { page, services, calls, sessions, setEvents: value => { events = value; }, setStatuses: value => { statuses = value; },
        setFailures: value => { failures = value; }, unreadable: () => { unreadable = true; }, deviceId };
}
const writes = f => f.calls.filter(row => row.method === "POST");
test("executor declaration submits the exact device, assignment and observation watermarks", async () => {
    const f = fixture(); await f.page.onShow(); await f.page.onDeclare();
    assert.equal(writes(f).length, 1); assert.equal(writes(f)[0].mutation.version, 7);
    assert.equal(writes(f)[0].body.deviceId, f.deviceId); assert.equal(writes(f)[0].body.assignmentVersion, 2);
    assert.equal(writes(f)[0].body.observationWatermark, "9007199254740993"); assert.equal(f.page.data.status, null);
});
for (const cleanup of [false, true]) test(`declaration refuses ${cleanup ? "unfinished photo cleanup" : "pending upload"}`, async () => {
    const f = fixture(); f.setEvents([{ eventId: "a", taskId: "9" }]); if (cleanup) f.setStatuses([{ eventId: "a", outcome: "ACCEPTED" }]);
    await f.page.onShow(); await f.page.onDeclare(); assert.equal(writes(f).length, 0); assert.match(f.page.data.error, /待上传|待清理/);
});
test("a queue event created during confirmation prevents any declaration", async () => {
    const f = fixture(); await f.page.onShow(); f.services.confirm = async () => { f.setEvents([{ eventId: "new", taskId: "9" }]); return true; };
    await f.page.onDeclare(); assert.equal(writes(f).length, 0); assert.match(f.page.data.error, /队列已变化/);
});
test("unknown committed response retains the original request across hide, reauthorization and retry", async () => {
    const f = fixture(); const request = f.sessions.request; let lost = true;
    f.sessions.request = async (...args) => { const value = await request(...args); if (args[0] === "POST" && lost) { lost = false; throw new Error("lost actual reply"); } return value; };
    await f.page.onShow(); await f.page.onDeclare(); assert.equal(f.page.data.retry, true);
    f.page.onHide(); await f.page.onShow(); await f.page.onRetry(); assert.equal(writes(f).length, 2);
    assert.deepEqual(writes(f)[0], writes(f)[1]); assert.equal(f.page.data.retry, false);
});
test("identity replacement during confirmation submits nothing", async () => {
    const f = fixture(); await f.page.onShow(); f.services.confirm = async () => { f.sessions.identity = { accountId: "3", bindingId: "4" }; return true; };
    await f.page.onDeclare(); assert.equal(writes(f).length, 0);
});
test("unreadable durable storage blocks declarations", async () => {
    const f = fixture(); await f.page.onShow(); f.unreadable(); await f.page.onDeclare();
    assert.equal(writes(f).length, 0); assert.equal(f.page.data.busy, false); assert.match(f.page.data.error, /Unreadable/);
});
test("a current authorization denial clears pending operation and status", async () => {
    const f = fixture(); await f.page.onShow(); f.sessions.request = async () => { throw new MiniRequestError(403, "INVENTORY_PARTICIPATION_REQUIRED"); };
    await f.page.onDeclare(); assert.equal(f.page.data.retry, false); assert.equal(f.page.data.status, null);
});
test("failure evidence is declared explicitly and cannot become an empty success", async () => {
    const f = fixture(); f.setFailures([{ eventId: "missing", taskId: "9", deviceId: f.deviceId, code: "EXPIRED_RECOLLECT" }]);
    await f.page.onShow(); await f.page.onDeclare(); assert.equal(writes(f)[0].body.unresolvedCount, 1);
    assert.match(writes(f)[0].body.queueSnapshot, /EXPIRED_RECOLLECT/);
});
test("an observation uploaded while response is in flight invalidates the local success display", async () => {
    const f = fixture(), request = f.sessions.request;
    f.sessions.request = async (...args) => { const result = await request(...args); if (args[0] === "POST") f.setStatuses([{ eventId: "new", outcome: "ACCEPTED" }]); return result; };
    await f.page.onShow(); await f.page.onDeclare(); assert.match(f.page.data.notice, /队列已变化/); assert.equal(f.page.data.status, null);
});
test("an unknown reply cannot resend an old empty-queue assertion after new local evidence", async () => {
    const f = fixture(), request = f.sessions.request;
    f.sessions.request = async (...args) => { const result = await request(...args); if (args[0] === "POST") throw new Error("lost reply"); return result; };
    await f.page.onShow(); await f.page.onDeclare(); f.setEvents([{ eventId: "later", taskId: "9" }]);
    await f.page.onRetry(); assert.equal(writes(f).length, 1); assert.equal(f.page.data.status, null); assert.match(f.page.data.error, /不能重发/);
});
test("an unknown reply cannot be resent when durable storage becomes unreadable", async () => {
    const f = fixture(), request = f.sessions.request;
    f.sessions.request = async (...args) => { const result = await request(...args); if (args[0] === "POST") throw new Error("lost reply"); return result; };
    await f.page.onShow(); await f.page.onDeclare(); f.unreadable(); await f.page.onRetry(); assert.equal(writes(f).length, 1);
});
