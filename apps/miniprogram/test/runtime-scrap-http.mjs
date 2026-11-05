// Real Page -> opaque session -> owned HTTP/MySQL. The SDK and HTTPS hostname are test adapters.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";
import { createScrapPage } from "../src/scrap-pages.ts";

const port = process.env.FAMS_TEST_API_PORT;
assert.match(port ?? "", /^[0-9]{1,5}$/);
const grants = JSON.parse(process.env.FAMS_TEST_SCRAP_GRANTS);
const origin = "https://scrap-test.invalid";
const writes = [];
let drop = true;
const event = dataset => ({ currentTarget: { dataset } });
const field = (name, value) => ({ ...event({ field: name }), detail: { value } });
function services(role) {
    const storage = new Map([["fams.session", grants[role]]]);
    const wx = {
        getStorageSync: key => storage.get(key),
        setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
        removeStorageSync: key => storage.delete(key),
        request: input => {
            void (async () => {
                assert.ok(input.url.startsWith(origin + "/api/v1/mini/scrap-requests"));
                const response = await fetch("http://127.0.0.1:" + port + input.url.slice(origin.length), {
                    method: input.method, headers: { "Content-Type": "application/json", ...input.header },
                    body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error",
                });
                const data = await response.json();
                if (input.method === "POST" && input.url.endsWith("/execute")) {
                    writes.push({ path: input.url, key: input.header["Idempotency-Key"],
                        version: input.header["If-Match-Version"], body: structuredClone(input.data) });
                    if (drop && response.status === 200) {
                        drop = false; input.fail(Error("Owned adapter dropped committed scrap response")); return;
                    }
                }
                input.success({ statusCode: response.status, data });
            })().catch(input.fail);
        },
    };
    return { sessions: new MiniSessionClient(origin, createWxRuntime(wx)), createKey: randomUUID };
}
async function page(role, id) {
    const service = services(role), value = createScrapPage(() => service);
    value.data = structuredClone(value.data); value.setData = update => Object.assign(value.data, update);
    value.onLoad({ id }); await value.onShow(); okay(value); return value;
}
function okay(value) { assert.equal(value.data.error, ""); assert.equal(value.data.retry, false); }
async function act(value, action) {
    value.onPrepare(event({ action })); assert.ok(value.data.confirmation, value.data.error);
    await value.onConfirm();
}
const employee = await page("employee");
await employee.onAssets(); okay(employee);
employee.onAsset(event({ id: "951020" }));
for (const [name, value] of Object.entries({ reason: "已核对原机无法继续使用", condition: "同一原机实际损坏", proposed: "核对后待处置" })) employee.onField(field(name, value));
await act(employee, "submit"); okay(employee);
const id = employee.data.detail.id;
const admin = await page("admin", id);
admin.onField(field("decisionReason", "管理员已核对申请事实及处置安排"));
admin.onField(field("approved", "核对后待处置"));
await act(admin, "approve"); okay(admin);
const warehouse = await page("warehouse", id);
await act(warehouse, "claim"); okay(warehouse);
await warehouse.onCatalog(event({ kind: "location" }));
await warehouse.onCatalog(event({ kind: "employee" }));
const place = warehouse.data.places.findIndex(item => item.id === "951001");
const custodian = warehouse.data.people.findIndex(item => item.id === "951003");
assert.ok(place >= 0 && custodian >= 0);
warehouse.onChoice({ ...event({ kind: "location" }), detail: { value: String(place) } });
warehouse.onChoice({ ...event({ kind: "employee" }), detail: { value: String(custodian) } });
warehouse.onField(field("observed", "REP-ASSET-951020"));
warehouse.onField(field("evidence", "现场亲见原机并核对同一身份及实际保管安排"));
warehouse.onNow(); warehouse.onChecked({ detail: { value: true } });
await act(warehouse, "execute");
assert.equal(warehouse.data.retry, true);
const original = structuredClone(writes[0]);
await warehouse.onRefresh();
warehouse.onHide(); await warehouse.onShow();
assert.equal(warehouse.data.detail.status, "EXECUTED");
assert.match(warehouse.data.pendingSummary, /核实|地点|保管/);
await warehouse.onRetry(); okay(warehouse);
assert.deepEqual(writes[1], original);
assert.equal(writes.length, 2);
assert.equal(warehouse.data.detail.disposition.disposition_status, "PENDING_DISPOSITION");
for (const value of [employee, admin, warehouse]) value.onUnload();
console.log(JSON.stringify({ nativeScrapExecuted: true, originalReceiptReplayed: true,
    separatePendingDisposition: true, requestId: id }));
