// Native handlers/opaque session → real Spring/MySQL → actual owned filesystem bytes.
// wx callbacks, HTTPS loopback and the server's explicit CLEAN scanner fixture are substitutes.
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { createWxRuntime } from "../src/wx-runtime.ts";
import { MiniSessionClient } from "../src/session.ts";
import { createInventoryReviewPage } from "../src/inventory-review-pages.ts";
const state = process.env.FAMS_TEST_PHOTO_STATE, stage = process.env.FAMS_TEST_PHOTO_STAGE;
const port = process.env.FAMS_TEST_API_PORT, task = process.env.FAMS_TEST_INVENTORY_TASK, item = process.env.FAMS_TEST_INVENTORY_ITEM;
const observation = process.env.FAMS_TEST_PHOTO_OBSERVATION, object = process.env.FAMS_TEST_PHOTO_OBJECT, unused = process.env.FAMS_TEST_PHOTO_UNUSED;
assert.ok(state && ["crash", "recover"].includes(stage)); assert.match(port ?? "", /^[0-9]{1,5}$/);
for (const value of [task, item, observation]) assert.match(value ?? "", /^[1-9][0-9]*$/);
const filesRoot = path.join(state, "files"), storagePath = path.join(state, "storage.json"); fs.mkdirSync(filesRoot, { recursive: true });
function writeJson(file, value) { fs.writeFileSync(file + ".tmp", JSON.stringify(value)); fs.renameSync(file + ".tmp", file); }
if (stage === "crash") writeJson(storagePath, { "fams.session": JSON.parse(process.env.FAMS_TEST_MINI_GRANT) });
const load = () => JSON.parse(fs.readFileSync(storagePath, "utf8"));
const requests = [], downloads = [], origin = "https://inventory-photo-test.invalid";
function target(url) { assert.ok(url.startsWith(origin + "/api/v1/mini/")); return `http://127.0.0.1:${port}` + url.slice(origin.length); }
const wx = {
    env: { USER_DATA_PATH: filesRoot },
    getFileSystemManager: () => ({ mkdirSync: (name, recursive) => fs.mkdirSync(name, { recursive }), copyFileSync: fs.copyFileSync,
        statSync: fs.statSync, unlinkSync: fs.unlinkSync, readdirSync: fs.readdirSync, accessSync: fs.accessSync }),
    getStorageSync: key => load()[key], setStorageSync: (key, value) => { const data = load(); data[key] = value; writeJson(storagePath, data); },
    removeStorageSync: key => { const data = load(); delete data[key]; writeJson(storagePath, data); },
    request(input) { void (async () => {
        try { const response = await fetch(target(input.url), { method: input.method, headers: input.header,
            body: input.data === undefined ? undefined : JSON.stringify(input.data), redirect: "error" });
            requests.push({ path: input.url.slice(origin.length).split("?")[0], status: response.status });
            input.success({ statusCode: response.status, data: response.status === 204 ? null : await response.json() });
        } catch (error) { input.fail(error); }
    })(); },
    downloadFile(input) { void (async () => {
        try { assert.ok(input.filePath.startsWith(filesRoot + "/fams-inventory-evidence-"));
            const response = await fetch(target(input.url), { headers: input.header, redirect: "error" });
            fs.writeFileSync(input.filePath, Buffer.from(await response.arrayBuffer()));
            downloads.push({ filePath: input.filePath, status: response.status, object: input.url.includes("/" + unused + "/"), url: input.url });
            input.success({ statusCode: response.status, filePath: input.filePath, tempFilePath: "" });
        } catch (error) { input.fail(error); }
    })(); },
};
const runtime = createWxRuntime(wx), sessions = new MiniSessionClient(origin, runtime);
const first = stage === "recover" ? JSON.parse(fs.readFileSync(path.join(state, "preview-crash.json"), "utf8")) : null;
if (first) { assert.notEqual(first.pid, process.pid); assert.equal(fs.existsSync(first.filePath), false); }
const navigations = [];
const page = createInventoryReviewPage(() => ({ sessions, createKey: randomUUID, confirm: async () => true, navigate: value => navigations.push(value) }));
page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value);
const event = id => ({ currentTarget: { dataset: { id } } });
page.onLoad({ taskId: task }); await page.onShow(); assert.equal(page.data.error, "");
await page.onOpenItem(event(item)); assert.equal(page.data.error, "");
await page.onPhotos(event(observation)); assert.equal(page.data.photoError, "");
assert.equal(page.data.photoTotal, 1); assert.equal(page.data.photos.length, 1); assert.equal(page.data.photos[0].objectId, object);
await page.onReadPhoto(event(object)); assert.equal(page.data.photoError, ""); assert.ok(page.data.photoPath);
const previewPath = page.data.photoPath;
const digest = createHash("sha256").update(fs.readFileSync(previewPath)).digest("hex"); assert.equal(digest, process.env.FAMS_TEST_PHOTO_SHA256);
writeJson(path.join(state, `preview-${stage}.json`), { filePath: previewPath, pid: process.pid, sha256: digest });
writeJson(path.join(state, `ready-${stage}`), { ready: true });
const deadline = Date.now() + 45000;
while (!fs.existsSync(path.join(state, `continue-${stage}`))) {
    assert.ok(Date.now() < deadline, "Owned photo runtime control deadline expired");
    await new Promise(resolve => setTimeout(resolve, 25));
}
assert.equal(stage, "recover", "Crash stage must be killed by the owning Java process");
// Previously issued signed content still requires the current role; error bytes must also disappear.
const signed = downloads[0].url.slice(origin.length);
await assert.rejects(sessions.downloadInventoryPhoto(signed, "image/png"), { status: 403 });
assert.equal(fs.existsSync(downloads.at(-1).filePath), false);
await page.onPhotos(event(observation));
assert.equal(page.data.board, null); assert.equal(page.data.photoPath, ""); assert.deepEqual(page.data.photos, []);
assert.equal(fs.existsSync(previewPath), false); assert.equal(downloads.some(row => row.object), false);
assert.equal(requests.some(row => row.path.includes("/auth/")), false); assert.deepEqual(navigations, []);
assert.equal(fs.readFileSync(storagePath, "utf8").includes("grant="), false);
writeJson(path.join(state, "proof.json"), { byteSha256: digest, startupRemovedKilledPreview: true, newProcess: true,
    originalReferencesOnly: true, roleLossRejectedSignedRead: true, roleLossClearedPageAndBytes: true,
    noNewLogin: true, grantsNotPersisted: true });
page.onUnload(); console.log("Native photo bytes, actual forced death/startup cleanup and current-role denial PASS");
