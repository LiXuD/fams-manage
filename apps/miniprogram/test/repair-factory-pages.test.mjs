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
function partsFixture(canRecord = true) {
    const f = fixture("upload"), request = f.sessions.request;
    f.round.roundStatus = "RECEIVED"; f.round.receivedAt = "2026-10-26T01:00:00Z"; f.round.evidence[0].purpose = "PARTS_CHANGE";
    f.detail.activeRound = null; f.detail.actions.uploadEvidence = false;
    const parts = { page: 1, pageSize: 20, total: 21, canRecord, items: [{ id: "901", roundId, assetId: "20", partName: "原始部件事实", beforeInfo: "原始旧件", afterInfo: "原始新件", observedAt: "2026-10-26T02:00:00Z", recordedAt: "2026-10-26T02:01:00Z", evidenceIds: [object], recordedBy: "1" }] };
    f.sessions.request = async (...args) => {
        if (args[0] === "GET" && args[1].includes("/parts?")) { f.calls.push(structuredClone({ method: args[0], path: args[1] })); return structuredClone(parts); }
        return request(...args);
    };
    return { ...f, parts };
}
async function partsDraft(f) {
    await f.page.onShow(); await f.page.onParts(event({}));
    for (const [name, value] of Object.entries({ partsName: "内存", partsBefore: "原内存8GB", partsAfter: "新内存16GB", partsReason: "已亲自核实同一原机内存前后资料和本轮原件相符。", observedCode: "ORIGINAL-1", serial: "SERIAL-1", actualDate: "2026-10-27", actualTime: "11:12:13" })) f.page.onField(field(name, value));
    f.page.onPartsEvidence({ detail: { value: [object] } }); f.page.onChecked({ detail: { value: ["original"] } });
}
test("parts remain lazy and explicit confirmation freezes original-machine fact and four versions", async () => {
    const f = partsFixture(); await f.page.onShow(); assert.equal(f.calls.some(c => c.path?.includes("/parts?")), false);
    await partsDraft(f); assert.equal(f.page.data.parts.items[0].beforeInfo, "原始旧件"); f.page.onPrepare(event({ action: "parts" }));
    assert.equal(writes(f).length, 0); assert.match(f.page.data.confirmation, /原内存8GB/); await f.page.onConfirm();
    assert.deepEqual(writes(f)[0].body, { assetVersion: 7, ownershipVersion: 2, lockVersion: 3, roundVersion: 10, partName: "内存", beforeInfo: "原内存8GB", afterInfo: "新内存16GB", observedAt: new Date("2026-10-27T11:12:13").toISOString(), observedCode: "ORIGINAL-1", observedSerial: "SERIAL-1", evidenceIds: [object], reason: "已亲自核实同一原机内存前后资料和本轮原件相符。", originalPhysicalIdentityChecked: true });
    assert.equal(writes(f)[0].mutation.version, 5); assert.equal(f.page.data.parts.items[0].afterInfo, "原始新件");
});
test("unknown parts result keeps exact original request after private refresh and hide-show", async () => {
    const f = partsFixture(), request = f.sessions.request; let lost = true;
    f.sessions.request = async (...args) => { const result = await request(...args); if (args[0] === "POST" && lost) { lost = false; f.detail.version = 80; f.detail.assetVersion = 81; f.round.version = 82; throw new Error("committed parts reply lost"); } return result; };
    await partsDraft(f); f.page.onPrepare(event({ action: "parts" })); await f.page.onConfirm(); assert.equal(f.page.data.retry, true);
    await f.page.onParts(event({})); f.page.onHide(); assert.equal(f.page.data.parts, null); await f.page.onShow(); await f.page.onRetry();
    assert.deepEqual(writes(f)[1], writes(f)[0]); assert.equal(f.page.data.retry, false);
});
test("parts uploads freeze purpose and owned original across unknown reply and cleanup after success", async () => {
    const f = partsFixture(), upload = f.sessions.uploadFactoryEvidence; let lost = true;
    f.sessions.uploadFactoryEvidence = async (...args) => { const result = await upload(...args); if (lost) { lost = false; f.detail.version = 90; f.round.version = 91; throw new Error("uploaded reply lost"); } return result; };
    await f.page.onShow(); await f.page.onParts(event({})); f.page.onPurpose({ detail: { value: String(f.page.data.purposeKinds.indexOf("PARTS_CHANGE")) } });
    await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" })); await f.page.onConfirm(); f.page.onHide(); assert.ok(!f.discarded.includes("/owned/copied.png"));
    await f.page.onShow(); await f.page.onRetry(); const uploads = f.calls.filter(c => c.upload); assert.deepEqual(uploads[1], uploads[0]); assert.equal(uploads[0].upload[2].purpose, "PARTS_CHANGE"); assert.equal(uploads[0].upload[2].roundVersion, 10); assert.ok(f.discarded.includes("/owned/copied.png"));
});
test("readonly parts permission cannot be bypassed by invoking native handlers", async () => {
    const f = partsFixture(false); await partsDraft(f); f.page.onPrepare(event({ action: "parts" })); assert.equal(f.page.data.confirmation, ""); assert.equal(writes(f).length, 0); assert.equal(f.page.data.parts.items[0].partName, "原始部件事实");
});
test("parts reject wrong-purpose originals, equal descriptions and rolled-over calendar dates", async () => {
    const f = partsFixture(); f.round.evidence[0].purpose = "QUOTE"; await partsDraft(f); f.page.onPrepare(event({ action: "parts" })); assert.equal(f.page.data.confirmation, "");
    f.round.evidence[0].purpose = "PARTS_CHANGE"; await partsDraft(f); f.page.onField(field("partsAfter", "原内存8GB")); f.page.onPrepare(event({ action: "parts" })); assert.equal(f.page.data.confirmation, "");
    await partsDraft(f); f.page.onField(field("actualDate", "2026-02-31")); f.page.onPrepare(event({ action: "parts" })); assert.equal(f.page.data.confirmation, ""); assert.equal(writes(f).length, 0);
});
test("parts rejection clears retry and requires explicit fresh decision", async () => {
    const f = partsFixture(), request = f.sessions.request;
    f.sessions.request = async (...args) => { if (args[0] === "POST") throw new MiniRequestError(409, "REPAIR_VERSION_MISMATCH"); return request(...args); };
    await partsDraft(f); f.page.onPrepare(event({ action: "parts" })); await f.page.onConfirm(); assert.equal(f.page.data.retry, false); assert.equal(f.page.data.stale, true);
    f.page.onPrepare(event({ action: "parts" })); assert.equal(f.page.data.confirmation, "");
});
test("identity renewal wipes parts records and pending owned original before retry", async () => {
    const f = partsFixture(); await f.page.onShow(); await f.page.onParts(event({})); f.page.onPurpose({ detail: { value: String(f.page.data.purposeKinds.indexOf("PARTS_CHANGE")) } });
    await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" })); f.sessions.uploadFactoryEvidence = async () => { throw new Error("unknown"); }; await f.page.onConfirm();
    f.sessions.identityRevision++; await f.page.onRetry(); assert.equal(f.page.data.parts, null); assert.equal(f.page.data.retry, false); assert.ok(f.discarded.includes("/owned/copied.png"));
});
test("parts pagination stays independent of rounds and private history", async () => {
    const f = partsFixture(); await f.page.onShow(); await f.page.onParts(event({})); await f.page.onParts(event({ delta: "1" }));
    assert.equal(f.page.data.partsPage, 2); assert.equal(f.page.data.page, 1); assert.equal(f.page.data.historyPage, 1); assert.ok(f.calls.some(c => c.path?.endsWith("/parts?page=2&pageSize=20")));
});
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

function financialFixture(purpose = "COST_RECEIPT") {
    const f = fixture("receive"); f.detail.activeRound = null; f.detail.actions.receive = false;
    f.round.roundStatus = "RECEIVED"; f.round.evidence[0].purpose = purpose;
    f.round.actions = { recordQuote: true, recordCost: true, uploadFinancialEvidence: true }; return f;
}
async function money(f, amount = "10.01") {
    await f.page.onShow(); f.page.onField(field("moneyAmount", amount));
    f.page.onField(field("moneyReason", "已核对本轮最终实际费用及原始凭证"));
    f.page.onMoneyEvidence({ detail: { value: [object] } }); f.page.onMoneyChecked({ detail: { value: ["checked"] } });
}
test("historical returned round final cost freezes exact decimal and all original versions across lost reply", async () => {
    const f = financialFixture(), original = f.sessions.request; let lost = true;
    f.sessions.request = async (...args) => { const result = await original(...args); if (args[0] === "POST" && lost) { lost = false; f.detail.version = 91; f.round.version = 92; f.round.actualCost = "10.01"; f.round.actions.recordCost = false; throw new Error("committed cost reply lost"); } return result; };
    await money(f); f.page.onPrepare(event({ action: "cost" })); assert.match(f.page.data.confirmation, /10.01/); assert.equal(writes(f).length, 0);
    await f.page.onConfirm(); assert.equal(f.page.data.retry, true); await f.page.onRefresh(); f.page.onHide();
    assert.equal(f.page.data.moneyAmount, ""); await f.page.onShow(); await f.page.onRetry();
    assert.deepEqual(writes(f)[1], writes(f)[0]); assert.equal(writes(f)[0].mutation.version, 5);
    assert.deepEqual(writes(f)[0].body, { assetVersion: 7, ownershipVersion: 2, lockVersion: 3, roundVersion: 10, amount: "10.01", evidenceIds: [object], reason: "已核对本轮最终实际费用及原始凭证", finalAmountChecked: true });
});
test("financial fact requires explicit checked amount and matching original purpose", async () => {
    const f = financialFixture("QUOTE"); await money(f); f.page.onPrepare(event({ action: "cost" })); assert.equal(f.page.data.confirmation, "");
    f.page.onPrepare(event({ action: "quote" })); assert.ok(f.page.data.confirmation); f.page.onCancel();
    f.page.onField(field("moneyAmount", "1.001")); f.page.onPrepare(event({ action: "quote" })); assert.equal(f.page.data.confirmation, "");
    assert.equal(writes(f).length, 0);
});
test("unknown physical return is explicit and never fabricated as a zero cost", async () => {
    const f = fixture("receive"); await physical(f, true); f.page.onCondition({ detail: { value: "1" } });
    f.page.onChecked({ detail: { value: ["original", "unassessed"] } }); f.page.onPrepare(event({ action: "receive" })); await f.page.onConfirm();
    assert.equal(writes(f)[0].body.actualCost, null); assert.equal(writes(f)[0].body.costUnassessed, true);
});
test("known positive final cost is carried into the actual original receipt unchanged", async () => {
    const f = fixture("receive"); f.round.actualCost = "99.95"; await physical(f, true); f.page.onCondition({ detail: { value: "1" } });
    f.page.onPrepare(event({ action: "receive" })); await f.page.onConfirm(); assert.equal(writes(f)[0].body.actualCost, "99.95");
    assert.equal(writes(f)[0].body.costUnassessed, undefined);
});
test("returned rounds can append financial originals without reopening physical dispatch", async () => {
    const f = financialFixture(); await f.page.onShow(); await f.page.onChoose();
    f.page.onPurpose({ detail: { value: "1" } }); f.page.onPrepare(event({ action: "upload" })); await f.page.onConfirm();
    const upload = f.calls.find(c => c.upload); assert.equal(upload.upload[2].purpose, "COST_RECEIPT"); assert.equal(upload.upload[2].roundVersion, 10);
    assert.equal(writes(f).length, 0);
});
test("identity renewal clears an unsent private amount and selected money originals", async () => {
    const f = financialFixture(); await money(f); f.sessions.identityRevision++; f.page.onField(field("moneyReason", "late private input"));
    assert.equal(f.page.data.moneyAmount, ""); assert.equal(f.page.data.moneyReason, ""); assert.deepEqual(f.page.data.moneyEvidenceIds, []); assert.equal(f.page.data.detail, null);
});
test("explicit limited replacement scope keeps cumulative limit as an exact string", async () => {
    const f = fixture(); f.detail.actions.requestCostScope = true; await f.page.onShow();
    f.page.onField(field("reason", "申请本维修所有轮次累计费用限额")); f.page.onCostPolicy({ detail: { value: "1" } }); f.page.onField(field("costLimit", "105.01"));
    f.page.onPrepare(event({ action: "requestCostScope" })); await f.page.onConfirm(); assert.equal(writes(f)[0].body.costLimit, "105.01"); assert.equal(writes(f)[0].body.costPolicy, "LIMITED");
});
