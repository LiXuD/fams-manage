import test from "node:test";
import assert from "node:assert/strict";
import { createInventoryReadinessPage } from "../src/inventory-readiness-pages.ts";
import { MiniRequestError } from "../src/session.ts";
const event = id => ({ currentTarget: { dataset: { id } } });
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
function fixture() {
    const calls = []; let key = 0;
    const view = { task: { version: 7, task_status: "IN_PROGRESS" }, total: 1, missingCount: 1, notObservedCount: 0,
        items: [{ id: "9007199254740993", item: { id: "9007199254740993", recheck_generation: 2, snapshot_json: { assetCode: "A-1", name: "现场甲" } }, current: { version: 4 }, canRecord: true, physicalMissingOpen: false }] };
    const sessions = { identity: { accountId: "1", bindingId: "2" }, async request(method, path, body, mutation) {
        calls.push(structuredClone({ method, path, body, mutation })); return method === "POST" ? { status: "NOT_OBSERVED" }
            : path.includes("unobserved-history") ? { items: [], total: 0 } : structuredClone(view);
    } };
    const services = { sessions, createKey: () => `00000000-0000-4000-8000-${String(++key).padStart(12, "0")}`, confirm: async () => true };
    const page = createInventoryReadinessPage(() => services); page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); page.onLoad({ taskId: "9" });
    return { page, sessions, services, calls, view };
}
const writes = f => f.calls.filter(row => row.method === "POST");
function prepare(f) { f.page.onSelect(event("9007199254740993")); f.page.onField(field("reason", "现场封闭未取得实盘观察")); f.page.onField(field("evidence", "现场负责人员说明及受限环境记录，保留明确未盘依据")); }
test("unobserved save preserves lossless item identity, current asset version and exact current round", async () => {
    const f = fixture(); await f.page.onShow(); prepare(f); await f.page.onSave();
    assert.equal(writes(f).length, 1); assert.equal(writes(f)[0].path, "/api/v1/mini/inventory-readiness/tasks/9/items/9007199254740993/unobserved");
    assert.equal(writes(f)[0].body.recheckGeneration, 2); assert.equal(writes(f)[0].body.assetVersion, 4); assert.equal(writes(f)[0].mutation.version, 7);
});
test("an actual unknown reply retains original UUID payload and version across hide and current reauthorization", async () => {
    const f = fixture(), request = f.sessions.request; let lost = true;
    f.sessions.request = async (...args) => { const value = await request(...args); if (args[0] === "POST" && lost) { lost = false; f.view.task.version = 99; throw new Error("lost reply"); } return value; };
    await f.page.onShow(); prepare(f); await f.page.onSave(); assert.equal(f.page.data.retry, true);
    f.page.onHide(); await f.page.onShow(); await f.page.onRetry(); assert.equal(writes(f).length, 2); assert.deepEqual(writes(f)[0], writes(f)[1]);
});
test("identity replacement during confirmation sends nothing and hides original facts", async () => {
    const f = fixture(); await f.page.onShow(); prepare(f); f.services.confirm = async () => { f.sessions.identity = { accountId: "3", bindingId: "4" }; return true; };
    await f.page.onSave(); assert.equal(writes(f).length, 0); assert.equal(f.page.data.view, null); assert.equal(f.page.data.selected, null);
});
test("a rejected confirmation releases busy state and leaves no declaration", async () => {
    const f = fixture(); await f.page.onShow(); prepare(f); f.services.confirm = async () => { throw new Error("dialog failure"); };
    await f.page.onSave(); assert.equal(writes(f).length, 0); assert.equal(f.page.data.busy, false);
});
test("waiting review refuses missing items and requires explicit confirmation after every result or reason", async () => {
    const f = fixture(); await f.page.onShow(); f.page.onField(field("waitingReason", "全部项目已有结果或明确原因")); await f.page.onWaiting(); assert.equal(writes(f).length, 0);
    f.view.missingCount = 0; f.view.notObservedCount = 1; await f.page.onRefresh(); await f.page.onWaiting();
    assert.equal(writes(f).length, 1); assert.equal(writes(f)[0].path, "/api/v1/mini/inventory-readiness/tasks/9/waiting-review");
});
test("current role denial clears facts and any original pending operation", async () => {
    const f = fixture(); await f.page.onShow(); prepare(f); f.sessions.request = async () => { throw new MiniRequestError(403, "INVENTORY_REVIEW_ADMIN_REQUIRED"); };
    await f.page.onSave(); assert.equal(f.page.data.retry, false); assert.equal(f.page.data.view, null); assert.equal(f.page.data.selected, null);
});
test("original unobserved selection cannot switch kind or evidence after an unknown response", async () => {
    const f = fixture(); await f.page.onShow(); prepare(f); const request = f.sessions.request;
    f.sessions.request = async (...args) => { const result = await request(...args); if (args[0] === "POST") throw new Error("lost"); return result; };
    await f.page.onSave(); f.page.onKind({ detail: { value: "2" } }); f.page.onField(field("reason", "不得替换原决定"));
    await f.page.onRetry(); assert.equal(writes(f).length, 2); assert.deepEqual(writes(f)[0], writes(f)[1]);
});
