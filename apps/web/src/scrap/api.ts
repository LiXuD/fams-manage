import { useSessionStore } from "@/auth/sessionStore";
export interface ScrapChoice { id: string; name: string; version: number }
export interface ScrapAsset extends ScrapChoice { assetCode: string }
export interface ScrapRequest {
  id: string; assetId: string; version: number; assetVersion: number; ownershipVersion: number;
  lockVersion: number | null; processorAccountId: string | null; status: string;
  reason: string; observedCondition: string; proposedDisposition: string;
  sourceType?: string;
  source: { assetCode: string; name: string; serialNumber: string };
  actions: { decide: boolean; claim: boolean; execute: boolean; executeLoss?: boolean; dispose?: boolean };
  decision: { decision?: { approvedDisposition?: string } } | null;
  disposition: { disposition_status: string; due_date: string | null; version?: number;
    completion?: { occurredAt: string; method: string; recipient: string; operatorName: string } } | null;
}
export type ScrapAttachmentKind = "request" | "disposition";
export interface ScrapAttachmentCapabilities {
  canRead: boolean; canUploadRequest: boolean; canUploadDisposition: boolean;
  requestVersion: number; dispositionVersion: number | null;
}
export interface ScrapAttachment {
  objectId: string; filename: string; contentType: string; sizeBytes: number;
}
export interface ScrapIntent {
  path: string; key: string; version?: number; body: Readonly<Record<string, unknown>>; summary: string; file?: File;
}
export class ScrapApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export async function scrapRequest<T>(path: string, intent?: ScrapIntent): Promise<T> {
  const headers: Record<string, string> = {};
  let body: BodyInit | undefined;
  if (intent) {
    const token = useSessionStore().csrfToken;
    if (!token) throw new ScrapApiError(401, "AUTH_SESSION_INVALID");
    headers["X-CSRF-Token"] = token;
    headers["Idempotency-Key"] = intent.key;
    if (intent.version !== undefined) headers["If-Match-Version"] = String(intent.version);
    if (intent.file) {
      const multipart = new FormData();
      multipart.append("file", intent.file);
      body = multipart;
    } else {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(intent.body);
    }
  }
  const response = await fetch("/api/v1/scrap-requests" + path, {
    credentials: "include", method: intent ? "POST" : "GET", headers, body,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { code?: string };
    throw new ScrapApiError(response.status, error.code ?? "SCRAP_REQUEST_FAILED");
  }
  return await response.json() as T;
}
export async function scrapOriginal(id: string, kind: ScrapAttachmentKind, item: ScrapAttachment,
    isCurrent: () => boolean): Promise<Blob | null> {
  const csrf = useSessionStore().csrfToken;
  if (!csrf) throw new ScrapApiError(401, "AUTH_SESSION_INVALID");
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(item.objectId)) throw new Error("Invalid attachment identity");
  const path = "/api/v1/scrap-requests/" + id + "/attachments/" + kind + "/" + item.objectId;
  const grant = await fetch(path + "/download-grant", {
    method: "POST", credentials: "include", headers: { "X-CSRF-Token": csrf },
  });
  if (!grant.ok) throw new ScrapApiError(grant.status, "SCRAP_ATTACHMENT_GRANT_FAILED");
  const { url } = await grant.json() as { url: string };
  if (!isCurrent()) return null;
  const prefix = path + "/content?grant=";
  if (typeof url !== "string" || !url.startsWith(prefix) || !/^[A-Za-z0-9_.-]{1,256}$/.test(url.slice(prefix.length))) {
    throw new Error("Invalid attachment grant URL");
  }
  const response = await fetch(url, { credentials: "include", cache: "no-store", redirect: "error" });
  if (!response.ok) throw new ScrapApiError(response.status, "SCRAP_ATTACHMENT_READ_FAILED");
  const blob = await response.blob();
  if (!isCurrent()) return null;
  if (!["image/png", "image/jpeg", "image/webp", "application/pdf"].includes(blob.type)
      || blob.type !== item.contentType || blob.size !== item.sizeBytes || blob.size <= 0
      || blob.size > (blob.type === "application/pdf" ? 20 : 10) * 1024 * 1024) {
    throw new Error("Invalid attachment content");
  }
  return blob;
}
export const scrapStates: Record<string, string> = {
  PENDING_ADMIN_DECISION: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管认领",
  PROCESSING: "待实物核验与执行", RESTORE_REQUIRED: "原保管关系失效，待纠正",
  REJECTED: "已驳回并恢复", CANCELLED: "已取消", EXECUTED: "已执行报废",
};
