// Actual registered Pages and opaque sessions against owned Spring/MySQL.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";

const port = process.env.FAMS_TEST_API_PORT, repairId = process.env.FAMS_TEST_REPAIR_ID;
const grants = JSON.parse(process.env.FAMS_TEST_REPAIR_GRANTS), origin = "https://repair-scrap-test.invalid";
assert.match(port, /^\d{1,5}$/);
assert.match(repairId, /^[1-9]\d*$/);
const clients = new Map(), writes = new Map();
const event = dataset => ({ currentTarget: { dataset } });
const field = (page, name, value) => page.onField({ ...event({ field: name }), detail: { value } });
const okay = page => { assert.equal(page.data.error, ""); assert.equal(page.data.retry, false); };

function services(role) {
    if (clients.has(role)) return clients.get(role);
    const store = new Map([["fams.session", grants[role]]]);
    const wx = {
        getStorageSync: key => store.get(key),
        setStorageSync: (key, value) => store.set(key, structuredClone(value)),
        removeStorageSync: key => store.delete(key),
        request(input) {
            void (async () => {
                assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
                const url = input.url.slice(origin.length);
                const reply = await fetch("http://127.0.0.1:" + port + url, { method: input.method,
                    headers: { "Content-Type": "application/json", ...input.header }, redirect: "error",
                    body: input.data === undefined ? undefined : JSON.stringify(input.data) });
                const data = await reply.json();
                if (input.method === "POST" && (url.includes("/from-repair/") || url.endsWith("/execute"))) {
                    const attempts = writes.get(url) ?? [];
                    attempts.push({ key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"],
                        body: structuredClone(input.data), status: reply.status });
                    writes.set(url, attempts);
                    if (reply.ok && attempts.length === 1) { input.fail(Error("Owned adapter dropped committed reply")); return; }
                }
                input.success({ statusCode: reply.status, data });
            })().catch(input.fail);
        },
    };
    const value = { sessions: new MiniSessionClient(origin, createWxRuntime(wx)), createKey: randomUUID, confirm: async () => true, navigate() {} };
    clients.set(role, value);
    return value;
}

async function open(kind, role, id) {
    let page;
    globalThis.getApp = () => ({ getServices: () => services(role) });
    globalThis.Page = value => { page = value; };
    if (kind === "repair") await import("../src/pages/repairs/index.ts?" + randomUUID());
    else await import("../src/pages/scrap-requests/index.ts?" + randomUUID());
    assert.ok(page);
    page.data = structuredClone(page.data);
    page.setData = update => Object.assign(page.data, update);
    page.onLoad({ id });
    await page.onShow();
    okay(page);
    return page;
}

async function confirm(page, action) {
    page.onPrepare(event({ action }));
    assert.ok(page.data.confirmation, page.data.error);
    await page.onConfirm();
}

async function retry(page, path) {
    assert.equal(page.data.retry, true, page.data.error);
    page.onHide();
    await page.onShow();
    await page.onRetry();
    okay(page);
    assert.equal(writes.get(path).length, 2);
    assert.deepEqual(...writes.get(path));
    assert.equal(writes.get(path)[0].status, 200);
}

try {
    const operator = await open("repair", "warehouse", repairId);
    const original = operator.data.detail;
    assert.equal(original.actions.requestScrap, true);
    field(operator, "scrapReason", "已受理原机维修，现申请按实物报废");
    field(operator, "scrapCondition", "故障无法恢复，原实物及保管责任已核实");
    field(operator, "scrapMethod", "实物待处置");
    await operator.onScrap();
    const submitPath = "/api/v1/mini/scrap-requests/from-repair/" + repairId;
    await retry(operator, submitPath);
    assert.equal(writes.get(submitPath)[0].version, String(original.version));
    assert.equal(operator.data.detail.id, repairId);
    assert.equal(operator.data.detail.actions.requestScrap, false);
    const scrap = operator.data.scrapReceiptId;
    assert.match(scrap, /^[1-9]\d*$/);
    operator.onUnload();
    const administrator = await open("scrap", "admin", scrap);
    field(administrator, "decisionReason", "核对原维修与原机处置范围后批准");
    field(administrator, "approved", "实物待处置");
    await confirm(administrator, "approve");
    okay(administrator);
    assert.equal(administrator.data.detail.status, "PROCESSING");
    administrator.onUnload();
    const warehouse = await open("scrap", "warehouse", scrap);
    assert.equal(warehouse.data.detail.actions.claim, false);
    assert.equal(warehouse.data.detail.actions.execute, true);
    await warehouse.onCatalog(event({ kind: "location" }));
    await warehouse.onCatalog(event({ kind: "employee" }));
    const location = warehouse.data.places.findIndex(item => item.id === "951001");
    const employee = warehouse.data.people.findIndex(item => item.id === "951003");
    assert.ok(location >= 0 && employee >= 0);
    warehouse.onChoice({ ...event({ kind: "location" }), detail: { value: String(location) } });
    warehouse.onChoice({ ...event({ kind: "employee" }), detail: { value: String(employee) } });
    field(warehouse, "observed", "REP-ASSET-951020");
    field(warehouse, "evidence", "已经现场核对原机身份和实际保管位置及处置责任");
    warehouse.onNow();
    warehouse.onChecked({ detail: { value: true } });
    await confirm(warehouse, "execute");
    await retry(warehouse, "/api/v1/mini/scrap-requests/" + scrap + "/execute");
    assert.equal(warehouse.data.detail.status, "EXECUTED");
    assert.equal(warehouse.data.detail.disposition.disposition_status, "PENDING_DISPOSITION");
    warehouse.onUnload();
    const closed = await open("repair", "warehouse", repairId);
    assert.equal(closed.data.detail.status, "COMPLETED");
    assert.equal(closed.data.detail.actions.requestScrap, false);
    closed.onUnload();
    console.log(JSON.stringify({ nativeRepairScrap: true, originalSubmitRetry: true, originalExecuteRetry: true }));
} finally {
    delete globalThis.getApp;
    delete globalThis.Page;
    for (const value of clients.values()) value.sessions.clear();
}
