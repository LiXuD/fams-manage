import { requestJson, type HttpMethod, type Profile } from "@/api/http";
import type { RequisitionCatalog, RequisitionChoice } from "@fams/api-types/requisition";

export const requisitionLabels: Record<string, string> = { DRAFT: "草稿", PENDING_ADMIN_DECISION: "待管理员确认", REJECTED: "已驳回",
  WAITING_WAREHOUSE_CLAIM: "待库管领取", WAREHOUSE_PROCESSING: "库管处理中", WAITING_HANDOVER: "待交接",
  PARTIALLY_COMPLETED: "部分完成", COMPLETED: "已完成", UNFULFILLABLE: "无法满足", CANCELLED: "已取消" };
export const allocationStatusLabels: Record<string, string> = { WAITING_DELIVERY: "待交出",
  DELIVERED_WAITING_RECEIPT: "已交出待接收", RECEIVED: "已接收", RECOVERY_PENDING_CANCEL: "待收回取消", CANCELLED: "已取消" };
export const handoverEventLabels: Record<string, string> = { DELIVERED: "已交出", RECEIVED: "已接收",
  REFUSED: "接收人反馈不符", RECOVERY_REQUESTED: "已登记收回", RECOVERED: "已收回验收", CANCELLED: "已取消",
  PROXY_RECEIVED: "代确认签收", TARGET_CHANGED: "已批准目标变更" };

export async function loadRequisitionCatalogs(): Promise<{ catalogs: Record<string, RequisitionChoice[]>;
  canShared: boolean; employeeId: string; organizationId: string }> {
  const catalogs: Record<string, RequisitionChoice[]> = {};
  let canShared = false; let employeeId = ""; let organizationId = "";
  for (const kind of ["category", "organization", "employee", "location"]) {
    const items: RequisitionChoice[] = []; let catalogPage = 1; let result: RequisitionCatalog;
    do { result = await requisitionRequest<RequisitionCatalog>(`/catalog/${kind}?page=${catalogPage++}&pageSize=200`);
      items.push(...result.items); } while (result.items.length === 200);
    catalogs[kind] = items; canShared = result.canApplyShared; employeeId = result.employeeId; organizationId = result.organizationId;
  }
  return { catalogs, canShared, employeeId, organizationId };
}

export class RequisitionApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export function requisitionMessage(error: unknown): string {
  if (error instanceof RequisitionApiError) {
    if (error.status === 401) return "登录已失效，请重新登录。";
    if (error.status === 403 || error.status === 404) return "没有此申请或操作的当前权限。";
    if (error.status === 409) return "申请、人员资料、处理权或库存已变更，请刷新后核对。";
    if (error.status === 400) return "请核对必填项、数量、原因和接收目标。";
  }
  return "操作结果尚未确认，请使用重试按钮或刷新查询结果。";
}
const profile: Profile<RequisitionApiError> = {
  codeFallback: "REQUISITION_REQUEST_FAILED",
  failure: ({ status, code }) => new RequisitionApiError(status, code),
  csrfMissing: () => new RequisitionApiError(401, "AUTH_SESSION_INVALID"),
};
export async function requisitionRequest<T>(path: string, method = "GET",
  mutation?: { key: string; version: number; body?: unknown }): Promise<T> {
  // 幂等键与乐观版本始终取自调用方的待重放对象，丢失应答后按同一请求原样重试。
  return requestJson<T, RequisitionApiError>(path, profile, mutation
    ? { method: method as HttpMethod, prefix: "/requisitions", key: mutation.key,
        version: mutation.version, json: mutation.body }
    : { method: method as HttpMethod, prefix: "/requisitions" });
}
