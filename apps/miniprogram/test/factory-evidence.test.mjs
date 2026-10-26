import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";
import { createInventoryEvidenceFiles } from "../src/inventory-evidence-files.ts";

const saved = { accountId: "9007199254740993", bindingId: "25", sessionToken: "s".repeat(43) };
const object = "00000000-0000-4000-8000-000000000001";
const route = "/api/v1/mini/repairs/9/factory/rounds/2/evidence";
const download = route + "/" + object + "/content?token=valid.signed";
const mutation = Object.freeze({ key: "00000000-0000-4000-8000-000000000002", version: 3, roundVersion: 2,
    assetVersion: 8, ownershipVersion: 4, lockVersion: 0, purpose: "FACTORY_ACK" });
function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "fams-factory-original-"));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const disk = new Map([["fams.session", saved]]), uploads = [], requests = [];
    const io = { mkdirSync: (p, recursive) => fs.mkdirSync(p, { recursive }), copyFileSync: fs.copyFileSync,
        statSync: fs.statSync, unlinkSync: fs.unlinkSync, readdirSync: fs.readdirSync, accessSync: fs.accessSync };
    const wx = { env: { USER_DATA_PATH: root }, getFileSystemManager: () => io,
        getStorageSync: k => disk.get(k), setStorageSync: (k, v) => disk.set(k, structuredClone(v)), removeStorageSync: k => disk.delete(k),
        uploadFile: input => { uploads.push({ ...input, bytes: fs.readFileSync(input.filePath) }); input.success({ statusCode: 200, data: '{"repairId":"9"}' }); },
        downloadFile: input => requests.push(input), request: () => {} };
    const runtime = createWxRuntime(wx), client = new MiniSessionClient("https://api.example.test", runtime);
    const source = path.join(root, "selected.png"); fs.writeFileSync(source, "selected original bytes");
    return { root, disk, uploads, requests, io, wx, runtime, client, source,
        selected: { path: source, name: "厂家接收.png", size: fs.statSync(source).size } };
}
test("factory upload uses an owned immutable copy, complete frozen versions and only opaque credentials", async t => {
    const f = fixture(t), file = f.client.stageFactoryEvidence(f.selected);
    fs.writeFileSync(f.source, "source changed after selection");
    await f.client.uploadFactoryEvidence(route, file, mutation);
    const upload = f.uploads[0]; assert.equal(upload.bytes.toString(), "selected original bytes");
    assert.deepEqual(upload.header, { "X-FAMS-Mini-Session": saved.sessionToken, "Idempotency-Key": mutation.key, "If-Match-Version": "3" });
    assert.deepEqual(upload.formData, { roundVersion: "2", assetVersion: "8", ownershipVersion: "4", lockVersion: "0",
        purpose: "FACTORY_ACK", originalFilename: "厂家接收.png", declaredContentType: "image/png" });
    assert.equal(upload.identity, undefined); assert.equal(Object.isFrozen(file), true);
    assert.match(file.path, /fams-factory-evidence-9007199254740993-25/);
    assert.deepEqual(f.disk.get("fams.factory-evidence-owners"), [{ accountId: saved.accountId, bindingId: "25" }]);
});
test("unknown upload response retains the copied bytes and exact request for safe retry", async t => {
    const f = fixture(t), file = f.client.stageFactoryEvidence(f.selected); let count = 0;
    f.wx.uploadFile = input => { f.uploads.push({ header: input.header, formData: input.formData, bytes: fs.readFileSync(input.filePath) });
        input.success({ statusCode: ++count === 1 ? 503 : 200, data: count === 1 ? '{"code":"REPAIR_FACTORY_SCAN_UNAVAILABLE"}' : '{"repairId":"9"}' }); };
    await assert.rejects(f.client.uploadFactoryEvidence(route, file, mutation), e => e.status === 503);
    assert.equal(fs.existsSync(file.path), true); await f.client.uploadFactoryEvidence(route, file, mutation);
    assert.deepEqual(f.uploads[0], f.uploads[1]);
});
test("factory cannot widen the old photo route or send private credentials to foreign paths", async t => {
    const f = fixture(t), file = f.client.stageFactoryEvidence(f.selected);
    for (const invalid of ["https://evil.invalid" + route, route.replace("/mini/", "/"), route + "?x=1", route.replace("/9/", "/../")])
        await assert.rejects(f.client.uploadFactoryEvidence(invalid, file, mutation));
    await assert.rejects(f.client.uploadEvidence(route, file.path, mutation));
    for (const invalid of [{ ...mutation, roundVersion: -1 }, { ...mutation, ownershipVersion: Number.MAX_SAFE_INTEGER + 1 }, { ...mutation, purpose: "UNKNOWN_PURPOSE" }])
        await assert.rejects(f.client.uploadFactoryEvidence(route, file, invalid));
    assert.equal(f.uploads.length, 0);
});
test("a new binding cannot reuse the old binding's copied original", async t => {
    const f = fixture(t), file = f.client.stageFactoryEvidence(f.selected); f.client.clear();
    assert.equal(fs.existsSync(file.path), false); assert.equal(fs.existsSync(f.source), true);
    f.disk.set("fams.session", { ...saved, bindingId: "26" }); const next = new MiniSessionClient("https://api.example.test", f.runtime);
    await assert.rejects(next.uploadFactoryEvidence(route, file, mutation), /当前身份/); assert.equal(f.uploads.length, 0);
});
test("signed factory PDF is written to its own namespace with the exact declared byte count", async t => {
    const f = fixture(t), pending = f.client.downloadFactoryEvidence(download, "application/pdf", 6), request = f.requests[0];
    fs.writeFileSync(request.filePath, "%PDF-1"); request.success({ statusCode: 200, filePath: request.filePath, tempFilePath: "" });
    const file = await pending; assert.match(file, /fams-factory-evidence-.*\.pdf$/);
    assert.deepEqual(request.header, { "X-FAMS-Mini-Session": saved.sessionToken }); assert.equal(request.identity, undefined);
    f.client.clear(); assert.equal(fs.existsSync(file), false);
});
test("factory download rejects wrong lengths, unsupported media, foreign URLs and extra parameters", async t => {
    const f = fixture(t);
    for (const invalid of ["https://evil.invalid" + download, download + "&x=1", download.replace("/mini/", "/"), download.replace("token=valid.signed", "token=%2f")])
        await assert.rejects(f.client.downloadFactoryEvidence(invalid, "image/png", 3));
    await assert.rejects(f.client.downloadFactoryEvidence(download, "image/svg+xml", 3));
    await assert.rejects(f.client.downloadFactoryEvidence(download, "image/png", 10 * 1024 * 1024 + 1));
    assert.equal(f.requests.length, 0);
    const pending = f.client.downloadFactoryEvidence(download, "image/png", 3), request = f.requests[0];
    fs.writeFileSync(request.filePath, "wrong length"); const denied = assert.rejects(pending, /容量/);
    request.success({ statusCode: 200, filePath: request.filePath }); await denied; assert.equal(fs.existsSync(request.filePath), false);
});
test("late old factory download is erased after identity clearing without reviving private data", async t => {
    const f = fixture(t), pending = f.client.downloadFactoryEvidence(download, "image/png", 4), request = f.requests[0];
    f.client.clear(); fs.writeFileSync(request.filePath, "late"); const denied = assert.rejects(pending, /身份已变化/);
    request.success({ statusCode: 200, filePath: request.filePath }); await denied; assert.equal(fs.existsSync(request.filePath), false);
});
test("both evidence namespaces and the queue are cleaned even when one deletion fails", t => {
    const f = fixture(t), inventory = createInventoryEvidenceFiles(f.wx), original = inventory.reserve(saved, object, "image/png");
    fs.writeFileSync(original.path, "old photo"); const file = f.client.stageFactoryEvidence(f.selected);
    const queue = path.join(f.root, `fams-inventory-${saved.accountId}-${saved.bindingId}`); fs.mkdirSync(queue); fs.writeFileSync(path.join(queue, "queue.png"), "queued");
    f.io.unlinkSync = p => { if (p === original.path) throw new Error("owned blocked deletion"); fs.unlinkSync(p); };
    assert.throws(() => f.runtime.clearIdentityData(saved), /blocked deletion/);
    assert.equal(fs.existsSync(file.path), false); assert.equal(fs.existsSync(path.join(queue, "queue.png")), false);
    assert.equal(f.disk.has("fams.inventory-cleanup"), true);
    f.io.unlinkSync = fs.unlinkSync; createWxRuntime(f.wx);
    assert.equal(fs.existsSync(original.path), false); assert.equal(f.disk.has("fams.inventory-cleanup"), false);
});
test("PDF allowance belongs only to factory and cannot relax inventory photo limits", t => {
    const f = fixture(t), inventory = createInventoryEvidenceFiles(f.wx), factory = createInventoryEvidenceFiles(f.wx, "factory");
    assert.throws(() => inventory.reserve(saved, object, "application/pdf"), /格式/);
    const pdf = factory.reserve(saved, object, "application/pdf"); fs.writeFileSync(pdf.path, ""); fs.truncateSync(pdf.path, 20 * 1024 * 1024);
    factory.verify(pdf, 20 * 1024 * 1024); fs.truncateSync(pdf.path, 20 * 1024 * 1024 + 1);
    assert.throws(() => factory.verify(pdf), /容量/); assert.equal(fs.existsSync(pdf.path), false);
});
test("401 clears identity and both staged and downloaded factory originals before rejection", async t => {
    const f = fixture(t), file = f.client.stageFactoryEvidence(f.selected);
    const pending = f.client.downloadFactoryEvidence(download, "image/png", 4), request = f.requests[0];
    fs.writeFileSync(request.filePath, "deny"); const denied = assert.rejects(pending, e => e.status === 401);
    request.success({ statusCode: 401, filePath: request.filePath }); await denied;
    assert.equal(f.client.identity, null); assert.equal(fs.existsSync(file.path), false);
    assert.equal(fs.existsSync(request.filePath), false); assert.equal(fs.existsSync(f.source), true);
});
test("same-account credential renewal changes the nonsecret identity revision for private page invalidation", async t => {
    const f = fixture(t), before = f.client.identityRevision, identity = f.client.identity;
    f.wx.request = input => input.success({ statusCode: 200, data: { ...saved, bindingStatus: "BOUND",
        idleExpiresAt: "2030-01-01T00:00:00Z", absoluteExpiresAt: "2030-01-02T00:00:00Z" } });
    assert.equal(await f.client.login("owned-fresh-login-code"), "BOUND");
    assert.deepEqual(f.client.identity, identity); assert.notEqual(f.client.identityRevision, before);
    assert.deepEqual(Object.keys(f.client.identity).sort(), ["accountId", "bindingId"]);
});
test("unsafe selected names and oversized media fail before private files or owner markers are created", t => {
    const f = fixture(t);
    for (const name of ["../escape.png", "dir\\escape.png", "bad\u0000.png", "bad\u0080.png", "executable.svg"])
        assert.throws(() => f.client.stageFactoryEvidence({ ...f.selected, name }), /格式/);
    for (const file of [{ ...f.selected, size: 10 * 1024 * 1024 + 1 }, { ...f.selected, name: "large.pdf", size: 20 * 1024 * 1024 + 1 }])
        assert.throws(() => f.client.stageFactoryEvidence(file), /容量/);
    assert.equal(f.disk.has("fams.factory-evidence-owners"), false); assert.equal(fs.existsSync(f.source), true);
});

test("financial originals use the factory route and retain private opaque credentials", async t => {
    const f = fixture(t), file = f.client.stageFactoryEvidence(f.selected);
    for (const purpose of ["QUOTE", "COST_RECEIPT"]) {
        await f.client.uploadFactoryEvidence(route, file, { ...mutation, purpose });
        const upload = f.uploads.at(-1); assert.equal(upload.formData.purpose, purpose);
        assert.deepEqual(upload.header, { "X-FAMS-Mini-Session": saved.sessionToken, "Idempotency-Key": mutation.key, "If-Match-Version": "3" });
        assert.equal(upload.bytes.toString(), "selected original bytes");
    }
});
