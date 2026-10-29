// Actual Page/opaque client/owned Spring/MySQL; SDK, HTTPS hostname and scanner are explicit adapters.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID, createHash } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";

const port = process.env.FAMS_TEST_API_PORT, grants = JSON.parse(process.env.FAMS_TEST_LOSS_GRANTS);
assert.match(port ?? "", /^[0-9]{1,5}$/);
const root = fs.mkdtempSync(path.join(os.tmpdir(), "fams-native-loss-")), origin = "https://loss-test.invalid";
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+afo0AAAAASUVORK5CYII=", "base64");
const hash = value => createHash("sha256").update(value).digest("hex"), uploads = [], writes = [], clients = new Map();
const event = dataset => ({ currentTarget: { dataset } }), field = (name, value) => ({ ...event({ field: name }), detail: { value } });
let dropUpload = true, dropWrite = true, saved;
const recoveryRun = process.env.FAMS_TEST_LOSS_RECOVERY === "true";
const confirmationRun = process.env.FAMS_TEST_LOSS_CONFIRMATION === "true", progressWrites = [];
const dropped = new Set();
const target = url => { assert.ok(url.startsWith(origin + "/api/v1/mini/")); return "http://127.0.0.1:" + port + url.slice(origin.length); };
function service(role) {
    if (clients.has(role)) return clients.get(role);
    const directory = path.join(root, role); fs.mkdirSync(directory); const source = path.join(directory, "chosen.png"), store = new Map([["fams.session", grants[role]]]);
    const io = { mkdirSync: (p, recursive) => fs.mkdirSync(p, { recursive }), copyFileSync: fs.copyFileSync, statSync: fs.statSync, unlinkSync: fs.unlinkSync, readdirSync: fs.readdirSync, accessSync: fs.accessSync };
    const wx = { env: { USER_DATA_PATH: directory }, getFileSystemManager: () => io, getStorageSync: key => store.get(key), setStorageSync: (key, value) => store.set(key, structuredClone(value)), removeStorageSync: key => store.delete(key),
        request: input => { void (async () => {
            const response = await fetch(target(input.url), { method: input.method, headers: { "Content-Type": "application/json", ...input.header }, body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error" });
            const data = await response.json();
            if (input.method === "POST" && input.url.endsWith("/loss-reports")) {
                writes.push({ path: input.url, body: structuredClone(input.data), key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"] });
                if (response.status === 200) { saved = data; if (dropWrite) { dropWrite = false; input.fail(new Error("Owned adapter dropped committed loss")); return; } }
            }
            if (input.method === "POST" && (confirmationRun && /\/(confirm-lost|follow-up)$/.test(input.url) || recoveryRun && /\/(recovery-requests|recover-original|recoveries\/[^/]+\/(decision|claim|execute|cancel))$/.test(input.url))) {
                progressWrites.push({path:input.url,body:structuredClone(input.data),key:input.header["Idempotency-Key"],version:input.header["If-Match-Version"]});
                if (response.status === 200 && !dropped.has(input.url)) { dropped.add(input.url);input.fail(new Error("Owned adapter dropped committed loss progress reply"));return; }
            }
            input.success({ statusCode: response.status, data });
        })().catch(input.fail); },
        uploadFile: input => { void (async () => {
            const bytes = fs.readFileSync(input.filePath), form = new FormData(); for (const [key, value] of Object.entries(input.formData ?? {})) form.append(key, value);
            form.append(input.name, new Blob([bytes], { type: "image/png" }), path.basename(input.filePath));
            const response = await fetch(target(input.url), { method: "POST", headers: input.header, body: form, redirect: "error" }), data = await response.text();
            uploads.push({ key: input.header["Idempotency-Key"], version: input.header["If-Match-Version"], form: structuredClone(input.formData ?? {}), sha: hash(bytes), path: input.filePath });
            if (dropUpload && response.status === 200) { dropUpload = false; input.fail(new Error("Owned adapter dropped committed loss original")); return; }
            input.success({ statusCode: response.status, data });
        })().catch(input.fail); },
        downloadFile: input => { void (async () => { const response = await fetch(target(input.url), { headers: input.header, redirect: "error" }); fs.writeFileSync(input.filePath, Buffer.from(await response.arrayBuffer())); input.success({ statusCode: response.status, filePath: input.filePath, tempFilePath: input.filePath }); })().catch(input.fail); },
    };
    const result = { sessions: new MiniSessionClient(origin, createWxRuntime(wx)), createKey: randomUUID, navigate() {}, source,
        chooseInventoryPhoto: async () => { fs.writeFileSync(source, png); return { path: source, name: "报失情况原件.png", size: png.length }; } };
    clients.set(role, result); return result;
}
async function page(role,id) {
    let result; globalThis.getApp = () => ({ getServices: () => service(role) }); globalThis.Page = value => { result=value; };
    await import("../src/pages/loss-reports/index.ts?role="+role+"&instance="+randomUUID()); assert.ok(result,"Actual native Page registration");
    result.data=structuredClone(result.data);result.setData=update=>Object.assign(result.data,update);result.onLoad({id});await result.onShow();return result;
}
const okay=page=>{assert.equal(page.data.error,"");assert.equal(page.data.retry,false);};
const prepare=async(page,action)=>{page.onPrepare(event({action}));assert.ok(page.data.confirmation,page.data.error);await page.onConfirm();};
try {
    const employee=await page("employee");okay(employee);assert.ok(employee.data.assets.some(item=>item.id==="951020"));employee.onAsset(event({id:"951020"}));employee.onNow(event({kind:"last"}));
    employee.onField(field("lastLocation","原本人办公室"));employee.onField(field("circumstances","本人已经查找办公室与保管地点，暂未找到原资产。"));await prepare(employee,"submit");assert.equal(employee.data.retry,true);
    const first=structuredClone(writes[0]);await employee.onRefresh();employee.onHide();await employee.onShow();await employee.onRetry();okay(employee);assert.deepEqual(writes[1],first);const id=employee.data.detail.id;assert.equal(employee.data.detail.status,"PENDING_ADMIN_DECISION");
    await employee.onUpload();assert.ok(employee.data.confirmation,employee.data.error);fs.writeFileSync(service("employee").source,"source changed after immutable staging");await employee.onConfirm();assert.equal(employee.data.retry,true);
    const upload=structuredClone(uploads[0]);employee.onHide();assert.equal(fs.existsSync(upload.path),true);await employee.onShow();await employee.onRetry();okay(employee);assert.deepEqual(uploads[1],upload);assert.equal(upload.sha,hash(png));assert.equal(fs.existsSync(upload.path),false);
    const original=employee.data.detail.photos[0];assert.equal(original.filename,path.basename(upload.path));await employee.onPhoto(event({id:original.objectId}));okay(employee);const preview=employee.data.photoPath;assert.equal(hash(fs.readFileSync(preview)),hash(png));employee.onHide();assert.equal(employee.data.detail,null);assert.equal(fs.existsSync(preview),false);employee.onUnload();
    const admin=await page("admin",id);okay(admin);admin.onField(field("reason","已核对报告与本次情况原件，批准开展核实查找。"));await prepare(admin,"approve");okay(admin);assert.equal(admin.data.detail.status,"WAITING_WAREHOUSE_CLAIM");admin.onUnload();
    const warehouse=await page("warehouse",id);okay(warehouse);await prepare(warehouse,"claim");okay(warehouse);assert.equal(warehouse.data.detail.status,"VERIFYING");
    warehouse.onField(field("custodianVerification","已经当面核实最后保管人及原办公室，未找到资产。"));warehouse.onField(field("searchedScope","已经查找原办公室、登记保管区及同事交接位置。"));warehouse.onNow(event({kind:"search"}));warehouse.onField(field("nextReviewDate",new Date(Date.now()+7*86400000).toISOString().slice(0,10)));await prepare(warehouse,"search");okay(warehouse);assert.equal(warehouse.data.detail.status,"SEARCHING");assert.equal(warehouse.data.detail.photos.length,1);assert.equal(warehouse.data.detail.history.length,5);
    if (confirmationRun) {
        for(const[name,value]of Object.entries({custodianVerification:"已经与最后保管人当面核实原责任事实并保留记录。",searchedScope:"已经逐一查找办公室和全部登记保管位置仍无实物。",relatedClues:"已经核对交接维修及盘点线索，尚无原物去向。",inabilityReason:"现场查找和各项线索均无原物，凭核实证据判断无法找回。",responsibilityEvidence:"原责任登记及最后保管人核实记录共同作为遗失责任证据。"}))warehouse.onField(field(name,value));
        warehouse.onNow(event({kind:"search"}));warehouse.onNow(event({kind:"confirm"}));await prepare(warehouse,"confirmLost");assert.equal(warehouse.data.retry,true);
        const original=structuredClone(progressWrites[0]);await warehouse.onRefresh();warehouse.onHide();await warehouse.onShow();assert.match(warehouse.data.pendingLabel,/确认遗失/);await warehouse.onRetry();okay(warehouse);assert.deepEqual(progressWrites[1],original);assert.equal(warehouse.data.detail.status,"CONFIRMED_LOST");
        warehouse.onField(field("findings","本轮重新核实原保管人和所有登记位置仍然没有发现原物。"));warehouse.onField(field("reason","继续保留原责任并在下个周期复核。"));warehouse.onNow(event({kind:"review"}));await prepare(warehouse,"followUp");assert.equal(warehouse.data.retry,true);const follow=structuredClone(progressWrites[2]);warehouse.onHide();await warehouse.onShow();await warehouse.onRetry();okay(warehouse);assert.deepEqual(progressWrites[3],follow);assert.equal(warehouse.data.detail.history.length,7);assert.equal(warehouse.data.detail.status,"CONFIRMED_LOST");
        await warehouse.onMode(event({mode:"followups"}));okay(warehouse);assert.equal(warehouse.data.total,1);assert.equal(warehouse.data.rows[0].source.responsibleEmployeeId,"951002");
    }
    if (recoveryRun) {
        await warehouse.onMode(event({mode:"all"}));await warehouse.onOpen(event({id}));okay(warehouse);
        const recoveryPrepare=async(page,action,recovery)=>{const count=progressWrites.length;page.onRecoveryPrepare(event({action,id:recovery}));assert.ok(page.data.confirmation,page.data.error);await page.onConfirm();assert.equal(page.data.retry,true,page.data.error);const pair=progressWrites.slice(-1)[0];await page.onRefresh();page.onHide();await page.onShow();await page.onRetry();okay(page);assert.equal(progressWrites.length,count+2);assert.deepEqual(progressWrites.at(-1),pair);assert.equal(page.data.detail.id,id);};
        const proof=page=>{for(const[name,value]of Object.entries({observedAssetCode:page.data.detail.recoveryAsset.assetCode,observedSerialNumber:page.data.detail.recoveryAsset.serialNumber||"",recoveryEvidence:"当面核对原机编码并亲见完好原物仍在原登记位置由原人保管。"}))page.onField(field(name,value));page.onNow(event({kind:"found"}));page.onRecoveryChecked({detail:{value:true}});};
        proof(warehouse);await recoveryPrepare(warehouse,"requestRecovery");const rid=warehouse.data.detail.recoveries[0].id;
        const adminRecovery=await page("admin",id);okay(adminRecovery);assert.equal(adminRecovery.data.detail.recoveries[0].request.facts.observedAssetCode,"REP-ASSET-951020");assert.match(adminRecovery.data.detail.recoveries[0].request.facts.evidence,/亲见完好原物/);adminRecovery.onField(field("reason","已核对本次找回申请与原遗失事实，重新批准核实。"));await recoveryPrepare(adminRecovery,"approve",rid);adminRecovery.onUnload();
        warehouse.onUnload();const executor=await page("warehouse",id);okay(executor);await recoveryPrepare(executor,"claim",rid);proof(executor);await recoveryPrepare(executor,"execute",rid);assert.equal(executor.data.detail.recoveries[0].status,"COMPLETED");assert.ok(executor.data.detail.recoveredAt);assert.equal(executor.data.detail.status,"CONFIRMED_LOST");assert.equal(executor.data.detail.history.length,11);
        await executor.onMode(event({mode:"followups"}));okay(executor);assert.equal(executor.data.total,0);executor.onUnload();
    }
    warehouse.onUnload();
    const other=await page("other",id);assert.equal(other.data.detail,null);assert.ok(other.data.error);assert.equal(JSON.stringify(other.data).includes("报失情况原件"),false);other.onUnload();
    for(const client of clients.values())client.sessions.clear();
    console.log(JSON.stringify({nativeActualLossSearching:true,nativeActualLossConfirmed:confirmationRun,nativeActualLossRecovered:recoveryRun,submitOriginalRetry:true,imageOriginalRetry:true,opaqueOriginalBytes:true,foreignIdentityDenied:true,reportId:id,sdk:"adapter",https:"adapter",scanner:"fixture"}));
} finally { delete globalThis.Page;delete globalThis.getApp;fs.rmSync(root,{recursive:true,force:true}); }
