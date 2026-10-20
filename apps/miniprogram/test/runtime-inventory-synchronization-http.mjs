// Native handler + production opaque transport -> owned Spring/MySQL; HTTPS is a loopback test bridge.
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync, renameSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { MiniSessionClient } from "../src/session.ts";
import { InventoryQueue } from "../src/inventory-queue.ts";
import { createInventorySynchronizationPage } from "../src/inventory-synchronization-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, task = process.env.FAMS_TEST_INVENTORY_TASK;
assert.match(port ?? "", /^[0-9]{1,5}$/); assert.match(task ?? "", /^[1-9][0-9]*$/);
const saved = JSON.parse(process.env.FAMS_TEST_MINI_GRANT), writes = [];
let drop = true;
const root = mkdtempSync(join(tmpdir(), "fams-native-sync-")), disk = join(root, "queue.json");
try {
    const sessions = new MiniSessionClient("https://synchronization-test.invalid", {
        readSession: () => saved, writeSession() { throw new Error("No new login allowed"); }, removeSession() {}, clearIdentityData() {},
        async request(input) {
            assert.ok(input.url.startsWith("https://synchronization-test.invalid/api/v1/mini/"));
            const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice("https://synchronization-test.invalid".length), {
                method: input.method, headers: { "Content-Type": "application/json", ...input.header },
                body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error",
            });
            const value = { statusCode: response.status, data: await response.json() };
            if (input.method === "POST") {
                assert.equal(response.status, 200, JSON.stringify(value.data));
                writes.push(structuredClone({ path: input.url, body: input.data,
                    key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] }));
                if (drop) { drop = false; throw new Error("Owned adapter lost the actual committed HTTP reply"); }
            }
            return value;
        },
    });
    const deviceId = "22222222-2222-4222-8222-222222222222";
    const queue = new InventoryQueue(saved, deviceId, {
        read() { try { return JSON.parse(readFileSync(disk, "utf8")); } catch (error) { if (error.code === "ENOENT") return null; throw error; } },
        write(key, value) { assert.ok(key.startsWith("fams.identity.")); writeFileSync(disk + ".new", JSON.stringify(value)); renameSync(disk + ".new", disk); },
        photoExists: async () => false, removePhoto: async () => { throw new Error("No photos owned by empty queue"); },
    }, { now: () => Date.now() });
    queue.cache({ id: task, name: "Owned sync task", task_status: "IN_PROGRESS", items: [] });
    const key = process.env.FAMS_TEST_SYNC_KEY; assert.match(key ?? "", /^[0-9a-f-]{36}$/);
    const page = createInventorySynchronizationPage(() => ({ sessions, inventory: () => ({ queue, deviceId }),
        createKey: () => key, confirm: async () => true }));
    page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
    page.onLoad({ taskId: task }); await page.onShow(); assert.equal(page.data.error, "");
    assert.equal(page.data.status.device.current, false); await page.onDeclare();
    assert.equal(page.data.retry, true); assert.equal(writes.length, 1);
    page.onHide(); assert.equal(page.data.status, null); await page.onShow();
    assert.equal(page.data.status.device.current, true); assert.equal(page.data.retry, true);
    await page.onRetry(); assert.equal(page.data.retry, false); assert.equal(writes.length, 2);
    assert.deepEqual(writes[0], writes[1]); assert.equal(typeof writes[0].body.observationWatermark, "string");
    page.onUnload();
    console.log(JSON.stringify({ nativeSynchronizationOriginalReplyRecovered: true, originalKeyPayloadVersionRetained: true,
        durableQueueRead: true, currentStateSeparatelyRevalidated: true }));
} finally { rmSync(root, { recursive: true, force: true }); }
