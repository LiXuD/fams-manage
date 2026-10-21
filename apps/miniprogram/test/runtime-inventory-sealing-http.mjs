// Native handlers -> opaque session -> owned real Spring/MySQL. HTTPS hostname is an explicit loopback adapter.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createInventorySealingPage } from "../src/inventory-sealing-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, task = process.env.FAMS_TEST_INVENTORY_TASK;
assert.match(port ?? "", /^[0-9]{1,5}$/); assert.match(task ?? "", /^[1-9][0-9]*$/);
const saved = JSON.parse(process.env.FAMS_TEST_MINI_GRANT), writes = []; let drop = true;
const sessions = new MiniSessionClient("https://sealing-test.invalid", {
    readSession: () => saved, writeSession() { throw new Error("No new login allowed"); }, removeSession() {}, clearIdentityData() {},
    async request(input) {
        assert.ok(input.url.startsWith("https://sealing-test.invalid/api/v1/mini/"));
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice("https://sealing-test.invalid".length), {
            method: input.method, headers: { "Content-Type": "application/json", ...input.header },
            body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error",
        });
        const value = { statusCode: response.status, data: await response.json() };
        assert.equal(response.status, 200, JSON.stringify(value.data));
        if (input.method === "POST") {
            writes.push(structuredClone({ path: input.url, body: input.data, key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] }));
            if (drop) { drop = false; throw new Error("Owned adapter lost actual committed seal response"); }
        }
        return value;
    },
});
const page = createInventorySealingPage(() => ({ sessions, createKey: () => randomUUID(), confirm: async () => true }));
page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
page.onLoad({ taskId: task }); await page.onShow(); assert.equal(page.data.error, ""); assert.equal(page.data.view.blockerCount, 0);
page.onField(field("reason", "原生端逐项核对后冻结报告")); page.onField(field("waiver", "执行人尚缺同步确认，明确保留待补证名单与安排"));
await page.onSeal(); assert.equal(page.data.retry, true); assert.equal(writes.length, 1);
page.onHide(); assert.equal(page.data.view, null); await page.onShow(); assert.equal(page.data.view.sealed, true);
await page.onRetry(); assert.equal(page.data.retry, false); assert.equal(writes.length, 2); assert.deepEqual(writes[0], writes[1]);
await page.onReadReport(); assert.equal(page.data.total, 2); assert.ok(page.data.records.every(row => row.text.includes("冻结校验")));
page.onSection({ detail: { value: "2" } }); await page.onReadReport(); assert.equal(page.data.total, 2);
assert.ok(page.data.records.some(row => row.text.includes("当前没有有效绑定")));
page.onUnload();
console.log(JSON.stringify({ nativeSealedOriginalReplyRecovered: true, exactOriginalKeyPayloadVersionAndRoster: true,
    frozenItemsRead: true, frozenMissingSynchronizationRosterRead: true }));
