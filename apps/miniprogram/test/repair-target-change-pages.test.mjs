import assert from "node:assert/strict";
import test from "node:test";
import { MiniRequestError } from "../src/session.ts";
import { createTargetChangePage } from "../src/target-change-pages.ts";
const event = (field, value) => ({ detail: { value }, currentTarget: { dataset: { field } } });
const assetId = "9007199254740995", repairId = "9007199254740993";
const snapshot = { businessType: "REPAIR", businessId: repairId, itemId: assetId, assetCode: "REPAIR-TARGET",
    version: 5, ownershipVersion: 1, assetVersion: 1, lockVersion: 0, loanVersion: null,
    target: { custodyType: "PERSONAL", organizationId: "1", employeeId: "3", locationId: "7" } };
function fixture(post) {
    const writes = [], reads = [];
    const services = { sessions: { identity: { accountId: "1", bindingId: "2" }, async request(method, path, body, operation) {
        if (method === "POST") {
            writes.push(structuredClone({ path, body, key: operation.key, version: operation.version }));
            await post?.(writes.length);
            return { id: "88", business_id: repairId, item_id: assetId, version: 0, change_status: "PENDING", proposed_target_json: body.target };
        }
        reads.push(path);
        if (path.includes("/context?")) return structuredClone(snapshot);
        if (path.includes("/catalog/employee")) return { items: [{ id: "4", name: "新责任人", version: 6, sharedEligible: true }] };
        if (path.includes("/catalog/organization")) return { items: [{ id: "1", name: "使用组织", version: 2 }] };
        if (path.includes("/catalog/location")) return { items: [{ id: "7", name: "固定地点", version: 3, locationType: "ROOM" }] };
        if (path.includes("/catalog/warehouse")) return { items: [] };
        if (path.endsWith("/repairs/" + repairId)) return { assetId, source: { assetCode: "REPAIR-TARGET" }, actions: { changeReturnTarget: true } };
        if (path.includes("/repairs?")) return { items: [{ id: repairId, status: "WAREHOUSE_PROCESSING", source: { assetCode: "REPAIR-TARGET" } }], total: 1 };
        return { items: [], total: 0, canExecute: true, canApprove: true };
    } }, createKey: () => "repair-target-original-request-key", navigate() {}, confirm: async () => true };
    const page = createTargetChangePage(() => services);
    page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
    return { page, services, writes, reads };
}
async function ready(page) {
    await page.onShow(); await page.onBusiness(event("", "2")); await page.onSources();
    await page.onSource({ currentTarget: { dataset: { id: repairId } } });
    assert.deepEqual(page.data.items, [{ id: assetId, assetCode: "REPAIR-TARGET" }]);
    await page.onStart({ currentTarget: { dataset: { id: assetId } } });
    await page.onChoice(event("employee", "0")); page.onField(event("reason", "原目标失效后提请批准新的实际接收目标"));
    page.onField(event("code", "REPAIR-TARGET")); page.onField(event("physical", "核实设备实物和新接收人员、组织及位置"));
}
test("repair source and target preserve exact IDs, frozen scope and independently selected versions", async () => {
    const { page, writes, reads } = fixture(); await ready(page);
    await page.onPropose(); assert.equal(writes.length, 0);
    page.onCheck(event("", ["checked"])); await page.onPropose();
    assert.equal(writes.length, 1); assert.match(writes[0].path, /businessType=REPAIR/);
    assert.deepEqual(writes[0].body.target, { custodyType: "PERSONAL", organizationId: "1", organizationVersion: 2,
        employeeId: "4", employeeVersion: 6, locationId: "7", locationVersion: 3 });
    assert.equal(writes[0].body.businessId, repairId); assert.equal(writes[0].body.itemId, assetId);
    assert.deepEqual(writes[0].body.snapshot, snapshot); assert.ok(reads.some(path => path.includes("/mini/repairs?")));
});
test("repair lost committed proposal survives refresh and hide then retries exactly", async () => {
    const { page, writes } = fixture(count => { if (count === 1) throw Error("committed response lost"); });
    await ready(page); page.onCheck(event("", ["checked"])); await page.onPropose();
    assert.equal(page.data.retryable, true); await page.onRefresh(); assert.equal(page.data.retryable, true);
    page.onHide(); assert.equal(page.data.snapshot, null); assert.equal(page.data.physical, "");
    await page.onShow(); assert.equal(page.data.retryable, true); await page.onRetry();
    assert.deepEqual(writes[1], writes[0]); assert.equal(page.data.retryable, false);
});
test("identity replacement during repair confirmation prevents writes and hides old target", async () => {
    const { page, services, writes } = fixture(); await ready(page); page.onCheck(event("", ["checked"]));
    services.confirm = async () => { services.sessions.identity = { accountId: "other", bindingId: "other" }; return true; };
    await page.onPropose();
    assert.equal(writes.length, 0); assert.equal(page.data.snapshot, null); assert.equal(page.data.physical, "");
    assert.equal(page.data.busy, false);
});

test("current repair permission rejection discards private target and retry authority", async () => {
    const { page, services, writes } = fixture(); await ready(page); page.onCheck(event("", ["checked"]));
    const request = services.sessions.request;
    services.sessions.request = async (method, ...args) => {
        if (method === "POST") throw new MiniRequestError(403, "TARGET_CHANGE_FORBIDDEN");
        return request(method, ...args);
    };
    await page.onPropose();
    assert.equal(writes.length, 0); assert.equal(page.data.snapshot, null);
    assert.equal(page.data.physical, ""); assert.equal(page.data.retryable, false);
    await page.onRetry(); assert.equal(writes.length, 0);
});
