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
