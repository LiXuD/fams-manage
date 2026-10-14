import assert from "node:assert/strict";
import test from "node:test";
import { createInventoryPage } from "../src/inventory-pages.ts";
function fixture(request) {
 const services = { sessions: { identity: { accountId: "1", bindingId: "1" }, request }, navigate() {} };
 const page = createInventoryPage(() => services); page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
 return { page, services };
}
test("native inventory keeps opaque string identifiers and reads only server-authorized snapshots", async () => {
 const calls = []; const id = "9007199254740993";
 const { page } = fixture(async (method, path) => { calls.push([method, path]);
  return path.includes(`/${id}?`) ? { task: { id, name: "Own task" }, items: [{ id: "9", snapshot_json: { assetCode: "A-1" } }], total: 1 }
   : { items: [{ id, name: "Own task" }], total: 1 }; });
 await page.onShow(); await page.onOpen({ currentTarget: { dataset: { id } } });
 assert.equal(page.data.task.id, id); assert.equal(page.data.items[0].snapshot_json.assetCode, "A-1");
 assert.ok(calls.every(([method]) => method === "GET")); assert.match(calls[1][1], /9007199254740993/);
});
test("binding switch and hidden page reject late inventory details", async () => {
 let release; const { page, services } = fixture(async (_method, path) => path.includes("/9?") ? new Promise(resolve => release = resolve) : { items: [], total: 0 });
 await page.onShow(); const opening = page.onOpen({ currentTarget: { dataset: { id: "9" } } });
 await new Promise(resolve => setImmediate(resolve)); page.onHide(); services.sessions.identity = { accountId: "2", bindingId: "2" };
 await page.onShow(); release({ task: { id: "9", name: "Old private task" }, items: [{ id: "8" }], total: 1 }); await opening;
 assert.equal(page.data.task, null); assert.deepEqual(page.data.items, []); assert.equal(page.data.busy, false);
});
