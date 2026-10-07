// Internal proof: actual native handlers -> opaque session client -> Spring HTTP -> owned MySQL.
// WeChat exchange and loopback transport are explicit test substitutes; no device/HTTPS claim.
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { createRequisitionPage } from "../src/requisition-pages.ts";
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
        if (input.method === "POST" && input.url.endsWith("/requisitions")) {
            writes.push({ key: input.header["Idempotency-Key"], body: input.data });
            if (lose && response.status === 201) { lose = false; throw new Error("test-only lost response after commit"); }
        }
        return reply;
    },
};
const sessions = new MiniSessionClient(origin, runtime);
assert.equal(await sessions.login(process.env.FAMS_TEST_WECHAT_CODE), "BOUND");
const services = { sessions, createKey: () => `native-req-key-${++sequence}`, navigate() {} };
const page = createRequisitionPage(() => services, true);
page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
await page.onShow(); assert.equal(page.data.showForm, true, page.data.error);
const event = (field, value) => ({ detail: { value }, currentTarget: { dataset: { index: 0, field } } });
page.onField(event("specification", "Native real HTTP")); page.onField(event("purpose", "Native application"));
page.onField(event("quantity", "2"));
await page.onSave(); assert.equal(page.data.retryable, true, page.data.error);
await page.onRetry(); assert.equal(page.data.selected?.requestStatus, "DRAFT", page.data.error);
assert.deepEqual(writes[0], writes[1]);
await page.onSubmit(); assert.equal(page.data.selected?.requestStatus, "PENDING_ADMIN_DECISION", page.data.error);
assert.equal(page.data.selected.demands[0].requestedQuantity, 2);
assert.equal(page.data.selected.demands[0].completedQuantity, 0);
const id = page.data.selected.id;
await page.onRefresh(); assert.equal(page.data.selected.id, id, page.data.error);
page.onHide(); page.onUnload(); await sessions.logout(); assert.equal(saved, null);
console.log("Native requisition create, lost-response replay, submit, refresh and identity cleanup PASS.");
