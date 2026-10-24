// Real Page handlers, opaque sessions, Spring/MySQL and original filesystem image bytes.
// wx callbacks, loopback HTTPS bridge, confirmation and the explicit CLEAN scanner are fixtures.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { createWxRuntime } from "../src/wx-runtime.ts";
import { MiniSessionClient } from "../src/session.ts";
import { createInventoryReviewPage } from "../src/inventory-review-pages.ts";
import { createRepairPage } from "../src/repair-pages.ts";
import { createInventoryRepairPhotoPage } from "../src/inventory-repair-photo-pages.ts";
const port = process.env.FAMS_TEST_API_PORT, task = process.env.FAMS_TEST_INVENTORY_TASK;
const item = process.env.FAMS_TEST_INVENTORY_ITEM, observation = process.env.FAMS_TEST_INVENTORY_OBSERVATION;
const existing = process.env.FAMS_TEST_REPAIR_EXISTING ?? "", grants = JSON.parse(process.env.FAMS_TEST_REPAIR_GRANTS);
assert.match(port ?? "", /^[0-9]{1,5}$/);
for (const value of [task, item, observation]) assert.match(value ?? "", /^[1-9][0-9]*$/);
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "fams-inventory-repair-native-"));
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
                    if (response.status === 200 && input.url.endsWith("/differences/decisions") && !dropped) { dropped = true; throw Error("Actually committed inventory repair reply deliberately lost"); }
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
    const review = bind(createInventoryReviewPage(() => adminServices));
    review.onLoad({ taskId: task }); await review.onShow(); await review.onOpenItem(event({ id: item })); await review.onOpenObservation(event({ id: observation }));
    assert.equal(review.data.error, ""); assert.equal(review.data.damageDate, "");
    await review.onPhysicalMethod({ detail: { value: existing ? "5" : "4" } }); review.onDimensions({ detail: { value: ["CONDITION"] } });
    for (const [name,value] of Object.entries({ reason: "核实盘点损坏后走正式维修", evidence: "已独立核对原图实物及发现时间，不采信异常设备时间", damageDate: "2026-01-01", damageTime: "09:10", damageSecond: "11.123", damageFault: "盘点发现真实实物故障" })) review.onField(field(name,value));
    review.onDamageChecked({ detail: { value: ["physical", "time"] } });
    if (existing) { review.onField(field("damageRepairId", existing)); await review.onInspectDamageRepair(); assert.equal(review.data.damageLinked.id, existing); }
    await review.onPhysicalDecision(); assert.equal(review.data.retryable, true, review.data.error);
    review.onHide(); await review.onShow(); await review.onRetry(); assert.equal(review.data.error, ""); assert.equal(review.data.retryable, false);
    const condition = review.data.subjects.find(row => row.dimension === "CONDITION"); assert.equal(condition.subject_status, "PROCESSING");
    const id = condition.repairLinks.at(-1).repair_id;
    const creation = writes.filter(row => row.path.endsWith("/differences/decisions")); assert.equal(creation.length, 2); assert.deepEqual(creation[0], creation[1]);
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
        warehouse.onField(field("observedCode", "INV-ASSET-943010")); warehouse.onField(field("evidence", "核对原始盘点照片及现场实物后实际办理维修"));
        warehouse.onPhysical({ detail: { value: ["location", "neverLeft"] } }); await warehouse.onAction(event({ action }));
        assert.equal(warehouse.data.error, "");
    }
    assert.equal(warehouse.data.detail.status, "COMPLETED"); warehouse.onUnload();
    await review.onRefresh(); review.onDimensions({ detail: { value: ["CONDITION"] } }); await review.onPhysicalMethod({ detail: { value: "6" } });
    review.onField(field("reason", "核对正式维修实际完成")); review.onField(field("evidence", "维修已实际受理修复并完成，其他维度继续单独核实")); await review.onPhysicalDecision();
    assert.equal(review.data.error, ""); assert.equal(review.data.subjects.find(row => row.dimension === "CONDITION").subject_status, "CLOSED");
    assert.equal(review.data.subjects.find(row => row.dimension === "TIME").subject_status, "OPEN"); review.onUnload();
    assert.equal(downloads.length, 1); assert.equal(downloads[0].status, 200); assert.equal(fs.existsSync(downloads[0].filePath), false);
    assert.equal(JSON.stringify(warehouseServices.store).includes("grant="), false);
    console.log(JSON.stringify({ nativeActualInventoryRepairClosed: true, mode: existing ? "LINK_REPAIR" : "FORMAL_REPAIR", originalReplyReplayed: true,
        originalPhotoBytes: true, unclaimedWarehouseDenied: true, localPhotoRemoved: true, independentTimeOpen: true, repairId: id }));
} finally { fs.rmSync(directory, { recursive: true, force: true }); }
