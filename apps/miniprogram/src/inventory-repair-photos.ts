import type { MiniSessionClient } from "./session.ts";
import { photoReferences, type ReviewPhoto } from "./inventory-review-photos.ts";

export function repairPhotoScope(repair: string, observation: string) {
    if (![repair, observation].every(value => /^[1-9][0-9]{0,18}$/.test(value))) throw new Error("维修来源编号无效。");
    return `/api/v1/mini/inventory-repair-evidence/${repair}/observations/${observation}/photos`;
}
export async function readRepairInventoryPhoto(sessions: MiniSessionClient, repair: string, observation: string,
    photo: ReviewPhoto, createKey: () => string, isCurrent: () => boolean) {
    const identity = JSON.stringify(sessions.identity);
    const current = () => { if (!isCurrent() || identity !== JSON.stringify(sessions.identity)) throw new Error("照片读取期间身份或记录已变化。"); };
    current();
    if (!photoReferences([photo.objectId], [photo])[0]?.readable) throw new Error("原始照片尚不可读取。");
    const path = `${repairPhotoScope(repair, observation)}/${photo.objectId}`;
    const grant = await sessions.request("POST", path + "/download-grant", undefined, { key: createKey(), version: 0 }) as { url: string };
    current();
    const expected = path + "/content?grant=";
    if (typeof grant.url !== "string" || !grant.url.startsWith(expected) || !/^[A-Za-z0-9_.-]{1,256}$/.test(grant.url.slice(expected.length))) throw new Error("原图下载地址无效。");
    return await sessions.downloadInventoryPhoto(grant.url, photo.contentType);
}
