import assert from "node:assert/strict";
import test from "node:test";
import { createTransferReassignmentPage } from "../src/transfer-reassignment-pages.ts";
const snapshot = { id: "9", version: 4, ownershipVersion: 1, processorAccountId: "2",
    operators: [{ id: "3", name: "Successor" }], items: [{ id: "7", version: 2, itemStatus: "DELIVERED", lockVersion: 0 }] };
function fixture(request) {
    const services = { sessions: { identity: { accountId: "1", bindingId: "1" }, request },
        createKey: () => "reassignment-key-000001", navigate() {}, confirm: async () => true };
    const page = createTransferReassignmentPage(() => services);
    page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
    return { page, services };
}
async function prepare(page) {
    page.onShow(); page.onRequest({ detail: { value: "9" } }); await page.onInspect();
    page.onTarget({ detail: { value: "0" } }); page.onReason({ detail: { value: "Shift change" } });
    page.onEvidence({ detail: { value: "Signed handover record" } }); page.onCheck({ detail: { value: ["checked"] } });
}
test("lost reply retries exact ownership snapshot and prevents form mutation", async () => {
    const writes = []; let lost = true;
    const { page } = fixture(async (method, path, body, options) => {
        if (method === "GET") return structuredClone(snapshot);
        writes.push(structuredClone({ path, body, key: options.key, version: options.version }));
        if (lost) { lost = false; throw Error("lost after commit"); } return {};
    });
    await prepare(page); await page.onSubmit(); assert.equal(page.data.retryable, true);
    page.onReason({ detail: { value: "Changed" } }); page.onTarget({ detail: { value: "99" } });
    await page.onRetry(); assert.deepEqual(writes[0], writes[1]);
    assert.equal(writes[0].body.items[0].status, "DELIVERED");
    assert.equal(writes[0].body.ownershipVersion, 1); assert.equal(writes[0].version, 4);
    assert.match(page.data.notice, /已转派/); assert.equal(page.data.snapshot, null);
});
test("identity switch clears pending and hides late data", async () => {
    let resolve; const writes = [];
    const { page, services } = fixture(async (method, path, body) => {
        if (method === "GET") return new Promise(r => resolve = r);
        writes.push(body); return {};
    });
    page.onShow(); page.onRequest({ detail: { value: "9" } }); const loading = page.onInspect();
    page.onHide(); services.sessions.identity = { accountId: "other", bindingId: "other" }; page.onShow();
    resolve(structuredClone(snapshot)); await loading;
    assert.equal(page.data.snapshot, null); assert.equal(page.data.requestId, ""); assert.equal(page.data.busy, false);
    await page.onRetry(); assert.equal(writes.length, 0);
});
test("checks and explicit confirmation precede any write; failed dialog releases busy", async () => {
    let writes = 0; const { page, services } = fixture(async method => {
        if (method === "GET") return structuredClone(snapshot); writes++; return {};
    });
    await prepare(page); page.onCheck({ detail: { value: [] } }); await page.onSubmit(); assert.equal(writes, 0);
    page.onCheck({ detail: { value: ["checked"] } }); services.confirm = async () => { throw Error("dialog"); };
    await page.onSubmit(); assert.equal(page.data.busy, false); assert.equal(writes, 0);
    services.confirm = async () => false; await page.onSubmit(); assert.equal(writes, 0);
    services.confirm = async () => true; await page.onSubmit(); assert.equal(writes, 1);
});
test("hiding a submitted operation retains its key for retry on same identity", async () => {
    let resolve; const writes = []; let first = true;
    const { page } = fixture(async (method, path, body, options) => {
        if (method === "GET") return structuredClone(snapshot);
        writes.push(structuredClone({ body, key: options.key, version: options.version }));
        if (first) { first = false; return new Promise(r => resolve = r); } return {};
    });
    await prepare(page); const saving = page.onSubmit(); await new Promise(r => setImmediate(r));
    page.onHide(); page.onShow(); resolve({}); await saving;
    assert.equal(page.data.retryable, true); await page.onRetry(); assert.deepEqual(writes[0], writes[1]);
});
