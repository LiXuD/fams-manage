// Actual Page/opaque client/owned Spring/MySQL; SDK, HTTPS hostname and scanner are explicit adapters.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID, createHash } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";
import { createRepairReplacementPage } from "../src/repair-replacement-pages.ts";

const port = process.env.FAMS_TEST_API_PORT, grants = JSON.parse(process.env.FAMS_TEST_REPAIR_GRANTS);
const id = process.env.FAMS_TEST_REPLACEMENT_REPAIR, round = process.env.FAMS_TEST_REPLACEMENT_ROUND;
assert.match(port ?? "", /^[0-9]{1,5}$/); assert.match(id ?? "", /^[1-9][0-9]*$/); assert.match(round ?? "", /^[1-9][0-9]*$/);
const root = fs.mkdtempSync(path.join(os.tmpdir(), "fams-native-replacement-")), origin = "https://replacement-test.invalid";
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+afo0AAAAASUVORK5CYII=", "base64");
const hash = value => createHash("sha256").update(value).digest("hex"), uploads = [], writes = [], clients = new Map();
const event = dataset => ({ currentTarget: { dataset } }), field = (name, value) => ({ ...event({ field: name }), detail: { value } });
let dropUpload = true, dropWrite = true, saved;
const target = url => { assert.ok(url.startsWith(origin + "/api/v1/mini/")); return "http://127.0.0.1:" + port + url.slice(origin.length); };
function service(role) {
    if (clients.has(role)) return clients.get(role);
    const directory = path.join(root, role); fs.mkdirSync(directory); const source = path.join(directory, "chosen.png"), store = new Map([["fams.session", grants[role]]]);
    const io = { mkdirSync: (p, recursive) => fs.mkdirSync(p, { recursive }), copyFileSync: fs.copyFileSync, statSync: fs.statSync, unlinkSync: fs.unlinkSync, readdirSync: fs.readdirSync, accessSync: fs.accessSync };
    const wx = { env: { USER_DATA_PATH: directory }, getFileSystemManager: () => io, getStorageSync: key => store.get(key), setStorageSync: (key, value) => store.set(key, structuredClone(value)), removeStorageSync: key => store.delete(key),
        request: input => { void (async () => {
            const response = await fetch(target(input.url), { method: input.method, headers: { "Content-Type": "application/json", ...input.header }, body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error" });
            const data = await response.json();
            if (input.method === "POST" && input.url.endsWith("/replacements")) {
                writes.push({ path: input.url, body: structuredClone(input.data), key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] });
                if (response.status === 200) { saved = data; if (dropWrite) { dropWrite = false; input.fail(new Error("Owned adapter dropped committed replacement")); return; } }
            }
            input.success({ statusCode: response.status, data });
        })().catch(input.fail); },
        uploadFile: input => { void (async () => {
            const bytes = fs.readFileSync(input.filePath), form = new FormData(); for (const [key, value] of Object.entries(input.formData)) form.append(key, value);
            form.append(input.name, new Blob([bytes], { type: "application/octet-stream" }), "opaque-native-original");
            const response = await fetch(target(input.url), { method: "POST", headers: input.header, body: form, redirect: "error" }), data = await response.text();
            uploads.push({ key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"], form: structuredClone(input.formData), sha: hash(bytes), path: input.filePath });
            if (dropUpload && response.status === 200) { dropUpload = false; input.fail(new Error("Owned adapter dropped committed replacement original")); return; }
            input.success({ statusCode: response.status, data });
        })().catch(input.fail); },
        downloadFile: input => { void (async () => { const response = await fetch(target(input.url), { headers: input.header, redirect: "error" }); fs.writeFileSync(input.filePath, Buffer.from(await response.arrayBuffer())); input.success({ statusCode: response.status, filePath: input.filePath, tempFilePath: input.filePath }); })().catch(input.fail); },
    };
    const result = { sessions: new MiniSessionClient(origin, createWxRuntime(wx)), createKey: randomUUID, navigate() {}, source,
        chooseEvidence: async () => { fs.writeFileSync(source, png); return { path: source, name: "厂家整机换新证明.png", size: png.length }; } };
    clients.set(role, result); return result;
}
async function page(role) { const result = createRepairReplacementPage(() => service(role)); result.data = structuredClone(result.data); result.setData = update => Object.assign(result.data, update); result.onLoad({ id, round }); await result.onShow(); return result; }
const okay = page => { assert.equal(page.data.error, ""); assert.equal(page.data.retry, false); };
try {
    const warehouse = await page("warehouse"); okay(warehouse); assert.equal(warehouse.data.records.canRecord, true); assert.equal(warehouse.data.references.length, 4);
    await warehouse.onChoose(); warehouse.onPrepare(event({ action: "upload" })); assert.ok(warehouse.data.confirmation, warehouse.data.error); fs.writeFileSync(service("warehouse").source, "selection changed after immutable staging"); await warehouse.onConfirm(); assert.equal(warehouse.data.retry, true);
    const upload = structuredClone(uploads[0]); warehouse.onHide(); assert.equal(fs.existsSync(upload.path), true); await warehouse.onShow(); await warehouse.onRetry(); okay(warehouse); assert.deepEqual(uploads[1], upload); assert.equal(upload.sha, hash(png)); assert.equal(upload.form.purpose, "REPLACEMENT"); assert.equal(fs.existsSync(upload.path), false);
    const object = warehouse.data.originals[0].id, file = warehouse.data.round.files.find(item => item.objectId === object), session = service("warehouse").sessions;
    assert.equal(file.filename, "厂家整机换新证明.png"); assert.equal(file.contentType, "image/png"); const evidence = "/api/v1/mini/repairs/" + id + "/factory/rounds/" + round + "/evidence/" + object;
    const grant = await session.request("GET", evidence + "/grant"), downloaded = await session.downloadFactoryEvidence(evidence + "/content?token=" + encodeURIComponent(grant.token), file.contentType, file.sizeBytes); assert.equal(hash(fs.readFileSync(downloaded)), hash(png)); session.discardFactoryEvidence(downloaded);
    const serial = "NATIVE-" + randomUUID();
    for (const [name, value] of Object.entries({ name: "私有原生独立新机", serial, model: "Native exchange model", unit: "台", businessDate: new Date().toISOString().slice(0, 10), reason: "亲自核实厂家换机原件和新实物新序列号，旧机留在厂家且责任仍待办理。" })) warehouse.onField(field(name, value));
    for (const kind of ["category", "organization", "warehouse", "location"]) { assert.ok(warehouse.data.references.find(item => item.kind === kind).items.some(item => item.id === "951000")); warehouse.onReference(event({ kind, id: "951000" })); }
    warehouse.onNow(); warehouse.onEvidence({ detail: { value: [object] } }); warehouse.onChecked({ detail: { value: ["checked"] } }); warehouse.onDisposition(event({ id: "FACTORY_RETAINED" })); warehouse.onPrepare(event({ action: "create" })); assert.ok(warehouse.data.confirmation, warehouse.data.error); await warehouse.onConfirm(); assert.equal(warehouse.data.retry, true);
    const first = structuredClone(writes[0]); await warehouse.onRefresh(); warehouse.onHide(); await warehouse.onShow(); await warehouse.onRetry(); okay(warehouse); assert.deepEqual(writes[1], first); assert.equal(warehouse.data.records.items.length, 1); assert.equal(warehouse.data.records.items[0].inbound.status, "DRAFT"); assert.equal(warehouse.data.records.canRecord, false); warehouse.onUnload();
    const admin = await page("admin"); okay(admin); assert.equal(admin.data.records.items[0].replacementAssetId, null); assert.equal(admin.data.records.items[0].inbound, null); assert.equal(admin.data.records.canRecord, false); admin.onPrepare(event({ action: "create" })); assert.equal(admin.data.confirmation, ""); admin.onUnload();
    const history = await service("admin").sessions.request("GET", "/api/v1/mini/repairs/" + id + "/factory/history?page=1&pageSize=20"); assert.ok(!JSON.stringify(history).includes(serial)); assert.ok(!JSON.stringify(history).includes("私有原生独立新机"));
    const employee = await page("employee"); assert.equal(employee.data.round, null); assert.equal(employee.data.records, null); assert.ok(employee.data.error); employee.onUnload(); const publicRepair = await service("employee").sessions.request("GET", "/api/v1/mini/repairs/" + id); assert.ok(!JSON.stringify(publicRepair).includes(serial)); assert.ok(!JSON.stringify(publicRepair).includes("厂家整机换新证明.png"));
    for (const client of clients.values()) client.sessions.clear();
    console.log(JSON.stringify({ nativeReplacementDraft: true, originalUploadReplay: true, replacementOriginalReplay: true, opaqueOriginalBytes: true, employeePrivacy: true, foreignDraftPrivacy: true, replacementAssetId: saved.replacementAssetId, inboundReceiptId: saved.inboundReceiptId }));
} finally { fs.rmSync(root, { recursive: true, force: true }); }
