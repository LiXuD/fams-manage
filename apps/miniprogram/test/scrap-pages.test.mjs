import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { createScrapPage } from "../src/scrap-pages.ts";
import { MiniRequestError } from "../src/session.ts";

const event = dataset => ({ currentTarget: { dataset } });
const field = (name, value) => ({ ...event({ field: name }), detail: { value } });
function fixture() {
    const calls = [];
    let keys = 0;
    const row = {
        id: "9007199254740997", assetId: "9007199254740999", version: 2, assetVersion: 3,
        ownershipVersion: 4, lockVersion: 5, status: "PROCESSING", processorAccountId: "1",
        source: { assetCode: "ORIGINAL-1", name: "原机", serialNumber: "SERIAL-1" },
        reason: "原机损坏", observedCondition: "不能继续使用", proposedDisposition: "待处置",
        actions: { decide: false, claim: false, execute: true },
        decision: { decision: { approvedDisposition: "核对后待处置" } },
    };
    const sessions = {
        identity: { accountId: "1", bindingId: "2" }, identityRevision: 0,
        async request(method, path, body, mutation) {
            calls.push(structuredClone({ method, path, body, mutation }));
            if (method === "POST") return { id: row.id };
            if (path.includes("/eligible")) return { items: [{ id: row.assetId, assetCode: "ORIGINAL-1", name: "原机", version: 7 }], total: 1 };
            if (path.includes("/execution-catalog/location")) return { items: [{ id: "11", name: "核实地点", version: 8 }], total: 1 };
            if (path.includes("/execution-catalog/employee")) return { items: [{ id: "12", name: "实物保管人", version: 9 }], total: 1 };
            return path.includes("?") ? { items: [structuredClone(row)], total: 1 } : structuredClone(row);
        },
    };
    const services = { sessions, createKey: () => "scrap-key-" + ++keys };
    const page = createScrapPage(() => services);
    page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
    page.onLoad({ id: row.id });
    return { page, row, calls, sessions, services };
}
const writes = fixture => fixture.calls.filter(item => item.method === "POST");
test("native submit requires confirmation and preserves exact decimal asset id and source version", async () => {
    const f = fixture();
    await f.page.onShow();
    await f.page.onAssets();
    f.page.onAsset(event({ id: f.row.assetId }));
    for (const [name, value] of Object.entries({ reason: "不能继续使用", condition: "原机损坏", proposed: "等待处置" })) f.page.onField(field(name, value));
    f.page.onPrepare(event({ action: "submit" }));
    assert.equal(writes(f).length, 0); assert.match(f.page.data.confirmation, /ORIGINAL-1/);
    await f.page.onConfirm();
    assert.equal(writes(f)[0].path, "/api/v1/mini/scrap-requests");
    assert.deepEqual(writes(f)[0].body, { assetId: f.row.assetId, assetVersion: 7, reason: "不能继续使用", observedCondition: "原机损坏", proposedDisposition: "等待处置" });
    assert.equal(writes(f)[0].mutation.version, 0);
});
async function executionFacts(f) {
    await f.page.onShow();
    await f.page.onCatalog(event({ kind: "location" }));
    await f.page.onCatalog(event({ kind: "employee" }));
    f.page.onChoice({ ...event({ kind: "location" }), detail: { value: "0" } });
    f.page.onChoice({ ...event({ kind: "employee" }), detail: { value: "0" } });
    for (const [name, value] of Object.entries({ observed: "ORIGINAL-1", serial: "SERIAL-1", actualDate: "2026-11-05", actualTime: "10:20:30", evidence: "已现场核实同一原机实物、位置及待处置责任安排" })) f.page.onField(field(name, value));
    f.page.onChecked({ detail: { value: true } });
}
test("execution freezes custody versions and keeps its original operation after hide, reread and committed reply loss", async () => {
    const f = fixture(), request = f.sessions.request;
    let lost = true;
    f.sessions.request = async (...args) => {
        const result = await request(...args);
        if (args[0] === "POST" && lost) { lost = false; f.row.version = 90; f.row.actions.execute = false; throw Error("committed reply lost"); }
        return result;
    };
    await executionFacts(f);
    f.page.onPrepare(event({ action: "execute" })); await f.page.onConfirm();
    assert.equal(f.page.data.retry, true);
    f.page.onHide(); assert.equal(f.page.data.detail, null);
    await f.page.onShow();
    assert.match(f.page.data.pendingSummary, /核实地点/);
    f.page.onField(field("observed", "MUTATED"));
    await f.page.onRetry();
    assert.deepEqual(writes(f)[0], writes(f)[1]);
    assert.deepEqual(writes(f)[0].mutation, { key: "scrap-key-1", version: 2 });
    assert.equal(writes(f)[0].body.observedCode, "ORIGINAL-1");
    assert.deepEqual([writes(f)[0].body.assetVersion, writes(f)[0].body.ownershipVersion, writes(f)[0].body.lockVersion], [3, 4, 5]);
    assert.deepEqual([writes(f)[0].body.locationId, writes(f)[0].body.locationVersion, writes(f)[0].body.custodianEmployeeId, writes(f)[0].body.custodianVersion], ["11", 8, "12", 9]);
    assert.equal(f.page.data.retry, false);
});
test("conflicts require fresh reads and identity changes discard pending and late catalogs", async () => {
    const f = fixture(), request = f.sessions.request;
    await executionFacts(f);
    f.sessions.request = async (...args) => {
        if (args[0] === "POST") throw new MiniRequestError(409, "SCRAP_REQUEST_CHANGED");
        return request(...args);
    };
    f.page.onPrepare(event({ action: "execute" })); await f.page.onConfirm();
    assert.equal(f.page.data.stale, true); assert.equal(f.page.data.retry, false);
    f.page.onPrepare(event({ action: "execute" })); assert.equal(f.page.data.confirmation, "");
    await f.page.onRefresh();
    let resolve;
    f.sessions.request = () => new Promise(value => { resolve = value; });
    const work = f.page.onCatalog(event({ kind: "location" }));
    await new Promise(value => setImmediate(value));
    f.sessions.identityRevision++;
    resolve({ items: [{ id: "99", name: "旧身份迟到位置", version: 1 }], total: 1 });
    await work;
    assert.deepEqual(f.page.data.places, []); assert.equal(f.page.data.detail, null);
});
test("registered native Page and all bound WXML handlers are present", async () => {
    const f = fixture();
    const manifest = JSON.parse(fs.readFileSync(new URL("../native/app.json", import.meta.url), "utf8"));
    assert.ok(manifest.pages.includes("pages/scrap-requests/index"));
    globalThis.getApp = () => ({ getServices: () => f.services });
    let page;
    globalThis.Page = value => { page = value; };
    try {
        await import("../src/pages/scrap-requests/index.ts");
        const text = fs.readFileSync(new URL("../native/pages/scrap-requests/index.wxml", import.meta.url), "utf8");
        for (const match of text.matchAll(/bind(?:tap|input|change)="([^"]+)"/g)) assert.equal(typeof page[match[1]], "function", match[1]);
        assert.match(fs.readFileSync(new URL("../native/pages/request-center/index.wxml", import.meta.url), "utf8"), /pages\/scrap-requests\/index/);
    } finally {
        delete globalThis.Page; delete globalThis.getApp;
    }
});

test("native loss write-off retries its confirmed facts and distinct version headers", async () => {
    const f = fixture(), request = f.sessions.request;
    f.row.sourceType = "LOSS"; f.row.actions.execute = false; f.row.actions.executeLoss = true;
    let lost = true;
    f.sessions.request = async (...args) => {
        const result = await request(...args);
        if (args[0] === "POST" && lost) {
            lost = false; f.row.version = 3; f.row.actions.executeLoss = false;
            throw Error("committed reply lost");
        }
        return result;
    };
    await f.page.onShow();
    const evidence = "复核原报失确认与借用责任后办理遗失核销";
    f.page.onField(field("evidence", evidence));
    f.page.onPrepare(event({ action: "executeLoss" }));
    assert.equal(writes(f).length, 0);
    assert.match(f.page.data.confirmation, /终止原借用/);
    await f.page.onConfirm();
    assert.equal(f.page.data.retry, true);
    f.page.onHide(); await f.page.onShow(); await f.page.onRetry();
    assert.deepEqual(writes(f)[0], writes(f)[1]);
    assert.equal(writes(f)[0].path, "/api/v1/mini/scrap-requests/" + f.row.id + "/execute-loss");
    assert.equal(writes(f)[0].mutation.version, 2);
    assert.deepEqual(writes(f)[0].body, { assetVersion: 3, lockVersion: 5, ownershipVersion: 4, evidence });
});
