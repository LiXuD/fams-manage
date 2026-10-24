import test from "node:test";
import assert from "node:assert/strict";
import { createInventoryLateDamagePage } from "../src/inventory-late-damage-pages.ts";
import { MiniRequestError } from "../src/session.ts";
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
function fixture() {
 const detail = { assetId: "9007199254740993", currentAsset: { version: 8, assetCode: "PRIVATE-DAMAGE" }, damage: { version: 3, damage_status: "OPEN", repair_id: null, repair_status: null }, originalEvidence: { rawObservedAt: "2099-01-01T00:00:00Z" }, recheckResult: "FOLLOWUP_OPEN", todoOpen: true, history: { items: [], page: 1, total: 0 } }, calls = [], navigations = []; let count = 0;
 const sessions = { identity: { accountId: "1", bindingId: "2", bindingVersion: 0 }, async request(method, path, body, mutation) { calls.push(structuredClone({ method, path, body, mutation })); return structuredClone(detail); } };
 const services = { sessions, createKey: () => `00000000-0000-4000-8000-${String(++count).padStart(12, "0")}`, confirm: async () => true, navigate: url => navigations.push(url) };
 const page = createInventoryLateDamagePage(() => services); page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data); page.onLoad({ taskId: "9", observationId: "9007199254740994" });
 return { page, sessions, services, calls, detail, navigations };
}
const writes = f => f.calls.filter(row => row.method === "POST");
function prepare(f) {
 for (const [name, value] of Object.entries({ reason: "独立核对实际损坏", evidence: "原始照片实物和独立发现时间均已核实", fault: "现场真实故障", discoveryDate: "2026-01-01", discoveryTime: "09:10", discoverySecond: "11.123" })) f.page.onField(field(name, value));
 f.page.onChecked({ detail: { value: ["physical", "time"] } });
}
test("captures independent damage and asset versions with separately verified discovery time", async () => {
 const f = fixture(); await f.page.onShow(); assert.equal(f.page.data.discoveryDate, ""); prepare(f); await f.page.onDecide();
 assert.equal(writes(f).length, 1); const write = writes(f)[0]; assert.equal(write.mutation.version, 3); assert.equal(write.body.assetVersion, 8);
 assert.equal(write.body.repair.verifiedDiscoveryAt, new Date("2026-01-01T09:10:11.123").toISOString()); assert.equal(write.body.repair.physicalDamageChecked, true); assert.equal(write.body.repair.discoveryTimeVerified, true);
 assert.equal(f.page.data.detail.todoOpen, true); assert.equal(f.page.data.busy, false); assert.match(f.page.data.notice, /原补充复核/);
});
test("an unknown committed reply survives hide/show, refreshed versions and a failed GET unchanged", async () => {
 const f = fixture(), request = f.sessions.request; let lost = true, failRead = false;
 f.sessions.request = async (...args) => { if (args[0] === "GET" && failRead) { failRead = false; throw new MiniRequestError(409, "READ_CHANGED"); }
  const result = await request(...args); if (args[0] === "POST" && lost) { lost = false; f.detail.damage.version = 12; throw Error("lost reply"); } return result; };
 await f.page.onShow(); prepare(f); await f.page.onDecide(); assert.equal(f.page.data.retry, true);
 f.page.onHide(); assert.equal(f.page.data.detail, null); await f.page.onShow(); assert.equal(f.page.data.detail.damage.version, 12);
 failRead = true; await f.page.onRefresh(); assert.equal(f.page.data.retry, true); await f.page.onRetry();
 assert.deepEqual(writes(f)[0], writes(f)[1]); assert.equal(f.page.data.retry, false); assert.equal(f.page.data.busy, false);
});
test("same-asset active repair inspection is required and captures its actual version", async () => {
 const f = fixture(), request = f.sessions.request; let wrong = true;
 f.sessions.request = async (...args) => args[1].includes("/repairs/") ? { id: "88", assetId: wrong ? "other" : f.detail.assetId, version: 6, status: "WAREHOUSE_PROCESSING" } : request(...args);
 await f.page.onShow(); prepare(f); f.page.onMethod({ detail: { value: "1" } }); f.page.onField(field("repairId", "88"));
 await f.page.onInspect(); await f.page.onDecide(); assert.equal(writes(f).length, 0); wrong = false;
 await f.page.onInspect(); await f.page.onDecide(); assert.equal(writes(f)[0].body.repair.repairVersion, 6); assert.equal(writes(f)[0].body.repair.repairId, "88");
});
test("existing repair progress needs no new physical or discovery claims", async () => {
 const f = fixture(); f.detail.currentAsset = null; f.detail.damage = { version: 4, damage_status: "PROCESSING", repair_id: "88", repair_status: "WAREHOUSE_PROCESSING" };
 await f.page.onShow(); f.page.onField(field("reason", "核对真实维修进度")); f.page.onField(field("evidence", "独立核对正式维修实际受理与完成记录")); await f.page.onDecide();
 assert.equal(writes(f)[0].body.method, "REFRESH_REPAIR"); assert.equal(writes(f)[0].body.assetVersion, 0); assert.equal(writes(f)[0].body.repair, undefined);
 f.page.onRepair(); assert.deepEqual(f.navigations, ["/pages/repairs/index?id=88"]);
});
test("identity replacement during confirmation sends nothing and hides all private inputs", async () => {
 const f = fixture(); await f.page.onShow(); prepare(f);
 f.services.confirm = async () => { f.sessions.identity = { accountId: "3", bindingId: "4" }; return true; }; await f.page.onDecide();
 assert.equal(writes(f).length, 0); assert.equal(f.page.data.detail, null); assert.equal(f.page.data.evidence, ""); assert.equal(f.page.data.taskId, "");
});
test("current permission denial discards unknown pending operation and hides evidence", async () => {
 const f = fixture(), request = f.sessions.request;
 f.sessions.request = async (...args) => { const result = await request(...args); if (args[0] === "POST") throw Error("unknown"); return result; };
 await f.page.onShow(); prepare(f); await f.page.onDecide(); assert.equal(f.page.data.retry, true);
 f.sessions.request = async () => { throw new MiniRequestError(403, "FORBIDDEN"); }; await f.page.onRefresh();
 assert.equal(f.page.data.retry, false); assert.equal(f.page.data.detail, null); await f.page.onRetry(); assert.equal(writes(f).length, 1);
});
test("late hidden and old identity reads cannot restore private evidence", async () => {
 const f = fixture(); let release;
 f.sessions.request = () => new Promise(resolve => { release = resolve; }); const reading = f.page.onShow();
 f.page.onHide(); release(f.detail); await reading; assert.equal(f.page.data.detail, null);
 const readingAgain = f.page.onShow(); f.sessions.identity = { accountId: "3", bindingId: "4" }; release(f.detail); await readingAgain;
 assert.equal(f.page.data.detail, null); assert.equal(f.page.data.taskId, "");
});
test("invalid calendar dates, unverified time and confirmation errors never create a repair", async () => {
 const f = fixture(); await f.page.onShow(); prepare(f); f.page.onField(field("discoveryDate", "2026-02-30")); await f.page.onDecide(); assert.equal(writes(f).length, 0);
 f.page.onField(field("discoveryDate", "2026-01-01")); f.page.onChecked({ detail: { value: ["physical"] } }); await f.page.onDecide(); assert.equal(writes(f).length, 0);
 f.page.onChecked({ detail: { value: ["physical", "time"] } }); f.services.confirm = async () => { throw Error("SDK confirm failed"); }; await f.page.onDecide();
 assert.equal(writes(f).length, 0); assert.equal(f.page.data.busy, false); assert.equal(f.page.data.retry, false);
});
