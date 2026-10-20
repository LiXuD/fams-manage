// Real native handlers + opaque session -> owned Spring/MySQL; HTTPS hostname is a loopback test adapter.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createInventoryReadinessPage } from "../src/inventory-readiness-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, task = process.env.FAMS_TEST_INVENTORY_TASK;
const first = process.env.FAMS_TEST_READINESS_FIRST, second = process.env.FAMS_TEST_READINESS_SECOND;
assert.match(port ?? "", /^[0-9]{1,5}$/); for (const value of [task, first, second]) assert.match(value ?? "", /^[1-9][0-9]*$/);
const saved = JSON.parse(process.env.FAMS_TEST_MINI_GRANT), writes = []; let drop = true;
const sessions = new MiniSessionClient("https://readiness-test.invalid", {
    readSession: () => saved, writeSession() { throw new Error("No new login allowed"); }, removeSession() {}, clearIdentityData() {},
    async request(input) {
        assert.ok(input.url.startsWith("https://readiness-test.invalid/api/v1/mini/"));
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice("https://readiness-test.invalid".length), {
            method: input.method, headers: { "Content-Type": "application/json", ...input.header },
            body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error",
        });
        const value = { statusCode: response.status, data: await response.json() };
        if (input.method === "POST") {
            assert.equal(response.status, 200, JSON.stringify(value.data));
            writes.push(structuredClone({ path: input.url, body: input.data, key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] }));
            if (drop) { drop = false; throw new Error("Owned adapter lost the actual committed reply"); }
        }
        return value;
    },
});
const page = createInventoryReadinessPage(() => ({ sessions, createKey: () => randomUUID(), confirm: async () => true }));
page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
const select = id => ({ currentTarget: { dataset: { id } } });
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
page.onLoad({ taskId: task }); await page.onShow(); assert.equal(page.data.error, ""); assert.equal(page.data.view.missingCount, 2);
page.onSelect(select(first)); page.onField(field("reason", "现场封闭暂未取得实盘观察")); page.onField(field("evidence", "现场负责人员说明及访问受限记录，保留明确未盘依据"));
await page.onSave(); assert.equal(page.data.retry, true); assert.equal(writes.length, 1);
page.onHide(); assert.equal(page.data.view, null); await page.onShow(); assert.equal(page.data.view.notObservedCount, 1);
await page.onRetry(); assert.equal(page.data.retry, false); assert.equal(writes.length, 2); assert.deepEqual(writes[0], writes[1]);
page.onSelect(select(second)); page.onKind({ detail: { value: "2" } });
page.onField(field("reason", "现场暂未找到该实物，后续继续查找")); page.onField(field("evidence", "现场房间及保管登记已核对，保留未找到线索并继续真实后续"));
await page.onSave(); assert.equal(page.data.error, ""); assert.equal(page.data.view.missingCount, 0); assert.equal(page.data.view.notObservedCount, 2);
assert.equal(page.data.view.items.find(row => row.item.id === second).physicalMissingOpen, true);
page.onField(field("waitingReason", "全部应盘项目已有结果或明确未盘原因，进入差异复核")); await page.onWaiting();
assert.equal(page.data.error, ""); assert.equal(page.data.view.task.task_status, "WAITING_REVIEW"); assert.equal(writes.length, 4);
page.onUnload();
console.log(JSON.stringify({ nativeUnobservedOriginalReplyRecovered: true, originalKeyPayloadVersionRetained: true,
    explicitWaitingReview: true, physicalMissingStillOpen: true, noNormalAcceptanceClaimed: true }));
