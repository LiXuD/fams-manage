import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRepairFactoryPage } from "../src/repair-factory-pages.ts";
import { MiniRequestError } from "../src/session.ts";

const event = dataset => ({ currentTarget: { dataset } });
const field = (name, value) => ({ ...event({ field: name }), detail: { value } });
const object = "00000000-0000-4000-8000-000000000001", place = "9007199254740995", roundId = "9007199254740993";
function fixture(mode = "scope") {
    const round = { id: roundId, roundNumber: 2, roundStatus: mode === "receive" ? "DISPATCHED" : "PENDING_DISPATCH", version: 10,
        providerName: "私有厂家", serviceContact: "私有联系人", servicePhone: "13800000001", expectedReturnDate: "2026-11-01", fault: "原机无法启动",
        dispatchedAt: null, receivedAt: null, conditionResult: null, treatmentResult: null, cancelReason: null, actualCost: null,
        originalIdentity: { assetCode: "ORIGINAL-1", serialNumber: "SERIAL-1" },
        files: [{ objectId: object, filename: "厂家原件.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" }],
        evidence: [{ object_id: object, purpose: mode === "receive" ? "RECEIPT" : "FACTORY_ACK" }] };
    const detail = { repairId: "9", version: 5, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, status: "WAREHOUSE_PROCESSING",
        pendingScope: null, approvedScope: { id: "27", version: 1, scopeStatus: "APPROVED", decisionReason: "明确批准无费用返厂" },
        activeRound: mode === "scope" ? null : round, summary: null, photosAvailable: true,
        actions: { requestScope: mode === "scope", uploadEvidence: mode === "upload", dispatch: mode === "dispatch", receive: mode === "receive" } };
    const calls = [], discarded = [], staged = []; let keys = 0;
    const sessions = { identity: { accountId: "1", bindingId: "2" }, identityRevision: 0,
        async request(method, path, body, mutation) {
            calls.push(structuredClone({ method, path, body, mutation }));
            if (method === "POST") return { round: mode === "scope" ? undefined : { id: roundId } };
            if (path.includes("physical-catalog/location")) return { items: [{ id: place, name: "实际收回地点", version: 11 }], total: 1 };
            if (path.endsWith("/grant")) return { token: "valid.signed" };
            if (path.includes("/history?")) return { items: [], total: 21 };
            if (path.includes("/rounds?")) return { items: mode === "scope" ? [] : [structuredClone(round)], total: 21 };
            if (path.includes("/rounds/")) return structuredClone(round);
            return structuredClone(detail);
        },
        stageFactoryEvidence(file) { const copy = Object.freeze({ ...file, path: "/owned/copied.png", contentType: "image/png" }); staged.push(copy); return copy; },
        async uploadFactoryEvidence(...args) { calls.push({ upload: structuredClone(args) }); return { round: { id: roundId } }; },
        async downloadFactoryEvidence(...args) { calls.push({ download: args }); return "/owned/preview.png"; },
        discardFactoryEvidence(path) { discarded.push(path); },
    };
    const services = { sessions, createKey: () => "00000000-0000-4000-8000-" + String(++keys).padStart(12, "0"),
        chooseEvidence: async () => ({ path: "/selected/original.png", name: "实际原件.png", size: 3 }), navigate() {} };
    const page = createRepairFactoryPage(() => services); page.data = structuredClone(page.data); page.setData = x => Object.assign(page.data, x);
    page.onLoad({ id: "9" }); return { page, sessions, services, calls, discarded, staged, detail, round };
}
const writes = f => f.calls.filter(c => c.method === "POST");
async function scope(f) { await f.page.onShow(); f.page.onField(field("reason", "实物接修后仍无法内部修复，需要明确返厂范围。")); f.page.onPrepare(event({ action: "requestScope" })); }
async function physical(f, receive = false) {
    await f.page.onShow();
    for (const [name, value] of Object.entries({ observedCode: "ORIGINAL-1", serial: "SERIAL-1", actualDate: "2026-10-25", actualTime: "11:12:13",
        evidence: "已亲自核对原机编码和序列号、本轮对应原件及实际办理事实。" })) f.page.onField(field(name, value));
    f.page.onChecked({ detail: { value: receive ? ["original", "noCost"] : ["original"] } });
    f.page.onEvidence({ detail: { value: [object] } });
    if (receive) { await f.page.onLocations(event({})); f.page.onLocation(event({ id: place })); }
}
test("factory scope waits for inline confirmation and freezes independent versions", async () => {
    const f = fixture(); await scope(f); assert.equal(writes(f).length, 0); assert.ok(f.page.data.confirmation);
    await f.page.onConfirm(); assert.deepEqual(writes(f)[0].body, { assetVersion: 7, ownershipVersion: 2, lockVersion: 3, reason: "实物接修后仍无法内部修复，需要明确返厂范围。" });
    assert.equal(writes(f)[0].mutation.version, 5); assert.equal(f.page.data.retry, false);
});
test("lost committed scope reply survives readonly refresh and hide/show with identical original request", async () => {
    const f = fixture(), original = f.sessions.request; let lost = true;
    f.sessions.request = async (...args) => { const result = await original(...args); if (args[0] === "POST" && lost) { lost = false; f.detail.version = 99; f.detail.assetVersion = 100; throw new Error("committed response lost"); } return result; };
    await scope(f); await f.page.onConfirm(); assert.equal(f.page.data.retry, true);
    await f.page.onRefresh(); f.page.onHide(); assert.equal(f.page.data.detail, null); await f.page.onShow(); await f.page.onRetry();
    assert.deepEqual(writes(f)[0], writes(f)[1]); assert.equal(f.page.data.retry, false);
});
test("upload pending keeps owned copy through hide and old version reread then discards only after success", async () => {
    const f = fixture("upload"), upload = f.sessions.uploadFactoryEvidence; let lost = true;
    f.sessions.uploadFactoryEvidence = async (...args) => { const result = await upload(...args); if (lost) { lost = false; f.detail.version = 90; f.round.version = 91; throw new Error("lost uploaded reply"); } return result; };
    await f.page.onShow(); await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" })); await f.page.onConfirm();
    f.page.onHide(); assert.ok(!f.discarded.includes("/owned/copied.png")); await f.page.onShow(); await f.page.onRetry();
    const uploads = f.calls.filter(c => c.upload); assert.deepEqual(uploads[0], uploads[1]);
    assert.equal(uploads[0].upload[2].roundVersion, 10); assert.ok(f.discarded.includes("/owned/copied.png")); assert.equal(f.page.data.retry, false);
});
test("same-account credential renewal discards old unknown file and never replays it", async () => {
    const f = fixture("upload"); f.sessions.uploadFactoryEvidence = async () => { throw new Error("unknown"); };
    await f.page.onShow(); await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" })); await f.page.onConfirm(); f.page.onHide();
    f.sessions.identityRevision++; await f.page.onShow(); assert.equal(f.page.data.retry, false); assert.ok(f.discarded.includes("/owned/copied.png"));
});
test("401 that has already cleared session still wipes private detail and retry in finally", async () => {
    const f = fixture("upload"); await f.page.onShow(); await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" }));
    f.sessions.uploadFactoryEvidence = async () => { f.sessions.identity = null; f.sessions.identityRevision++; throw new MiniRequestError(401, "AUTH_SESSION_INVALID"); };
    await f.page.onConfirm(); assert.equal(f.page.data.detail, null); assert.equal(f.page.data.retry, false); assert.ok(f.discarded.includes("/owned/copied.png"));
});
test("current ownership loss clears private contacts original preview and frozen request", async () => {
    const f = fixture("upload"); await f.page.onShow(); await f.page.onFile(event({ id: object })); assert.equal(f.page.data.previewPath, "/owned/preview.png");
    f.sessions.request = async () => { throw new MiniRequestError(403, "REPAIR_NOT_PROCESSOR"); }; await f.page.onRefresh();
    assert.equal(f.page.data.detail, null); assert.equal(f.page.data.selected, null); assert.equal(f.page.data.previewPath, ""); assert.ok(f.discarded.includes("/owned/preview.png"));
});
test("late hidden-page original is immediately discarded without reviving old private fields", async () => {
    const f = fixture("upload"); await f.page.onShow(); let release;
    f.sessions.downloadFactoryEvidence = () => new Promise(resolve => { release = resolve; });
    const loading = f.page.onFile(event({ id: object })); await Promise.resolve(); f.page.onHide(); release("/owned/late.png"); await loading;
    assert.equal(f.page.data.previewPath, ""); assert.equal(f.page.data.detail, null); assert.ok(f.discarded.includes("/owned/late.png"));
});
test("original receipt requires explicit condition, real location version and correct round evidence", async () => {
    const f = fixture("receive"); await physical(f, true); f.page.onPrepare(event({ action: "receive" })); assert.equal(f.page.data.confirmation, "");
    f.page.onCondition({ detail: { value: "2" } }); f.page.onPrepare(event({ action: "receive" })); await f.page.onConfirm();
    assert.deepEqual(writes(f)[0].body, { assetVersion: 7, ownershipVersion: 2, lockVersion: 3, roundVersion: 10,
        observedCode: "ORIGINAL-1", observedSerial: "SERIAL-1", occurredAt: new Date("2026-10-25T11:12:13").toISOString(),
        evidenceIds: [object], originalPhysicalIdentityChecked: true, actualLocationId: place, locationVersion: 11, conditionResult: "STILL_FAULTY", actualCost: "0.00",
        treatmentResult: "已亲自核对原机编码和序列号、本轮对应原件及实际办理事实。" });
});
test("wrong-purpose original and malformed calendar day cannot prepare a physical write", async () => {
    const f = fixture("dispatch"); f.round.evidence[0].purpose = "RECEIPT"; await physical(f);
    f.page.onPrepare(event({ action: "dispatch" })); assert.equal(f.page.data.confirmation, ""); assert.equal(writes(f).length, 0);
    f.round.evidence[0].purpose = "FACTORY_ACK"; await physical(f); f.page.onField(field("actualDate", "2026-02-31"));
    f.page.onPrepare(event({ action: "dispatch" })); assert.equal(f.page.data.confirmation, ""); assert.equal(writes(f).length, 0);
});
test("round and private history pages remain independent", async () => {
    const f = fixture("upload"); await f.page.onShow(); await f.page.onPage(event({ kind: "rounds", delta: "1" }));
    await f.page.onPage(event({ kind: "history", delta: "1" }));
    assert.equal(f.page.data.page, 2); assert.equal(f.page.data.historyPage, 2);
    assert.ok(f.calls.some(c => c.path?.endsWith("/rounds?page=2&pageSize=20")));
    assert.ok(f.calls.some(c => c.path?.endsWith("/history?page=2&pageSize=20")));
});
test("unconfirmed upload is canceled and cleaned on hide without sending anything", async () => {
    const f = fixture("upload"); await f.page.onShow(); await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" })); f.page.onHide();
    assert.ok(f.discarded.includes("/owned/copied.png")); assert.equal(f.calls.filter(c => c.upload).length, 0);
});
test("native manifest and markup bind only actual private Page handlers", () => {
    const config = JSON.parse(readFileSync(new URL("../native/app.json", import.meta.url), "utf8"));
    assert.ok(config.pages.includes("pages/repair-factory/index"));
    const markup = readFileSync(new URL("../native/pages/repair-factory/index.wxml", import.meta.url), "utf8"), f = fixture();
    for (const match of markup.matchAll(/bind(?:tap|input|change)="([^"]+)"/g)) assert.equal(typeof f.page[match[1]], "function", match[1]);
    assert.match(markup, /confirmation/); assert.match(markup, /detail\.actions\.receive/);
});
test("current-time choice preserves the actual instant instead of preceding same-second round creation", async t => {
    const instant = Date.parse("2026-10-25T03:04:05.678Z"); t.mock.timers.enable({ apis: ["Date"], now: instant });
    const f = fixture("dispatch"); await physical(f); f.page.onNow(); f.page.onPrepare(event({ action: "dispatch" })); await f.page.onConfirm();
    assert.equal(writes(f)[0].body.occurredAt, new Date(instant).toISOString());
});
test("identity change while inline confirmation is open wipes the old private facts and sends nothing", async () => {
    const f = fixture("upload"); await f.page.onShow(); await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" }));
    f.sessions.identity = { accountId: "8", bindingId: "9" }; f.sessions.identityRevision++;
    await f.page.onConfirm(); assert.equal(f.page.data.detail, null); assert.equal(f.page.data.confirmation, "");
    assert.ok(f.discarded.includes("/owned/copied.png")); assert.equal(f.calls.filter(c => c.upload).length, 0);
});
test("post succeeded but authoritative reread failed still retains the original request", async () => {
    const f = fixture(), request = f.sessions.request; let posted = false, failed = false;
    f.sessions.request = async (...args) => { const result = await request(...args); if (args[0] === "POST") { posted = true; f.detail.version = 50; }
        else if (posted && !failed && args[1].endsWith("/factory")) { failed = true; throw new Error("reread unavailable"); } return result; };
    await scope(f); await f.page.onConfirm(); assert.equal(f.page.data.retry, true);
    await f.page.onRefresh(); await f.page.onRetry(); assert.deepEqual(writes(f)[0], writes(f)[1]); assert.equal(f.page.data.retry, false);
});
