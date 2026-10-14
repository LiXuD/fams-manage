// Real native handler → opaque session → Spring HTTP → owned MySQL.
// WeChat exchange and loopback HTTPS bridging are explicit substitutes for device acceptance.
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { createInventoryPage } from "../src/inventory-pages.ts";
const port = process.env.FAMS_TEST_API_PORT; assert.match(port ?? "", /^[0-9]{1,5}$/);
const origin = "https://inventory-test.invalid"; let saved = null;
const sessions = new MiniSessionClient(origin, {
 readSession: () => saved, writeSession: value => saved = value, removeSession: () => saved = null, clearIdentityData() {},
 async request(input) {
  assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
  const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice(origin.length), { method: input.method,
   headers: { "Content-Type": "application/json", ...input.header }, body: input.data ? JSON.stringify(input.data) : undefined, redirect: "error" });
  return { statusCode: response.status, data: response.status === 204 ? null : await response.json() };
 }
});
assert.equal(await sessions.login(process.env.FAMS_TEST_INVENTORY_CODE), "BOUND");
const page = createInventoryPage(() => ({ sessions, navigate() {} })); page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
await page.onShow(); assert.equal(page.data.total, 1, page.data.error);
assert.equal(page.data.tasks[0].id, process.env.FAMS_TEST_INVENTORY_TASK);
await page.onOpen({ currentTarget: { dataset: { id: process.env.FAMS_TEST_INVENTORY_TASK } } });
assert.equal(page.data.itemTotal, 1, page.data.error); assert.equal(page.data.items[0].snapshot_json.assetCode, "INV-ASSET-933010");
assert.equal(page.data.task.range_json, undefined); assert.equal(page.data.task.expected_count, undefined);
page.onHide(); page.onUnload(); await sessions.logout();
console.log("Native inventory assigned-only snapshot and opaque session PASS");
