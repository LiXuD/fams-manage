// Registered Page handlers against the isolated product API and real opaque sessions.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";

const { batchId, rows } = JSON.parse(process.env.FAMS_TEST_INITIAL_SCRAP);
const grants = JSON.parse(process.env.FAMS_TEST_SCRAP_GRANTS);
const port = process.env.FAMS_TEST_API_PORT;
assert.match(port, /^\d{1,5}$/);
const origin = "https://initial-scrap-test.invalid";
const clients = new Map();
const attempts = new Map();
const event = dataset => ({ currentTarget: { dataset } });
const field = (page, name, value) => page.onField({ ...event({ field: name }), detail: { value } });
const okay = page => { assert.equal(page.data.error, ""); assert.equal(page.data.retry, false); };

function services(role) {
    if (clients.has(role)) return clients.get(role);
    const store = new Map([["fams.session", grants[role]]]);
    const runtime = createWxRuntime({
        getStorageSync: key => store.get(key),
        setStorageSync: (key, value) => store.set(key, structuredClone(value)),
        removeStorageSync: key => store.delete(key),
        request(input) {
            void (async () => {
                assert.ok(input.url.startsWith(origin + "/api/v1/mini/scrap-requests"));
                const path = input.url.slice(origin.length);
                const reply = await fetch("http://127.0.0.1:" + port + path, {
                    method: input.method, headers: { "Content-Type": "application/json", ...input.header },
                    body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error",
                });
                const data = await reply.json();
                if (input.method === "POST" && /\/execute(?:-factory|-loss)?$/.test(path)) {
                    const writes = attempts.get(path) ?? [];
                    writes.push({ key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"],
                        body: structuredClone(input.data), status: reply.status, receipt: data });
                    attempts.set(path, writes);
                    if (writes.length === 1 && reply.ok) {
                        input.fail(Error("Owned adapter dropped committed reply"));
                        return;
                    }
                }
                input.success({ statusCode: reply.status, data });
            })().catch(input.fail);
        },
    });
    const result = { sessions: new MiniSessionClient(origin, runtime), createKey: randomUUID };
    clients.set(role, result);
    return result;
}

async function open(role, row) {
    let page;
    globalThis.getApp = () => ({ getServices: () => services(role) });
    globalThis.Page = value => { page = value; };
    await import("../src/pages/scrap-requests/index.ts?" + randomUUID());
    assert.ok(page);
    page.data = structuredClone(page.data);
    page.setData = update => Object.assign(page.data, update);
    page.onLoad({ id: row.id });
    await page.onShow();
    okay(page);
    assert.equal(page.data.detail.source.assetCode, row.code);
    assert.deepEqual(page.data.detail.initialSource, { batchId, sheetRow: row.sheetRow });
    return page;
}

async function confirm(page, action) {
    page.onPrepare(event({ action }));
    assert.ok(page.data.confirmation, page.data.error);
    await page.onConfirm();
}

assert.equal(rows.length, 5);
for (const row of rows) {
    const admin = await open("admin", row);
    field(admin, "decisionReason", "复核期初来源、原保管责任或厂家凭证后批准");
    if (row.sourceType === "REPAIR") field(admin, "approved", "待处置保管");
    await confirm(admin, "approve");
    okay(admin);
    admin.onUnload();

    const warehouse = await open("warehouse", row);
    if (warehouse.data.detail.actions.claim) {
        await confirm(warehouse, "claim");
        okay(warehouse);
    }
    let action, endpoint, disposition;
    if (row.sourceType === "FACTORY_REPLACEMENT") {
        action = "executeFactory";
        endpoint = "execute-factory";
        disposition = "DISPOSED";
        assert.equal(warehouse.data.detail.initialFactoryDispositionAt, "2026-09-13T01:00:00.123Z");
        assert.ok(warehouse.data.detail.initialFactoryDispositionEvidence);
    } else if (row.sourceType === "LOSS") {
        action = "executeLoss";
        endpoint = "execute-loss";
        disposition = "LOSS_WRITTEN_OFF";
        field(warehouse, "evidence", "遗失确认记录与原保管责任已逐项复核");
    } else {
        action = endpoint = "execute";
        disposition = "PENDING_DISPOSITION";
        field(warehouse, "observed", row.code);
        field(warehouse, "serial", row.code + "-SN");
        field(warehouse, "evidence", "原机身份及原保管和实际安排已现场核对");
        await warehouse.onCatalog(event({ kind: "location" }));
        okay(warehouse);
        warehouse.onChoice({ ...event({ kind: "location" }), detail: { value: "0" } });
        await warehouse.onCatalog(event({ kind: "employee" }));
        okay(warehouse);
        warehouse.onChoice({ ...event({ kind: "employee" }), detail: { value: "0" } });
        warehouse.onChecked({ detail: { value: true } });
        warehouse.onNow();
    }
    assert.equal(warehouse.data.detail.actions[action], true);
    await confirm(warehouse, action);
    assert.equal(warehouse.data.retry, true, warehouse.data.error);
    warehouse.onHide();
    await warehouse.onShow();
    await warehouse.onRetry();
    okay(warehouse);
    const history = attempts.get("/api/v1/mini/scrap-requests/" + row.id + "/" + endpoint);
    assert.equal(history.length, 2);
    assert.equal(history[0].status, 200);
    assert.match(history[0].key, /^[0-9a-f-]{36}$/);
    assert.deepEqual(history[0], history[1]);
    if (action === "executeFactory") assert.equal(history[0].body.occurredAt, "2026-09-13T01:00:00.123Z");
    assert.equal(warehouse.data.detail.status, "EXECUTED");
    assert.equal(warehouse.data.detail.disposition.disposition_status, disposition);
    warehouse.onUnload();
}
assert.equal(attempts.size, 5);
console.log("D6_23_NATIVE_INITIAL_SCRAP executed=5 replayed=5");
