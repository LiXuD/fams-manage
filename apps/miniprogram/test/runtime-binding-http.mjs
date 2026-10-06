// Test-only bridge: page handlers -> real Spring HTTP -> real temporary MySQL.
// wx.login and SMS delivery are explicit test substitutes, not WeChat/device evidence.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { MiniSessionClient } from "../src/session.ts";
import { MiniBindingClient } from "../src/binding.ts";
import { createLoginPage, createMinePage } from "../src/pages.ts";

const port = process.env.FAMS_TEST_API_PORT;
const smsFile = process.env.FAMS_TEST_SMS_FILE;
assert.match(port ?? "", /^[0-9]{1,5}$/);
assert.ok(smsFile);
const origin = "https://test-only.invalid";
const storage = new Map();
const requests = [];
let codes = 0;
const runtime = {
    readSession: () => storage.get("session") ?? null,
    writeSession: value => storage.set("session", value),
    removeSession: () => storage.delete("session"),
    clearIdentityData: identity => storage.delete(`queue:${identity.accountId}:${identity.bindingId}`),
    loginCode: async () => `first-${++codes}`,
    async request(input) {
        assert.ok(input.url.startsWith(origin + "/api/v1/mini/"));
        const response = await fetch(`http://127.0.0.1:${port}` + input.url.slice(origin.length),
            { method: input.method, headers: { "Content-Type": "application/json", ...input.header },
                body: input.data ? JSON.stringify(input.data) : undefined, redirect: "error" });
        requests.push({ path: input.url.slice(origin.length), status: response.status });
        return { statusCode: response.status, data: response.status === 204 ? null : await response.json() };
    },
};
const sessions = new MiniSessionClient(origin, runtime);
const binding = new MiniBindingClient(origin, runtime, sessions, runtime,
    "00000000-0000-4000-8000-000000000001", () => "page-binding-key-0001");
const navigations = [];
const services = { sessions, binding, wechat: runtime, createKey: () => "page-unbind-key-0001",
    navigate: url => navigations.push(url), confirm: async () => true };
function mount(page) {
    page.data = structuredClone(page.data);
    page.setData = function (data) { Object.assign(this.data, data); };
    return page;
}
const login = mount(createLoginPage(() => services));
await login.onLoad({});
login.onEmployee({ detail: { value: "BIND920001" } });
login.onPhone({ detail: { value: "13800138000" } });
await login.onSend();
assert.equal(login.data.sent, true, login.data.error);
login.onOtp({ detail: { value: readFileSync(smsFile, "utf8") } });
await login.onVerify();
assert.equal(sessions.identity?.accountId, "920001", login.data.error);
assert.deepEqual(navigations, ["/pages/mine/index"]);
assert.equal(login.data.phone, "");
assert.equal(login.data.otp, "");
const identity = sessions.identity;
storage.set(`queue:${identity.accountId}:${identity.bindingId}`, "pending-test-evidence");
const mine = mount(createMinePage(() => services));
await mine.onShow();
assert.equal(mine.data.accountId, "920001", mine.data.error);
await mine.onUnbind();
assert.equal(sessions.identity, null, mine.data.error);
assert.equal(storage.size, 0);
assert.deepEqual(navigations, ["/pages/mine/index", "/pages/login/index"]);
assert.equal(await sessions.login(await runtime.loginCode()), "UNBOUND");
login.onUnload();
mine.onUnload();
console.log("Page handlers -> real HTTP -> MySQL: binding, login, session, unbind, cache cleanup PASS.");
console.log("Test substitutes: wx.login identity and SMS capture; loopback test transport, no real device or HTTPS acceptance.");
console.log(JSON.stringify(requests));
