// Internal native-handler proof; loopback and WeChat identity exchange are test adapters.
// The imported handler performs actual receipt and loses its committed response once.
import "./runtime-receipt-http.mjs";
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { createLoanPage } from "../src/loan-pages.ts";

const port = process.env.FAMS_TEST_API_PORT;
assert.match(port ?? "", /^[0-9]{1,5}$/);
const origin = "https://requisition-test.invalid";
let saved = null;
let sequence = 0;
const lost = new Set();
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
        if (input.method === "POST" && (input.url.endsWith("/extensions") || input.url.endsWith("/asset-returns"))) {
            writes.push({ path: input.url, key: input.header["Idempotency-Key"], body: structuredClone(input.data) });
            if (!lost.has(input.url) && response.status === 200) {
                lost.add(input.url);
                throw new Error("test-only lost response after commit");
            }
        }
        return reply;
    },
};
const sessions = new MiniSessionClient(origin, runtime);
assert.equal(await sessions.login(process.env.FAMS_TEST_WECHAT_CODE), "BOUND");
const services = { sessions, createKey: () => `native-loan-key-${++sequence}`, navigate() {}, confirm: async () => true };
const page = createLoanPage(() => services);
page.data = structuredClone(page.data);
page.setData = data => Object.assign(page.data, data);
await page.onShow();
assert.equal(page.data.loans.length, 1, page.data.error);
await page.onSelect({ currentTarget: { dataset: { id: page.data.loans[0].id } } });
assert.equal(page.data.selected?.expectedReturnDate, "2099-12-31", page.data.error);
page.onReason({ detail: { value: "原生延期丢响应重试" } });
page.onDueDate({ detail: { value: "2100-12-31" } });
await page.onExtend();
assert.equal(page.data.retryable, true, page.data.error);
await page.onRetry();
assert.equal(page.data.retryable, false, page.data.error);
assert.deepEqual(writes[0], writes[1]);
assert.equal(page.data.selected.expectedReturnDate, "2099-12-31");
assert.equal(page.data.selected.extensions.length, 1);
page.onReason({ detail: { value: "原生归还丢响应重试" } });
await page.onReturn();
assert.equal(page.data.retryable, true, page.data.error);
await page.onRetry();
assert.equal(page.data.retryable, false, page.data.error);
assert.deepEqual(writes[2], writes[3]);
assert.equal(page.data.returns.length, 1, page.data.error);
assert.equal(page.data.returns[0].returnStatus, "PENDING_APPROVAL");
page.onHide(); page.onUnload(); await sessions.logout();
assert.equal(saved, null);
console.log("Native borrowing receipt, M05 extension/return replay and persisted pending facts PASS.");
