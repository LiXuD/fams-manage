// Internal native handler -> opaque mini session -> real HTTP/MySQL proof.
// WeChat identity exchange and HTTPS loopback bridge are test substitutes, not device acceptance.
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { createTransferReassignmentPage } from "../src/transfer-reassignment-pages.ts";
const port = process.env.FAMS_TEST_API_PORT;
assert.match(port ?? "", /^[0-9]{1,5}$/);
const origin = "https://requisition-test.invalid";
let saved = null, lost = true;
const writes = [];
const runtime = {
    readSession: () => saved, writeSession: value => { saved = value; }, removeSession: () => { saved = null; }, clearIdentityData() {},
    async request(input) {
        assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice(origin.length), {
            method: input.method, headers: { "Content-Type": "application/json", ...input.header },
            body: input.data ? JSON.stringify(input.data) : undefined, redirect: "error" });
        const reply = { statusCode: response.status, data: response.status === 204 ? null : await response.json() };
        if (input.method === "POST" && input.url.endsWith("/reassignment")) {
            writes.push(structuredClone({ key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"], body: input.data }));
            if (lost && response.status === 200) { lost = false; throw Error("test-only lost reply after commit"); }
        }
        return reply;
    },
};
const sessions = new MiniSessionClient(origin, runtime);
assert.equal(await sessions.login(process.env.FAMS_TEST_WECHAT_CODE), "BOUND");
const services = { sessions, createKey: () => "native-transfer-reassign-key-0001", navigate() {}, confirm: async () => true };
const page = createTransferReassignmentPage(() => services);
page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
page.onShow(); await page.onList();
assert.ok(page.data.rows.some(row => row.id === process.env.FAMS_TEST_TRANSFER_ID), page.data.error);
await page.onOpen({ currentTarget: { dataset: { id: process.env.FAMS_TEST_TRANSFER_ID } } });
assert.ok(page.data.snapshot, page.data.error);
const index = page.data.snapshot.operators.findIndex(row => row.id === process.env.FAMS_TEST_SUCCESSOR_ID);
assert.ok(index >= 0);
page.onTarget({ detail: { value: String(index) } });
page.onReason({ detail: { value: "Original processor unavailable" } });
page.onEvidence({ detail: { value: "Confirmed delivered asset, destination, active lock and unsynchronized results" } });
page.onCheck({ detail: { value: ["checked"] } });
await page.onSubmit(); assert.equal(page.data.retryable, true, page.data.error);
await page.onRetry(); assert.match(page.data.notice, /已转派/); assert.deepEqual(writes[0], writes[1]);
const detail = await sessions.request("GET", `/api/v1/mini/transfers/${process.env.FAMS_TEST_TRANSFER_ID}`);
assert.equal(detail.processorAccountId, process.env.FAMS_TEST_SUCCESSOR_ID);
assert.equal(detail.ownershipVersion, 1); assert.equal(detail.items[0].itemStatus, "DELIVERED");
page.onHide(); page.onUnload(); await sessions.logout(); assert.equal(saved, null);
console.log("Native reassignment handler -> real HTTP persistence and lost-response replay PASS.");
