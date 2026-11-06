// Real Page -> opaque session -> owned HTTP/MySQL. The SDK and HTTPS hostname are test adapters.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID, createHash } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";
import { createScrapPage } from "../src/scrap-pages.ts";

const port = process.env.FAMS_TEST_API_PORT;
assert.match(port ?? "", /^[0-9]{1,5}$/);
const grants = JSON.parse(process.env.FAMS_TEST_SCRAP_GRANTS);
const origin = "https://scrap-test.invalid";
const root = fs.mkdtempSync(path.join(os.tmpdir(), "fams-native-scrap-"));
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0WQAAAAASUVORK5CYII=", "base64");
const hash = bytes => createHash("sha256").update(bytes).digest("hex");
const writes = [], uploads = [], completions = [], clients = new Map();
let drop = true, dropUpload = true, dropCompletion = true;
function target(url) {
    assert.ok(url.startsWith(origin + "/api/v1/mini/scrap-requests"));
    return "http://127.0.0.1:" + port + url.slice(origin.length);
}
const event = dataset => ({ currentTarget: { dataset } });
const field = (name, value) => ({ ...event({ field: name }), detail: { value } });
function services(role) {
    if (clients.has(role)) return clients.get(role);
    const dir = path.join(root, role); fs.mkdirSync(dir);
    const source = path.join(dir, "chosen.png");
    const storage = new Map([["fams.session", grants[role]]]);
    const wx = {
        env: { USER_DATA_PATH: dir },
        getFileSystemManager: () => ({ mkdirSync: (name, recursive) => fs.mkdirSync(name, { recursive }), copyFileSync: fs.copyFileSync,
            statSync: fs.statSync, unlinkSync: fs.unlinkSync, readdirSync: fs.readdirSync, accessSync: fs.accessSync }),
        getStorageSync: key => storage.get(key),
        setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
        removeStorageSync: key => storage.delete(key),
        request: input => {
            void (async () => {
                const response = await fetch(target(input.url), {
                    method: input.method, headers: { "Content-Type": "application/json", ...input.header },
                    body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error",
                });
                const data = await response.json();
                if (input.method === "POST" && input.url.endsWith("/execute")) {
                    writes.push({ path: input.url, key: input.header["Idempotency-Key"],
                        version: input.header["If-Match-Version"], body: structuredClone(input.data) });
                    if (drop && response.status === 200) {
                        drop = false; input.fail(Error("Owned adapter dropped committed scrap response")); return;
                    }
                }
                if (input.method === "POST" && input.url.endsWith("/disposition/complete")) {
                    completions.push({ path: input.url, key: input.header["Idempotency-Key"],
                        version: input.header["If-Match-Version"], body: structuredClone(input.data), status: response.status });
                    if (dropCompletion && response.ok) {
                        dropCompletion = false; input.fail(Error("Owned adapter dropped committed disposition closure")); return;
                    }
                }
                input.success({ statusCode: response.status, data });
            })().catch(input.fail);
        },
        uploadFile: input => {
            void (async () => {
                const bytes = fs.readFileSync(input.filePath), multipart = new FormData();
                for (const [key, value] of Object.entries(input.formData)) multipart.append(key, value);
                multipart.append(input.name, new Blob([bytes], { type: "application/octet-stream" }), "opaque-native-original");
                const response = await fetch(target(input.url), { method: "POST", headers: input.header, body: multipart, redirect: "error" });
                const data = await response.text();
                uploads.push({ url: input.url, key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"],
                    form: structuredClone(input.formData), digest: hash(bytes), path: input.filePath, status: response.status });
                if (dropUpload && response.ok) { dropUpload = false; input.fail(Error("Owned adapter dropped committed upload")); return; }
                input.success({ statusCode: response.status, data });
            })().catch(input.fail);
        },
        downloadFile: input => {
            void (async () => {
                const response = await fetch(target(input.url), { headers: input.header, redirect: "error" });
                fs.writeFileSync(input.filePath, Buffer.from(await response.arrayBuffer()));
                input.success({ statusCode: response.status, filePath: input.filePath, tempFilePath: input.filePath });
            })().catch(input.fail);
        },
    };
    const service = { sessions: new MiniSessionClient(origin, createWxRuntime(wx)), createKey: randomUUID, source,
        chooseEvidence: async () => { fs.writeFileSync(source, png); return { path: source, name: "报废原件.png", size: png.length }; } };
    clients.set(role, service); return service;
}
async function page(role, id) {
    const service = services(role), value = createScrapPage(() => service);
    value.data = structuredClone(value.data); value.setData = update => Object.assign(value.data, update);
    value.onLoad({ id }); await value.onShow(); okay(value); return value;
}
function okay(value) { assert.equal(value.data.error, ""); assert.equal(value.data.retry, false); }
async function act(value, action) {
    value.onPrepare(event({ action })); assert.ok(value.data.confirmation, value.data.error);
    await value.onConfirm();
}
async function upload(value, role, kind) {
    await value.onPickAttachment(event({ kind }));
    assert.match(value.data.confirmation, /报废原件/);
    fs.writeFileSync(services(role).source, "original picker file modified after staging");
    await value.onConfirm();
    if (value.data.retry) {
        const frozen = uploads.at(-1);
        assert.equal(frozen.status, 200);
        value.onHide(); assert.equal(fs.existsSync(frozen.path), true);
        await value.onShow(); assert.match(value.data.pendingSummary, /报废原件/);
        await value.onRetry();
        assert.deepEqual(uploads.at(-1), frozen);
    }
    okay(value);
    assert.equal(fs.existsSync(uploads.at(-1).path), false);
    assert.equal(uploads.at(-1).digest, hash(png));
}
async function inspect(value, kind, close) {
    const files = kind === "request" ? value.data.requestFiles : value.data.dispositionFiles;
    assert.equal(files.length, 1);
    assert.equal(files[0].filename, "报废原件.png");
    await value.onReadAttachment(event({ kind, id: files[0].objectId })); okay(value);
    const original = value.data.previewPath;
    assert.equal(hash(fs.readFileSync(original)), hash(png));
    close(); assert.equal(value.data.previewPath, ""); assert.equal(fs.existsSync(original), false);
}
try {
    const employee = await page("employee");
    assert.equal(employee.data.canListOverdue, false);
    await employee.onAssets(); okay(employee);
    employee.onAsset(event({ id: "951020" }));
    for (const [name, value] of Object.entries({ reason: "已核对原机无法继续使用", condition: "同一原机实际损坏", proposed: "核对后待处置" })) employee.onField(field(name, value));
    await act(employee, "submit"); okay(employee);
    const id = employee.data.detail.id;
    await upload(employee, "employee", "request");
    assert.equal(uploads.length, 2);
    assert.equal(uploads[0].version, "0");
    assert.equal(employee.data.detail.version, 1);
    const admin = await page("admin", id);
    await inspect(admin, "request", () => admin.onCloseAttachment());
    admin.onField(field("decisionReason", "管理员已核对申请事实及处置安排"));
    admin.onField(field("approved", "核对后待处置"));
    await act(admin, "approve"); okay(admin);
    const warehouse = await page("warehouse", id);
    await act(warehouse, "claim"); okay(warehouse);
    await warehouse.onCatalog(event({ kind: "location" }));
    await warehouse.onCatalog(event({ kind: "employee" }));
    const place = warehouse.data.places.findIndex(item => item.id === "951001");
    const custodian = warehouse.data.people.findIndex(item => item.id === "951003");
    assert.ok(place >= 0 && custodian >= 0);
    warehouse.onChoice({ ...event({ kind: "location" }), detail: { value: String(place) } });
    warehouse.onChoice({ ...event({ kind: "employee" }), detail: { value: String(custodian) } });
    warehouse.onField(field("observed", "REP-ASSET-951020"));
    warehouse.onField(field("evidence", "现场亲见原机并核对同一身份及实际保管安排"));
    warehouse.onNow(); warehouse.onChecked({ detail: { value: true } });
    await act(warehouse, "execute");
    assert.equal(warehouse.data.retry, true);
    const original = structuredClone(writes[0]);
    await warehouse.onRefresh();
    warehouse.onHide(); await warehouse.onShow();
    assert.equal(warehouse.data.detail.status, "EXECUTED");
    assert.match(warehouse.data.pendingSummary, /核实|地点|保管/);
    await warehouse.onRetry(); okay(warehouse);
    assert.deepEqual(writes[1], original);
    assert.equal(writes.length, 2);
    assert.equal(warehouse.data.detail.disposition.disposition_status, "PENDING_DISPOSITION");
    const executedVersion = warehouse.data.detail.version;
    await upload(warehouse, "warehouse", "disposition");
    assert.equal(uploads.length, 3);
    assert.equal(uploads[2].version, "0");
    assert.equal(warehouse.data.capabilities.dispositionVersion, 1);
    assert.equal(warehouse.data.detail.version, executedVersion);
    await inspect(warehouse, "disposition", () => warehouse.onHide());
    await warehouse.onShow(); okay(warehouse);
    await warehouse.onCatalog(event({ kind: "employee" }));
    const operator = warehouse.data.people.findIndex(item => item.id === "951003");
    assert.ok(operator >= 0);
    warehouse.onChoice({ ...event({ kind: "employee" }), detail: { value: String(operator) } });
    warehouse.onField(field("observed", "REP-ASSET-951020"));
    warehouse.onField(field("recipient", "现场接收处置单位"));
    warehouse.onNow();
    warehouse.onPrepareDisposition(); assert.equal(warehouse.data.confirmation, "");
    warehouse.onDispositionEvidence({ detail: { value: [warehouse.data.dispositionFiles[0].objectId] } });
    warehouse.onPrepareDisposition(); assert.match(warehouse.data.confirmation, /现场接收处置单位/);
    await warehouse.onConfirm(); assert.equal(warehouse.data.retry, true);
    assert.equal(completions[0].status, 200); assert.equal(completions[0].version, "1");
    warehouse.onHide(); await warehouse.onShow();
    assert.equal(warehouse.data.detail.disposition.disposition_status, "DISPOSED");
    await warehouse.onRetry(); okay(warehouse);
    assert.deepEqual(completions, [completions[0], completions[0]]);
    assert.equal(warehouse.data.detail.version, executedVersion);
    assert.equal(warehouse.data.detail.disposition.version, 2);
    assert.equal(warehouse.data.detail.disposition.completion.recipient, "现场接收处置单位");
    assert.equal(warehouse.data.detail.actions.dispose, false);
    assert.equal(warehouse.data.canListOverdue, true);
    await warehouse.onToggleOverdue(); okay(warehouse);
    assert.equal(warehouse.data.overdueOnly, true);
    assert.match(warehouse.data.businessDate, /^\d{4}-\d{2}-\d{2}$/);
    assert.equal(warehouse.data.total, 0);
    await warehouse.onToggleOverdue(); okay(warehouse);
    assert.equal(warehouse.data.overdueOnly, false);
    assert.ok(warehouse.data.rows.some(row => row.id === id));
    for (const value of [employee, admin, warehouse]) value.onUnload();
    console.log(JSON.stringify({ nativeScrapExecuted: true, originalReceiptReplayed: true,
        separatePendingDisposition: true, nativeScrapAttachments: true, nativeDispositionClosed: true, requestId: id }));
} finally {
    fs.rmSync(root, { recursive: true, force: true });
}
