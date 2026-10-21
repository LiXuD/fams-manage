import test from "node:test";
import assert from "node:assert/strict";
import { createInventoryLatePage } from "../src/inventory-late-pages.ts";
import { MiniRequestError } from "../src/session.ts";
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
function fixture() {
 const entry = { evidence: { observation_id: "9007199254740993", comparison_kind: "FROZEN_CONFLICT", snapshot_json: { rawObservedAt: "2026-10-21T00:00:00Z", calibratedObservedAt: null, receivedAt: "2026-10-21T01:00:00Z", acceptedObservationId: "8" } }, review: { decision_version: 0, result: "OPEN" }, todoOpen: true };
 const board = { task: { version: 7, name: "冻结任务", range_json: { usingOrganizationId: "9007199254740995" } }, items: [entry], total: 1 }, calls = []; let next = 0;
 const sessions = { identity: { accountId: "1", bindingId: "2" }, async request(method, path, body, mutation) { calls.push(structuredClone({ method, path, body, mutation })); return method === "POST" ? { result: "REVIEWED" } : path.includes("/observations/") ? { case: structuredClone(entry), history: { items: [], page: 1, pageSize: 20, total: 0 } } : structuredClone(board); } };
 const services = { sessions, createKey: () => `00000000-0000-4000-8000-${String(++next).padStart(12, "0")}`, confirm: async () => true };
 const page = createInventoryLatePage(() => services);page.data = structuredClone(page.data);page.setData = value => Object.assign(page.data, value);page.onLoad({ taskId: "9" });
 return { page, services, sessions, calls, board, entry };
}
async function prepare(f) { await f.page.onOpen({ currentTarget: { dataset: { id: "9007199254740993" } } });f.page.onField(field("reason", "核对不同时点现场线索并保留原结果"));f.page.onField(field("evidence", "独立核对冻结采信及原始观察谱系")); }
const writes = f => f.calls.filter(row => row.method === "POST");
test("unknown committed supplement reply survives hide and reread with exact original request", async () => {
 const f = fixture(), request = f.sessions.request;let lost = true;
 f.sessions.request = async (...args) => { const value = await request(...args);if (args[0] === "POST" && lost) { lost = false;f.entry.review.decision_version = 9;throw new Error("lost"); }return value; };
 await f.page.onShow();await prepare(f);await f.page.onDecide();assert.equal(f.page.data.retry, true);f.page.onHide();await f.page.onShow();await f.page.onRetry();assert.equal(writes(f).length, 2);assert.deepEqual(writes(f)[0], writes(f)[1]);
});
test("identity replacement during SDK confirmation submits nothing and hides evidence", async () => {
 const f = fixture();await f.page.onShow();await prepare(f);f.services.confirm = async () => { f.sessions.identity = { accountId: "3", bindingId: "4" };return true; };await f.page.onDecide();assert.equal(writes(f).length, 0);assert.equal(f.page.data.detail, null);
});
test("confirmation failure releases busy state without saving a supplemental operation", async () => {
 const f = fixture();await f.page.onShow();await prepare(f);f.services.confirm = async () => { throw new Error("SDK confirmation failed"); };await f.page.onDecide();assert.equal(f.page.data.busy, false);assert.equal(writes(f).length, 0);
});
test("current role denial clears original pending supplement and private facts", async () => {
 const f = fixture();await f.page.onShow();await prepare(f);f.sessions.request = async () => { throw new MiniRequestError(403, "INVENTORY_REVIEW_ADMIN_REQUIRED"); };await f.page.onDecide();assert.equal(f.page.data.retry, false);assert.equal(f.page.data.detail, null);
});
test("linked recheck preserves exact original range and large observation identifiers", async () => {
 const f = fixture();await f.page.onShow();await prepare(f);f.page.onMethod({ detail: { value: "1" } });f.page.onField(field("startsAt", "2026-10-22T00:00:00Z"));f.page.onField(field("dueAt", "2026-10-23T00:00:00Z"));await f.page.onDecide();
 const request = writes(f)[0];assert.match(request.path, /9007199254740993/);assert.deepEqual(request.body.recheck.range, { usingOrganizationId: "9007199254740995" });assert.equal(request.body.recheck.startsAt, "2026-10-22T00:00:00.000Z");
});
test("late old-identity history reply stays hidden", async () => {
 const f = fixture();await f.page.onShow();let release;f.sessions.request = () => new Promise(resolve => { release = resolve; });const reading = f.page.onOpen({ currentTarget: { dataset: { id: "9007199254740993" } } });
 f.sessions.identity = { accountId: "3", bindingId: "4" };release({ case: f.entry, history: { items: [{ decision_version: 1, evidence: "private" }], page: 1, pageSize: 20, total: 1 } });await reading;assert.equal(f.page.data.detail, null);assert.deepEqual(f.page.data.history, []);
});

test("history pagination preserves unsent reasons and never sends a decision", async () => {
 const f=fixture(), request=f.sessions.request;f.sessions.request=async (...args)=>{ if(args[1].includes("/observations/")){ f.calls.push(structuredClone({method:args[0],path:args[1]}));const next=args[1].includes("page=2");return {case:structuredClone(f.entry),history:{items:[{decision_version:next?21:1,method:"NOTE",reason:"原结论",evidence:"依据",actor_username:next?"第二页核实人":"首条核实人",decided_at:"2026-10-21",linked_task_id:null}],page:next?2:1,pageSize:20,total:21}};}return request(...args);};
 await f.page.onShow();await prepare(f);await f.page.onHistoryPage({currentTarget:{dataset:{delta:"1"}}});assert.match(f.page.data.history[0].text,/第二页核实人/);assert.equal(f.page.data.reason,"核对不同时点现场线索并保留原结果");assert.equal(writes(f).length,0);assert.ok(f.calls.some(row=>row.path.endsWith("?page=2&pageSize=20")));
});
