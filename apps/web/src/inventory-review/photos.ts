import { useSessionStore } from "@/auth/sessionStore";
import { reviewRequest, ReviewApiError } from "./api";

export interface InventoryPhoto {
  objectId: string; filename: string; contentType: string; sizeBytes: number;
  scanStatus: string; createdAt?: string;
}
export interface PhotoReference { objectId: string; photo: InventoryPhoto | null }
export class InventoryPhotoUnavailable extends Error {
  constructor() { super("原始照片暂不可读取，引用仍保留。"); }
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const imageTypes = new Set(["image/jpeg", "image/png", "image/webp"]);
function scope(task: string, event: string) {
  if (!/^[1-9][0-9]{0,18}$/.test(task) || !uuid.test(event)) throw new Error("照片记录无效，请重新读取原始证据。");
  return `/inventory-observations/tasks/${task}/events/${event}/photos`;
}
export function readablePhoto(photo: InventoryPhoto): boolean {
  return uuid.test(photo.objectId) && photo.scanStatus === "CLEAN" && imageTypes.has(photo.contentType)
    && Number.isSafeInteger(photo.sizeBytes) && photo.sizeBytes > 0 && photo.sizeBytes <= 10 * 1024 * 1024;
}
function freshness(isCurrent: () => boolean): () => void {
  const session = useSessionStore();
  const identity = () => JSON.stringify([session.user?.id, session.csrfToken, session.user?.roles]);
  const captured = identity();
  return () => { if (!isCurrent() || identity() !== captured) throw new Error("照片读取已取消，请在当前身份和记录下重新读取。"); };
}
async function revalidateMissing(task: string, cause: unknown, check: () => void): Promise<void> {
  check();
  if (!(cause instanceof ReviewApiError) || cause.status !== 404
    || !["INVENTORY_PHOTO_NOT_FOUND", "ATTACHMENT_NOT_FOUND"].includes(cause.code)) throw cause;
  await reviewRequest(`/inventory-reviews/tasks/${task}?page=1&pageSize=1`);
  check();
}
export async function listInventoryPhotos(task: string, event: string, references: readonly string[], isCurrent: () => boolean = () => true): Promise<PhotoReference[]> {
  const check = freshness(isCurrent); check();
  const path = scope(task, event);
  if (!references.every(value => uuid.test(value))) throw new Error("原始照片引用无效，请核对记录。");
  if (!references.length) return [];
  let result: { items: InventoryPhoto[] };
  try { result = await reviewRequest<{ items: InventoryPhoto[] }>(path); }
  catch (cause) { await revalidateMissing(task, cause, check); return references.map(objectId => ({ objectId, photo: null })); }
  check();
  const rows = new Map(result.items.map(row => [row.objectId.toLowerCase(), row]));
  // Unused uploads in the event scope never become original observation evidence.
  return references.map(objectId => ({ objectId, photo: rows.get(objectId.toLowerCase()) ?? null }));
}
export async function readInventoryPhoto(task: string, event: string, photo: InventoryPhoto, isCurrent: () => boolean = () => true): Promise<Blob> {
  const check = freshness(isCurrent); check();
  if (!readablePhoto(photo)) throw new Error("照片未通过扫描或格式不可读取，原始引用仍保留。");
  const path = `${scope(task, event)}/${photo.objectId}`;
  const grantPath = path + "/download-grant";
  const grant = await reviewRequest<{ url: string }>(grantPath, { path: grantPath, key: crypto.randomUUID(), version: 0 })
    .catch(async cause => { await revalidateMissing(task, cause, check); throw new InventoryPhotoUnavailable(); });
  check();
  const url = new URL(grant.url, window.location.origin);
  const token = url.searchParams.get("grant");
  if (url.origin !== window.location.origin || url.pathname !== "/api/v1" + path + "/content"
    || url.hash || url.username || url.password || [...url.searchParams.keys()].length !== 1
    || !token || !/^[A-Za-z0-9_.-]{1,256}$/.test(token)) throw new Error("照片读取地址无效，请重新核对。");
  const response = await fetch(url.toString(), { credentials: "include", cache: "no-store" });
  check();
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { code?: unknown };
    await revalidateMissing(task, new ReviewApiError(response.status, typeof error.code === "string" ? error.code : "INVENTORY_PHOTO_READ_FAILED", {}), check);
    throw new InventoryPhotoUnavailable();
  }
  const type = response.headers.get("Content-Type")?.split(";")[0]?.trim().toLowerCase();
  if (!type || !imageTypes.has(type) || type !== photo.contentType) throw new Error("照片内容类型不一致，暂不可读取。");
  const blob = await response.blob();
  check();
  if (blob.size !== photo.sizeBytes) throw new Error("照片内容容量不一致，请重新核对原始证据。");
  return blob;
}
