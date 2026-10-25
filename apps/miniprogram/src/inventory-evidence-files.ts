import type { InventoryFileRuntime } from "./inventory-files.ts";

export interface InventoryEvidenceOwner { accountId: string; bindingId: string }
interface Reservation { path: string; ownerKey: string; epoch: number }
const identityPart = /^[1-9][0-9]{0,18}$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Read-only evidence files never share the durable collection queue's pruning directory. */
export function createInventoryEvidenceFiles(wx: InventoryFileRuntime, scene: "inventory" | "factory" = "inventory") {
    if (!["inventory", "factory"].includes(scene)) throw new Error("凭证文件范围无效。");
    const registryKey = `fams.${scene}-evidence-owners`;
    const extensions = new Map([["image/jpeg", "jpg"], ["image/png", "png"], ["image/webp", "webp"]]);
    if (scene === "factory") extensions.set("application/pdf", "pdf");
    const filename = new RegExp(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp${scene === "factory" ? "|pdf" : ""})$`, "i");
    const fs = wx.getFileSystemManager(), base = wx.env.USER_DATA_PATH.replace(/\/$/, "");
    const prefix = `${base}/fams-${scene}-evidence-`, epochs = new Map<string, number>();
    const pendingCleanup = new Map<string, InventoryEvidenceOwner>();
    const validOwner = (owner: InventoryEvidenceOwner) => typeof owner?.accountId === "string" && identityPart.test(owner.accountId)
        && typeof owner.bindingId === "string" && identityPart.test(owner.bindingId);
    const key = (owner: InventoryEvidenceOwner) => {
        if (!validOwner(owner)) throw new Error("照片文件身份无效。");
        return `${owner.accountId}-${owner.bindingId}`;
    };
    const root = (owner: InventoryEvidenceOwner) => prefix + key(owner);
    const owners = (): InventoryEvidenceOwner[] => {
        const value = wx.getStorageSync(registryKey);
        if (value === undefined || value === null || value === "") return [];
        if (!Array.isArray(value) || value.some(row => !validOwner(row))) throw new Error("照片清理记录无法读取，请重新进入后重试。");
        return value.map(row => ({ accountId: row.accountId as string, bindingId: row.bindingId as string }));
    };
    const directoryExists = (owner: InventoryEvidenceOwner) => fs.readdirSync(base).includes(`fams-${scene}-evidence-${key(owner)}`);
    const pathOwner = (path: string) => {
        if (!path.startsWith(prefix)) throw new Error("不能清理其他业务的文件。");
        const parts = path.slice(prefix.length).split("/");
        if (parts.length !== 2 || !filename.test(parts[1]!)) throw new Error("照片文件路径无效。");
        const identity = parts[0]!.split("-");
        if (identity.length !== 2) throw new Error("照片文件身份无效。");
        const owner = { accountId: identity[0]!, bindingId: identity[1]! }; key(owner);
        return { owner, name: parts[1]! };
    };
    const discard = (path: string) => {
        const { owner, name } = pathOwner(path);
        try {
            if (!directoryExists(owner) || !fs.readdirSync(root(owner)).includes(name)) return;
            if (!fs.statSync(path).isFile()) throw new Error("照片清理路径不是文件。");
            fs.unlinkSync(path);
        } catch (error) { pendingCleanup.set(key(owner), owner); throw error; }
    };
    const clear = (owner: InventoryEvidenceOwner) => {
        const ownerKey = key(owner); epochs.set(ownerKey, (epochs.get(ownerKey) ?? 0) + 1);
        pendingCleanup.set(ownerKey, owner);
        if (directoryExists(owner)) for (const name of fs.readdirSync(root(owner))) discard(`${root(owner)}/${name}`);
        pendingCleanup.delete(ownerKey);
        // Keep the registered owner until startup: a late old download may still write this root.
    };
    const verify = (reservation: Reservation, expectedSize?: number) => {
        const owner = pathOwner(reservation.path).owner;
        if (key(owner) !== reservation.ownerKey || (epochs.get(reservation.ownerKey) ?? 0) !== reservation.epoch) {
            discard(reservation.path); throw new Error("照片读取期间身份已变化。");
        }
        const stat = fs.statSync(reservation.path), limit = scene === "factory" && reservation.path.endsWith(".pdf") ? 20 : 10;
        if (!stat.isFile() || !Number.isSafeInteger(stat.size) || stat.size <= 0 || stat.size > limit * 1024 * 1024
            || expectedSize !== undefined && (!Number.isSafeInteger(expectedSize) || stat.size !== expectedSize)) {
            discard(reservation.path); throw new Error("凭证容量无效或与原件不符。");
        }
    };
    const reserve = (owner: InventoryEvidenceOwner, nonce: string, mime: string): Reservation => {
        const ownerKey = key(owner), extension = extensions.get(mime);
        if (!uuid.test(nonce) || !extension) throw new Error("照片格式或文件编号无效。");
        for (const previous of [...pendingCleanup.values()]) clear(previous);
        const registered = owners();
        if (!registered.some(row => key(row) === ownerKey)) {
            wx.setStorageSync(registryKey, [...registered, { accountId: owner.accountId, bindingId: owner.bindingId }]);
        }
        fs.mkdirSync(root(owner), true);
        const path = `${root(owner)}/${nonce}.${extension}`;
        if (fs.readdirSync(root(owner)).includes(`${nonce}.${extension}`)) throw new Error("照片读取文件编号已存在。");
        return { path, ownerKey, epoch: epochs.get(ownerKey) ?? 0 };
    };
    const fileMime = (name: string) => ({ jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", webp: "image/webp", pdf: "application/pdf" } as Record<string, string>)[name.split(".").pop()?.toLowerCase() ?? ""];
    return {
        cleanupAbandoned() {
            for (const owner of owners()) clear(owner);
            wx.removeStorageSync(registryKey);
        },
        reserve, verify,
        stage(owner: InventoryEvidenceOwner, nonce: string, file: { path: string; name: string; size: number }) {
            const mime = fileMime(file.name);
            if (scene !== "factory" || !file.path || !file.name.trim() || file.name.length > 255 || /[/\\]/.test(file.name)
                || Array.from(file.name).some(c => c.charCodeAt(0) < 32 || c.charCodeAt(0) >= 127 && c.charCodeAt(0) <= 159)
                || !mime || !Number.isSafeInteger(file.size) || file.size <= 0 || file.size > (mime === "application/pdf" ? 20 : 10) * 1024 * 1024) throw new Error("返厂原件格式或容量无效。");
            const reservation = reserve(owner, nonce, mime);
            try { fs.copyFileSync(file.path, reservation.path); verify(reservation, file.size); }
            catch (error) { discard(reservation.path); throw error; }
            return Object.freeze({ path: reservation.path, name: file.name, size: file.size, contentType: mime });
        },
        verifyUpload(owner: InventoryEvidenceOwner, file: { path: string; name: string; size: number; contentType: string }) {
            const actual = pathOwner(file.path).owner, ownerKey = key(owner);
            if (scene !== "factory" || key(actual) !== ownerKey || fileMime(file.name) !== file.contentType
                || !file.path.endsWith("." + extensions.get(file.contentType))) throw new Error("返厂原件不属于当前身份。");
            if (pendingCleanup.has(ownerKey)) clear(owner);
            verify({ path: file.path, ownerKey, epoch: epochs.get(ownerKey) ?? 0 }, file.size);
        },
        clear, discard,
    };
}
