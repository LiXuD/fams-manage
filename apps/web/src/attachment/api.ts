import { useSessionStore } from "@/auth/sessionStore";
import { AuthApiError } from "@/auth/api";

export interface Attachment {
  objectId: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  scanStatus: "CLEAN" | "INFECTED" | "UNAVAILABLE";
  businessVersion: number;
  createdAt?: string;
  revisionNumber?: number;
  previousObjectId?: string | null;
  nextObjectId?: string | null;
  correctionReason?: string | null;
}

const messages: Record<string, string> = {
  AUTH_SESSION_INVALID: "会话已失效，请重新登录。",
  AUTH_FORBIDDEN: "当前账号没有附件操作权限。",
  INBOUND_NOT_FOUND: "单据不存在或当前账号不可访问。",
  INBOUND_ATTACHMENT_READ_ONLY: "当前单据为只读，无法追加附件。",
  INBOUND_VERSION_CONFLICT: "单据已更新，请刷新详情后重新操作。",
  INVALID_ATTACHMENT: "附件格式或容量不符合要求，请检查文件和业务总量。",
  ATTACHMENT_IDEMPOTENCY_CONFLICT: "上传请求内容发生变化，请重新选择文件。",
  ATTACHMENT_NOT_CLEAN: "附件尚未通过扫描，暂不可下载。",
  ATTACHMENT_GRANT_INVALID: "下载授权已失效，请重新点击下载。",
  ATTACHMENT_CONTENT_CHANGED: "附件内容校验失败，暂不可下载。",
  ATTACHMENT_STORAGE_UNAVAILABLE: "附件服务暂不可用，请稍后重试。",
  ATTACHMENT_TOO_LARGE: "文件超过上传大小限制。",
};

async function failure(response: Response): Promise<AuthApiError> {
  const body = await response.json().catch(() => ({})) as { code?: string; requestId?: string };
  const message = response.status === 401 ? messages.AUTH_SESSION_INVALID
    : messages[body.code ?? ""] ?? "附件操作未完成，请稍后重试。";
  return new AuthApiError(response.status, body.code ?? "ATTACHMENT_REQUEST_FAILED", message, body.requestId);
}

function csrfHeaders(): Record<string, string> {
  const token = useSessionStore().csrfToken;
  if (!token) throw new AuthApiError(401, "AUTH_SESSION_INVALID", messages.AUTH_SESSION_INVALID);
  return { "X-CSRF-Token": token };
}

export async function listAttachments(receiptId: string): Promise<Attachment[]> {
  const response = await fetch(`/api/v1/inbound-receipts/${encodeURIComponent(receiptId)}/attachments`,
    { credentials: "include" });
  if (!response.ok) throw await failure(response);
  return (await response.json() as { items: Attachment[] }).items;
}

export async function uploadAttachment(receiptId: string, version: number, file: File,
  key: string): Promise<Attachment> {
  const body = new FormData();
  body.append("file", file);
  const response = await fetch(`/api/v1/inbound-receipts/${encodeURIComponent(receiptId)}/attachments`, {
    method: "POST", credentials: "include", body,
    headers: { ...csrfHeaders(), "Idempotency-Key": key, "If-Match-Version": String(version) },
  });
  if (!response.ok) throw await failure(response);
  return await response.json() as Attachment;
}

export async function correctAttachment(receiptId: string, version: number, previousObjectId: string,
  reason: string, file: File, key: string): Promise<Attachment> {
  const body = new FormData();
  body.append("file", file);
  body.append("reason", reason);
  const response = await fetch(`/api/v1/inbound-receipts/${encodeURIComponent(receiptId)}/attachments/${encodeURIComponent(previousObjectId)}/corrections`, {
    method: "POST", credentials: "include", body,
    headers: { ...csrfHeaders(), "Idempotency-Key": key, "If-Match-Version": String(version) },
  });
  if (!response.ok) throw await failure(response);
  return await response.json() as Attachment;
}

export async function downloadAttachment(attachment: Attachment, isCurrent: () => boolean = () => true): Promise<void> {
  if (attachment.scanStatus !== "CLEAN") throw new Error(messages.ATTACHMENT_NOT_CLEAN);
  const id = encodeURIComponent(attachment.objectId);
  const issued = await fetch(`/api/v1/attachments/${id}/download-grant`, {
    method: "POST", credentials: "include", headers: csrfHeaders(),
  });
  if (!isCurrent()) return;
  if (!issued.ok) throw await failure(issued);
  const grant = await issued.json() as { url: string };
  if (!isCurrent()) return;
  const url = new URL(grant.url, window.location.origin);
  if (url.origin !== window.location.origin || url.pathname !== `/api/v1/attachments/${id}/content`) {
    throw new Error("下载地址无效，请刷新后重试。");
  }
  const response = await fetch(url.toString(), { credentials: "include", cache: "no-store" });
  if (!isCurrent()) return;
  if (!response.ok) throw await failure(response);
  const bytes = await response.blob();
  if (!isCurrent()) return;
  const objectUrl = URL.createObjectURL(bytes);
  const link = document.createElement("a");
  link.href = objectUrl;
  link.download = Array.from(attachment.filename, (char) =>
    char.charCodeAt(0) < 32 || char === "/" || char === "\\" ? "_" : char).join("");
  document.body.append(link);
  try { link.click(); }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000); }
}
