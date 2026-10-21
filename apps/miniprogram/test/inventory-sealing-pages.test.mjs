import test from "node:test";
import assert from "node:assert/strict";
import { createInventorySealingPage } from "../src/inventory-sealing-pages.ts";
import { MiniRequestError } from "../src/session.ts";
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
function fixture() {
    const calls = []; let key = 0;
    const view = { task: { version: 7, task_status: "WAITING_REVIEW" }, sealed: false, blockers: [], blockerCount: 0,
        missingSynchronizationCount: 1, synchronizationDigest: "a".repeat(64), synchronization: [] };
    const sessions = { identity: { accountId: "1", bindingId: "2" }, async request(method, path, body, mutation) {
        calls.push(structuredClone({ method, path, body, mutation })); return method === "POST" ? { status: "COMPLETED" }
            : path.includes("/report/") ? { items: [{ snapshot_json: { id: "9007199254740993" }, snapshot_sha256: "saved" }], total: 1 } : structuredClone(view);
    } };
    const services = { sessions, createKey: () => `00000000-0000-4000-8000-${String(++key).padStart(12, "0")}`, confirm: async () => true };
    const page = createInventorySealingPage(() => services); page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); page.onLoad({ taskId: "9" });
    return { page, sessions, services, calls, view };
}
const writes = f => f.calls.filter(row => row.method === "POST");
function prepare(f) { f.page.onField(field("reason", "逐项核对采信与未盘原因并冻结报告")); f.page.onField(field("waiver", "执行人声明暂缺，明确记录名单及补证义务")); }
test("nonwaivable physical blockers prevent seal regardless of missing-sync reason", async () => {
    const f = fixture(); f.view.blockerCount = 1; f.view.blockers = [{ code: "INVENTORY_SEAL_PHYSICAL_MISSING_OPEN" }];
    await f.page.onShow(); prepare(f); await f.page.onSeal(); assert.equal(writes(f).length, 0); assert.match(f.page.data.blockers[0].text, /实物未找到/);
});
test("unknown successful seal keeps original UUID version and roster digest across hide and reread", async () => {
    const f = fixture(), request = f.sessions.request; let lost = true;
    f.sessions.request = async (...args) => { const value = await request(...args); if (args[0] === "POST" && lost) { lost = false; f.view.task.version = 99; f.view.synchronizationDigest = "b".repeat(64); f.view.sealed = true; throw new Error("lost"); } return value; };
    await f.page.onShow(); prepare(f); await f.page.onSeal(); assert.equal(f.page.data.retry, true);
    f.page.onHide(); await f.page.onShow(); await f.page.onRetry(); assert.equal(writes(f).length, 2); assert.deepEqual(writes(f)[0], writes(f)[1]);
});
test("identity replacement during confirmation sends nothing and hides original facts", async () => {
    const f = fixture(); await f.page.onShow(); prepare(f); f.services.confirm = async () => { f.sessions.identity = { accountId: "3", bindingId: "4" }; return true; };
    await f.page.onSeal(); assert.equal(writes(f).length, 0); assert.equal(f.page.data.view, null);
});
test("current role denial clears original pending seal and private report", async () => {
    const f = fixture(); await f.page.onShow(); prepare(f); f.sessions.request = async () => { throw new MiniRequestError(403, "INVENTORY_REVIEW_ADMIN_REQUIRED"); };
    await f.page.onSeal(); assert.equal(f.page.data.retry, false); assert.equal(f.page.data.view, null);
});
test("confirmation failure releases busy and does not save a seal", async () => {
    const f = fixture(); await f.page.onShow(); prepare(f); f.services.confirm = async () => { throw new Error("dialog failed"); };
    await f.page.onSeal(); assert.equal(f.page.data.busy, false); assert.equal(writes(f).length, 0);
});
test("frozen page read preserves large identifiers and late old-identity content stays hidden", async () => {
    const f = fixture(); f.view.sealed = true; await f.page.onShow(); await f.page.onReadReport(); assert.match(f.page.data.records[0].text, /9007199254740993/);
    const request = f.sessions.request; let release;
    f.sessions.request = (...args) => args[1].includes("/report/") ? new Promise(resolve => { release = resolve; }) : request(...args);
    const read = f.page.onReadReport(); f.sessions.identity = { accountId: "3", bindingId: "4" }; release({ items: [{ snapshot_json: { private: true } }], total: 1 }); await read;
    assert.equal(f.page.data.view, null); assert.deepEqual(f.page.data.records, []);
});
