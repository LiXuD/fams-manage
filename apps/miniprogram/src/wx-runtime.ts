import { type MiniRuntime, type SavedSession } from "./session.ts";
import { type WechatLogin } from "./binding.ts";
import { createInventoryFiles, type InventoryFileSystem, type InventoryFileRuntime } from "./inventory-files.ts";

export interface WxApi {
    chooseImage?(input: { count: number; sourceType: ("camera" | "album")[]; sizeType: ("original" | "compressed")[];
        success(reply: { tempFiles: { path: string; size: number }[] }): void; fail(error: unknown): void }): void;
    getImageInfo?(input: { src: string; success(reply: { type: string }): void; fail(error: unknown): void }): void;
    env?: { USER_DATA_PATH: string };
    getFileSystemManager?(): InventoryFileSystem;
    uploadFile?(input: { url: string; filePath: string; name: string; header: Record<string, string>; timeout: number;
        success(reply: { statusCode: number; data: string }): void; fail(error: unknown): void }): void;
    downloadFile?(input: { url: string; header: Record<string, string>; timeout: number;
        success(reply: { statusCode: number; tempFilePath: string }): void; fail(error: unknown): void }): void;
    chooseMessageFile?(input: { count: number; type: string; extension: string[];
        success(reply: { tempFiles: { path: string; name: string; size: number }[] }): void; fail(error: unknown): void }): void;
    previewImage?(input: { current: string; urls: string[]; success(): void; fail(error: unknown): void }): void;
    openDocument?(input: { filePath: string; showMenu: boolean; success(): void; fail(error: unknown): void }): void;
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

/** Dedicated scene-photo entry, separate from business-document selection. Server checks bytes again. */
export function chooseInventoryPhoto(wx: WxApi, source: "camera" | "album"): Promise<{ path: string; name: string; size: number }> {
    return new Promise((resolve, reject) => {
        if (!wx.chooseImage || !wx.getImageInfo) { reject(new Error("当前微信无法打开相机或相册，请更新后重试。")); return; }
        wx.chooseImage({ count: 1, sourceType: [source], sizeType: ["compressed"], success: reply => {
            const photo = reply.tempFiles[0]; if (!photo) { reject(new Error("尚未选择现场照片。")); return; }
            wx.getImageInfo!({ src: photo.path, success: info => {
                const extension = ({ jpeg: "jpg", png: "png", webp: "webp" } as Record<string, string>)[info.type];
                if (!extension) { reject(new Error("请使用JPG、PNG或WEBP现场照片。")); return; }
                resolve({ path: photo.path, name: `现场照片.${extension}`, size: photo.size });
            }, fail: () => reject(new Error("照片无法读取，请重新拍摄或选择。")) });
        }, fail: () => reject(new Error("拍照或选图未完成，请重试。")) });
    });
}

export function createWxRuntime(wx: WxApi): MiniRuntime & WechatLogin {
    const clean = (identity: { accountId: string; bindingId: string }) => {
        if (wx.env && wx.getFileSystemManager) createInventoryFiles(wx as InventoryFileRuntime, identity).clear();
        else wx.removeStorageSync(`fams.identity.${identity.accountId}.${identity.bindingId}`);
    };
    const pending = wx.getStorageSync("fams.inventory-cleanup") as { accountId?: unknown; bindingId?: unknown } | null;
    if (pending && typeof pending.accountId === "string" && typeof pending.bindingId === "string") {
        clean({ accountId: pending.accountId, bindingId: pending.bindingId }); wx.removeStorageSync("fams.inventory-cleanup");
    }
    return {
        upload: input => new Promise((resolve, reject) => {
            if (!wx.uploadFile) { reject(new Error("Evidence upload unavailable")); return; }
            wx.uploadFile({ ...input, name: "file", timeout: 60000,
                success: reply => {
                    try { resolve({ statusCode: reply.statusCode, data: JSON.parse(reply.data) as unknown }); }
                    catch { reject(new Error("Invalid upload response")); }
                }, fail: reject });
        }),
        download: input => new Promise((resolve, reject) => {
            if (!wx.downloadFile) { reject(new Error("Evidence download unavailable")); return; }
            wx.downloadFile({ ...input, timeout: 60000, success: resolve, fail: reject });
        }),
        request: input => new Promise((resolve, reject) => wx.request({ ...input, timeout: 10000,
            header: { "Content-Type": "application/json", ...input.header }, success: resolve, fail: reject })),
        loginCode: () => new Promise((resolve, reject) => wx.login({ timeout: 10000,
            success: reply => reply.code ? resolve(reply.code) : reject(new Error("WeChat code unavailable")), fail: reject })),
        readSession: () => wx.getStorageSync("fams.session") || null,
        writeSession: (session: SavedSession) => wx.setStorageSync("fams.session", session),
        removeSession: () => wx.removeStorageSync("fams.session"),
        clearIdentityData: identity => {
            wx.setStorageSync("fams.inventory-cleanup", identity);
            clean(identity);
            wx.removeStorageSync("fams.inventory-cleanup");
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
