import { AuthApiError } from "@/auth/api";
import { useSessionStore } from "@/auth/sessionStore";

export interface ImportTicket {
  id: string; status: string; previewVersion: number; mapping?: Record<string, string> | null
}
export interface ImportCellError {
  sheet: string; sheetRow: number; column: string; original: string; reason: string
}
export interface ImportPreview {
  id: string; version: number; totalRows: number; readyRows: number; errorRows: number;
  errors: ImportCellError[]; moreErrors: boolean;
}
export interface ImportRow {
  sheetRow: number; status: "READY" | "ERROR";
  source: Record<string, string>; resolved?: Record<string, unknown>;
  errors?: ImportCellError[];
}
export interface ImportRowPage { total: number; items: ImportRow[] }
export interface ImportReview {
  batchId: string; status: "PENDING" | "APPROVED" | "REJECTED";
  previewVersion: number; processorAccountId: string; decidedBy?: string | null; jobId?: string | null
}
export interface PendingImportReview {
  batchId: string; previewVersion: number; readyRows: number; errorRows: number;
  operatorUsername: string; requestedAt: string
}
export interface ImportRollback {
  batchId: string; status: "PENDING" | "APPROVED" | "REJECTED" | "QUEUED" | "COMPLETED" | "FAILED";
  version: number; jobId?: string | null
}
export interface PendingImportRollback {
  batchId: string; version: number; assetCount: number;
  operatorUsername: string; reason: string; requestedAt: string
}
export interface InitializationWindow {
  id: string; status: "OPEN" | "CLOSED"; assignedOperatorId: string; expiresAt: string
}
export interface EligibleOperator { id: string; username: string }

const messages: Record<string, string> = {
  AUTH_SESSION_INVALID: "会话已失效，请重新登录。",
  AUTH_FORBIDDEN: "当前账号没有资产导入权限。",
  ASSET_IMPORT_FILE_LIMIT: "单个文件不能超过 20 MB。",
  ASSET_IMPORT_ROW_LIMIT: "单个文件不能超过 20,000 行。",
  ASSET_IMPORT_INVALID_XLSX: "无法读取 XLSX 文件，请检查格式。",
  ASSET_IMPORT_HEADER_INVALID: "表头为空或重复，请检查工作表。",
  ASSET_IMPORT_SHEET_MISSING: "文件中缺少“资产”工作表。",
  ASSET_IMPORT_UPLOAD_CONFLICT: "同一上传请求的文件发生变化，请重新选择文件。",
  ASSET_IMPORT_MAPPING_INVALID: "字段映射无效，请检查列名。",
  ASSET_IMPORT_VERSION_CONFLICT: "预检版本已变化，请刷新批次后重试。",
  ASSET_IMPORT_NOT_FOUND: "批次不存在或当前账号不可访问。",
  ASSET_IMPORT_REVIEW_NOT_FOUND: "尚未提交管理员核验。",
  ASSET_IMPORT_ROLLBACK_NOT_FOUND: "尚未申请整批撤销。",
  ASSET_IMPORT_WINDOW_NOT_FOUND: "当前没有开放的期初窗口。",
  ASSET_IMPORT_WINDOW_CLOSED: "期初窗口已关闭或过期。",
  ASSET_IMPORT_DECISION_INVALID: "请核对正确行、错误行数量并确认抽样核验。",
};

async function failure(response: Response): Promise<AuthApiError> {
  const body = await response.json().catch(() => ({})) as { code?: string; requestId?: string };
  const code = body.code ?? "ASSET_IMPORT_REQUEST_FAILED";
  return new AuthApiError(response.status, code,
    messages[code] ?? "资产导入操作未完成，请稍后重试。", body.requestId);
}

function csrfHeaders(): Record<string, string> {
  const token = useSessionStore().csrfToken;
  if (!token) throw new AuthApiError(401, "AUTH_SESSION_INVALID", messages.AUTH_SESSION_INVALID);
  return { "X-CSRF-Token": token };
}

async function jsonResult<T>(response: Response): Promise<T> {
  if (!response.ok) throw await failure(response);
  return await response.json() as T;
}

export async function downloadImportTemplate(): Promise<void> {
  const response = await fetch("/api/v1/asset-import-batches/template", { credentials: "include" });
  if (!response.ok) throw await failure(response);
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = "fams-assets.xlsx";
  document.body.append(link);
  try { link.click(); }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
}

export async function uploadImport(file: File, key: string): Promise<ImportTicket> {
  const body = new FormData();
  body.append("file", file);
  return jsonResult<ImportTicket>(await fetch("/api/v1/asset-import-batches", {
    method: "POST", credentials: "include", body,
    headers: { ...csrfHeaders(), "Idempotency-Key": key },
  }));
}

export async function getImport(id: string): Promise<ImportTicket> {
  return jsonResult<ImportTicket>(await fetch(`/api/v1/asset-import-batches/${encodeURIComponent(id)}`,
    { credentials: "include" }));
}

export async function importColumns(id: string): Promise<string[]> {
  const result = await jsonResult<{ columns: string[] }>(await fetch(
    `/api/v1/asset-import-batches/${encodeURIComponent(id)}/columns`, { credentials: "include" }));
  return result.columns;
}

export async function previewImport(id: string, version: number,
  mapping: Record<string, string>): Promise<ImportPreview> {
  return jsonResult<ImportPreview>(await fetch(
    `/api/v1/asset-import-batches/${encodeURIComponent(id)}/preview`, {
      method: "POST", credentials: "include", body: JSON.stringify({ mapping }),
      headers: { ...csrfHeaders(), "Content-Type": "application/json", "If-Match-Version": String(version) },
    }));
}

export async function importRows(id: string, status: "READY" | "ERROR",
  page: number): Promise<ImportRowPage> {
  const query = new URLSearchParams({ status, page: String(page), pageSize: "50" });
  return jsonResult<ImportRowPage>(await fetch(
    `/api/v1/asset-import-batches/${encodeURIComponent(id)}/rows?${query}`, { credentials: "include" }));
}

export async function downloadPreflightReport(id: string): Promise<void> {
  const response = await fetch(
    `/api/v1/asset-import-batches/${encodeURIComponent(id)}/preflight-report`,
    { credentials: "include" });
  if (!response.ok) throw await failure(response);
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = `fams-asset-preflight-${id}.csv`;
  document.body.append(link);
  try { link.click(); }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
}

export async function downloadImportResult(id: string): Promise<void> {
  const response = await fetch(`/api/v1/asset-import-batches/${encodeURIComponent(id)}/result`,
    { credentials: "include" });
  if (!response.ok) throw await failure(response);
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = `fams-asset-result-${id}.csv`;
  document.body.append(link);
  try { link.click(); }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
}

const batchPath = (id: string): string => `/api/v1/asset-import-batches/${encodeURIComponent(id)}`;

async function postJson<T>(path: string, body: unknown, extraHeaders = {}): Promise<T> {
  return jsonResult<T>(await fetch(path, { method: "POST", credentials: "include",
    headers: { ...csrfHeaders(), "Content-Type": "application/json", ...extraHeaders },
    body: JSON.stringify(body) }));
}

export async function requestImportReview(id: string, version: number): Promise<ImportReview> {
  return jsonResult<ImportReview>(await fetch(`${batchPath(id)}/review`, {
    method: "POST", credentials: "include",
    headers: { ...csrfHeaders(), "If-Match-Version": String(version) },
  }));
}
export async function ownImportReview(id: string): Promise<ImportReview> {
  return jsonResult<ImportReview>(await fetch(`${batchPath(id)}/review`, { credentials: "include" }));
}
export async function pendingImportReviews(page = 1): Promise<PendingImportReview[]> {
  return jsonResult<PendingImportReview[]>(await fetch(
    `/api/v1/asset-import-batches/reviews/pending?page=${page}`, { credentials: "include" }));
}
export async function reviewImportRows(id: string, status: "READY" | "ERROR", page: number): Promise<ImportRowPage> {
  return jsonResult<ImportRowPage>(await fetch(
    `${batchPath(id)}/review-rows?status=${status}&page=${page}`, { credentials: "include" }));
}
export async function decideImportReview(id: string, approved: boolean, reason: string,
  readyRows: number, errorRows: number, sampleVerified: boolean): Promise<ImportReview> {
  return postJson<ImportReview>(`${batchPath(id)}/decision`, {
    approved, reason, confirmedReadyRows: readyRows, confirmedErrorRows: errorRows, sampleVerified,
  });
}
export async function requestImportRollback(id: string, reason: string): Promise<ImportRollback> {
  return postJson<ImportRollback>(`${batchPath(id)}/rollback`, { reason });
}
export async function ownImportRollback(id: string): Promise<ImportRollback> {
  return jsonResult<ImportRollback>(await fetch(`${batchPath(id)}/rollback`, { credentials: "include" }));
}
export async function pendingImportRollbacks(page = 1): Promise<PendingImportRollback[]> {
  return jsonResult<PendingImportRollback[]>(await fetch(
    `/api/v1/asset-import-batches/rollbacks/pending?page=${page}`, { credentials: "include" }));
}
export async function decideImportRollback(id: string, approved: boolean, reason: string): Promise<ImportRollback> {
  return postJson<ImportRollback>(`${batchPath(id)}/rollback-decision`, { approved, reason });
}
export async function queueImportRollback(id: string): Promise<ImportRollback> {
  return jsonResult<ImportRollback>(await fetch(`${batchPath(id)}/rollback-execute`, {
    method: "POST", credentials: "include", headers: csrfHeaders(),
  }));
}
export async function eligibleImportOperators(): Promise<EligibleOperator[]> {
  return jsonResult<EligibleOperator[]>(await fetch("/api/v1/asset-initialization-windows/operators",
    { credentials: "include" }));
}
export async function currentImportWindow(): Promise<InitializationWindow> {
  return jsonResult<InitializationWindow>(await fetch("/api/v1/asset-initialization-windows/current",
    { credentials: "include" }));
}
export async function openImportWindow(operatorId: string, reason: string, expiresAt: string): Promise<InitializationWindow> {
  return postJson<InitializationWindow>("/api/v1/asset-initialization-windows", {
    assignedOperatorId: operatorId, reason, expiresAt,
  });
}
export async function closeImportWindow(id: string): Promise<InitializationWindow> {
  return jsonResult<InitializationWindow>(await fetch(
    `/api/v1/asset-initialization-windows/${encodeURIComponent(id)}/close`, {
      method: "POST", credentials: "include", headers: csrfHeaders(),
    }));
}
