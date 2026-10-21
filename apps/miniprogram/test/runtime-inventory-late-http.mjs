// Real native page handlers -> opaque session -> owned Spring/MySQL; SDK and HTTPS hostname are test adapters.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createInventoryLatePage } from "../src/inventory-late-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, task = process.env.FAMS_TEST_INVENTORY_TASK;
assert.match(port ?? "", /^[0-9]{1,5}$/);assert.match(task ?? "", /^[1-9][0-9]*$/);
const saved = JSON.parse(process.env.FAMS_TEST_MINI_GRANT), writes = [];let drop = true;
const sessions = new MiniSessionClient("https://late-test.invalid", {
 readSession: () => saved, writeSession() { throw new Error("No new login expected"); }, removeSession() {}, clearIdentityData() {},
 async request(input) {
  assert.ok(input.url.startsWith("https://late-test.invalid/api/v1/mini/"));
  const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice("https://late-test.invalid".length), { method: input.method,
   headers: { "Content-Type": "application/json", ...input.header }, body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error" });
  const value = { statusCode: response.status, data: await response.json() };assert.equal(response.status, 200, JSON.stringify(value.data));
  if (input.method === "POST") {
   writes.push(structuredClone({ path: input.url, body: input.data, key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] }));
   if (drop) { drop = false;throw new Error("Owned adapter lost the actual committed supplemental response"); }
  }
  return value;
 }
});
const page = createInventoryLatePage(() => ({ sessions, createKey: () => randomUUID(), confirm: async () => true }));
page.data = structuredClone(page.data);page.setData = value => Object.assign(page.data, value);
const field = (name, value) => ({ currentTarget: { dataset: { field: name } }, detail: { value } });
page.onLoad({ taskId: task });await page.onShow();assert.equal(page.data.error, "");assert.equal(page.data.board.total, 1);
const id = page.data.entries[0].id;await page.onOpen({ currentTarget: { dataset: { id } } });
assert.equal(page.data.detail.case.todoOpen, true);page.onField(field("reason", "原生端核对补充观察与冻结采信，保留原结果"));page.onField(field("evidence", "独立核对原观察及不同现场时点，补充结论不改原报告、日期或采信"));
await page.onDecide();assert.equal(page.data.retry, true);assert.equal(writes.length, 1);
page.onHide();assert.equal(page.data.detail, null);await page.onShow();assert.equal(page.data.board.items[0].review.decision_version, 1);
await page.onRetry();assert.equal(page.data.retry, false);assert.equal(writes.length, 2);assert.deepEqual(writes[0], writes[1]);
await page.onOpen({ currentTarget: { dataset: { id } } });assert.equal(page.data.detail.history.items.length, 1);assert.equal(page.data.detail.case.todoOpen, false);assert.equal(page.data.detail.case.review.result, "REVIEWED");page.onUnload();
console.log(JSON.stringify({ nativeSupplementOriginalReplyRecovered: true, exactOriginalKeyPayloadAndVersion: true, immutableHistoryRead: true, todoClosedBySupplementOnly: true }));
