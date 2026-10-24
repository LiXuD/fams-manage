// Real Page handlers, opaque sessions, Spring/MySQL and original filesystem image bytes.
// wx callbacks, loopback HTTPS bridge, confirmation and the explicit CLEAN scanner are fixtures.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { createWxRuntime } from "../src/wx-runtime.ts";
import { MiniSessionClient } from "../src/session.ts";
import { createInventoryLateDamagePage } from "../src/inventory-late-damage-pages.ts";
import { createInventoryLatePage } from "../src/inventory-late-pages.ts";
import { createRepairPage } from "../src/repair-pages.ts";
import { createInventoryRepairPhotoPage } from "../src/inventory-repair-photo-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, task = process.env.FAMS_TEST_INVENTORY_TASK;
const item = process.env.FAMS_TEST_INVENTORY_ITEM, observation = process.env.FAMS_TEST_INVENTORY_OBSERVATION;
const existing = process.env.FAMS_TEST_REPAIR_EXISTING ?? "", grants = JSON.parse(process.env.FAMS_TEST_REPAIR_GRANTS);
assert.match(port ?? "", /^[0-9]{1,5}$/);
for (const value of [task, item, observation]) assert.match(value ?? "", /^[1-9][0-9]*$/);
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "fams-inventory-late-damage-native-"));
const origin = "https://inventory-repair-test.invalid", writes = [], downloads = [];
let dropped = false;
const event = dataset => ({ currentTarget: { dataset } }), field = (name, value) => ({ ...event({ field: name }), detail: { value } });
const bind = page => { page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); return page; };
function services(role) {
    const store = { "fams.session": grants[role] }, root = path.join(directory, role); fs.mkdirSync(root);
    const target = url => { assert.ok(url.startsWith(origin + "/api/v1/mini/")); return `http://127.0.0.1:${port}` + url.slice(origin.length); };
    const wx = {
        env: { USER_DATA_PATH: root },
        getFileSystemManager: () => ({ mkdirSync: (name, recursive) => fs.mkdirSync(name, { recursive }), copyFileSync: fs.copyFileSync,
            statSync: fs.statSync, unlinkSync: fs.unlinkSync, readdirSync: fs.readdirSync, accessSync: fs.accessSync }),
        getStorageSync: key => store[key], setStorageSync: (key, value) => { store[key] = structuredClone(value); }, removeStorageSync: key => { delete store[key]; },
        request(input) { void (async () => {
            try {
                const response = await fetch(target(input.url), { method: input.method, headers: input.header,
                    body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error" });
                const data = await response.json();
                if (input.method === "POST") {
                    writes.push(structuredClone({ role, path: input.url.slice(origin.length), body: input.data, key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] }));
                    if (response.status === 200 && input.url.includes("/damage-repair") && !dropped) { dropped = true; throw Error("Actually committed inventory repair reply deliberately lost"); }
                }
                input.success({ statusCode: response.status, data });
            } catch (error) { input.fail(error); }
        })(); },
        downloadFile(input) { void (async () => {
            try {
                assert.ok(input.filePath.startsWith(root + "/fams-inventory-evidence-"));
                const response = await fetch(target(input.url), { headers: input.header, redirect: "error" });
                fs.writeFileSync(input.filePath, Buffer.from(await response.arrayBuffer()));
                downloads.push({ status: response.status, filePath: input.filePath });
                input.success({ statusCode: response.status, filePath: input.filePath, tempFilePath: "" });
            } catch (error) { input.fail(error); }
        })(); }
    };
    const sessions = new MiniSessionClient(origin, createWxRuntime(wx));
    return { sessions, store, createKey: randomUUID, confirm: async () => true, navigate() {} };
}
try {
    const adminServices = services("admin"), warehouseServices = services("warehouse");
    const navigations = []; adminServices.navigate = url => navigations.push(url);
    const late = bind(createInventoryLatePage(() => adminServices)); late.onLoad({ taskId: task }); await late.onShow();
    await late.onOpen(event({ id: observation })); await late.onDamage();
    assert.deepEqual(navigations, [`/pages/inventory-late-damage/index?taskId=${task}&observationId=${observation}`]); late.onUnload();
    const review = bind(createInventoryLateDamagePage(() => adminServices));
    review.onLoad({ taskId: task, observationId: observation }); await review.onShow();
    assert.equal(review.data.error, ""); assert.equal(review.data.discoveryDate, "");
    review.onMethod({ detail: { value: existing ? "1" : "0" } });
    for (const [name,value] of Object.entries({ reason: "核实迟到实物损坏后走正式维修", evidence: "已独立核对原图实物及发现时间，不采用异常设备时间", discoveryDate: "2026-01-01", discoveryTime: "09:10", discoverySecond: "11.123", fault: "迟到盘点线索中的真实实物故障" })) review.onField(field(name,value));
    review.onChecked({ detail: { value: ["physical", "time"] } });
    if (existing) { review.onField(field("repairId", existing)); await review.onInspect(); assert.equal(review.data.linked.id, existing); }
    await review.onDecide(); assert.equal(review.data.retry, true, review.data.error);
    review.onHide(); await review.onShow(); await review.onRetry(); assert.equal(review.data.error, ""); assert.equal(review.data.retry, false);
    assert.equal(review.data.detail.damage.damage_status, "PROCESSING"); const id = review.data.detail.damage.repair_id;
    const creation = writes.filter(row => row.path.includes("/damage-repair")); assert.equal(creation.length, 2); assert.deepEqual(creation[0], creation[1]);
    const admin = bind(createRepairPage(() => adminServices)); admin.onLoad({ id }); await admin.onShow();
    assert.equal(admin.data.detail.status, "PENDING_ADMIN_DECISION"); admin.onField(field("reason", "已核实原始盘点损坏及内部零费用范围"));
    await admin.onAction(event({ action: "approve" })); assert.equal(admin.data.detail.status, "WAITING_WAREHOUSE_CLAIM"); admin.onUnload();
    const evidence = bind(createInventoryRepairPhotoPage(() => warehouseServices)); evidence.onLoad({ repairId: id, observationId: observation }); await evidence.onShow();
    assert.deepEqual(evidence.data.photos, []); assert.match(evidence.data.error, /已隐藏/);
    const warehouse = bind(createRepairPage(() => warehouseServices)); warehouse.onLoad({ id }); await warehouse.onShow();
    await warehouse.onAction(event({ action: "claim" })); assert.equal(warehouse.data.detail.status, "WAREHOUSE_PROCESSING");
    await evidence.onRefresh(); assert.equal(evidence.data.error, ""); assert.equal(evidence.data.photos.length, 1);
    const object = evidence.data.photos[0].objectId; await evidence.onRead(event({ id: object })); assert.equal(evidence.data.error, ""); assert.ok(evidence.data.photoPath);
    const image = evidence.data.photoPath;
    assert.equal(createHash("sha256").update(fs.readFileSync(image)).digest("hex"), process.env.FAMS_TEST_PHOTO_SHA256);
    evidence.onHide(); assert.equal(fs.existsSync(image), false); assert.deepEqual(evidence.data.photos, []); evidence.onUnload();
    for (const action of ["accept", "complete"]) {
        warehouse.onField(field("observedCode", "SEAL-10")); warehouse.onField(field("evidence", "核对原始盘点照片及现场实物后实际办理维修"));
        warehouse.onPhysical({ detail: { value: ["location", "neverLeft"] } }); await warehouse.onAction(event({ action }));
        assert.equal(warehouse.data.error, "");
    }
    assert.equal(warehouse.data.detail.status, "COMPLETED"); warehouse.onUnload();
    await review.onRefresh(); assert.equal(review.data.methodIndex, 2);
    review.onField(field("reason", "核对正式维修实际完成")); review.onField(field("evidence", "维修已实际受理修复完成，原补充复核独立继续")); await review.onDecide();
    assert.equal(review.data.error, ""); assert.equal(review.data.detail.damage.damage_status, "CLOSED");
    assert.equal(review.data.detail.recheckResult, "OPEN"); assert.equal(review.data.detail.todoOpen, true); review.onUnload();
    assert.equal(downloads.length, 1); assert.equal(downloads[0].status, 200); assert.equal(fs.existsSync(downloads[0].filePath), false);
    assert.equal(JSON.stringify(warehouseServices.store).includes("grant="), false);
    console.log(JSON.stringify({ nativeActualLateDamageClosed: true, mode: existing ? "LINK_REPAIR" : "FORMAL_REPAIR", originalReplyReplayed: true,
        originalPhotoBytes: true, unclaimedWarehouseDenied: true, localPhotoRemoved: true, independentSupplementStillOpen: true, repairId: id }));
} finally { fs.rmSync(directory, { recursive: true, force: true }); }
