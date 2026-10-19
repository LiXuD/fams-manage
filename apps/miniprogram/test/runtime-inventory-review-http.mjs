// Exported native page handlers → opaque session → real Spring HTTP → owned MySQL.
// The HTTPS loopback bridge and direct session provisioning do not prove WeChat/device acceptance.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createInventoryReviewPage } from "../src/inventory-review-pages.ts";

const port = process.env.FAMS_TEST_API_PORT;
assert.match(port ?? "", /^[0-9]{1,5}$/);
const taskId = process.env.FAMS_TEST_INVENTORY_TASK;
const itemId = process.env.FAMS_TEST_INVENTORY_ITEM;
const originalId = process.env.FAMS_TEST_INVENTORY_OBSERVATION;
const counterId = process.env.FAMS_TEST_INVENTORY_COUNTER;
const targetId = process.env.FAMS_TEST_INVENTORY_TARGET ?? "943099";
for (const value of [taskId, itemId, originalId, counterId]) assert.match(value ?? "", /^[1-9][0-9]*$/);
let saved = JSON.parse(process.env.FAMS_TEST_MINI_GRANT);
let loseFinalReply = true;
const finalRequests = [];
const origin = "https://inventory-review-test.invalid";
const sessions = new MiniSessionClient(origin, {
    readSession: () => saved, writeSession: value => { saved = value; }, removeSession: () => { saved = null; }, clearIdentityData() {},
    async request(input) {
        assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
        const final = input.method === "POST" && input.url.endsWith("/acceptance");
        if (final) finalRequests.push(structuredClone(input));
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice(origin.length), {
            method: input.method, headers: { "Content-Type": "application/json", ...input.header },
            body: input.data ? JSON.stringify(input.data) : undefined, redirect: "error" });
        const data = response.status === 204 ? null : await response.json();
        if (final && response.status === 200 && loseFinalReply) { loseFinalReply = false; throw new Error("Owned lost-response probe after persisted acceptance"); }
        return { statusCode: response.status, data };
    }
});
const page = createInventoryReviewPage(() => ({ sessions, createKey: randomUUID, confirm: async () => true,
    navigate() { throw new Error("Unexpected authorization loss"); } }));
page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
const event = id => ({ currentTarget: { dataset: { id } } });
function proof() {
    for (const [field, value] of [["reason", "真实原生处理器联合核实"], ["evidence", "已共同核对发布账面、原始观察、接收时刻与实际流转记录"]]) {
        page.onField({ currentTarget: { dataset: { field } }, detail: { value } });
    }
}
function clean() { assert.equal(page.data.error, "", page.data.error); assert.equal(page.data.busy, false); }
page.onLoad({ taskId }); await page.onShow(); clean();
await page.onOpenItem(event(itemId)); clean();
if (process.env.FAMS_TEST_INVENTORY_MODE === "formal") {
    await page.onPrepare(); clean(); await page.onOpenObservation(event(counterId)); clean(); proof();
    await page.onPhysicalMethod({ detail: { value: "2" } }); clean();
    for (const [kind, choice] of [["warehouse", "943000"], ["location", targetId]]) {
        await page.onCatalog({ currentTarget: { dataset: { kind } } }); clean();
        assert.ok(page.data.catalog.rows.some(row => row.id === choice));
        page.onTargetChoice({ currentTarget: { dataset: { field: kind, id: choice } } });
    }
    page.onDimensions({ detail: { value: ["LOCATION"] } }); await page.onPhysicalDecision(); clean();
    let location = page.data.subjects.find(row => row.dimension === "LOCATION");
    assert.equal(location.subject_status, "PROCESSING"); assert.equal(location.links.length, 1);
    await page.onViewTransfer(event(location.links[0].transfer_id)); clean();
    assert.equal(page.data.linked.requestStatus, "PENDING_APPROVAL");
    assert.equal(page.data.linked.items[0].targetSnapshot.locationId, targetId);
    assert.equal(page.data.linked.items[0].targetSnapshot.warehouseId, "943000");
    assert.equal(page.data.linked.items[0].targetSnapshot.referenceVersions.location, 0);
    assert.equal(page.data.linked.items[0].itemStatus, "READY");
    proof(); await page.onPhysicalMethod({ detail: { value: "3" } });
    page.onDimensions({ detail: { value: ["LOCATION"] } }); await page.onPhysicalDecision(); clean();
    location = page.data.subjects.find(row => row.dimension === "LOCATION");
    assert.equal(location.subject_status, "PROCESSING");
    assert.equal(page.data.board.reviewedCount, 0); assert.equal(finalRequests.length, 0);
    page.onUnload(); console.log("Native owner catalogs, atomic formal transfer and real pending progress through HTTP PASS");
} else {
await page.onOpenObservation(event(originalId)); clean(); proof();
await page.onSelectEvidence(); clean(); assert.equal(page.data.selection.review.review_status, "SELECTED");
await page.onPrepare(); clean(); proof();
await page.onEvidenceDecision(); clean();
await page.onOpenObservation(event(counterId)); clean(); proof();
assert.ok(page.data.subjects.some(row => row.dimension === "LOCATION"));
await page.onEvidenceDecision(); clean();
proof(); await page.onFinalAcceptance(); assert.equal(page.data.retryable, true);
page.onHide(); await page.onShow(); clean();
assert.equal(page.data.selection.review.review_status, "ACCEPTED");
await page.onRetry(); clean(); assert.equal(page.data.retryable, false);
assert.equal(finalRequests.length, 2); assert.deepEqual(finalRequests[1], finalRequests[0]);
proof(); const normal = page.data.board.items.find(row => row.canAcceptNormal);
assert.ok(normal); page.onNormalSelection({ detail: { value: [normal.item.id] } });
await page.onNormalAcceptance(); clean(); assert.equal(page.data.board.reviewedCount, 2);
page.onUnload(); assert.equal(page.data.board, null);
console.log("Native inventory evidence decisions, original replay and normal acceptance through real HTTP PASS");
}
