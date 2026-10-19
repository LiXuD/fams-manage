import { MiniRequestError, type MiniSessionClient } from "./session.ts";

export interface ReviewPhoto { objectId: string; filename: string; contentType: string; sizeBytes: number; scanStatus: string }
export interface ReviewPhotoReference { objectId: string; photo: ReviewPhoto | null; readable: boolean; key: string }
export class ReviewPhotoUnavailable extends Error {
    constructor() { super("原始照片暂不可读取，引用仍保留。"); }
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function base(task: string, event: string) {
    if (!/^[1-9][0-9]{0,18}$/.test(task) || !uuid.test(event)) throw new Error("照片记录无效。");
    return `/api/v1/mini/inventory-observations/tasks/${task}/events/${event}/photos`;
}
const owner = (sessions: MiniSessionClient) => JSON.stringify(sessions.identity);
function unchanged(sessions: MiniSessionClient, identity: string, isCurrent: () => boolean) {
    if (!isCurrent() || owner(sessions) !== identity) throw new Error("照片读取期间身份已变化。");
}
export function photoReferences(ids: readonly string[], photos: readonly ReviewPhoto[]): ReviewPhotoReference[] {
    if (!ids.every(id => uuid.test(id))) throw new Error("原始照片引用无效。");
    return ids.map((objectId, index) => {
        const photo = photos.find(row => row.objectId.toLowerCase() === objectId.toLowerCase()) ?? null;
        const readable = !!photo && photo.scanStatus === "CLEAN" && ["image/jpeg", "image/png", "image/webp"].includes(photo.contentType)
            && Number.isSafeInteger(photo.sizeBytes) && photo.sizeBytes > 0 && photo.sizeBytes <= 10 * 1024 * 1024;
        return { objectId, photo, readable, key: `${index}:${objectId}` };
    });
}
async function revalidateMissing(sessions: MiniSessionClient, task: string, cause: unknown, identity: string, isCurrent: () => boolean) {
    if (!(cause instanceof MiniRequestError) || cause.status !== 404) throw cause;
    unchanged(sessions, identity, isCurrent);
    await sessions.request("GET", `/api/v1/mini/inventory-reviews/tasks/${task}?page=1&pageSize=1`);
    unchanged(sessions, identity, isCurrent);
}
export async function listReviewPhotos(sessions: MiniSessionClient, task: string, event: string, ids: readonly string[], isCurrent: () => boolean = () => true) {
    const path = base(task, event), identity = owner(sessions); photoReferences(ids, []);
    unchanged(sessions, identity, isCurrent);
    if (!ids.length) return [];
    try {
        const response = await sessions.request("GET", path) as { items: ReviewPhoto[] }; unchanged(sessions, identity, isCurrent);
        return photoReferences(ids, response.items);
    } catch (cause) { await revalidateMissing(sessions, task, cause, identity, isCurrent); return photoReferences(ids, []); }
}
export async function readReviewPhoto(sessions: MiniSessionClient, task: string, event: string, photo: ReviewPhoto, createKey: () => string, isCurrent: () => boolean = () => true) {
    const path = `${base(task, event)}/${photo.objectId}`, identity = owner(sessions);
    if (!photoReferences([photo.objectId], [photo])[0]?.readable) throw new Error("照片未通过扫描或格式不可读取。");
    unchanged(sessions, identity, isCurrent);
    try {
        const grant = await sessions.request("POST", path + "/download-grant", undefined, { key: createKey(), version: 0 }) as { url: string };
        unchanged(sessions, identity, isCurrent);
        const expected = path + "/content?grant=";
        if (typeof grant.url !== "string" || !grant.url.startsWith(expected) || !/^[A-Za-z0-9_.-]{1,256}$/.test(grant.url.slice(expected.length))) throw new Error("照片读取地址无效。");
        return await sessions.downloadInventoryPhoto(grant.url, photo.contentType);
    } catch (cause) { await revalidateMissing(sessions, task, cause, identity, isCurrent); throw new ReviewPhotoUnavailable(); }
}
