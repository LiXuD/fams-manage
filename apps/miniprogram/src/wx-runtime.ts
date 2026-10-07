import { type MiniRuntime, type SavedSession } from "./session.ts";
import { type WechatLogin } from "./binding.ts";

export interface WxApi {
    request(input: { url: string; method: string; header: Record<string, string>; data?: unknown; timeout: number;
        success(reply: { statusCode: number; data: unknown }): void; fail(error: unknown): void }): void;
    scanCode?(input: { onlyFromCamera: boolean; success(reply: { result: string }): void; fail(error: unknown): void }): void;
    login(input: { timeout: number; success(result: { code: string }): void; fail(error: unknown): void }): void;
    getStorageSync(key: string): unknown;
    setStorageSync(key: string, value: unknown): void;
    removeStorageSync(key: string): void;
    redirectTo(input: { url: string }): void;
    showModal(input: { title: string; content: string; success(reply: { confirm: boolean }): void }): void;
}

export function createWxRuntime(wx: WxApi): MiniRuntime & WechatLogin {
    return {
        request: input => new Promise((resolve, reject) => wx.request({ ...input, timeout: 10000,
            header: { "Content-Type": "application/json", ...input.header }, success: resolve, fail: reject })),
        loginCode: () => new Promise((resolve, reject) => wx.login({ timeout: 10000,
            success: reply => reply.code ? resolve(reply.code) : reject(new Error("WeChat code unavailable")), fail: reject })),
        readSession: () => wx.getStorageSync("fams.session") || null,
        writeSession: (session: SavedSession) => wx.setStorageSync("fams.session", session),
        removeSession: () => wx.removeStorageSync("fams.session"),
        clearIdentityData: identity => {
            // Future D6-17 queues use this exact identity namespace, never another user's cache.
            wx.removeStorageSync(`fams.identity.${identity.accountId}.${identity.bindingId}`);
        },
    };
}

export function createUuid(): string {
    // Identity/idempotency keys are not credentials. Credentials are generated only on the server.
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, c => {
        const value = Math.floor(Math.random() * 16);
        return (c === "x" ? value : (value & 3) | 8).toString(16);
    });
}

export function deviceId(wx: WxApi): string {
    const current = wx.getStorageSync("fams.device-id");
    if (typeof current === "string" && /^[0-9a-f-]{36}$/i.test(current)) return current;
    const id = createUuid();
    wx.setStorageSync("fams.device-id", id);
    return id;
}
