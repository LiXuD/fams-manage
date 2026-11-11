import test from "node:test";
import assert from "node:assert/strict";
import { createMessagesPage } from "../src/messages-pages.ts";
import { MiniRequestError } from "../src/session.ts";

const reminder = { id: "41", kind: "REMINDER", businessType: "INBOUND", businessId: "7",
    stage: "UNRESOLVED_AFTER_1_WORKDAY", objectStage: "ADMIN_DECISION", poolType: "ASSET_ADMIN",
    assigneeAccountId: "", openedAt: "2026-11-09T01:00:00Z", deadlineAt: "2026-11-10T04:00:00Z", read: false };
const todo = { id: "42", kind: "TODO", businessType: "SCRAP", businessId: "8", stage: "DISPOSITION",
    objectStage: "", poolType: "WAREHOUSE_OPERATOR", assigneeAccountId: "3", openedAt: "2026-11-08T01:00:00Z",
    deadlineAt: "", read: false };

function bind(page) { page.data = structuredClone(page.data); page.setData = value => Object.assign(page.data, value); return page; }
const event = (id, kind = "TODO") => ({ currentTarget: { dataset: { id, kind } } });

function fixture(view = "INBOX", identity = { accountId: "1", bindingId: "1" }) {
    const writes = []; const reads = []; const routes = [];
    let refused = false, lost = true, keys = 0, rows = [structuredClone(todo), structuredClone(reminder)];
    const services = { sessions: { identity,
        async request(method, path, _body, options) {
            if (method === "POST") {
                writes.push(structuredClone({ path, key: options?.key, version: options?.version }));
                const id = path.split("/")[6];
                if (lost) { lost = false; throw new Error("lost read reply"); }
                rows = rows.map(row => row.id === id ? { ...row, read: true } : row);
                return { kind: "TODO", id, read: true };
            }
            reads.push(path);
            if (refused) throw new MiniRequestError(403, "MESSAGE_SCOPE_FORBIDDEN");
            return { items: structuredClone(rows), total: rows.length, unread: rows.filter(row => !row.read).length,
                view };
        } }, createKey: () => "message-key-" + ++keys, navigate: route => routes.push(route) };
    return { services, writes, reads, routes, page: bind(createMessagesPage(() => services, view)),
        refuse: () => { refused = true; }, rows: () => rows };
}

test("inbox labels both sources and retries the identical read identity", async () => {
    const { page, writes } = fixture();
    await page.onShow();
    assert.deepEqual(page.data.rows.map(row => row.label), ["待办 · 待实际处置", "提醒 · 管理员确认 1个工作日未处理"]);
    assert.equal(page.data.unread, 2);
    assert.equal(page.data.rows[1].deadlineDisplay !== "", true);
    await page.onRead(event("42"));
    assert.equal(page.data.retryable, true);
    assert.equal(writes.length, 1);
    assert.equal(writes[0].path, "/api/v1/mini/messages/TODO/42/read");
    await page.onRetry();
    assert.deepEqual(writes[1], writes[0]);
    assert.equal(page.data.rows.find(row => row.id === "42").read, true);
    assert.equal(page.data.unread, 1);
    assert.equal(page.data.notice, "已读登记只记录本人状态，业务办理仍以原单据为准。");
    const before = writes.length;
    await page.onRead(event("42"));
    assert.equal(writes.length, before, "an already read message cannot be marked again");
});

test("a late inbox from a previous identity is discarded and the reader returns to login", async () => {
    const { page, services, routes } = fixture();
    let finish, held = false;
    const original = services.sessions.request;
    services.sessions.request = (method, path) => {
        if (!held) { held = true; return new Promise(resolve => { finish = resolve; }); }
        return original(method, path);
    };
    const first = page.onShow();
    services.sessions.identity = { accountId: "2", bindingId: "2" };
    const second = page.onShow();
    finish({ items: [{ ...todo, businessId: "PRIVATE-8" }], total: 1, unread: 1, view: "INBOX" });
    await Promise.all([first, second]);
    assert.ok(!page.data.rows.some(row => String(row.businessId).includes("PRIVATE")));
    assert.equal(page.data.rows.length, 2);
    services.sessions.identity = null;
    await page.onShow();
    assert.deepEqual(routes.slice(-1), ["/pages/login/index"]);
});

test("the exception view requests the overdue scope and clears when the caller is not follow-up staff", async () => {
    const { page, reads, refuse } = fixture("EXCEPTION", { accountId: "9", bindingId: "9" });
    await page.onShow();
    assert.equal(reads[0], "/api/v1/mini/messages?view=EXCEPTION&page=1&pageSize=20");
    assert.equal(page.data.exception, true);
    refuse();
    await page.onRefresh();
    assert.equal(page.data.rows.length, 0);
    assert.ok(page.data.error.length > 0);
});

test("only business pages that revalidate an id offer a deep link", async () => {
    const { page, routes } = fixture();
    await page.onShow();
    await page.onOpen(event("42"));
    assert.deepEqual(routes, ["/pages/scrap-requests/index?id=8"]);
    await page.onOpen(event("41", "REMINDER"));
    assert.equal(routes.length, 1, "inbound reminders have no native detail page yet");
});
