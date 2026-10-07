import test from "node:test";
import assert from "node:assert/strict";
import { createRequisitionPage } from "../src/requisition-pages.ts";

function fixture() {
    const writes = []; let lose = false; let sequence = 0;
    const sessions = { identity: { accountId: "1", bindingId: "1" }, async request(method, path, body, mutation) {
        if (method === "GET" && path.includes("catalog")) return { items: [{ id: "1", version: 0, name: "fixture", organizationId: "1" }], employeeId: "1", organizationId: "1", canApplyShared: false };
        if (method === "GET") return { items: [], total: 0 };
        writes.push(structuredClone({ path, body, mutation }));
        if (lose) { lose = false; throw new Error("lost response"); }
        return { id: "1", version: 0, demands: [], creatorAccountId: "1" };
    } };
    const services = { sessions, createKey: () => `requisition-key-${++sequence}`, navigate() {} };
    const page = createRequisitionPage(() => services, true);
    page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
    return { page, sessions, writes, loseNext() { lose = true; } };
}

test("lost native save retries immutable demand and key even after displayed form changes", async () => {
    const f = fixture(); await f.page.onShow();
    Object.assign(f.page.data.form[0], { specification: "fixture", purpose: "work", quantity: 3 });
    f.loseNext(); await f.page.onSave(); assert.equal(f.page.data.retryable, true);
    f.page.data.form[0].quantity = 999;
    await f.page.onRetry();
    assert.deepEqual(f.writes[0], f.writes[1]); assert.equal(f.writes[1].body.demands[0].quantity, 3);
    assert.deepEqual(Object.keys(f.writes[1].body.demands[0]).sort(), ["category","specification","preferredBrand","preferredModel","purpose","expectedDate","custodyType","organization","employee","location","quantity"].sort());
    assert.equal(f.page.data.retryable, false);
});

test("hide and identity change discard old response and never repaint another account", async () => {
    const f = fixture(); await f.page.onShow();
    Object.assign(f.page.data.form[0], { specification: "fixture", purpose: "work" });
    let resolve; const original = f.sessions.request;
    f.sessions.request = (method, ...args) => method === "POST" ? new Promise(done => { resolve = done; }) : original.call(f.sessions, method, ...args);
    const old = f.page.onSave(); f.page.onHide();
    f.sessions.identity = { accountId: "2", bindingId: "2" }; await f.page.onShow();
    resolve({ id: "old-secret", version: 0, demands: [] }); await old;
    assert.equal(f.page.data.selected, null); assert.equal(f.page.data.retryable, false);
    assert.equal(JSON.stringify(f.page.data).includes("old-secret"), false);
});
