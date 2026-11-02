import test from "node:test";
import assert from "node:assert/strict";
import { createRepairReplacementPage } from "../src/repair-replacement-pages.ts";
import { MiniRequestError } from "../src/session.ts";

const event = dataset => ({ currentTarget: { dataset } });
const field = (name, value) => ({ ...event({ field: name }), detail: { value } });
const object = "00000000-0000-4000-8000-000000000055", reference = "9007199254740995", roundId = "9007199254740993";
function fixture(canRecord = true) {
    const header = { version: 5, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, photosAvailable: true };
    const round = { id: roundId, version: 10, roundStatus: "DISPATCHED", evidence: [{ object_id: object, purpose: "REPLACEMENT" }], files: [{ objectId: object, filename: "厂家换机原件.png", scanStatus: "CLEAN" }] };
    const records = { canRecord, items: [] }, calls = [], discarded = []; let keys = 0;
    const sessions = { identity: { accountId: "1", bindingId: "2" }, identityRevision: 0,
        async request(method, path, body, mutation) {
            calls.push(structuredClone({ method, path, body, mutation }));
            if (method === "POST") return {};
            if (path.includes("replacement-references")) return { page: path.includes("page=2") ? 2 : 1, total: 21, items: [{ id: reference, code: "REF", name: "有效资料", version: 11, warehouse: { id: reference } }] };
            if (path.endsWith("/replacements")) return structuredClone(records);
            return structuredClone(path.includes("/rounds/") ? round : header);
        },
        stageFactoryEvidence(file) { return Object.freeze({ ...file, path: "/owned/replacement.png", contentType: "image/png" }); },
        async uploadFactoryEvidence(...upload) { calls.push({ upload: structuredClone(upload) }); return {}; },
        discardFactoryEvidence(path) { discarded.push(path); },
    };
    const services = { sessions, createKey: () => "00000000-0000-4000-8000-" + String(++keys).padStart(12, "0"), chooseEvidence: async () => ({ path: "/selected/proof.png", name: "换机证明.png", size: 3 }), navigate() {} };
    const page = createRepairReplacementPage(() => services); page.data = structuredClone(page.data); page.setData = x => Object.assign(page.data, x); page.onLoad({ id: "9", round: roundId });
    return { page, sessions, services, calls, discarded, header, round, records };
}
const writes = f => f.calls.filter(call => call.method === "POST");
async function draft(f) {
    await f.page.onShow();
    for (const [name, value] of Object.entries({ name: "独立新机", serial: "NEW-IDENTITY", model: "New Model", unit: "台", businessDate: "2026-10-27", actualDate: "2026-10-27", actualTime: "11:12:13", reason: "已亲自核实厂家换机凭证及新实物序列号，旧机暂留厂家。" })) f.page.onField(field(name, value));
    for (const kind of ["category", "organization", "warehouse", "location"]) f.page.onReference(event({ kind, id: reference }));
    f.page.onDisposition(event({ id: "FACTORY_RETAINED" })); f.page.onEvidence({ detail: { value: [object] } }); f.page.onChecked({ detail: { value: ["checked"] } });
}
test("replacement freezes complete independent new-card draft, reference versions, and four guards", async () => {
    const f = fixture(); await draft(f); f.page.onPrepare(event({ action: "create" })); assert.equal(writes(f).length, 0); assert.match(f.page.data.confirmation, /独立提交、批准和实际验收入库/); await f.page.onConfirm();
    assert.deepEqual(writes(f)[0].body, { roundVersion: 10, assetVersion: 7, ownershipVersion: 2, lockVersion: 3, originalDisposition: "FACTORY_RETAINED", observedAt: new Date("2026-10-27T11:12:13").toISOString(), evidenceIds: [object], reason: "已亲自核实厂家换机凭证及新实物序列号，旧机暂留厂家。", replacementPhysicalIdentityChecked: true, newAsset: { name: "独立新机", serialNumber: "NEW-IDENTITY", model: "New Model", unit: "台", category: { id: reference, version: 11 }, organization: { id: reference, version: 11 } }, businessDate: "2026-10-27", warehouseId: reference, warehouseVersion: 11, locationId: reference, locationVersion: 11 });
    assert.equal(writes(f)[0].mutation.version, 5); assert.match(f.page.data.notice, /旧机责任继续保留/);
});
test("unknown replacement result keeps exact decision across refresh and hide-show", async () => {
    const f = fixture(), request = f.sessions.request; let lost = true;
    f.sessions.request = async (...args) => { const result = await request(...args); if (args[0] === "POST" && lost) { lost = false; f.header.version = 80; f.round.version = 81; throw new Error("committed reply lost"); } return result; };
    await draft(f); f.page.onPrepare(event({ action: "create" })); await f.page.onConfirm(); assert.equal(f.page.data.retry, true); f.page.onHide(); await f.page.onShow(); assert.match(f.page.data.pendingLabel, /NEW-IDENTITY/); await f.page.onRetry(); assert.deepEqual(writes(f)[1], writes(f)[0]); assert.equal(f.page.data.retry, false);
});
test("dedicated originals survive unknown upload until original-key retry succeeds", async () => {
    const f = fixture(), upload = f.sessions.uploadFactoryEvidence; let lost = true;
    f.sessions.uploadFactoryEvidence = async (...args) => { const result = await upload(...args); if (lost) { lost = false; throw new Error("lost upload reply"); } return result; };
    await f.page.onShow(); await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" })); await f.page.onConfirm(); f.page.onHide(); assert.equal(f.discarded.length, 0); await f.page.onShow(); await f.page.onRetry(); const uploads = f.calls.filter(call => call.upload); assert.deepEqual(uploads[1], uploads[0]); assert.equal(uploads[0].upload[2].purpose, "REPLACEMENT"); assert.ok(f.discarded.includes("/owned/replacement.png"));
});
test("read-only permission cannot be bypassed by calling native prepare handlers", async () => {
    const f = fixture(false); await draft(f); f.page.onPrepare(event({ action: "create" })); assert.equal(f.page.data.confirmation, ""); assert.equal(writes(f).length, 0); assert.equal(f.calls.some(call => call.path?.includes("replacement-references")), false);
});
test("definitive rejection discards retry until explicit new read and decision", async () => {
    const f = fixture(), request = f.sessions.request; f.sessions.request = async (...args) => { if (args[0] === "POST") throw new MiniRequestError(409, "REPAIR_VERSION_MISMATCH"); return request(...args); };
    await draft(f); f.page.onPrepare(event({ action: "create" })); await f.page.onConfirm(); assert.equal(f.page.data.retry, false); assert.equal(f.page.data.stale, true); f.page.onPrepare(event({ action: "create" })); assert.equal(f.page.data.confirmation, "");
});
test("identity renewal removes pending private original and prevents replay", async () => {
    const f = fixture(); f.sessions.uploadFactoryEvidence = async () => { throw new Error("unknown"); }; await f.page.onShow(); await f.page.onChoose(); f.page.onPrepare(event({ action: "upload" })); await f.page.onConfirm(); f.page.onHide(); f.sessions.identityRevision++; await f.page.onShow(); await f.page.onRetry(); assert.equal(f.page.data.retry, false); assert.ok(f.discarded.includes("/owned/replacement.png"));
});
test("invalid calendar, wrong-purpose proof and missing physical confirmation cannot prepare", async () => {
    const f = fixture(); await draft(f); f.page.onField(field("actualDate", "2026-02-31")); f.page.onPrepare(event({ action: "create" })); assert.equal(f.page.data.confirmation, "");
    await draft(f); f.page.onChecked({ detail: { value: [] } }); f.page.onPrepare(event({ action: "create" })); assert.equal(f.page.data.confirmation, "");
    f.round.evidence[0].purpose = "QUOTE"; await draft(f); f.page.onPrepare(event({ action: "create" })); assert.equal(f.page.data.confirmation, ""); assert.equal(writes(f).length, 0);
});
test("reference paging clears selected warehouse and location before a new decision", async () => {
    const f = fixture(); await draft(f); await f.page.onReferences(event({ kind: "warehouse", delta: "1" })); assert.equal(f.page.data.references[2].page, 2); assert.equal(f.page.data.references[2].selected, ""); assert.equal(f.page.data.references[3].selected, ""); f.page.onPrepare(event({ action: "create" })); assert.equal(f.page.data.confirmation, "");
});
