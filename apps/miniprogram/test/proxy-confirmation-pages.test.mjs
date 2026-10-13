import test from "node:test";
import assert from "node:assert/strict";
import { createProxyConfirmationPage } from "../src/proxy-confirmation-pages.ts";
import { createBusinessResultPage } from "../src/business-result-pages.ts";

const base = "/api/v1/mini/proxy-confirmations";
const snapshot = { businessType: "REQUISITION", businessId: "9", itemId: "7", assetCode: "FA-1", targetEmployeeId: "5" };
function bind(page) { page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); return page; }
const event = (id, value = "", field) => ({ currentTarget: { dataset: { id, field } }, detail: { value } });
function fixture() {
    const writes = [], uploads = []; let proposal = null, proofs = [], loseCreate = true, loseUpload = true, keys = 0;
    const services = { sessions: { identity: { accountId: "1", bindingId: "1" },
        async request(method, path, body, options) {
            if (method === "POST") {
                writes.push(structuredClone({ path, body, key: options?.key, version: options?.version }));
                if (path === base) {
                    proposal ??= { id: "1", creatorAccountId: "1", businessType: "REQUISITION", businessId: "9", itemId: "7", version: 0, confirmationStatus: "DRAFT", businessSnapshotJson: snapshot };
                    if (loseCreate) { loseCreate = false; throw Error("lost create reply"); }
                } else if (path.endsWith("/submit")) { proposal.version = 2; proposal.confirmationStatus = "SUBMITTED"; }
                return structuredClone(proposal);
            }
            if (path.startsWith(base + "?")) return { items: proposal ? [structuredClone(proposal)] : [], total: proposal ? 1 : 0, canExecute: true, canApprove: false };
            if (path.includes("/context?")) return structuredClone(snapshot);
            if (path.endsWith("/attachments")) return { items: structuredClone(proofs) };
            return structuredClone(proposal);
        },
        async uploadEvidence(path, filePath, options) {
            uploads.push(structuredClone({ path, filePath, key: options.key, version: options.version }));
            proposal.version = 1; proofs = [{ objectId: "proof", filename: "signed.pdf", scanStatus: "CLEAN" }];
            if (loseUpload) { loseUpload = false; throw Error("lost upload reply"); }
            return { businessVersion: 1 };
        } }, chooseEvidence: async () => ({ path: "/chosen/signed.pdf", name: "signed.pdf", size: 100 }),
        createKey: () => "original-proxy-key-" + ++keys, navigate() {}, confirm: async () => true };
    return { services, writes, uploads, page: bind(createProxyConfirmationPage(() => services)) };
}
async function fill(page) {
    await page.onShow(); page.setData({ snapshot: structuredClone(snapshot) });
    for (const [field, value] of Object.entries({ actualSigner: "Recipient", actualDate: "2026-10-12", actualTime: "08:00", cannotConfirmReason: "Device unavailable", contactRecord: "Contacted recipient", observedAssetCode: "FA-1" })) page.onField(event(undefined, value, field));
    page.onFlag(event(undefined, ["checked"], "targetOrAuthorizedProxyReceived"));
}
test("lost creation and multipart replies keep exact payload, file, key and version", async () => {
    const { page, writes, uploads } = fixture(); await fill(page);
    await page.onCreate(); assert.equal(page.data.retryable, true);
    page.onField(event(undefined, "Changed signer", "actualSigner"));
    await page.onRetry(); assert.deepEqual(writes[0], writes[1]); assert.equal(writes[0].body.actualSigner, "Recipient");
    await page.onPickFile(); await page.onUpload(); assert.equal(page.data.retryable, true);
    await page.onPickFile(); await page.onRetry(); assert.deepEqual(uploads[0], uploads[1]);
    assert.equal(uploads[0].version, 0); assert.equal(page.data.selected.version, 1);
    await page.onSubmit(); assert.equal(page.data.selected.confirmationStatus, "SUBMITTED");
});
test("identity switch discards a late file selection and cannot upload another account's file", async () => {
    const { page, services, uploads } = fixture(); await fill(page); await page.onCreate(); await page.onRetry();
    let finish; services.chooseEvidence = () => new Promise(resolve => finish = resolve);
    const picking = page.onPickFile(); page.onHide(); services.sessions.identity = { accountId: "2", bindingId: "2" };
    await page.onShow(); finish({ path: "/old/signed.pdf", name: "old.pdf", size: 100 }); await picking;
    assert.equal(page.data.fileName, ""); await page.onUpload(); assert.equal(uploads.length, 0);
});
test("same identity can return from a native file picker without losing its selected proof", async () => {
    const { page, services, uploads } = fixture(); await fill(page); await page.onCreate(); await page.onRetry();
    let finish; services.chooseEvidence = () => new Promise(resolve => finish = resolve);
    const picking = page.onPickFile(); page.onHide();
    finish({ path: "/chosen/returned.pdf", name: "returned.pdf", size: 100 }); await picking;
    await page.onShow(); assert.equal(page.data.fileName, "returned.pdf");
    await page.onUpload(); await page.onRetry(); assert.equal(uploads[0].filePath, "/chosen/returned.pdf");
});
test("same identity hide/show keeps original mutation while late result stays hidden", async () => {
    const { page, services, writes } = fixture(); await fill(page);
    const original = services.sessions.request; let finish, first = true;
    services.sessions.request = async (...args) => {
        if (args[0] === "POST" && first) { first = false; return new Promise(resolve => finish = () => original(...args).catch(() => ({})).then(resolve)); }
        return original(...args);
    };
    const saving = page.onCreate(); page.onHide(); await page.onShow(); finish(); await saving;
    assert.equal(page.data.selected, null); assert.equal(page.data.retryable, true);
    await page.onRetry(); assert.deepEqual(writes[0], writes[1]); assert.equal(page.data.selected.id, "1");
});
test("administrator approval needs both checks and administrator cannot execute warehouse receipt", async () => {
    let writes = 0;
    const proposal = { id: "1", version: 2, confirmationStatus: "SUBMITTED", creatorAccountId: "1", businessSnapshotJson: snapshot };
    const services = { sessions: { identity: { accountId: "2", bindingId: "2" }, request: async (method, path) => {
        if (method === "POST") { writes++; return proposal; }
        return path.includes("?") ? { items: [proposal], total: 1, canApprove: true, canExecute: false } : path.endsWith("/attachments") ? { items: [] } : proposal;
    } }, createKey: () => "approval-original-key-001", navigate() {}, confirm: async () => true };
    const page = bind(createProxyConfirmationPage(() => services)); page.onLoad({ id: "1" }); await page.onShow();
    page.onDecisionReason(event(undefined, "Verified original"));
    const approved = { currentTarget: { dataset: { approved: "true" } } };
    await page.onDecision(approved); assert.equal(writes, 0);
    page.onFlag(event(undefined, ["checked"], "physicalChecked")); await page.onDecision(approved); assert.equal(writes, 0);
    page.onFlag(event(undefined, ["checked"], "noDisputeChecked")); await page.onDecision(approved); assert.equal(writes, 1);
    page.setData({ selected: { ...proposal, confirmationStatus: "APPROVED" } }); await page.onExecute(); assert.equal(writes, 1);
});
test("recipient objection retries unchanged reason and administrator opens original proposal", async () => {
    const writes = []; let lost = true, reported = false;
    const services = { sessions: { identity: { accountId: "5", bindingId: "5" }, request: async (method, path, body, options) => {
        if (method === "POST") { writes.push(structuredClone({ path, body, key: options.key })); reported = true; if (lost) { lost = false; throw Error("lost"); } return {}; }
        return { items: [{ id: 7, read: false, reviewRequested: reported }], total: 1, unread: 1 };
    } }, createKey: () => "recipient-review-key-001", navigate() {} };
    const page = bind(createBusinessResultPage(() => services)); await page.onShow();
    page.onReason(event("7", "Please check the signed proof")); await page.onReview(event("7"));
    page.onReason(event("7", "Changed reason")); await page.onRetry(); assert.deepEqual(writes[0], writes[1]);
    assert.equal(page.data.rows[0].reviewRequested, true); assert.equal(page.data.unread, 1);
    let destination; services.navigate = value => destination = value;
    const admin = bind(createBusinessResultPage(() => services, true)); await admin.onShow(); admin.onOpen(event("9"));
    assert.equal(destination, "/pages/proxy-confirmations/index?id=9");
});
