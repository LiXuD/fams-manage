// Native handlers and opaque sessions reach actual Spring HTTP and owned MySQL.
// WeChat exchange and loopback HTTPS bridge are explicit substitutes, not device acceptance.
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { createTargetChangePage } from "../src/target-change-pages.ts";
import { createReceiptPage } from "../src/receipt-pages.ts";
import { createTransferReceiptPage } from "../src/transfer-receipt-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, business = process.env.FAMS_TEST_TARGET_BUSINESS;
assert.match(port ?? "", /^[0-9]{1,5}$/); assert.ok(["REQUISITION", "TRANSFER"].includes(business));
const origin = "https://requisition-test.invalid", clients = [], writes = {};
function client(role) {
    let saved = null, lost = true, sequence = 0; writes[role] = [];
    const sessions = new MiniSessionClient(origin, {
        readSession: () => saved, writeSession: value => { saved = value; }, removeSession: () => { saved = null; }, clearIdentityData() {},
        async request(input) {
            assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
            const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice(origin.length), {
                method: input.method, headers: { "Content-Type": "application/json", ...input.header },
                body: input.data ? JSON.stringify(input.data) : undefined, redirect: "error" });
            const reply = { statusCode: response.status, data: response.status === 204 ? null : await response.json() };
            if (input.method === "POST" && (input.url.includes("/item-target-changes") || input.url.endsWith("/receive"))) {
                writes[role].push(structuredClone({ key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"], body: input.data }));
                if (lost && response.status === 200) { lost = false; throw Error("test-only lost committed reply"); }
            }
            return reply;
        },
    });
    const services = { sessions, createKey: () => `native-target-${role}-${++sequence}-original`, navigate() {}, confirm: async () => true };
    clients.push(sessions); return services;
}
function mount(factory, services) { const page = factory(() => services); page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); return page; }
const event = (field, value) => ({ detail: { value }, currentTarget: { dataset: { field } } });
const warehouse = client("warehouse"), admin = client("admin"), recipient = client("recipient"), original = client("original");
for (const [role, services] of Object.entries({ warehouse, admin, recipient, original })) {
    assert.equal(await services.sessions.login(process.env.FAMS_TEST_TARGET_CODE_PREFIX + "-" + role), "BOUND");
}
const page = mount(createTargetChangePage, warehouse); await page.onShow();
await page.onBusiness(event("", business === "TRANSFER" ? "1" : "0")); await page.onSources();
assert.ok(page.data.sources.some(row => row.id === process.env.FAMS_TEST_TARGET_REQUEST), page.data.error);
await page.onSource({ currentTarget: { dataset: { id: process.env.FAMS_TEST_TARGET_REQUEST } } });
await page.onStart({ currentTarget: { dataset: { id: process.env.FAMS_TEST_TARGET_ITEM } } });
assert.ok(page.data.snapshot, page.data.error);
const organization = page.data.organizations.findIndex(row => row.id === process.env.FAMS_TEST_TARGET_ORGANIZATION);
assert.ok(organization >= 0); await page.onChoice(event("using", String(organization)));
const employee = page.data.people.findIndex(row => row.id === process.env.FAMS_TEST_TARGET_EMPLOYEE);
assert.ok(employee >= 0); await page.onChoice(event("employee", String(employee)));
page.onField(event("reason", "Native corrected recipient")); page.onField(event("code", page.data.snapshot.assetCode));
page.onField(event("physical", "Original physical delivery remains; no recovery or unlock")); page.onCheck(event("", ["checked"]));
await page.onPropose(); assert.equal(page.data.retryable, true, page.data.error); await page.onRetry();
assert.equal(page.data.selected?.change_status, "PENDING", page.data.error); assert.deepEqual(writes.warehouse[0], writes.warehouse[1]);
const proposal = page.data.selected.id;
const approval = mount(createTargetChangePage, admin); await approval.onShow(); await approval.onBusiness(event("", business === "TRANSFER" ? "1" : "0"));
approval.onOpen({ currentTarget: { dataset: { id: proposal } } }); approval.onField(event("decision", "Checked current target and physical facts"));
await approval.onDecision({ currentTarget: { dataset: { result: "APPROVED" } } });
assert.equal(approval.data.retryable, true, approval.data.error); await approval.onRetry();
assert.equal(approval.data.selected?.change_status, "APPROVED", approval.data.error); assert.deepEqual(writes.admin[0], writes.admin[1]);
const factory = business === "TRANSFER" ? createTransferReceiptPage : createReceiptPage;
const old = mount(factory, original); await old.onShow();
assert.ok(!old.data.rows.some(row => row.id === process.env.FAMS_TEST_TARGET_ITEM), old.data.error);
const receipt = mount(factory, recipient); await receipt.onShow();
const row = receipt.data.rows.find(row => row.id === process.env.FAMS_TEST_TARGET_ITEM); assert.ok(row, receipt.data.error);
await receipt.onOpen({ currentTarget: { dataset: { request: row.requestId, id: row.id } } });
const observedCode = business === "TRANSFER" ? row.assetCode : row.assetSnapshotJson.assetCode;
assert.equal(typeof observedCode, "string");
receipt.onCode({ detail: { value: observedCode } }); receipt.onCheck({ detail: { value: ["checked"] } });
await receipt.onReceive(); assert.equal(receipt.data.retryable, true, receipt.data.error); await receipt.onRetry();
assert.equal(receipt.data.rows.length, 0, receipt.data.error); assert.deepEqual(writes.recipient[0], writes.recipient[1]);
for (const p of [page, approval, old, receipt]) { p.onHide(); p.onUnload(); }
for (const sessions of clients) await sessions.logout();
console.log(`${business} native target correction, original-key replay and actual new-recipient receipt PASS`);
