import assert from "node:assert/strict";
import test from "node:test";
import { createInventoryFiles } from "../src/inventory-files.ts";
const event = "22222222-2222-4222-8222-222222222222";
const photo = "33333333-3333-4333-8333-333333333333";
function fixture() {
    const files = new Map([["tmp/photo", Buffer.from("real-local-photo-bytes")]]); const disk = new Map(); const dirs = new Set();
    const fs = { mkdirSync: path => dirs.add(path), accessSync: path => { if (!dirs.has(path)) throw new Error("Absent"); },
        copyFileSync: (source, target) => { if (!files.has(source)) throw new Error("Missing temporary file"); files.set(target, Buffer.from(files.get(source))); },
        statSync: path => { if (!files.has(path)) throw new Error("Absent"); return { size: files.get(path).length, isFile: () => true }; },
        unlinkSync: path => files.delete(path), readdirSync: path => [...files.keys()].filter(key => key.startsWith(path + "/")).map(key => key.slice(path.length + 1)) };
    const wx = { env: { USER_DATA_PATH: "owned" }, getFileSystemManager: () => fs,
        getStorageSync: key => disk.get(key), setStorageSync: (key, value) => disk.set(key, value), removeStorageSync: key => disk.delete(key) };
    return { files, disk, wx, input: { path: "tmp/photo", name: "现场.jpg", size: files.get("tmp/photo").length } };
}
test("durable identity photo survives temp cleanup and cannot be overwritten by retry", async () => {
    const f = fixture(); const owner = { accountId: "1", bindingId: "2" };
    const saved = createInventoryFiles(f.wx, owner).persist(f.input, event, photo);
    f.files.delete("tmp/photo");
    assert.equal(await createInventoryFiles(f.wx, owner).storage.photoExists(saved.path), true);
    assert.throws(() => createInventoryFiles(f.wx, owner).persist(f.input, event, photo), /already exists/);
});
test("clear removes only the selected identity's durable bytes and queue", () => {
    const f = fixture(); const a = createInventoryFiles(f.wx, { accountId: "1", bindingId: "2" });
    const b = createInventoryFiles(f.wx, { accountId: "1", bindingId: "3" });
    const first = a.persist(f.input, event, photo); const second = b.persist(f.input, event, photo);
    a.storage.write("fams.identity.1.2", { private: "own" }); b.storage.write("fams.identity.1.3", { private: "other" });
    a.clear(); assert.equal(f.files.has(first.path), false); assert.equal(f.files.has(second.path), true);
    assert.equal(f.disk.has("fams.identity.1.2"), false); assert.equal(f.disk.has("fams.identity.1.3"), true);
});
test("copy mismatch fails without retaining incomplete durable bytes", () => {
    const f = fixture(); const a = createInventoryFiles(f.wx, { accountId: "1", bindingId: "2" });
    assert.throws(() => a.persist({ ...f.input, size: 1 }, event, photo), /incomplete/);
    assert.deepEqual([...f.files.keys()], ["tmp/photo"]);
});
test("restart pruning removes abandoned drafts but retains photos referenced by durable events", () => {
    const f = fixture(); const files = createInventoryFiles(f.wx, { accountId: "1", bindingId: "2" });
    const kept = files.persist(f.input, event, photo);
    const abandoned = files.persist(f.input, event, "44444444-4444-4444-8444-444444444444");
    files.prune([kept.path]); assert.equal(f.files.has(kept.path), true); assert.equal(f.files.has(abandoned.path), false);
});
