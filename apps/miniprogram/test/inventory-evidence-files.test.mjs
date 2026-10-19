import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createInventoryEvidenceFiles } from "../src/inventory-evidence-files.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";

const nonce = "33333333-3333-4333-8333-333333333333";
const owner = { accountId: "9007199254740993", bindingId: "2" };
function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "fams-read-evidence-"));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const disk = new Map(), requests = [];
    const io = { mkdirSync: (file, recursive) => fs.mkdirSync(file, { recursive }), copyFileSync: fs.copyFileSync,
        statSync: fs.statSync, unlinkSync: fs.unlinkSync, readdirSync: fs.readdirSync, accessSync: fs.accessSync };
    const wx = { env: { USER_DATA_PATH: root }, getFileSystemManager: () => io,
        getStorageSync: key => disk.get(key), setStorageSync: (key, value) => disk.set(key, structuredClone(value)), removeStorageSync: key => disk.delete(key),
        downloadFile: input => requests.push(input) };
    return { root, io, disk, wx, requests };
}
test("readonly evidence stays outside the durable collection directory and preserves string owners", t => {
    const f = fixture(t), files = createInventoryEvidenceFiles(f.wx);
    const reserved = files.reserve({ ...owner, sessionToken: "must-not-persist" }, nonce, "image/png");
    assert.match(reserved.path, /fams-inventory-evidence-9007199254740993-2/);
    assert.deepEqual(f.disk.get("fams.inventory-evidence-owners"), [owner]);
    const queue = path.join(f.root, "fams-inventory-9007199254740993-2"); fs.mkdirSync(queue); fs.writeFileSync(path.join(queue, "pending.jpg"), "queued");
    const other = files.reserve({ ...owner, bindingId: "3" }, nonce, "image/png");
    fs.writeFileSync(reserved.path, "png"); fs.writeFileSync(other.path, "other"); files.verify(reserved); files.clear(owner);
    assert.equal(fs.existsSync(reserved.path), false); assert.equal(fs.existsSync(other.path), true); assert.equal(fs.existsSync(path.join(queue, "pending.jpg")), true);
});
test("startup removes registered abandoned reads including a late write after identity clearing", t => {
    const f = fixture(t), files = createInventoryEvidenceFiles(f.wx);
    const reserved = files.reserve(owner, nonce, "image/jpeg"); files.clear(owner);
    assert.deepEqual(f.disk.get("fams.inventory-evidence-owners"), [owner]);
    fs.writeFileSync(reserved.path, "late bytes before process death");
    createInventoryEvidenceFiles(f.wx).cleanupAbandoned();
    assert.equal(fs.existsSync(reserved.path), false); assert.equal(f.disk.has("fams.inventory-evidence-owners"), false);
});
test("old reservations reject and delete late bytes without touching a new binding", t => {
    const f = fixture(t), files = createInventoryEvidenceFiles(f.wx);
    const first = files.reserve(owner, nonce, "image/png"); files.clear(owner);
    const next = files.reserve({ ...owner, bindingId: "3" }, nonce, "image/png");
    fs.writeFileSync(first.path, "old"); fs.writeFileSync(next.path, "new");
    assert.throws(() => files.verify(first), /身份已变化/); files.verify(next);
    assert.equal(fs.existsSync(first.path), false); assert.equal(fs.readFileSync(next.path, "utf8"), "new");
});
test("reservation fails before creating files when the cleanup registry cannot be persisted", t => {
    const f = fixture(t), files = createInventoryEvidenceFiles(f.wx);
    f.wx.setStorageSync = () => { throw new Error("owned storage failure"); };
    assert.throws(() => files.reserve(owner, nonce, "image/png"), /storage failure/); assert.deepEqual(fs.readdirSync(f.root), []);
});
test("cleanup failure preserves the owner marker for a successful restart retry", t => {
    const f = fixture(t), files = createInventoryEvidenceFiles(f.wx), reserved = files.reserve(owner, nonce, "image/png");
    fs.writeFileSync(reserved.path, "png"); f.io.unlinkSync = () => { throw new Error("owned unlink failure"); };
    assert.throws(() => files.clear(owner), /unlink failure/); assert.deepEqual(f.disk.get("fams.inventory-evidence-owners"), [owner]);
    f.io.unlinkSync = fs.unlinkSync; createInventoryEvidenceFiles(f.wx).cleanupAbandoned(); assert.equal(fs.existsSync(reserved.path), false);
});
test("foreign paths unsupported formats and duplicate file IDs cannot overwrite or erase files", t => {
    const f = fixture(t), files = createInventoryEvidenceFiles(f.wx), reserved = files.reserve(owner, nonce, "image/png");
    fs.writeFileSync(reserved.path, "original");
    assert.throws(() => files.reserve(owner, nonce, "image/png"), /已存在/);
    assert.throws(() => files.reserve(owner, nonce, "image/svg+xml"), /格式/);
    assert.throws(() => files.discard(path.join(f.root, "unrelated.jpg")), /其他业务/);
    assert.throws(() => files.discard(reserved.path.replace(nonce + ".png", "../pending.jpg")), /路径/);
    assert.equal(fs.readFileSync(reserved.path, "utf8"), "original");
});
test("oversized downloaded bytes are removed without becoming a readable photo", t => {
    const f = fixture(t), files = createInventoryEvidenceFiles(f.wx), reserved = files.reserve(owner, nonce, "image/png");
    fs.writeFileSync(reserved.path, ""); fs.truncateSync(reserved.path, 10 * 1024 * 1024 + 1);
    assert.throws(() => files.verify(reserved), /容量/); assert.equal(fs.existsSync(reserved.path), false);
});
test("WeChat owned download uses official filePath result and current identity cleanup removes actual bytes", async t => {
    const f = fixture(t), runtime = createWxRuntime(f.wx);
    const downloading = runtime.downloadInventoryPhoto({ url: "https://owned.invalid/content", header: { token: "opaque" }, identity: owner, contentType: "image/png" });
    const request = f.requests[0]; assert.equal(request.identity, undefined); fs.writeFileSync(request.filePath, "png");
    request.success({ statusCode: 200, filePath: request.filePath, tempFilePath: "" });
    const reply = await downloading; assert.equal(reply.filePath, request.filePath);
    runtime.clearIdentityData(owner); assert.equal(fs.existsSync(reply.filePath), false);
});
test("late WeChat response after clearing is rejected and its actual file is removed", async t => {
    const f = fixture(t), runtime = createWxRuntime(f.wx);
    const downloading = runtime.downloadInventoryPhoto({ url: "https://owned.invalid/content", header: {}, identity: owner, contentType: "image/png" });
    const request = f.requests[0]; runtime.clearIdentityData(owner); fs.writeFileSync(request.filePath, "late");
    const denied = assert.rejects(downloading, /身份已变化/);
    request.success({ statusCode: 200, filePath: request.filePath, tempFilePath: "" }); await denied;
    assert.equal(fs.existsSync(request.filePath), false);
});
test("synchronous WeChat download failure removes a partially written owned file", async t => {
    const f = fixture(t); let reserved;
    f.wx.downloadFile = input => { reserved = input.filePath; fs.writeFileSync(reserved, "partial"); throw new Error("owned synchronous failure"); };
    const runtime = createWxRuntime(f.wx);
    await assert.rejects(runtime.downloadInventoryPhoto({ url: "https://owned.invalid/content", header: {}, identity: owner, contentType: "image/png" }), /synchronous failure/);
    assert.equal(fs.existsSync(reserved), false);
});
test("unresolved deletion prevents another identity from starting a photo download reservation", t => {
    const f = fixture(t), files = createInventoryEvidenceFiles(f.wx), reserved = files.reserve(owner, nonce, "image/png");
    fs.writeFileSync(reserved.path, "private"); f.io.unlinkSync = () => { throw new Error("owned cleanup blocked"); };
    assert.throws(() => files.discard(reserved.path), /cleanup blocked/);
    assert.throws(() => files.reserve({ accountId: "2", bindingId: "3" }, nonce, "image/png"), /cleanup blocked/);
    assert.equal(fs.existsSync(path.join(f.root, "fams-inventory-evidence-2-3")), false);
    f.io.unlinkSync = fs.unlinkSync; const next = files.reserve({ accountId: "2", bindingId: "3" }, nonce, "image/png");
    assert.equal(fs.existsSync(reserved.path), false); fs.writeFileSync(next.path, "new"); files.verify(next);
});
