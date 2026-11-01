import { apiUrl, fileForm, mutationHeaders, sessionCsrfToken } from "@/api/http";
import { AuthApiError } from "@/auth/api";

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

export const importReviewLabels: Record<string, string> = { PENDING: "待管理员核验", APPROVED: "已批准发布", REJECTED: "已驳回核验" };
export const importRollbackLabels: Record<string, string> = { PENDING: "待管理员批准", APPROVED: "已批准，待执行",
  REJECTED: "已驳回", QUEUED: "撤销任务排队中", COMPLETED: "已撤销", FAILED: "撤销执行失败" };

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

// 本域错误类是 auth 的 AuthApiError，其第四参 requestId 必须由响应体直接取；底座的 Failure 三元组不承载
// requestId，故失败体留在域内读取，错误构造与文案映射逐字保持原样。
async function failure(response: Response): Promise<AuthApiError> {
  const body = await response.json().catch(() => ({})) as { code?: string; requestId?: string };
  const code = body.code ?? "ASSET_IMPORT_REQUEST_FAILED";
  return new AuthApiError(response.status, code,
    messages[code] ?? "资产导入操作未完成，请稍后重试。", body.requestId);
}

function csrfToken(): string {
  const token = sessionCsrfToken();
  if (!token) throw new AuthApiError(401, "AUTH_SESSION_INVALID", messages.AUTH_SESSION_INVALID);
  return token;
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(apiUrl(path), { credentials: "include" });
  if (!response.ok) throw await failure(response);
  return await response.json() as T;
}

// 本域只有上传端点声明了 @RequestHeader("Idempotency-Key")，预检、核验、撤销与期初窗口等写操作历史上不发幂等键，
// 因此不走 mutationHeaders 的默认新建，请求头集合按既有口径逐字保留。
async function postJson<T>(path: string, body?: unknown, version?: number): Promise<T> {
  const headers: Record<string, string> = { "X-CSRF-Token": csrfToken() };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (version !== undefined) headers["If-Match-Version"] = String(version);
  const response = await fetch(apiUrl(path), {
    method: "POST", credentials: "include", headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw await failure(response);
  return await response.json() as T;
}

const batchPath = (id: string): string => `/asset-import-batches/${encodeURIComponent(id)}`;

async function downloadBlob(path: string, filename: string): Promise<void> {
  const response = await fetch(apiUrl(path), { credentials: "include" });
  if (!response.ok) throw await failure(response);
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  try { link.click(); }
  finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
}

export async function downloadImportTemplate(): Promise<void> {
  await downloadBlob("/asset-import-batches/template", "fams-assets.xlsx");
}

export async function uploadImport(file: File, key: string): Promise<ImportTicket> {
  const headers = mutationHeaders({ token: csrfToken(), key });
  const response = await fetch(apiUrl("/asset-import-batches"), {
    method: "POST", credentials: "include", body: fileForm(file), headers,
  });
  if (!response.ok) throw await failure(response);
  return await response.json() as ImportTicket;
}

export async function getImport(id: string): Promise<ImportTicket> {
  return getJson<ImportTicket>(batchPath(id));
}

export async function importColumns(id: string): Promise<string[]> {
  const result = await getJson<{ columns: string[] }>(`${batchPath(id)}/columns`);
  return result.columns;
}

export async function previewImport(id: string, version: number,
  mapping: Record<string, string>): Promise<ImportPreview> {
  return postJson<ImportPreview>(`${batchPath(id)}/preview`, { mapping }, version);
}

export async function importRows(id: string, status: "READY" | "ERROR",
  page: number): Promise<ImportRowPage> {
  const query = new URLSearchParams({ status, page: String(page), pageSize: "50" });
  return getJson<ImportRowPage>(`${batchPath(id)}/rows?${query}`);
}

export async function downloadPreflightReport(id: string): Promise<void> {
  await downloadBlob(`${batchPath(id)}/preflight-report`, `fams-asset-preflight-${id}.csv`);
}

export async function downloadImportResult(id: string): Promise<void> {
  await downloadBlob(`${batchPath(id)}/result`, `fams-asset-result-${id}.csv`);
}

export async function requestImportReview(id: string, version: number): Promise<ImportReview> {
  return postJson<ImportReview>(`${batchPath(id)}/review`, undefined, version);
}
export async function ownImportReview(id: string): Promise<ImportReview> {
  return getJson<ImportReview>(`${batchPath(id)}/review`);
}
export async function pendingImportReviews(page = 1): Promise<PendingImportReview[]> {
  return getJson<PendingImportReview[]>(`/asset-import-batches/reviews/pending?page=${page}`);
}
export async function reviewImportRows(id: string, status: "READY" | "ERROR", page: number): Promise<ImportRowPage> {
  return getJson<ImportRowPage>(`${batchPath(id)}/review-rows?status=${status}&page=${page}`);
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
  return getJson<ImportRollback>(`${batchPath(id)}/rollback`);
}
export async function pendingImportRollbacks(page = 1): Promise<PendingImportRollback[]> {
  return getJson<PendingImportRollback[]>(`/asset-import-batches/rollbacks/pending?page=${page}`);
}
export async function decideImportRollback(id: string, approved: boolean, reason: string): Promise<ImportRollback> {
  return postJson<ImportRollback>(`${batchPath(id)}/rollback-decision`, { approved, reason });
}
export async function queueImportRollback(id: string): Promise<ImportRollback> {
  return postJson<ImportRollback>(`${batchPath(id)}/rollback-execute`);
}
export async function eligibleImportOperators(): Promise<EligibleOperator[]> {
  return getJson<EligibleOperator[]>("/asset-initialization-windows/operators");
}
export async function currentImportWindow(): Promise<InitializationWindow> {
  return getJson<InitializationWindow>("/asset-initialization-windows/current");
}
export async function openImportWindow(operatorId: string, reason: string, expiresAt: string): Promise<InitializationWindow> {
  return postJson<InitializationWindow>("/asset-initialization-windows", {
    assignedOperatorId: operatorId, reason, expiresAt,
  });
}
export async function closeImportWindow(id: string): Promise<InitializationWindow> {
  return postJson<InitializationWindow>(`/asset-initialization-windows/${encodeURIComponent(id)}/close`);
}
