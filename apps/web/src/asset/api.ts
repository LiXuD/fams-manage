import { useSessionStore } from "@/auth/sessionStore";

export interface AssetReference { id: string; version: number; code?: string; name?: string; warehouse?: { id: string; version: number } }
export interface AssetCard {
  id: string;
  assetCode: string;
  name: string;
  model?: string | null;
  unit?: string | null;
  lifecycle: string;
  exception: string;
  cardStage: string;
  version: number;
  category?: AssetReference | null;
  organization?: AssetReference | null;
  plannedInboundWarehouse?: AssetReference | null;
  plannedInboundLocation?: AssetReference | null;
  currentCustody?: Record<string, unknown> | null;
  physicalDestination?: { destination: string; status: string; temporaryEmployeeId: string; deliveredAt: string; receiptDeadlineAt: string } | null;
}

export interface Page<T> { page: number; pageSize: number; total: number; items: T[] }
export interface AssetTag { enterpriseName: string; assetName: string; assetCode: string; qrToken: string; qrCode: string }

export class AssetApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message); }
}

async function request<T>(path: string, method = "GET", body?: unknown, version?: number): Promise<T> {
  const session = useSessionStore();
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") {
    if (!session.csrfToken) throw new AssetApiError(403, "AUTH_CSRF_REQUIRED", "请重新登录后操作。");
    headers["X-CSRF-Token"] = session.csrfToken;
    headers["Idempotency-Key"] = crypto.randomUUID();
    if (version !== undefined) headers["If-Match-Version"] = String(version);
  }
  const response = await fetch("/api/v1" + path, {
    method,
    credentials: "include",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { code?: string; message?: string };
    throw new AssetApiError(response.status, error.code ?? "ASSET_REQUEST_FAILED",
      error.message ?? "请求未完成，请稍后重试。");
  }
  return await response.json() as T;
}

export function listAssets(page = 1, code = "", name = ""): Promise<Page<AssetCard>> {
  const query = new URLSearchParams({ page: String(page), pageSize: "20" });
  if (code.trim()) query.set("assetCode", code.trim());
  if (name.trim()) query.set("name", name.trim());
  return request<Page<AssetCard>>("/assets?" + query);
}

export function getAsset(id: string): Promise<AssetCard> {
  return request<AssetCard>("/assets/" + encodeURIComponent(id));
}

export function createAsset(payload: Record<string, unknown>): Promise<AssetCard> {
  return request<AssetCard>("/assets", "POST", payload);
}

export function updateAsset(id: string, version: number, payload: Record<string, unknown>): Promise<AssetCard> {
  return request<AssetCard>("/assets/" + encodeURIComponent(id), "PATCH", payload, version);
}

export function copyAsset(id: string, version: number, quantity: number): Promise<{ assets: AssetCard[] }> {
  return request<{ assets: AssetCard[] }>("/assets/" + encodeURIComponent(id) + "/copy",
    "POST", { quantity }, version);
}

export function cancelAsset(id: string, version: number, reason: string): Promise<AssetCard> {
  return request<AssetCard>("/assets/" + encodeURIComponent(id) + "/cancel",
    "POST", { reason }, version);
}

export function getTag(id: string): Promise<AssetTag> {
  return request<AssetTag>("/assets/" + encodeURIComponent(id) + "/tag");
}

export function reprintTag(id: string, version: number): Promise<{ jobId: string; tag: AssetTag }> {
  return request<{ jobId: string; tag: AssetTag }>("/assets/" + encodeURIComponent(id) + "/tag/reprint",
    "POST", {}, version);
}

export function activeReferences(path: string): Promise<Page<AssetReference>> {
  return request<Page<AssetReference>>(path + "/active-references?page=1&pageSize=200");
}
