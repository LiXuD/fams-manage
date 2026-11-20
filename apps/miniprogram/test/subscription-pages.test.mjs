import test from "node:test";
import assert from "node:assert/strict";
import { createLoanPage } from "../src/loan-pages.ts";
import { createReceiptPage } from "../src/receipt-pages.ts";
import { createTransferReceiptPage } from "../src/transfer-receipt-pages.ts";
import { MiniRequestError } from "../src/session.ts";

const SUBSCRIBED = "已订阅，微信将发送一次服务通知。";
const FALLBACK = "未开启微信通知，可继续在站内查看。";
const keyPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function subscribeCapability(options, calls) {
    if (options.noCapability) return {};
    return { requestSubscribe: async templateId => {
        calls.push(templateId);
        if (options.reply === "throw") throw new Error("wx requestSubscribeMessage failed");
        return options.reply ?? "accept";
    } };
}

function loanFixture(options = {}) {
    const writes = [], calls = []; let key = 0;
    const loan = { id: "41", assetId: "51", assetCode: "A51", currentAssetVersion: 3, version: 2,
        expectedReturnDate: "2099-01-01", relationStatus: "OPEN", extensions: [] };
    const sessions = { identity: { accountId: "1", bindingId: "1" }, async request(method, path, body, mutation) {
        if (path === "/api/v1/mini/subscription-visibility") {
            if (options.visibilityError) throw options.visibilityError;
            return { wechatTemplate: options.template ?? "tmpl-1" };
        }
        if (method === "GET") return path.endsWith("/41") ? structuredClone(loan) : { items: [], total: 0 };
        if (path === "/api/v1/mini/subscription-grants") {
            writes.push(structuredClone({ path, body, mutation }));
            if (options.grantError) throw options.grantError;
            return { id: "71", templateId: body.templateId, scene: body.scene, businessType: body.businessType,
                businessId: body.businessId, status: "AVAILABLE", createdAt: "2026-11-01T00:00:00Z" };
        }
        return { id: "61", version: 0 };
    } };
    const services = { sessions, createKey: () => `00000000-0000-4000-8000-${String(++key).padStart(12, "0")}`,
        navigate() {}, ...subscribeCapability(options, calls) };
    const page = createLoanPage(() => services);
    page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
    return { page, services, writes, calls };
}

async function openLoan(fixture) {
    await fixture.page.onShow();
    await fixture.page.onSelect({ currentTarget: { dataset: { id: "41" } } });
}

test("loan progress tap posts the accepted template once with a fresh key and version 0", async () => {
    const f = loanFixture(); await openLoan(f);
    assert.equal(f.page.data.subscriptionTemplate, "tmpl-1");
    await f.page.onSubscribeProgress();
    assert.deepEqual(f.calls, ["tmpl-1"]);
    assert.equal(f.writes.length, 1);
    assert.deepEqual(f.writes[0].body, { templateId: "tmpl-1", scene: "PROGRESS", businessType: "LOAN", businessId: "41" });
    assert.equal(f.writes[0].mutation.version, 0);
    assert.match(f.writes[0].mutation.key, keyPattern);
    assert.equal(f.page.data.subscriptionNotice, SUBSCRIBED);
    assert.equal(f.page.data.busy, false); assert.equal(f.page.data.error, "");
});

test("loan return tap uses the RETURN scene for the same selected loan", async () => {
    const f = loanFixture(); await openLoan(f);
    await f.page.onSubscribeReturn();
    assert.equal(f.writes.length, 1);
    assert.deepEqual(f.writes[0].body, { templateId: "tmpl-1", scene: "RETURN", businessType: "LOAN", businessId: "41" });
    assert.equal(f.page.data.subscriptionNotice, SUBSCRIBED);
});

test("refusal, ban, wx failure and missing capability never post a grant and keep the in-app fallback", async () => {
    for (const options of [{ reply: "reject" }, { reply: "ban" }, { reply: "throw" }, { noCapability: true }]) {
        const f = loanFixture(options); await openLoan(f);
        await f.page.onSubscribeProgress();
        assert.equal(f.writes.length, 0);
        assert.equal(f.page.data.subscriptionNotice, FALLBACK);
        assert.equal(f.page.data.subscriptionTemplate, "tmpl-1");
        assert.equal(f.page.data.busy, false); assert.equal(f.page.data.error, "");
    }
});

test("empty or unreadable visibility hides the entry and taps stay inert", async () => {
    for (const options of [{ template: "" }, { visibilityError: new Error("network down") }]) {
        const f = loanFixture(options); await openLoan(f);
        assert.equal(f.page.data.subscriptionTemplate, "");
        await f.page.onSubscribeProgress(); await f.page.onSubscribeReturn();
        assert.deepEqual(f.calls, []); assert.equal(f.writes.length, 0);
        assert.equal(f.page.data.subscriptionNotice, "");
    }
});

test("a cleared template answer on grant hides the entry without surfacing an error", async () => {
    const f = loanFixture({ grantError: new MiniRequestError(409, "WECHAT_SUBSCRIPTION_TEMPLATE_UNCONFIGURED") });
    await openLoan(f); await f.page.onSubscribeProgress();
    assert.equal(f.writes.length, 1);
    assert.equal(f.page.data.subscriptionTemplate, "");
    assert.equal(f.page.data.subscriptionNotice, FALLBACK);
    assert.equal(f.page.data.error, "");
});

test("other grant failures use the factory error message and keep the entry visible", async () => {
    const f = loanFixture({ grantError: new MiniRequestError(403, "WECHAT_SUBSCRIPTION_FORBIDDEN") });
    await openLoan(f); await f.page.onSubscribeProgress();
    assert.equal(f.page.data.error, "没有当前记录或操作的权限。");
    assert.equal(f.page.data.subscriptionTemplate, "tmpl-1");
    assert.equal(f.page.data.subscriptionNotice, "");
    assert.equal(f.page.data.busy, false);
});

function receiptFixture(options = {}) {
    const writes = [], calls = []; let key = 0;
    const detail = { requestId: "1", requestNo: "REQ1", requestVersion: 4, allocationId: "2", allocationVersion: 1,
        allocationStatus: "DELIVERED_WAITING_RECEIPT", asset: { assetCode: "A1", name: "Computer" },
        handover: { deliveredAt: "now", receiptDeadlineAt: "later", destinationText: "Office" } };
    const sessions = { identity: { accountId: "1", bindingId: "1" }, async request(method, path, body, mutation) {
        if (path === "/api/v1/mini/subscription-visibility") return { wechatTemplate: options.template ?? "tmpl-1" };
        if (path === "/api/v1/mini/subscription-grants") {
            writes.push(structuredClone({ path, body, mutation }));
            if (options.grantError) throw options.grantError;
            return { id: "72", status: "AVAILABLE" };
        }
        if (path.includes("/pending")) return { items: [], total: 0 };
        if (path.endsWith("/receipt")) return structuredClone(detail);
        if (path.endsWith("/grant")) return { grant: "receipt-grant" };
        throw new Error("unexpected path " + path);
    } };
    const services = { sessions, createKey: () => `00000000-0000-4000-8000-${String(++key).padStart(12, "0")}`,
        confirm: async () => true, navigate() {}, ...subscribeCapability(options, calls) };
    const page = createReceiptPage(() => services);
    page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
    return { page, writes, calls };
}

test("requisition receipt tap subscribes the opened allocation under the RECEIPT scene", async () => {
    const f = receiptFixture(); await f.page.onShow();
    await f.page.onOpen({ currentTarget: { dataset: { request: "1", id: "2" } } });
    assert.equal(f.page.data.subscriptionTemplate, "tmpl-1");
    await f.page.onSubscribeReceipt();
    assert.equal(f.writes.length, 1);
    assert.deepEqual(f.writes[0].body, { templateId: "tmpl-1", scene: "RECEIPT", businessType: "REQUISITION_ALLOCATION", businessId: "2" });
    assert.equal(f.writes[0].mutation.version, 0);
    assert.match(f.writes[0].mutation.key, keyPattern);
    assert.equal(f.page.data.subscriptionNotice, SUBSCRIBED);
});

test("receipt refusal keeps the receipt flow usable and posts no grant", async () => {
    const f = receiptFixture({ reply: "reject" }); await f.page.onShow();
    await f.page.onOpen({ currentTarget: { dataset: { request: "1", id: "2" } } });
    await f.page.onSubscribeReceipt();
    assert.equal(f.writes.length, 0);
    assert.equal(f.page.data.subscriptionNotice, FALLBACK);
    assert.ok(f.page.data.selected);
});

test("transfer receipt tap subscribes the opened item under TRANSFER_ITEM", async () => {
    const writes = [], calls = []; let key = 0;
    const row = { id: "2", requestId: "1", requestVersion: 4, version: 1, assetCode: "A1", name: "Computer",
        itemStatus: "DELIVERED_WAITING_RECEIPT", deliveredAt: "now", receiptDeadlineAt: "later", destination: "Office" };
    const sessions = { identity: { accountId: "1", bindingId: "1" }, async request(method, path, body, mutation) {
        if (path === "/api/v1/mini/subscription-visibility") return { wechatTemplate: "tmpl-1" };
        if (path === "/api/v1/mini/subscription-grants") { writes.push(structuredClone({ path, body, mutation })); return { id: "73", status: "AVAILABLE" }; }
        if (path.includes("/pending")) return { items: [structuredClone(row)], total: 1 };
        if (path.endsWith("/grant")) return { grant: "transfer-grant", requestVersion: 4 };
        throw new Error("unexpected path " + path);
    } };
    const services = { sessions, createKey: () => `00000000-0000-4000-8000-${String(++key).padStart(12, "0")}`,
        confirm: async () => true, navigate() {}, ...subscribeCapability({}, calls) };
    const page = createTransferReceiptPage(() => services);
    page.data = structuredClone(page.data); page.setData = data => Object.assign(page.data, data);
    await page.onShow(); await page.onOpen({ currentTarget: { dataset: { request: "1", id: "2" } } });
    assert.equal(page.data.subscriptionTemplate, "tmpl-1");
    await page.onSubscribeReceipt();
    assert.equal(writes.length, 1);
    assert.deepEqual(writes[0].body, { templateId: "tmpl-1", scene: "RECEIPT", businessType: "TRANSFER_ITEM", businessId: "2" });
    assert.equal(page.data.subscriptionNotice, SUBSCRIBED);
});

test("wx subscription wiring resolves reject without the API and maps accept, missing reply and failure", async () => {
    const { requestSubscribe } = await import("../src/wx-runtime.ts");
    const wx = {};
    assert.equal(await requestSubscribe(wx, "tmpl-1"), "reject");
    const seen = [];
    wx.requestSubscribeMessage = input => { seen.push(input.tmplIds); input.success({ [input.tmplIds[0]]: "accept" }); };
    assert.equal(await requestSubscribe(wx, "tmpl-1"), "accept");
    assert.deepEqual(seen, [["tmpl-1"]]);
    wx.requestSubscribeMessage = input => input.success({});
    assert.equal(await requestSubscribe(wx, "tmpl-1"), "reject");
    wx.requestSubscribeMessage = input => input.fail(new Error("user closed the panel"));
    assert.equal(await requestSubscribe(wx, "tmpl-1"), "reject");
});