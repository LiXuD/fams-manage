import type { InventoryQueueStorage } from "./inventory-queue.ts";

interface Stats { size: number; isFile(): boolean }
export interface InventoryFileSystem {
    mkdirSync(path: string, recursive?: boolean): void;
    copyFileSync(source: string, target: string): void;
    statSync(path: string): Stats;
    unlinkSync(path: string): void;
    readdirSync(path: string): string[];
    accessSync(path: string): void;
}
export interface InventoryFileRuntime {
    env: { USER_DATA_PATH: string };
    getFileSystemManager(): InventoryFileSystem;
    getStorageSync(key: string): unknown;
    setStorageSync(key: string, value: unknown): void;
    removeStorageSync(key: string): void;
}
const uuid = /^[0-9a-f-]{36}$/i;
const identityPart = /^[1-9][0-9]*$/;

/** Owned user files survive temporary-file cleanup; all deletions are confined to one identity. */
export function createInventoryFiles(wx: InventoryFileRuntime, identity: { accountId: string; bindingId: string }) {
    if (!identityPart.test(identity.accountId) || !identityPart.test(identity.bindingId)) throw new Error("Invalid inventory file owner");
    const fs = wx.getFileSystemManager();
    const key = `fams.identity.${identity.accountId}.${identity.bindingId}`;
    const root = `${wx.env.USER_DATA_PATH}/fams-inventory-${identity.accountId}-${identity.bindingId}`;
    const owned = (path: string) => path.startsWith(`${root}/`) && !path.slice(root.length + 1).includes("/")
        && !path.includes("..") && !path.includes("\\");
    const exists = (path: string) => {
        try { return owned(path) && fs.statSync(path).isFile(); } catch { return false; }
    };
    const remove = (path: string) => {
        if (!owned(path)) throw new Error("Photo belongs to another identity");
        if (exists(path)) fs.unlinkSync(path);
    };
    const storage: InventoryQueueStorage = {
        read: target => { if (target !== key) throw new Error("Inventory storage owner mismatch"); return wx.getStorageSync(key) || null; },
        write: (target, value) => { if (target !== key) throw new Error("Inventory storage owner mismatch"); wx.setStorageSync(key, value); },
        photoExists: async path => exists(path), removePhoto: async path => remove(path),
    };
    return {
        storage,
        prune(referenced: string[]) {
            try { fs.accessSync(root); } catch { return 0; }
            const retained = new Set(referenced);
            let removed = 0;
            for (const file of fs.readdirSync(root)) {
                const target = `${root}/${file}`; if (!retained.has(target)) { remove(target); removed++; }
            }
            return removed;
        },
        persist(photo: { path: string; name: string; size: number }, eventId: string, photoId: string) {
            if (!uuid.test(eventId) || !uuid.test(photoId) || photo.size <= 0 || photo.size > 10 * 1024 * 1024
                || !/\.(png|jpe?g|webp)$/i.test(photo.name)) throw new Error("Invalid inventory photo");
            const extension = photo.name.split(".").pop()!.toLowerCase();
            const target = `${root}/${eventId}-${photoId}.${extension}`;
            // A repeat cannot overwrite bytes referenced by a possibly submitted event.
            if (exists(target)) throw new Error("Inventory photo identity already exists");
            fs.mkdirSync(root, true);
            try {
                fs.copyFileSync(photo.path, target);
                const stat = fs.statSync(target);
                if (!stat.isFile() || stat.size !== photo.size) throw new Error("Inventory photo copy incomplete");
            } catch (error) { remove(target); throw error; }
            return { path: target, name: photo.name, size: photo.size };
        },
        clear() {
            try { fs.accessSync(root); } catch { wx.removeStorageSync(key); return; }
            const files = fs.readdirSync(root);
            for (const file of files) remove(`${root}/${file}`);
            wx.removeStorageSync(key);
        },
    };
}
