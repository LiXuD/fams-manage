import test from "node:test";
import assert from "node:assert/strict";
import { MiniSessionClient } from "../src/session.ts";
import { MiniBindingClient } from "../src/binding.ts";
import { createLoginPage, createMinePage } from "../src/pages.ts";
import { createWxRuntime, deviceId } from "../src/wx-runtime.ts";

const origin = "https://api.example.test";
const device = "00000000-0000-4000-8000-000000000001";
const saved = { sessionToken: "a".repeat(43), accountId: "920001", bindingId: "25" };
const challenge = { challengeToken: "b".repeat(43), expiresAt: "2026-10-05T03:00:00Z", resendAfterSeconds: 60 };
function fixture() {
    const rt = {
        saved: null, requests: [], cleared: [], loginCount: 0,
        readSession() { return this.saved; }, writeSession(v) { this.saved = v; },
        removeSession() { this.saved = null; }, clearIdentityData(v) { this.cleared.push(v); },
        async loginCode() { return `fresh-code-${++this.loginCount}`; },
        async request(input) {
            this.requests.push(input);
            if (input.url.endsWith("/binding-challenges")) return { statusCode: 200, data: challenge };
            if (input.url.endsWith("/support")) return { statusCode: 200, data: { supportText: "Corporate helpdesk" } };
            if (input.url.endsWith("/binding")) return { statusCode: 200, data: { accountId: "920001", bindingId: "25", bindingVersion: 0 } };
            if (input.url.endsWith("/login")) return { statusCode: 200, data: { ...saved, bindingStatus: "BOUND",
                idleExpiresAt: "2026-10-05T02:30:00Z", absoluteExpiresAt: "2026-10-05T10:00:00Z" } };
            if (input.url.endsWith("/session")) return { statusCode: 200, data: { accountId: "920001", bindingVersion: 0 } };
            return { statusCode: 204, data: null };
        },
    };
    const sessions = new MiniSessionClient(origin, rt);
    const binding = new MiniBindingClient(origin, rt, sessions, rt, device, () => "binding-client-key-0001");
    const services = { sessions, binding, wechat: rt, createKey: () => "unbind-client-key-0001",
        navigations: [], navigate(url) { this.navigations.push(url); }, confirm: async () => true };
    return { rt, services, binding, sessions };
}
const form = { employeeNo: "BIND920001", phone: "13800138000", action: "BIND" };
function mount(page) {
    page.data = structuredClone(page.data);
    page.setData = function (data) { Object.assign(this.data, data); };
    return page;
}

test("binding uses fresh wx codes, retains no phone or OTP in storage, logs in after receipt", async () => {
    const { rt, binding, sessions } = fixture();
    await binding.send(form);
    assert.equal(rt.saved, null);
    await binding.verify("123456");
    assert.equal(rt.loginCount, 3);
    assert.equal(new Set(rt.requests.filter(x => x.method === "POST").map(x => x.data.code)).size, 3);
    assert.deepEqual(rt.saved, saved);
    assert.equal(JSON.stringify(rt.saved).includes(form.phone), false);
    assert.equal(JSON.stringify(rt.saved).includes("123456"), false);
    assert.equal(sessions.identity.bindingId, "25");
    await assert.rejects(binding.verify("123456"));
});

test("lost confirmation retries same binding key with a fresh wx code", async () => {
    const { rt, binding } = fixture();
    await binding.send(form);
    const request = rt.request.bind(rt);
    let lost = true;
    rt.request = async input => {
        const reply = await request(input);
        if (input.url.endsWith("/binding") && lost) { lost = false; throw new Error("Confirmation lost"); }
        return reply;
    };
    await assert.rejects(binding.verify("123456"), /Confirmation lost/);
    await binding.verify("123456");
    const writes = rt.requests.filter(x => x.url.endsWith("/binding"));
    assert.equal(writes.length, 2);
    assert.equal(writes[0].header["Idempotency-Key"], writes[1].header["Idempotency-Key"]);
    assert.notEqual(writes[0].data.code, writes[1].data.code);
    assert.equal(writes[0].header["If-Match-Version"], "0");
});

test("input reset rejects late SMS challenge and cannot verify previous identity", async () => {
    const { rt, binding } = fixture();
    let complete;
    rt.request = () => new Promise(resolve => { complete = resolve; });
    const pending = binding.send(form);
    await new Promise(resolve => setImmediate(resolve));
    binding.reset();
    complete({ statusCode: 200, data: challenge });
    await assert.rejects(pending, /Binding input changed/);
    await assert.rejects(binding.verify("123456"));
});

test("replacement clears old identity before a failed new login", async () => {
    const { rt, binding, sessions } = fixture();
    await sessions.login("old-code");
    const request = rt.request.bind(rt);
    rt.request = input => input.url.endsWith("/login") ? Promise.reject(new Error("Login lost")) : request(input);
    await binding.send({ ...form, action: "REBIND" });
    await assert.rejects(binding.verify("123456"), /Login lost/);
    assert.equal(rt.saved, null);
    assert.deepEqual(rt.cleared, [{ accountId: "920001", bindingId: "25" }]);
});

test("native login handlers validate fields, bind and discard phone/OTP on navigation", async () => {
    const { services } = fixture();
    const page = mount(createLoginPage(() => services));
    await page.onLoad({});
    await page.onSend();
    assert.match(page.data.error, /工号/);
    page.onEmployee({ detail: { value: form.employeeNo } });
    page.onPhone({ detail: { value: form.phone } });
    await page.onSend();
    assert.equal(page.data.sent, true);
    page.onOtp({ detail: { value: "123456" } });
    await page.onVerify();
    assert.deepEqual(services.navigations, ["/pages/mine/index"]);
    assert.equal(page.data.otp, "");
    assert.equal(page.data.phone, "");
    page.onUnload();
});

test("native page hide discards SMS proof and late completion cannot navigate", async () => {
    const { services, rt } = fixture();
    const page = mount(createLoginPage(() => services));
    await page.onLoad({ action: "REBIND" });
    page.onEmployee({ detail: { value: form.employeeNo } });
    page.onPhone({ detail: { value: form.phone } });
    let complete;
    rt.request = () => new Promise(resolve => { complete = resolve; });
    const send = page.onSend();
    await new Promise(resolve => setImmediate(resolve));
    page.onHide();
    complete({ statusCode: 200, data: challenge });
    await send;
    assert.equal(page.data.sent, false);
    assert.equal(page.data.phone, "");
    assert.deepEqual(services.navigations, []);
    page.onUnload();
});

test("native mine confirms unbind, uses version and clears identity cache", async () => {
    const { services, sessions, rt } = fixture();
    await sessions.login("code");
    const page = mount(createMinePage(() => services));
    await page.onShow();
    services.confirm = async () => false;
    await page.onUnbind();
    assert.notEqual(sessions.identity, null);
    services.confirm = async () => true;
    await page.onUnbind();
    assert.equal(sessions.identity, null);
    assert.deepEqual(rt.cleared, [{ accountId: "920001", bindingId: "25" }]);
    const request = rt.requests.find(x => x.url.endsWith("/unbind"));
    assert.equal(request.header["If-Match-Version"], "0");
    assert.equal(request.header["X-FAMS-Mini-Session"], saved.sessionToken);
    assert.deepEqual(services.navigations, ["/pages/login/index"]);
});

test("wx bridge maps request/login and scopes storage cleanup to the old identity", async () => {
    const storage = new Map([["fams.identity.1.2", "queue"], ["fams.identity.3.4", "other"]]);
    let request;
    const wx = { request(input) { request = input; input.success({ statusCode: 204, data: null }); },
        login(input) { input.success({ code: "wx-fresh" }); }, getStorageSync: k => storage.get(k),
        setStorageSync: (k,v) => storage.set(k,v), removeStorageSync: k => storage.delete(k) };
    const rt = createWxRuntime(wx);
    assert.equal(await rt.loginCode(), "wx-fresh");
    await rt.request({ url: origin, method: "GET", header: {} });
    assert.equal(request.timeout, 10000);
    assert.equal(request.header["Content-Type"], "application/json");
    rt.clearIdentityData({ accountId: "1", bindingId: "2" });
    assert.equal(storage.has("fams.identity.1.2"), false);
    assert.equal(storage.has("fams.identity.3.4"), true);
    assert.equal(deviceId(wx), deviceId(wx));
    assert.equal(storage.has("fams.device-id"), true);
});
