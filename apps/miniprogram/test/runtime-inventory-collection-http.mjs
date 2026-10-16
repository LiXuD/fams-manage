// Same native handlers and durable adapter; real HTTP/MySQL, explicit local bridge and WeChat substitute.
import assert from "node:assert/strict";
import * as fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { InventoryQueue } from "../src/inventory-queue.ts";
import { createInventoryFiles } from "../src/inventory-files.ts";
import { createInventoryCollectionPage } from "../src/inventory-collection-pages.ts";
const port = process.env.FAMS_TEST_API_PORT; const taskId = process.env.FAMS_TEST_INVENTORY_TASK;
const directory = process.env.FAMS_TEST_INVENTORY_STATE; const stage = process.env.FAMS_TEST_INVENTORY_STAGE;
assert.match(port || "", /^[0-9]{1,5}$/); assert.match(taskId || "", /^[1-9][0-9]*$/);
assert.ok(directory && path.basename(directory).startsWith("fams-inventory-collection-"));
assert.ok(["collect", "lost", "retry"].includes(stage));
fs.mkdirSync(directory, { recursive: true });
const diskPath = key => path.join(directory, `${key}.json`);
const read = key => fs.existsSync(diskPath(key)) ? JSON.parse(fs.readFileSync(diskPath(key), "utf8")) : null;
const write = (key, value) => { const target = diskPath(key); fs.writeFileSync(target + ".new", JSON.stringify(value)); fs.renameSync(target + ".new", target); };
const wx = { env: { USER_DATA_PATH: directory }, getStorageSync: read, setStorageSync: write,
    removeStorageSync: key => fs.rmSync(diskPath(key), { force: true }), getFileSystemManager: () => ({
        mkdirSync: (name, recursive) => fs.mkdirSync(name, { recursive }), copyFileSync: fs.copyFileSync,
        statSync: fs.statSync, unlinkSync: fs.unlinkSync, readdirSync: fs.readdirSync, accessSync: fs.accessSync }) };
const origin = "https://inventory-test.invalid"; let dropped = false;
const sessions = new MiniSessionClient(origin, {
    readSession: () => read("session"), writeSession: value => write("session", value), removeSession: () => wx.removeStorageSync("session"),
    clearIdentityData: identity => createInventoryFiles(wx, identity).clear(),
    async download(input) {
        assert.ok(input.url.startsWith(origin+"/api/v1/mini/inventory-observations/"));
        const response=await fetch(`http://127.0.0.1:${port}`+input.url.slice(origin.length),{headers:input.header,redirect:"error"});
        const file=path.join(directory,"downloaded-evidence.png");
        if(response.status===200)fs.writeFileSync(file,Buffer.from(await response.arrayBuffer()));
        return {statusCode:response.status,tempFilePath:file};
    },
    async upload(input) {
        assert.ok(input.url.startsWith(origin + "/api/v1/mini/inventory-observations/"));
        const form = new FormData(); form.set("file",new Blob([fs.readFileSync(input.filePath)],{type:"image/png"}),path.basename(input.filePath));
        const response=await fetch(`http://127.0.0.1:${port}`+input.url.slice(origin.length),{method:"POST",headers:input.header,body:form,redirect:"error"});
        return {statusCode:response.status,data:await response.json()};
    },
    async request(input) {
        assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
        const event = input.method === "POST" && /\/events$/.test(input.url);
        if (event) write(`payload-${stage}`, input.data);
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice(origin.length), {
            method: input.method, headers: { "Content-Type": "application/json", ...input.header },
            body: input.data ? JSON.stringify(input.data) : undefined, redirect: "error" });
        const data = response.status === 204 ? null : await response.json();
        if (event && stage === "lost" && !dropped) {
            assert.equal(response.status, 200, JSON.stringify(data)); dropped = true;
            throw new Error("Actual successful HTTP reply deliberately lost");
        }
        return { statusCode: response.status, data };
    },
});
if (!sessions.identity) assert.equal(await sessions.login(process.env.FAMS_TEST_INVENTORY_CODE), "BOUND");
const device = "11111111-1111-4111-8111-111111111111";
const services = { sessions, createKey: randomUUID, navigate() {}, confirm: async () => true,
    chooseEvidence: async () => {
        const file=path.join(directory,"temporary-photo.png");
        fs.writeFileSync(file,Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=","base64"));
        return {path:file,name:"temporary-photo.png",size:fs.statSync(file).size};
    },
    inventory: () => { const files = createInventoryFiles(wx, sessions.identity); return { files, deviceId: device,
        queue: new InventoryQueue(sessions.identity, device, files.storage, { now: () => Date.now() }) }; } };
const page = createInventoryCollectionPage(() => services); page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
page.onLoad({ taskId }); await page.onShow(); assert.ok(page.data.task, page.data.error); assert.equal(page.data.offline, false, page.data.message);
if (stage === "collect") {
    await page.onRoom({ detail: { value: "0" } });
    page.onInput({ currentTarget: { dataset: { field: "query" } }, detail: { value: "INV-ASSET-933010" } });
    page.onInput({ currentTarget: { dataset: { field: "manualReason" } }, detail: { value: "Damaged printed label; explicit field confirmation" } });
    await page.onSearch(); assert.equal(page.data.candidates.length, 1);
    page.onSelect({ currentTarget: { dataset: { id: page.data.candidates[0].id } } });
    page.onDifference(); await page.onPhoto(); assert.equal(page.data.photos.length,1,page.data.error);
    fs.unlinkSync(path.join(directory,"temporary-photo.png"));
    await page.onSave({ currentTarget: { dataset: { field: "normal" } } });
    assert.equal(page.data.pending.length, 1, page.data.error); write("original-event", services.inventory().queue.list()[0]);
} else {
    assert.equal(page.data.pending.length, 1, page.data.error); await page.onSync();
    if (stage === "lost") { assert.ok(dropped); assert.equal(page.data.pending.length, 1); }
    else {
        assert.deepEqual(read("payload-retry"), read("payload-lost"));
        assert.equal(page.data.pending.length, 0, page.data.error); assert.match(page.data.message, /待管理员复核/);
        const submitted=read("payload-retry");const object=submitted.photoIds[0];
        const grant=await sessions.request("POST",`/api/v1/mini/inventory-observations/tasks/${taskId}/events/${submitted.eventId}/photos/${object}/download-grant`);
        const downloaded=await sessions.downloadEvidence(grant.url);
        assert.ok(fs.readFileSync(downloaded).subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])));
        await sessions.logout(); console.log("Native persistent queue, two forced process deaths, actual lost reply and exact original retry PASS");
    }
}
fs.writeFileSync(path.join(directory, `ready-${stage}`), "ready");
if (stage !== "retry") setInterval(() => {}, 1000);
