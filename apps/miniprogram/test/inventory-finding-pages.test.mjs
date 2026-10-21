import test from "node:test";
import assert from "node:assert/strict";
import { createInventoryFindingPage } from "../src/inventory-finding-pages.ts";
import { MiniRequestError } from "../src/session.ts";
const object = "33333333-3333-4333-8333-333333333333";
const raw = { id: "8", client_event_id: "22222222-2222-4222-8222-222222222222", outcome: "FINDING_REVIEW", facts_json: { actual: { description: "Private finding" }, photoIds: [object] } };
const event = (id, field) => ({ currentTarget: { dataset: { id, field } } });
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
function fixture() {
    const calls = [], discarded = []; let key = 0;
    const detail = { taskVersion: 7, observation: raw, subject: { version: 0, subject_status: "OPEN" }, history: [], historyTotal: 0 };
    const sessions = { identity: { accountId: "1", bindingId: "2" },
        async request(method, path, body, mutation) {
            calls.push({ method, path, body, mutation });
            if (method === "POST") return { status: "CLOSED" };
            if (path.includes("finding-targets?")) return { items: [{ kind: "FORMAL_INBOUND", assetId: "9007199254740995", assetCode: "A-1", inboundReceiptId: "9007199254740993", receiptNumber: "RK-1", ownerVersion: 4 }] };
            if (path.includes("/photos")) return { items: [{ objectId: object, filename: "现场.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" }] };
            return path.endsWith("/findings/8") ? detail : { items: [raw], total: 1 };
        }, discardInventoryPhoto: path => discarded.push(path), downloadInventoryPhoto: async () => "/owned/photo.png" };
    const services = { sessions, createKey: () => `original-request-key-${++key}`, confirm: async () => true, navigate() {} };
    const page = createInventoryFindingPage(() => services);
    page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
    page.onLoad({ taskId: "9" });
    return { page, sessions, services, calls, discarded, detail };
}
async function open(f) { await f.page.onShow(); await f.page.onOpen(event("8")); }
function reasons(f) { f.page.onField(field("reason", "现场核对权属及正式单据")); f.page.onField(field("evidence", "照片及设备特征与正式单据核对一致，保留原始线索")); }
test("finding unknown reply retains its exact UUID body and version across same-page hide and authorized refresh", async () => {
    const f = fixture(); await open(f); reasons(f);
    const request = f.sessions.request; let failed = false;
    f.sessions.request = async (...args) => { const value = await request(...args); if (args[0] === "POST" && !failed) { failed = true; throw new Error("lost actual reply"); } return value; };
    await f.page.onSave(); assert.equal(f.page.data.retryable, true);
    f.page.onHide(); assert.equal(f.page.data.detail, null); await f.page.onShow(); await f.page.onRetry();
    const writes = f.calls.filter(row => row.method === "POST"); assert.equal(writes.length, 2); assert.deepEqual(writes[0], writes[1]);
    assert.equal(writes[0].body.method, "NON_ENTERPRISE"); assert.equal(writes[0].mutation.version, 7); assert.equal(f.page.data.retryable, false); f.page.onUnload();
});
test("an identity change while confirmation is pending transmits no decision", async () => {
    const f = fixture(); await open(f); reasons(f); let finish;
    f.services.confirm = () => new Promise(resolve => { finish = resolve; });
    const saving = f.page.onSave(); f.sessions.identity = { accountId: "3", bindingId: "4" }; finish(true); await saving;
    assert.equal(f.calls.filter(row => row.method === "POST").length, 0); assert.equal(f.page.data.detail, null); f.page.onUnload();
});
test("formal owner search retains string IDs and versions without creating or submitting an inbound document", async () => {
    const f = fixture(); await open(f); reasons(f); f.page.onMethod({ detail: { value: "2" } });
    f.page.onField(field("assetCode", "A-1")); f.page.onField(field("receiptNumber", "RK-1")); await f.page.onSearch();
    f.page.onTarget(event(f.page.data.targets[0].key)); await f.page.onSave();
    const write = f.calls.find(row => row.method === "POST");
    assert.equal(write.path, "/api/v1/mini/inventory-reviews/tasks/9/findings/8/decisions");
    assert.deepEqual({ assetId: write.body.assetId, inboundReceiptId: write.body.inboundReceiptId, ownerVersion: write.body.ownerVersion },
        { assetId: "9007199254740995", inboundReceiptId: "9007199254740993", ownerVersion: 4 }); f.page.onUnload();
});
test("a current role denial clears finding facts and pending decisions before further retries", async () => {
    const f = fixture(); await open(f); reasons(f);
    f.sessions.request = async () => { throw new MiniRequestError(403, "INVENTORY_REVIEW_ADMIN_REQUIRED"); };
    await f.page.onRefresh(); assert.equal(f.page.data.detail, null); assert.deepEqual(f.page.data.rows, []); await f.page.onRetry(); assert.equal(f.page.data.retryable, false); f.page.onUnload();
});
test("a scoped photo preview is removed on hide while the original reference remains server-owned", async () => {
    const f = fixture(); await open(f); const request = f.sessions.request;
    f.sessions.request = async (...args) => args[1].endsWith("download-grant")
        ? { url: `/api/v1/mini/inventory-observations/tasks/9/events/${raw.client_event_id}/photos/${object}/content?grant=owned` } : request(...args);
    await f.page.onPhotos(); await f.page.onReadPhoto(event(object)); assert.equal(f.page.data.photoPath, "/owned/photo.png");
    f.page.onHide(); assert.equal(f.page.data.photoPath, ""); assert.deepEqual(f.discarded, ["/owned/photo.png"]); assert.deepEqual(raw.facts_json.photoIds, [object]); f.page.onUnload();
});

test("a rejected confirmation releases busy state and writes no finding decision", async () => {
    const f = fixture(); await open(f); reasons(f); f.services.confirm = async () => { throw new Error("owned confirm failure"); };
    await f.page.onSave(); assert.equal(f.page.data.busy, false); assert.equal(f.calls.filter(row => row.method === "POST").length, 0); f.page.onUnload();
});


test("time recollection retains exact opaque operation through lost reply, hide and reread", async () => {
    const f = fixture(); f.detail.observation = { ...raw, outcome: "TIME_REVIEW" }; f.detail.timeSubject = [{ version: 0, subject_status: "OPEN" }];
    const request = f.sessions.request; let lost = true;
    f.sessions.request = async (...args) => {
        if (args[1].includes("recollection-targets")) return { items: [{ ...raw, id: "9007199254740993" }], total: 1 };
        const result = await request(...args);
        if (args[0] === "POST" && lost) { lost = false; throw new Error("lost committed reply"); } return result;
    };
    await open(f); reasons(f); await f.page.onRecollectionTargets(); f.page.onCounter(event("9007199254740993")); await f.page.onTimeSave();
    assert.equal(f.page.data.retryable, true); f.page.onHide(); await f.page.onShow(); await f.page.onRetry();
    const writes = f.calls.filter(row => row.method === "POST"); assert.equal(writes.length, 2); assert.deepEqual(writes[0], writes[1]);
    assert.equal(writes[0].path, "/api/v1/mini/inventory-reviews/tasks/9/findings/8/time-decisions");
    assert.equal(writes[0].body.counterObservationId, "9007199254740993"); assert.equal(writes[0].body.subjectVersion, 0); f.page.onUnload();
});
test("time recollection never submits if current identity changes during confirmation", async () => {
    const f = fixture(); f.detail.observation = { ...raw, outcome: "TIME_REVIEW" }; f.detail.timeSubject = [{ version: 0, subject_status: "OPEN" }];
    await open(f); reasons(f); f.page.data.counters = [{ ...raw, id: "10" }]; f.page.onCounter(event("10")); let finish;
    f.services.confirm = () => new Promise(resolve => { finish = resolve; });
    const saving = f.page.onTimeSave(); f.sessions.identity = { accountId: "3", bindingId: "4" }; finish(true); await saving;
    assert.equal(f.calls.filter(row => row.method === "POST").length, 0); assert.equal(f.page.data.detail, null); f.page.onUnload();
});


test("late recollection candidate lookup after a binding change clears old private findings", async () => {
    const f = fixture(); f.detail.observation = { ...raw, outcome: "TIME_REVIEW" }; f.detail.timeSubject = [{ version: 0, subject_status: "OPEN" }];
    await open(f); let finish; f.sessions.request = () => new Promise(resolve => { finish = resolve; });
    const reading = f.page.onRecollectionTargets(); f.sessions.identity = { accountId: "3", bindingId: "4" };
    finish({ items: [{ ...raw, id: "10" }], total: 1 }); await reading;
    assert.equal(f.page.data.detail, null); assert.deepEqual(f.page.data.counters, []); assert.equal(f.page.data.busy, false); f.page.onUnload();
});
