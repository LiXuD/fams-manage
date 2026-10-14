import assert from "node:assert/strict";
import test from "node:test";
import { createTargetChangePage } from "../src/target-change-pages.ts";
const event = (field, value) => ({ detail: { value }, currentTarget: { dataset: { field } } });
const snapshot = { businessType: "TRANSFER", businessId: "9007199254740993", itemId: "77", version: 4, assetCode: "FA-1",
    source: { lifecycleStatus: "IN_USE" }, physical: { destination: "Original room" },
    target: { owningOrganizationId: "1", custodyType: "PERSONAL", usingOrganizationId: "1", employeeId: "3", warehouseId: null,
        locationId: "7", referenceVersions: { owningOrganization: 0, usingOrganization: 0, employee: 0, location: 0 } } };
const record = status => ({ id: "99", version: status === "PENDING" ? 0 : 1, business_id: snapshot.businessId, item_id: "77",
    change_status: status, source_snapshot_json: snapshot, proposed_target_json: { ...snapshot.target, employeeId: "4" },
    reason: "Changed target", physical_verification: "Still in Original room" });
function fixture(post) {
    const writes = []; let selected = record("PENDING");
    const services = { sessions: { identity: { accountId: "1", bindingId: "1" }, async request(method, path, body, options) {
        if (method === "POST") { writes.push(structuredClone({ path, body, key: options.key, version: options.version }));
            const result = await post?.(writes.length); selected = record("APPROVED"); return result ?? selected; }
        if (path.includes("/context?")) return structuredClone(snapshot);
        if (path.includes("/catalog/employee")) return { items: [{ id: "3", name: "Original", version: 0 },
            { id: "4", name: "New", version: 2, sharedEligible: true }, { id: "5", name: "Ordinary", version: 1 }] };
        if (path.includes("/catalog/organization")) return { items: [{ id: "1", name: "Org", version: 0 }] };
        if (path.includes("/catalog/location")) return { items: [{ id: "7", name: "Room", version: 0, locationType: "ROOM" }] };
        if (path.includes("/catalog/warehouse")) return { items: [] };
        return { items: [selected], total: 1, canApprove: true, canExecute: true };
    } }, createKey: () => "target-change-original-key", navigate() {}, confirm: async () => true };
    const page = createTargetChangePage(() => services);
    page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
    return { page, services, writes };
}
async function decision(page) {
    await page.onShow(); await page.onBusiness(event("", "1"));
    page.onOpen({ currentTarget: { dataset: { id: "99" } } }); page.onField(event("decision", "Checked target and physical facts"));
}
test("single-admin lost reply retries exact decision and blocks editing and business switching", async () => {
    const { page, writes } = fixture(count => { if (count === 1) throw Error("lost committed reply"); });
    await decision(page); await page.onDecision({ currentTarget: { dataset: { result: "APPROVED" } } });
    assert.equal(page.data.retryable, true); page.onField(event("decision", "changed")); await page.onBusiness(event("", "0"));
    assert.equal(page.data.businessType, "TRANSFER"); await page.onRetry(); assert.deepEqual(writes[1], writes[0]);
    assert.equal(writes[0].version, 0); assert.equal(writes[0].body.result, "APPROVED");
    assert.match(writes[0].path, /businessType=TRANSFER/); assert.equal(page.data.selected.change_status, "APPROVED");
});
test("native proposal preserves string IDs and filters shared responsibility to eligible managers", async () => {
    const { page, writes } = fixture(); await page.onShow(); await page.onBusiness(event("", "1"));
    page.setData({ sourceId: snapshot.businessId }); await page.onStart({ currentTarget: { dataset: { id: "77" } } });
    page.onChoice(event("custody", "1")); assert.deepEqual(page.data.people.map(row => row.id), ["4"]);
    await page.onChoice(event("employee", "0")); page.onField(event("reason", "New shared manager"));
    page.onField(event("code", "FA-1")); page.onField(event("physical", "Still at Original room")); page.onCheck(event("", ["checked"]));
    await page.onPropose(); assert.equal(writes.length, 1); assert.equal(writes[0].version, 4);
    assert.equal(writes[0].body.businessId, "9007199254740993"); assert.equal(writes[0].body.target.employeeId, "4");
    assert.equal(writes[0].body.target.referenceVersions.employee, 2); assert.equal(writes[0].body.snapshot.physical.destination, "Original room");
});
test("same identity hide retains pending key; switched binding clears it and ignores late data", async () => {
    let resolve; const { page, services, writes } = fixture(count => count === 1 ? new Promise(r => resolve = r) : undefined);
    await decision(page); const saving = page.onDecision({ currentTarget: { dataset: { result: "APPROVED" } } });
    await new Promise(r => setImmediate(r)); page.onHide(); await page.onShow(); resolve(record("APPROVED")); await saving;
    assert.equal(page.data.retryable, true); await page.onRetry(); assert.deepEqual(writes[1], writes[0]);
    await decision(page); services.sessions.identity = { accountId: "other", bindingId: "other" };
    page.onHide(); await page.onShow(); assert.equal(page.data.selected, null); assert.equal(page.data.snapshot, null);
    const count = writes.length; await page.onRetry(); assert.equal(writes.length, count);
});
test("cancelled or failed confirmation never writes and clears busy", async () => {
    const { page, services, writes } = fixture(); await decision(page);
    services.confirm = async () => false; await page.onDecision({ currentTarget: { dataset: { result: "APPROVED" } } });
    assert.equal(writes.length, 0); assert.equal(page.data.busy, false);
    services.confirm = async () => { throw Error("dialog failed"); }; await page.onDecision({ currentTarget: { dataset: { result: "REJECTED" } } });
    assert.equal(writes.length, 0); assert.equal(page.data.busy, false);
});

test("stock correction preserves null personnel fields and permits clearing optional ownership", async () => {
    let sent;
    const stock = { ...snapshot, source: { lifecycleStatus: "IN_STOCK" }, target: { ...snapshot.target,
        custodyType: "STORAGE", usingOrganizationId: null, employeeId: null, warehouseId: "8" } };
    const services = { sessions: { identity: { accountId: "1", bindingId: "1" }, async request(method, path, body) {
        if (method === "POST") { sent = body; return record("PENDING"); }
        if (path.includes("/context?")) return stock;
        if (path.includes("/catalog/organization")) return { items: [{ id: "1", name: "Owner", version: 0 }] };
        if (path.includes("/catalog/warehouse")) return { items: [{ id: "8", name: "Target warehouse", version: 2 }] };
        if (path.includes("/catalog/location")) return { items: [{ id: "7", name: "Bin", version: 3, locationType: "BIN", warehouseId: "8" }] };
        return { items: [], total: 0, canExecute: true, canApprove: false };
    } }, createKey: () => "stock-target-original-key", navigate() {}, confirm: async () => true };
    const page = createTargetChangePage(() => services); page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
    await page.onShow(); await page.onBusiness(event("", "1")); page.setData({ sourceId: snapshot.businessId });
    await page.onStart({ currentTarget: { dataset: { id: "77" } } }); await page.onChoice(event("owning", "0"));
    page.onField(event("reason", "Clear optional owner")); page.onField(event("code", "FA-1"));
    page.onField(event("physical", "Bin verified")); page.onCheck(event("", ["checked"])); await page.onPropose();
    assert.deepEqual(sent.target, { owningOrganizationId: null, custodyType: "STORAGE", usingOrganizationId: null,
        employeeId: null, warehouseId: "8", locationId: "7", referenceVersions: { location: 3, warehouse: 2 } });
});
