import test from "node:test";
import assert from "node:assert/strict";
import { createLoanPage } from "../src/loan-pages.ts";

function fixture() {
    const writes = []; let lose = false; let key = 0;
    const loan = { id: "41", assetId: "51", assetCode: "A51", currentAssetVersion: 3, version: 2,
        expectedReturnDate: "2099-01-01", relationStatus: "OPEN", extensions: [] };
    const sessions = { identity: { accountId: "1" }, async request(method, path, body, mutation) {
        if (method === "GET") return path.endsWith("/41") ? structuredClone(loan) : { items: [], total: 0 };
        writes.push(structuredClone({ path, body, mutation }));
        if (lose) { lose = false; throw new Error("lost response"); }
        return { id: "61", version: 0 };
    } };
    const services = { sessions, createKey: () => `loan-mutation-key-${++key}`, navigate() {} };
    const page = createLoanPage(() => services);
    page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
    return { page, sessions, writes, loseNext() { lose = true; } };
}

test("native extension retries the immutable date, reason, version and key after an uncertain response", async () => {
    const f = fixture(); await f.page.onShow(); await f.page.onSelect({ currentTarget: { dataset: { id: "41" } } });
    f.page.data.dueDate = "2099-02-01"; f.page.data.reason = "continued use";
    f.loseNext(); await f.page.onExtend(); assert.equal(f.page.data.retryable, true);
    f.page.data.dueDate = "2099-03-01"; f.page.data.reason = "changed";
    await f.page.onRetry(); assert.deepEqual(f.writes[0], f.writes[1]);
    assert.equal(f.writes[1].body.expectedReturnDate, "2099-02-01");
    assert.equal(f.writes[1].mutation.version, 2); assert.equal(f.page.data.retryable, false);
});

test("native return uses the currently selected asset version and account changes clear unfinished writes", async () => {
    const f = fixture(); await f.page.onShow(); await f.page.onSelect({ currentTarget: { dataset: { id: "41" } } });
    f.page.data.reason = "return"; f.loseNext(); await f.page.onReturn();
    assert.deepEqual(f.writes[0].body, { assetId: "51", assetVersion: 3, reason: "return" });
    f.page.onHide(); f.sessions.identity = { accountId: "2" }; await f.page.onShow();
    assert.equal(f.page.data.selected, null); assert.equal(f.page.data.retryable, false);
    await f.page.onRetry(); assert.equal(f.writes.length, 1);
});
