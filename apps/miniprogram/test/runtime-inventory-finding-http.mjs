// Exported native handlers and opaque session -> real Spring/MySQL. HTTPS hostname is a loopback adapter.
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { createInventoryFindingPage } from "../src/inventory-finding-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, task = process.env.FAMS_TEST_INVENTORY_TASK;
const observation = process.env.FAMS_TEST_FINDING_OBSERVATION, receiptNumber = process.env.FAMS_TEST_FINDING_RECEIPT;
assert.match(port ?? "", /^[0-9]{1,5}$/); for (const value of [task, observation]) assert.match(value ?? "", /^[1-9][0-9]*$/);
const saved = JSON.parse(process.env.FAMS_TEST_MINI_GRANT); let drop = true;
const writes = [];
const sessions = new MiniSessionClient("https://finding-test.invalid", {
    readSession: () => saved, writeSession() { throw new Error("No new login allowed"); }, removeSession() {}, clearIdentityData() {},
    async request(input) {
        assert.ok(input.url.startsWith("https://finding-test.invalid/api/v1/mini/"));
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice("https://finding-test.invalid".length), {
            method: input.method, headers: { "Content-Type": "application/json", ...input.header }, body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error",
        });
        const value = { statusCode: response.status, data: await response.json() };
        if (input.method === "POST") {
            assert.equal(response.status, 200, JSON.stringify(value.data));
            writes.push({ path: input.url.split("?")[0], body: input.data, key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] });
            if (drop) { drop = false; throw new Error("Owned adapter lost the actual committed HTTP reply"); }
        }
        return value;
    },
});
const page = createInventoryFindingPage(() => ({ sessions, createKey: () => process.env.FAMS_TEST_FINDING_KEY, confirm: async () => true, navigate() {} }));
page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
page.onLoad({ taskId: task }); await page.onShow(); assert.equal(page.data.error, "");
await page.onOpen({ currentTarget: { dataset: { id: observation } } }); assert.equal(page.data.detail.observation.id, observation);
page.onMethod({ detail: { value: "2" } }); page.onField(field("assetCode", process.env.FAMS_TEST_FINDING_ASSET_CODE ?? "INV-DRAFT")); page.onField(field("receiptNumber", receiptNumber));
await page.onSearch(); assert.equal(page.data.error, ""); assert.equal(page.data.targets.length, 1);
page.onTarget({ currentTarget: { dataset: { id: page.data.targets[0].key } } });
page.onField(field("reason", process.env.FAMS_TEST_FINDING_REASON));
page.onField(field("evidence", process.env.FAMS_TEST_FINDING_EVIDENCE));
await page.onSave(); assert.equal(page.data.retryable, true); assert.equal(writes.length, 1);
await page.onRefresh(); assert.equal(page.data.detail.subject.subject_status, "PROCESSING"); assert.equal(page.data.retryable, true);
await page.onRetry(); assert.equal(page.data.retryable, false); assert.equal(page.data.detail.subject.subject_status, "PROCESSING");
assert.equal(writes.length, 2); assert.deepEqual(writes[0], writes[1]);
page.onHide(); assert.equal(page.data.detail, null); page.onUnload();
console.log(JSON.stringify({ nativeFindingOriginalReplyRecovered: true, originalKeyPayloadVersionRetained: true, formalOwnerLookup: true, noPrematureClosure: true }));
