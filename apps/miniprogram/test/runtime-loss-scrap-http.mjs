// Actual registered Pages and opaque sessions against owned Spring/MySQL.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";

const port = process.env.FAMS_TEST_API_PORT, reportId = process.env.FAMS_TEST_LOSS_REPORT;
const grants = JSON.parse(process.env.FAMS_TEST_LOSS_GRANTS), origin = "https://loss-scrap-test.invalid";
assert.match(port, /^\d{1,5}$/);
assert.match(reportId, /^[1-9]\d*$/);
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
                if (input.method === "POST" && (url.includes("/from-loss/") || url.endsWith("/execute-loss"))) {
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
    const value = { sessions: new MiniSessionClient(origin, createWxRuntime(wx)), createKey: randomUUID };
    clients.set(role, value);
    return value;
}

async function open(kind, role, id) {
    let page;
    globalThis.getApp = () => ({ getServices: () => services(role) });
    globalThis.Page = value => { page = value; };
    if (kind === "loss") await import("../src/pages/loss-reports/index.ts?" + randomUUID());
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
    const employee = await open("loss", "employee", reportId);
    const source = employee.data.detail;
    assert.equal(source.actions.requestScrap, true);
    field(employee, "scrapReason", "持续查找原借出实物仍未找回");
    field(employee, "scrapCondition", "已确认原资产遗失并继续保留原借用责任");
    await confirm(employee, "requestScrap");
    const submitPath = "/api/v1/mini/scrap-requests/from-loss/" + reportId;
    await retry(employee, submitPath);
    assert.equal(writes.get(submitPath)[0].version, String(source.version));
    assert.equal(writes.get(submitPath)[0].body.assetVersion, source.recoveryAsset.version);
    assert.equal(employee.data.detail.id, reportId);
    assert.equal(employee.data.detail.recoveryAsset.lifecycle, "PENDING_SCRAP");
    assert.equal(employee.data.detail.actions.requestScrap, false);
    const scrap = employee.data.scrapReceiptId;
    assert.match(scrap, /^[1-9]\d*$/);
    const submitted = await open("scrap", "employee", scrap);
    assert.equal(submitted.data.detail.sourceBusinessId, reportId);
    assert.equal(submitted.data.detail.status, "PENDING_ADMIN_DECISION");
    submitted.onUnload();
    employee.onUnload();

    const administrator = await open("scrap", "admin", scrap);
    field(administrator, "decisionReason", "核对原报失确认和借用记录后批准遗失核销");
    await confirm(administrator, "approve");
    okay(administrator);
    assert.equal(administrator.data.detail.status, "WAITING_WAREHOUSE_CLAIM");
    administrator.onUnload();

    const warehouse = await open("scrap", "warehouse", scrap);
    await confirm(warehouse, "claim");
    okay(warehouse);
    assert.equal(warehouse.data.detail.actions.executeLoss, true);
    assert.equal(warehouse.data.detail.actions.execute, false);
    field(warehouse, "evidence", "已复核原报失证据和唯一原借用责任，办理遗失核销");
    await confirm(warehouse, "executeLoss");
    await retry(warehouse, "/api/v1/mini/scrap-requests/" + scrap + "/execute-loss");
    assert.equal(warehouse.data.detail.status, "EXECUTED");
    assert.equal(warehouse.data.detail.disposition.disposition_status, "LOSS_WRITTEN_OFF");
    assert.equal(warehouse.data.detail.disposition.due_date, null);
    warehouse.onUnload();
    console.log(JSON.stringify({ nativeLossScrap: true, originalSubmitRetry: true, originalExecuteRetry: true }));
} finally {
    delete globalThis.getApp;
    delete globalThis.Page;
    for (const value of clients.values()) value.sessions.clear();
}
