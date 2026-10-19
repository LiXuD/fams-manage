import test from "node:test";
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";

const token = "a".repeat(43);
const saved = { sessionToken: token, accountId: "9007199254740993", bindingId: "25" };
const bound = { ...saved, bindingStatus: "BOUND", idleExpiresAt: "2026-10-05T02:30:00Z", absoluteExpiresAt: "2026-10-05T10:00:00Z" };

function runtime(initial = null) {
    return {
        saved: initial, requests: [], cleared: [], reply: { statusCode: 200, data: bound },
        readSession() { return this.saved; },
        writeSession(value) { this.saved = structuredClone(value); },
        removeSession() { this.saved = null; },
        clearIdentityData(identity) { this.cleared.push(identity); },
        async request(input) { this.requests.push(input); return this.reply; },
    };
}

test("persists only opaque session and identity, reloads and uses HTTPS header", async () => {
    const rt = runtime();
    const first = new MiniSessionClient("https://api.example.test/", rt);
    assert.equal(await first.login("one-use-wechat-code"), "BOUND");
    assert.deepEqual(rt.saved, saved);
    assert.equal(JSON.stringify(rt.saved).includes("wechat-code"), false);
    const restored = new MiniSessionClient("https://api.example.test", rt);
    await restored.request("GET", "/api/v1/mini/auth/session");
    assert.equal(rt.requests[1].url, "https://api.example.test/api/v1/mini/auth/session");
    assert.deepEqual(rt.requests[1].header, { "X-FAMS-Mini-Session": token });
    assert.deepEqual(restored.identity, { accountId: saved.accountId, bindingId: saved.bindingId });
});

test("rejects non-HTTPS origin and credential forwarding to foreign or PC paths", async () => {
    for (const origin of ["http://api.example.test", "https://user:pass@api.example.test", "https://api.example.test/path"]) {
        assert.throws(() => new MiniSessionClient(origin, runtime(saved)));
    }
    const rt = runtime(saved);
    const client = new MiniSessionClient("https://api.example.test", rt);
    for (const path of ["https://evil.test", "//evil.test", "/api/v1/auth/me", "/api/v1/mini/../auth/me",
        "/api/v1/mini/%2e%2e/auth/me", "/api/v1/mini/auth/login", "/api/v1/mini/a\\b"]) {
        await assert.rejects(client.request("GET", path));
    }
    assert.equal(rt.requests.length, 0);
});

test("401 clears credential and identity data before raising the stable error", async () => {
    const rt = runtime(saved);
    rt.reply = { statusCode: 401, data: { code: "AUTH_SESSION_INVALID" } };
    const client = new MiniSessionClient("https://api.example.test", rt);
    await assert.rejects(client.request("GET", "/api/v1/mini/auth/session"), { code: "AUTH_SESSION_INVALID", status: 401 });
    assert.equal(client.identity, null);
    assert.equal(rt.saved, null);
    assert.deepEqual(rt.cleared, [{ accountId: saved.accountId, bindingId: saved.bindingId }]);
});

test("network and 503 failures preserve a valid current identity", async () => {
    const rt = runtime(saved);
    const client = new MiniSessionClient("https://api.example.test", rt);
    rt.request = async () => { throw new Error("network lost"); };
    await assert.rejects(client.request("GET", "/api/v1/mini/auth/session"), /network lost/);
    assert.deepEqual(rt.saved, saved);
    rt.request = async () => ({ statusCode: 503, data: "<html>private stack</html>" });
    await assert.rejects(client.login("code"), { code: "MINI_REQUEST_FAILED" });
    assert.deepEqual(rt.saved, saved);
});

test("UNBOUND removes prior identity and rebind clears previous identity data", async () => {
    const rt = runtime(saved);
    const client = new MiniSessionClient("https://api.example.test", rt);
    rt.reply = { statusCode: 200, data: { bindingStatus: "UNBOUND", sessionToken: null, accountId: null,
        bindingId: null, idleExpiresAt: null, absoluteExpiresAt: null } };
    assert.equal(await client.login("unbound"), "UNBOUND");
    assert.equal(rt.saved, null);
    rt.reply = { statusCode: 200, data: bound };
    await client.login("bound");
    rt.reply = { statusCode: 200, data: { ...bound, bindingId: "26" } };
    await client.login("rebound");
    assert.equal(rt.saved.bindingId, "26");
    assert.equal(rt.cleared.length, 2);
});

test("storage failure never reports a new authenticated identity", async () => {
    const rt = runtime();
    rt.writeSession = () => { throw new Error("storage full"); };
    const client = new MiniSessionClient("https://api.example.test", rt);
    await assert.rejects(client.login("code"), /storage full/);
    assert.equal(client.identity, null);
});

test("invalid cached data and invalid server metadata cannot establish a session", async () => {
    const rt = runtime({ sessionToken: "wechat-code", accountId: "1", bindingId: "2" });
    const client = new MiniSessionClient("https://api.example.test", rt);
    assert.equal(rt.saved, null);
    rt.reply = { statusCode: 200, data: { ...bound, absoluteExpiresAt: "bad-time" } };
    await assert.rejects(client.login("code"), /Invalid session response/);
    assert.equal(client.identity, null);
    await assert.rejects(client.login(" "), /Login code required/);
});

test("late login cannot restore a cleared credential", async () => {
    const rt = runtime();
    let finish;
    rt.request = () => new Promise(resolve => { finish = resolve; });
    const client = new MiniSessionClient("https://api.example.test", rt);
    const pending = client.login("code");
    client.clear();
    finish({ statusCode: 200, data: bound });
    await assert.rejects(pending, /Session changed/);
    assert.equal(rt.saved, null);
});

test("old request rejection and old logout cannot clear a newer login", async () => {
    const rt = runtime(saved);
    let finish;
    rt.request = input => input.url.endsWith("/login") ? Promise.resolve({ statusCode: 200, data: { ...bound, bindingId: "27" } })
        : new Promise(resolve => { finish = resolve; });
    const client = new MiniSessionClient("https://api.example.test", rt);
    const oldLogout = client.logout();
    await client.login("new-code");
    finish({ statusCode: 401, data: { code: "AUTH_SESSION_INVALID" } });
    await assert.rejects(oldLogout, /Session changed/);
    assert.equal(client.identity.bindingId, "27");
    assert.equal(rt.saved.bindingId, "27");
});

test("logout clears local identity even when server confirmation is lost", async () => {
    const rt = runtime(saved);
    rt.request = async () => { throw new Error("connection lost"); };
    const client = new MiniSessionClient("https://api.example.test", rt);
    await assert.rejects(client.logout(), /connection lost/);
    assert.equal(rt.saved, null);
    assert.equal(client.identity, null);
});


test("409 exposes only bounded immutable review metadata without persisting it in the credential", async () => {
    const rt = runtime(saved); rt.reply = { statusCode: 409, data: { code: "INVENTORY_REVIEW_VERSION_CONFLICT", fieldErrors: {
        processedBy: "Actual administrator", processedAt: "2026-10-18T00:00:00Z", method: "VERIFY_SELECTED", result: "CLOSED",
        subjectVersion: "3", taskVersion: "9", readOnlyUrl: "/api/v1/mini/inventory-reviews/tasks/1/differences",
        secret: "must not retain", reviewVersion: { nested: "not a string" } } } };
    const client = new MiniSessionClient("https://api.example.test", rt);
    await assert.rejects(client.request("POST", "/api/v1/mini/inventory-reviews/tasks/1/items/2/evidence-decisions", {}, { key: "request-key-01234567", version: 8 }), error => {
        assert.equal(error.status, 409); assert.equal(error.fields.processedBy, "Actual administrator");
        assert.equal(error.fields.method, "VERIFY_SELECTED"); assert.equal(error.fields.secret, undefined); assert.equal(error.fields.reviewVersion, undefined);
        assert.equal(Object.isFrozen(error.fields), true); return true;
    });
    assert.deepEqual(rt.saved, saved); assert.equal(JSON.stringify(rt.saved).includes("Actual administrator"), false);
});

test("401 does not retain private conflict context and malformed 409 metadata is ignored", async () => {
    const rt = runtime(saved); const client = new MiniSessionClient("https://api.example.test", rt);
    rt.reply = { statusCode: 409, data: { code: "CONFLICT", fieldErrors: { processedBy: "x".repeat(1001), result: 9 } } };
    await assert.rejects(client.request("GET", "/api/v1/mini/auth/session"), error => { assert.deepEqual(error.fields, {}); return true; });
    rt.reply = { statusCode: 409, data: { code: "CONFLICT", fieldErrors: ["not metadata"] } };
    await assert.rejects(client.request("GET", "/api/v1/mini/auth/session"), error => { assert.deepEqual(error.fields, {}); return true; });
    rt.reply = { statusCode: 401, data: { code: "AUTH_SESSION_INVALID", fieldErrors: { processedBy: "Private prior administrator" } } };
    await assert.rejects(client.request("GET", "/api/v1/mini/auth/session"), error => { assert.deepEqual(error.fields, {}); return true; });
    assert.equal(client.identity, null); assert.equal(rt.saved, null);
});

test("finding lookup permits encoded legacy business codes only in its exact GET query values", async () => {
    const rt = runtime(saved), client = new MiniSessionClient("https://api.example.test", rt);
    const base = "/api/v1/mini/inventory-reviews/tasks/9/finding-targets";
    const query = "?assetCode=" + encodeURIComponent("旧/码 甲") + "&receiptNumber=" + encodeURIComponent("RK/旧号");
    await client.request("GET", base + query);
    assert.equal(rt.requests[0].url, "https://api.example.test" + base + query);
    for (const path of [base.replace("/9/", "/%39/") + query, base + query + "&redirect=other",
        base + query + "&assetCode=duplicate", base + "?assetCode=%GG", "/api/v1/mini/auth/session?value=%2f"]) {
        await assert.rejects(client.request("GET", path));
    }
    await assert.rejects(client.request("POST", base + query));
    assert.equal(rt.requests.length, 1);
});
