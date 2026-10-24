import test from "node:test";
import assert from "node:assert/strict";
import { createRepairReturnSourcePage } from "../src/repair-return-source-pages.ts";
import { MiniRequestError } from "../src/session.ts";
const base = "/api/v1/mini/repairs/return-sources";
const source = { followupId: "9007199254740993", version: 3, returnId: "8", closedLoanId: "7", processorAccountId: "4", inspectedAt: "2026-10-24T00:00:00Z", inspectionReason: "Actual physical damage", lockVersion: 2, asset: { id: "9", assetCode: "DAMAGED-PRIVATE", name: "Actual asset", version: 6 } };
const event = (id, value = "", field) => ({ currentTarget: { dataset: { id, field } }, detail: { value } });
function fixture() {
    const writes = [], destinations = []; let lose = true;
    const services = { sessions: { identity: { accountId: "1", bindingId: "2" }, async request(method, path, body, options) {
        if (method === "POST") { writes.push(structuredClone({ path, body, key: options.key, version: options.version })); if (lose) { lose = false; throw Error("lost committed reply"); } return { id: "9007199254740995" }; }
        return path.includes("?") ? { items: [{ id: source.followupId, followup_status: writes.length ? "ACCEPTED" : "PENDING" }], total: 1 } : structuredClone(source);
    } }, createKey: () => "original-key", navigate: path => destinations.push(path) };
    const page = createRepairReturnSourcePage(() => services); page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
    return { page, services, writes, destinations };
}
test("damaged source needs inline confirmation and preserves original receipt after refresh and hide", async () => {
    const { page, writes, destinations } = fixture(); await page.onShow(); await page.onSelect(event(source.followupId));
    page.onPrepare(); assert.equal(writes.length, 0); assert.equal(page.data.prepared, true);
    await page.onConfirm(); assert.equal(page.data.retry, true); await page.onRefresh(); page.onHide(); await page.onShow();
    page.onField(event(undefined, "Changed fault", "fault")); await page.onRetry(); assert.deepEqual(writes[0], writes[1]);
    assert.equal(writes[0].path, base + "/9007199254740993"); assert.equal(writes[0].version, 3);
    assert.equal(writes[0].body.assetVersion, 6); assert.equal(writes[0].body.lockVersion, 2);
    page.onOpen(event()); assert.equal(destinations.at(-1), "/pages/repairs/index?id=9007199254740995");
});
test("binding switch discards pending source mutation and private context", async () => {
    const { page, services, writes } = fixture(); await page.onShow(); await page.onSelect(event(source.followupId)); page.onPrepare(); await page.onConfirm();
    page.onHide(); services.sessions.identity = { accountId: "1", bindingId: "3" }; await page.onShow(); await page.onRetry();
    assert.equal(page.data.source, null); assert.equal(writes.length, 1); assert.equal(page.data.retry, false);
});
test("forbidden source refresh clears private data and original authority", async () => {
    const { page, services, writes } = fixture(); await page.onShow(); await page.onSelect(event(source.followupId)); page.onPrepare(); await page.onConfirm();
    services.sessions.request = async () => { throw new MiniRequestError(403, "AUTH_FORBIDDEN"); };
    await page.onRefresh(); assert.equal(page.data.source, null); assert.deepEqual(page.data.rows, []); assert.equal(page.data.retry, false);
    await page.onRetry(); assert.equal(writes.length, 1);
});
test("late private source response cannot render after identity changes", async () => {
    const { page, services } = fixture(); await page.onShow(); let complete;
    services.sessions.request = () => new Promise(resolve => { complete = resolve; }); const selecting = page.onSelect(event(source.followupId));
    services.sessions.identity = { accountId: "8", bindingId: "8" }; complete(structuredClone(source)); await selecting;
    assert.equal(page.data.source, null); page.onPrepare(); assert.equal(page.data.prepared, false);
});
