// Actual opaque sessions and Page handlers -> Spring/MySQL; HTTPS bridge and SDK confirmation are explicit fixtures.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createRepairReturnSourcePage } from "../src/repair-return-source-pages.ts";
import { createRepairPage } from "../src/repair-pages.ts";
import { createLoanPage } from "../src/loan-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, grants = JSON.parse(process.env.FAMS_TEST_REPAIR_GRANTS);
const followup = process.env.FAMS_TEST_RETURN_FOLLOWUP, origin = "https://source-test.invalid", writes = [], dropped = new Set();
assert.match(port ?? "", /^[0-9]{1,5}$/); assert.match(followup ?? "", /^[1-9][0-9]*$/);
const event = dataset => ({ currentTarget: { dataset } }), field = (name, value) => ({ ...event({ field: name }), detail: { value } });
const bind = page => { page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); return page; };
function services(role) {
    const saved = grants[role];
    const sessions = new MiniSessionClient(origin, { readSession: () => saved, writeSession() { throw Error("Unexpected login"); }, removeSession() {}, clearIdentityData() {}, async request(input) {
        assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice(origin.length), { method: input.method,
            headers: { "Content-Type": "application/json", ...input.header }, body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error" });
        const data = await response.json(); assert.equal(response.status, 200, JSON.stringify(data));
        if (input.method === "POST") {
            writes.push(structuredClone({ role, path: input.url, body: input.data, key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] }));
            const kind = input.url.endsWith("/return-sources/" + followup) ? "source" : input.url.endsWith("/close-inbound") ? "inbound" : "other";
            if (kind !== "other" && !dropped.has(kind)) { dropped.add(kind); throw Error("Committed reply deliberately lost by adapter"); }
        }
        return { statusCode: response.status, data };
    } });
    return { sessions, createKey: randomUUID, navigate() {}, confirm: async () => true };
}
const adminServices = services("admin"), source = bind(createRepairReturnSourcePage(() => adminServices));
await source.onShow(); await source.onSelect({ ...event({ id: followup }), detail: { value: "" } }); assert.ok(source.data.source);
source.onPrepare(); await source.onConfirm(); assert.equal(source.data.retry, true);
await source.onRefresh(); source.onHide(); await source.onShow(); await source.onRetry(); assert.equal(source.data.error, "");
const id = source.data.repairId; assert.ok(id); source.onUnload();
const admin = bind(createRepairPage(() => adminServices)); admin.onLoad({ id }); await admin.onShow();
admin.onField(field("reason", "核对原损坏归还，批准内部零费用维修，保留原库管")); await admin.onAction(event({ action: "approve" }));
assert.equal(admin.data.detail.status, "WAREHOUSE_PROCESSING"); assert.equal(admin.data.detail.processorAccountId, grants.warehouse.accountId); admin.onUnload();
const warehouseServices = services("warehouse"), warehouse = bind(createRepairPage(() => warehouseServices)); warehouse.onLoad({ id }); await warehouse.onShow();
assert.equal(warehouse.data.detail.actions.claim, false); assert.equal(warehouse.data.detail.actions.acceptInPlace, false);
for (const mode of ["away", "inbound"]) {
    warehouse.onPhysicalMode(event({ mode }));
    if (mode === "inbound") { await warehouse.onPhysicalCatalog(event({ kind: "warehouse" })); warehouse.onPhysicalWarehouse(event({ id: "951000" })); }
    await warehouse.onPhysicalCatalog(event({ kind: "location" })); warehouse.onPhysicalLocation(event({ id: "951000" }));
    warehouse.onField(field("physicalObservedCode", "REP-ASSET-951020")); warehouse.onField(field("physicalAt", new Date().toISOString()));
    warehouse.onField(field("physicalEvidence", mode === "away" ? "核对已归还损坏实物，原库管按批准范围重新实际接修" : "核对实际修复结果并验收入库，已归还借用不得重新关闭"));
    await warehouse.onPhysicalAction();
    if (mode === "away") assert.equal(warehouse.data.detail.physicalHandling.length, 1); else assert.equal(warehouse.data.retry, true);
}
warehouse.onHide(); await warehouse.onShow(); await warehouse.onRetry(); assert.equal(warehouse.data.error, ""); assert.equal(warehouse.data.detail.status, "COMPLETED"); warehouse.onUnload();
const original = bind(createLoanPage(() => services("employee"))); await original.onShow();
const row = original.data.returns.find(value => value.inspectionResult === "DAMAGED"); assert.ok(row);
await original.onReturnProgress(event({ id: row.id }));
assert.equal(original.data.returnProgress.followups[0].followupStatus, "CLOSED"); assert.equal(original.data.returnProgress.followups[0].linkedRepairId, id);
assert.equal(original.data.loans.find(value => value.assetId === "951020").relationStatus, "RETURNED"); original.onHide(); assert.equal(original.data.returnProgress, null); original.onUnload();
for (const suffix of ["/return-sources/" + followup, "/close-inbound"]) { const group = writes.filter(row => row.path.endsWith(suffix)); assert.equal(group.length, 2); assert.deepEqual(group[0], group[1]); }
console.log(JSON.stringify({ nativeActualReturnSourceClosed: true, exactSourceAndInboundReplies: true, originalLoanStillReturned: true, repairId: id }));
