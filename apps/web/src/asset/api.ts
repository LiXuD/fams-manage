import { requestJson, type HttpMethod, type Page, type Profile } from "@/api/http";

/** 分页形状统一由共享底座定义；此处仅重导出，保持既有 `import type { Page } from "@/asset/api"` 口径。 */
export type { Page };

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

export interface AssetTag { enterpriseName: string; assetName: string; assetCode: string; qrToken: string; qrCode: string }

export const lifecycleLabels: Record<string, string> = { WAITING_INBOUND: "待入库", IN_STOCK: "在库", IN_USE: "在用",
  BORROWED: "借出", UNDER_REPAIR: "维修中", FACTORY_REPAIR: "返厂维修", PENDING_SCRAP: "待报废", SCRAPPED: "已报废" };
export const cardStageLabels: Record<string, string> = { DRAFT: "草稿", PENDING_INBOUND: "待验收入库", ACTIVE: "已生效",
  CANCELLED: "已取消" };
export const destinationLabels: Record<string, string> = { DELIVERED_WAITING_RECEIPT: "已交出待接收",
  RECOVERY_PENDING_CANCEL: "待收回取消" };
export const exceptionLabels: Record<string, string> = { NORMAL: "正常", SEARCHING: "查找中", LOST: "已遗失" };

export class AssetApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message); }
}

const profile: Profile<AssetApiError> = {
  codeFallback: "ASSET_REQUEST_FAILED",
  // 服务端未给 message 时沿用本域既有兜底文案，本批不动用户可见文案。
  failure: ({ status, code, message }) => new AssetApiError(status, code, message ?? "请求未完成，请稍后重试。"),
  csrfMissing: () => new AssetApiError(403, "AUTH_CSRF_REQUIRED", "请重新登录后操作。"),
};

/** 本域写操作历史上每次新建幂等键（无跨请求复用语义），交到底座按缺省新建。 */
function request<T>(path: string, method: HttpMethod = "GET", body?: unknown, version?: number): Promise<T> {
  return requestJson<T, AssetApiError>(path, profile, { method, json: body, version });
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

export function custodyLabel(asset: AssetCard): string {
  const custody = asset.currentCustody;
  if (!custody) return "尚未入库";
  const referenceId = (value: unknown): string => {
    if (typeof value !== "object" || value === null || !("id" in value)) return "—";
    return String(value.id);
  };
  if (custody.custodyType === "STORAGE") return `在库 · 仓库 ${referenceId(custody.warehouse)}`;
  if (custody.custodyType === "SHARED") return `部门公用 · 责任人 ${referenceId(custody.departmentResponsibleEmployee)}`;
  return `个人保管 · 责任人 ${referenceId(custody.responsibleEmployee)}`;
}
