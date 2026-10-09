// Internal proof: actual native handlers -> opaque session client -> Spring HTTP -> owned MySQL.
// WeChat exchange and loopback transport are explicit test substitutes; no device/HTTPS claim.
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { createTransferReceiptPage } from "../src/transfer-receipt-pages.ts";
const port = process.env.FAMS_TEST_API_PORT;
assert.match(port ?? "", /^[0-9]{1,5}$/);
const origin = "https://requisition-test.invalid";
let saved = null; let lose = true; let sequence = 0;
const writes = [];
const runtime = {
    readSession: () => saved, writeSession: value => { saved = value; },
    removeSession: () => { saved = null; }, clearIdentityData() {},
    async request(input) {
        assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice(origin.length), {
            method: input.method, headers: { "Content-Type": "application/json", ...input.header },
            body: input.data ? JSON.stringify(input.data) : undefined, redirect: "error",
        });
        const reply = { statusCode: response.status, data: response.status === 204 ? null : await response.json() };
        if (input.method === "POST" && input.url.endsWith("/receive")) {
            writes.push({ key: input.header["Idempotency-Key"], body: input.data });
            if (lose && response.status === 200) { lose = false; throw new Error("test-only lost response after commit"); }
        }
        return reply;
    },
};
const sessions = new MiniSessionClient(origin, runtime);
assert.equal(await sessions.login(process.env.FAMS_TEST_WECHAT_CODE), "BOUND");
const services = { sessions, createKey: () => `native-receipt-key-${++sequence}`, navigate() {}, confirm: async () => true };
const page = createTransferReceiptPage(() => services);
page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
await page.onShow(); assert.equal(page.data.rows.length, 1, page.data.error);
const row = page.data.rows[0];
await page.onOpen({ currentTarget: { dataset: { request: row.requestId, id: row.id } } });
assert.equal(page.data.selected?.assetCode, row.assetCode, page.data.error);
assert.equal(JSON.stringify(page.data).includes('grant'), false);
page.onCode({ detail: { value: row.assetCode } });
page.onCheck({ detail: { value: ['checked'] } });
await page.onReceive(); assert.equal(page.data.retryable, true, page.data.error);
await page.onRetry(); assert.match(page.data.notice, /正式责任已更新/); assert.equal(page.data.rows.length, 0, page.data.error);
assert.deepEqual(writes[0], writes[1]);
page.onHide(); page.onUnload(); await sessions.logout(); assert.equal(saved, null);
console.log("Native receipt handlers, scoped grant, lost-response replay and persistent custody PASS.");
