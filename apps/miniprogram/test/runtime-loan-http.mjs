// Internal native-handler proof; loopback and WeChat identity exchange are test adapters.
// The imported handler performs actual receipt and loses its committed response once.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createLoanPage } from "../src/loan-pages.ts";

const port = process.env.FAMS_TEST_API_PORT;
const selectedReturn = process.env.FAMS_TEST_ASSET_RETURN_ID;
if (!selectedReturn) await import("./runtime-receipt-http.mjs");
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
        if (input.method === "POST" && (input.url.endsWith("/extensions") || input.url.endsWith("/asset-returns")
            || input.url.endsWith("/reassignment/unavailable"))) {
            writes.push({ path: input.url, key: input.header["Idempotency-Key"],
                version: input.header["If-Match-Version"], body: structuredClone(input.data) });
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
const services = { sessions, createKey: () => selectedReturn ? randomUUID() : `native-loan-key-${++sequence}`, navigate() {}, confirm: async () => true };
const page = createLoanPage(() => services);
page.data = structuredClone(page.data);
page.setData = data => Object.assign(page.data, data);
await page.onShow();
if (selectedReturn) {
    await page.onReturnProgress({ currentTarget: { dataset: { id: selectedReturn } } });
    assert.equal(page.data.returnProgress?.returnStatus, "WAREHOUSE_PROCESSING", page.data.error);
    page.onReason({ detail: { value: "原库管无法继续借用归还验收" } });
    await page.onDeclareUnavailable();
    assert.equal(page.data.retryable, true, page.data.error);
    page.onHide();
    await page.onShow();
    await page.onRetry();
    assert.equal(page.data.retryable, false, page.data.error);
    assert.equal(writes.length, 2);
    assert.deepEqual(writes[1], writes[0]);
    page.onHide(); page.onUnload(); await sessions.logout();
    assert.equal(saved, null);
    console.log("Native asset return declaration and committed-reply replay PASS.");
} else {
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
}
