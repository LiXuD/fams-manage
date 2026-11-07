import { apiUrl, requestBlob, requestJson, type Profile } from "@/api/http";
export interface ScrapChoice { id: string; name: string; version: number }
export interface ScrapAsset extends ScrapChoice { assetCode: string }
export interface ScrapRequest {
  id: string; assetId: string; version: number; assetVersion: number; ownershipVersion: number;
  lockVersion: number | null; processorAccountId: string | null; status: string;
  reason: string; observedCondition: string; proposedDisposition: string;
  sourceType?: string;
  sourceBusinessId?: string | null;
  factoryDisposition?: "FACTORY_RETAINED" | "FACTORY_DESTROYED";
  source: { assetCode: string; name: string; serialNumber: string };
  actions: { decide: boolean; claim: boolean; execute: boolean; executeLoss?: boolean; executeFactory?: boolean; dispose?: boolean };
  decision: { decision?: { approvedDisposition?: string } } | null;
  disposition: { disposition_status: string; due_date: string | null; version?: number;
    completion?: { occurredAt: string; method: string; recipient?: string; operatorName?: string } } | null;
}
/** 附件的两类归属：申请原件与处置凭证，路径与能力位都按这一枚举分流。 */
export type ScrapAttachmentKind = "request" | "disposition";
export interface ScrapAttachmentCapabilities {
  canRead: boolean; canUploadRequest: boolean; canUploadDisposition: boolean;
  requestVersion: number; dispositionVersion: number | null;
}
export interface ScrapAttachment {
  objectId: string; filename: string; contentType: string; sizeBytes: number;
}
/** 申请列表与超期清单共用同一页形状；后两个字段只在各自作用域里出现。 */
export interface ScrapListResult {
  items: ScrapRequest[]; total: number; canListOverdueDispositions?: boolean; businessDate?: string;
}
/**
 * 视图起草的「待提交意图」：只带路径、版本、请求体与确认文案。
 * 幂等键属于视图的确认式提交底座，子组件不得自行生成，否则重试换键。
 */
export interface ScrapIntent {
  path: string; version?: number; body: Readonly<Record<string, unknown>>; summary: string; file?: File;
}
/** 底座确认后交给 scrapRequest 的完整意图。 */
export interface ScrapSubmission extends ScrapIntent { key: string }
export class ScrapApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

/** 本域在 /api/v1 下的路径前缀，读写两端同源。 */
const PREFIX = "/scrap-requests";

/** 本域的失败三元组形状：错误码不合形时落回本域兜底码。 */
const profile: Profile<ScrapApiError> = {
  codeFallback: "SCRAP_REQUEST_FAILED",
  failure: ({ status, code }) => new ScrapApiError(status, code),
  csrfMissing: () => new ScrapApiError(401, "AUTH_SESSION_INVALID"),
};

/** 允许的附件媒体类型；选择侧与读回侧同源，PDF 之外按图片上限。 */
export const SCRAP_ATTACHMENT_MIME = ["image/png", "image/jpeg", "image/webp", "application/pdf"];
export function scrapAttachmentSizeLimit(mime: string): number {
  return (mime === "application/pdf" ? 20 : 10) * 1024 * 1024;
}
/** 原件对象标识必须是 UUID；不合形时不得拼进下载路径。 */
const OBJECT_ID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
/** 授权令牌只允许 URL 安全字符且限长。 */
const GRANT_TOKEN = /^[A-Za-z0-9_.-]{1,256}$/;

/**
 * 上传用的 multipart 本体。
 * 这里不套 `fileForm`：它会显式带上 filename，UA 因此新建一个 File 实例；报废上传的「重试复用同一份原件」
 * 要求 FormData 里仍是调用方那一个 File（功能线用例逐字断言对象同一性）。不传 filename 时默认名即
 * `file.name`，线上字节与 Content-Disposition 完全相同。
 */
function attachmentForm(file: File): FormData {
  const form = new FormData();
  form.append("file", file);
  return form;
}

/** 读走 GET；带意图即按写三件套（CSRF + 幂等键 + 可选乐观版本）提交，带文件时改走 multipart。 */
export function scrapRequest<T>(path: string, intent?: ScrapSubmission): Promise<T> {
  return requestJson<T, ScrapApiError>(path, profile, intent
    ? { prefix: PREFIX, method: "POST", key: intent.key, version: intent.version,
        ...(intent.file ? { form: attachmentForm(intent.file) } : { json: intent.body }) }
    : { prefix: PREFIX });
}

/**
 * 报废原件下载：先取一次性授权地址，再按该地址原样读回字节。
 * 活性检查留在视图（它拥有身份与在途代数），本函数只在两次写入点之间询问是否还算数；
 * 读回的媒体类型、归属与体积必须与列表项声明一致，否则视为内容不可信。
 */
export async function scrapOriginal(id: string, kind: ScrapAttachmentKind, item: ScrapAttachment,
    isCurrent: () => boolean): Promise<Blob | null> {
  if (!OBJECT_ID.test(item.objectId)) throw new Error("Invalid attachment identity");
  const path = `/${id}/attachments/${kind}/${item.objectId}`;
  const { url } = await requestJson<{ url: string }, ScrapApiError>(`${path}/download-grant`, profile,
      { prefix: PREFIX, method: "POST", key: crypto.randomUUID(), json: {} });
  if (!isCurrent()) return null;
  const contentPrefix = apiUrl(`${path}/content?grant=`, PREFIX);
  if (typeof url !== "string" || !url.startsWith(contentPrefix) || !GRANT_TOKEN.test(url.slice(contentPrefix.length))) {
    throw new Error("Invalid attachment grant URL");
  }
  const blob = await requestBlob(url, (status) => new ScrapApiError(status, "SCRAP_ATTACHMENT_READ_FAILED"));
  if (!isCurrent()) return null;
  if (!SCRAP_ATTACHMENT_MIME.includes(blob.type) || blob.type !== item.contentType
      || blob.size !== item.sizeBytes || blob.size <= 0
      || blob.size > scrapAttachmentSizeLimit(blob.type)) {
    throw new Error("Invalid attachment content");
  }
  return blob;
}

export const scrapStates: Record<string, string> = {
  PENDING_ADMIN_DECISION: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管认领",
  PROCESSING: "待实物核验与执行", RESTORE_REQUIRED: "原保管关系失效，待纠正",
  REJECTED: "已驳回并恢复", CANCELLED: "已取消", EXECUTED: "已执行报废",
};
export function scrapRequestState(row: Pick<ScrapRequest, "status">): string {
  return scrapStates[row.status] ?? row.status;
}
