import test from "node:test";
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";

const saved = { sessionToken: "a".repeat(43), accountId: "9007199254740993", bindingId: "4" };
const uploadPath = "/api/v1/mini/proxy-confirmations/9/attachments";
const downloadPath = "/api/v1/mini/attachments/12345678-1234-1234-1234-123456789abc/content?grant=one.two-three_4";
function fixture() {
    const calls = [], cleared = [];
    const runtime = { readSession: () => saved, writeSession() {}, removeSession() {}, clearIdentityData: value => cleared.push(value),
        request: async () => ({ statusCode: 200, data: {} }),
        upload: async input => { calls.push(input); return { statusCode: 201, data: { objectId: "proof" } }; },
        download: async input => { calls.push(input); return { statusCode: 200, tempFilePath: "/owned/download.pdf" }; } };
    return { calls, cleared, runtime, client: new MiniSessionClient("https://api.example.test", runtime) };
}
test("evidence transfer uses the same origin and opaque mini header with original version/key", async () => {
    const { client, calls } = fixture();
    await client.uploadEvidence(uploadPath, "/selected/proof.pdf", { key: "original-upload-key-001", version: 7 });
    assert.equal(calls[0].url, "https://api.example.test" + uploadPath);
    assert.deepEqual(calls[0].header, { "X-FAMS-Mini-Session": saved.sessionToken, "Idempotency-Key": "original-upload-key-001", "If-Match-Version": "7" });
    assert.equal(await client.downloadEvidence(downloadPath), "/owned/download.pdf");
    assert.deepEqual(calls[1].header, { "X-FAMS-Mini-Session": saved.sessionToken });
});
test("file credentials cannot be sent to foreign, PC, traversal or unrelated mini paths", async () => {
    const { client, calls } = fixture();
    for (const path of ["https://evil.test/proof", "/api/v1/proxy-confirmations/9/attachments", "/api/v1/mini/../attachments", "/api/v1/mini/proxy-confirmations/9/attachments?evil=1"]) {
        await assert.rejects(client.uploadEvidence(path, "/proof", { key: "original-upload-key-001", version: 0 }));
    }
    for (const path of ["https://evil.test" + downloadPath, downloadPath + "&url=evil", downloadPath.replace("/mini/", "/"), "/api/v1/mini/auth/session"]) {
        await assert.rejects(client.downloadEvidence(path));
    }
    assert.equal(calls.length, 0);
});
test("late upload and download responses cannot cross a cleared identity", async () => {
    for (const kind of ["upload", "download"]) {
        const { client, runtime } = fixture(); let finish;
        runtime[kind] = () => new Promise(resolve => finish = resolve);
        const pending = kind === "upload" ? client.uploadEvidence(uploadPath, "/proof", { key: "original-upload-key-001", version: 0 }) : client.downloadEvidence(downloadPath);
        client.clear(); finish({ statusCode: 200, data: {}, tempFilePath: "/old/proof" });
        await assert.rejects(pending, /Session changed/); assert.equal(client.identity, null);
    }
});
test("file 401 clears identity while network failure preserves original retry credentials", async () => {
    const { client, runtime, cleared } = fixture();
    runtime.upload = async () => { throw Error("lost committed response"); };
    await assert.rejects(client.uploadEvidence(uploadPath, "/proof", { key: "original-upload-key-001", version: 0 }), /lost/);
    assert.deepEqual(client.identity, { accountId: saved.accountId, bindingId: saved.bindingId });
    runtime.upload = async () => ({ statusCode: 401, data: { code: "AUTH_SESSION_INVALID" } });
    await assert.rejects(client.uploadEvidence(uploadPath, "/proof", { key: "original-upload-key-001", version: 0 }), { status: 401 });
    assert.equal(client.identity, null); assert.equal(cleared.length, 1);
});
test("wx multipart transfer does not force a JSON content type and parses upload response", async () => {
    let request;
    const runtime = createWxRuntime({ uploadFile(input) { request = input; input.success({ statusCode: 201, data: '{"objectId":"proof"}' }); },
        downloadFile(input) { input.success({ statusCode: 200, tempFilePath: "/download.pdf" }); },
        getStorageSync() {}, setStorageSync() {}, removeStorageSync() {} });
    const result = await runtime.upload({ url: "https://api.example.test" + uploadPath, filePath: "/proof.pdf", header: { "X-FAMS-Mini-Session": saved.sessionToken } });
    assert.equal(request.name, "file"); assert.equal(request.header["Content-Type"], undefined);
    assert.deepEqual(result, { statusCode: 201, data: { objectId: "proof" } });
    assert.equal((await runtime.download({ url: "https://api.example.test" + downloadPath, header: {} })).tempFilePath, "/download.pdf");
});
