// Native handlers -> actual opaque mini sessions -> HTTP multipart/download -> MySQL.
// WeChat exchange, HTTPS bridge and malware scanner are explicit test substitutes.
import assert from "node:assert/strict";
import { mkdtemp, writeFile, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createProxyConfirmationPage } from "../src/proxy-confirmation-pages.ts";
import { createBusinessResultPage } from "../src/business-result-pages.ts";

const port = process.env.FAMS_TEST_API_PORT, type = process.env.FAMS_TEST_PROXY_BUSINESS;
assert.match(port ?? "", /^[0-9]{1,5}$/); assert.ok(["REQUISITION", "TRANSFER"].includes(type));
const origin = "https://proxy-test.invalid", directory = await mkdtemp(join(tmpdir(), "fams-mini-proxy-"));
const file = join(directory, "signed.pdf"), bytes = Buffer.from("%PDF-1.7\nTEST FIXTURE signed asset handover\n%%EOF\n");
const digest = value => createHash("sha256").update(value).digest("hex");
const writes = [], opened = [], destinations = [];
const event = (id, value = "", field) => ({ currentTarget: { dataset: { id, field } }, detail: { value } });
const bind = page => { page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); return page; };
async function actor(role) {
    let saved = null, sequence = 0;
    const lost = new Set(["create", "upload", "submit", "decision", "execute", "objection"]);
    const record = (kind, input, body) => {
        const value = { role, kind, key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"], body };
        writes.push(structuredClone(value));
        if (lost.delete(kind)) throw Error("test-only lost reply after durable commit");
    };
    const endpoint = input => { assert.ok(input.url.startsWith(origin + "/api/v1/mini/")); return `http://127.0.0.1:${port}` + input.url.slice(origin.length); };
    const runtime = {
        readSession: () => saved, writeSession: value => { saved = value; }, removeSession: () => { saved = null; }, clearIdentityData() {},
        async request(input) {
            const response = await fetch(endpoint(input), { method: input.method, headers: { "Content-Type": "application/json", ...input.header },
                body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error" });
            const reply = { statusCode: response.status, data: response.status === 204 ? null : await response.json() };
            if (input.method === "POST" && response.ok && input.url.includes("/proxy-confirmations")) {
                const kind = /\/proxy-confirmations$/.test(input.url) ? "create" : input.url.split("/").at(-1);
                record(kind, input, input.data);
            }
            if (input.method === "POST" && response.ok && input.url.endsWith("/objection")) record("objection", input, input.data);
            return reply;
        },
        async upload(input) {
            const data = new FormData(); data.append("file", new Blob([await readFile(input.filePath)], { type: "application/pdf" }), "signed.pdf");
            const response = await fetch(endpoint(input), { method: "POST", headers: input.header, body: data, redirect: "error" });
            const reply = { statusCode: response.status, data: await response.json() };
            if (response.ok) record("upload", input, { sha256: digest(await readFile(input.filePath)), filename: "signed.pdf" });
            return reply;
        },
        async download(input) {
            const response = await fetch(endpoint(input), { headers: input.header, redirect: "error" });
            const target = join(directory, `${role}-download.pdf`);
            await writeFile(target, Buffer.from(await response.arrayBuffer()));
            return { statusCode: response.status, tempFilePath: target };
        },
    };
    const sessions = new MiniSessionClient(origin, runtime);
    assert.equal(await sessions.login(process.env["FAMS_TEST_PROXY_" + role.toUpperCase() + "_CODE"]), "BOUND");
    return { sessions, createKey: () => `native-proxy-${role}-key-${++sequence}`,
        chooseEvidence: async () => ({ path: file, name: "signed.pdf", size: bytes.length }),
        openEvidence: async path => { assert.equal(digest(await readFile(path)), digest(bytes)); opened.push(role); },
        navigate: value => destinations.push(value), confirm: async () => true };
}
async function complete(page, action) {
    await action();
    if (page.data.retryable) await page.onRetry();
    assert.equal(page.data.retryable, false, page.data.error); assert.equal(page.data.error, "");
}
try {
    await writeFile(file, bytes);
    const warehouse = await actor("warehouse"), admin = await actor("admin"), recipient = await actor("recipient");
    const page = bind(createProxyConfirmationPage(() => warehouse)); await page.onShow();
    page.onBusiness(event(undefined, type === "TRANSFER" ? "1" : "0")); await page.onSources();
    const request = process.env.FAMS_TEST_PROXY_REQUEST_ID, item = process.env.FAMS_TEST_PROXY_ITEM_ID;
    assert.ok(page.data.sources.some(row => row.id === request), page.data.error);
    await page.onSource(event(request)); assert.ok(page.data.items.some(row => row.id === item), page.data.error);
    await page.onStart(event(item)); assert.ok(page.data.snapshot, page.data.error);
    const date = new Date(Date.now() - 120000), pad = value => String(value).padStart(2, "0");
    const fields = { actualSigner: "Actual responsible recipient", actualDate: `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
        actualTime: `${pad(date.getHours())}:${pad(date.getMinutes())}`, cannotConfirmReason: "Device unavailable after actual signed handover",
        contactRecord: "Checked signed original with recipient", observedAssetCode: String(page.data.snapshot.assetCode) };
    for (const [field, value] of Object.entries(fields)) page.onField(event(undefined, value, field));
    page.onFlag(event(undefined, ["checked"], "targetOrAuthorizedProxyReceived"));
    await complete(page, () => page.onCreate()); const id = page.data.selected.id;
    await page.onPickFile(); await complete(page, () => page.onUpload()); assert.equal(page.data.attachments.length, 1);
    await complete(page, () => page.onSubmit()); assert.equal(page.data.selected.confirmationStatus, "SUBMITTED");
    const approval = bind(createProxyConfirmationPage(() => admin)); approval.onLoad({ id }); await approval.onShow();
    const proof = approval.data.attachments[0]; await approval.onDownload(event(proof.objectId)); assert.deepEqual(opened, ["admin"]);
    approval.onDecisionReason(event(undefined, "Verified signed original and undisputed delivery"));
    await approval.onDecision({ currentTarget: { dataset: { approved: "true" } } });
    assert.match(approval.data.error, /下载核验/);
    approval.onFlag(event(undefined, ["checked"], "physicalChecked")); approval.onFlag(event(undefined, ["checked"], "noDisputeChecked"));
    await complete(approval, () => approval.onDecision({ currentTarget: { dataset: { approved: "true" } } }));
    assert.equal(approval.data.selected.confirmationStatus, "APPROVED");
    assert.equal((await recipient.sessions.request("GET", "/api/v1/mini/business-result-notices")).total, 0);
    await assert.rejects(admin.sessions.request("POST", `/api/v1/mini/proxy-confirmations/${id}/execute`, undefined,
        { key: admin.createKey(), version: approval.data.selected.version }), { status: 403 });
    await page.onRefresh(); await complete(page, () => page.onExecute()); assert.equal(page.data.selected.confirmationStatus, "EXECUTED");
    const notices = bind(createBusinessResultPage(() => recipient)); await notices.onShow(); assert.equal(notices.data.total, 1);
    const notice = notices.data.rows[0]; assert.equal(notice.businessType, type); assert.equal(notice.content.result.assetCode, fields.observedAssetCode);
    await complete(notices, () => notices.onRead(event(String(notice.id)))); assert.equal(notices.data.unread, 0);
    notices.onReason(event(String(notice.id), "Please check the signed handover original"));
    await complete(notices, () => notices.onReview(event(String(notice.id)))); assert.equal(notices.data.rows[0].reviewRequested, true);
    const review = bind(createBusinessResultPage(() => admin, true)); await review.onShow();
    assert.ok(review.data.reviews.some(row => row.executionId === id)); review.onOpen(event(id));
    assert.equal(destinations.at(-1), `/pages/proxy-confirmations/index?id=${id}`);
    await assert.rejects(recipient.sessions.request("GET", `/api/v1/mini/proxy-confirmations/${id}`), { status: 404 });
    for (const role of ["warehouse", "admin", "recipient"]) for (const kind of ["create", "upload", "submit", "decision", "execute", "objection"]) {
        const values = writes.filter(row => row.role === role && row.kind === kind);
        if (values.length) { assert.equal(values.length, 2, `${role}/${kind}`); assert.deepEqual(values[0], values[1]); }
    }
    for (const handler of [page, approval, notices, review]) { handler.onHide(); handler.onUnload(); }
    await warehouse.sessions.logout(); await admin.sessions.logout(); await recipient.sessions.logout();
    console.log(`Native ${type} proxy handlers, real multipart/download, six lost-reply identities and recipient review PASS.`);
} finally { await rm(directory, { recursive: true, force: true }); }
