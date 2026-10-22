import { useSessionStore } from "@/auth/sessionStore";
export interface RepairAsset { id: string; assetCode: string; name: string; version: number; lifecycle: string;
  custodyType: string; locationId: string | null; locationName: string | null }
export interface RepairPhoto { objectId: string; filename: string; contentType: string; sizeBytes: number; scanStatus: string }
export interface Repair { id: string; assetId: string; creatorAccountId: string; source: RepairAsset; fault: string; impact: string;
  occurredAt: string; status: string; version: number; assetVersion: number; ownershipVersion: number;
  processorAccountId: string | null; acceptedAt: string | null; completedAt: string | null; lockVersion?: number | null;
  photosAvailable?: boolean; photos?: RepairPhoto[]; actions?: { approve: boolean; claim: boolean; acceptInPlace: boolean; completeInPlace: boolean; addFaultPhoto: boolean; cancel: boolean };
  history?: { id: string; action: string; actor_username: string; actor_role: string; occurred_at: string; detail_json: Record<string, unknown> }[]; historyTotal?: number }
export interface RepairMutation { path: string; key: string; version: number; body?: unknown; file?: File }
export class RepairApiError extends Error {
  constructor(readonly status: number, readonly code: string, readonly fields: Record<string, string>) { super(code); }
}
export async function repairRequest<T>(path: string, mutation?: RepairMutation): Promise<T> {
  const headers: Record<string, string> = {}; let body: BodyInit | undefined;
  if (mutation) {
    const token = useSessionStore().csrfToken; if (!token) throw new RepairApiError(401, "AUTH_SESSION_INVALID", {});
    headers["X-CSRF-Token"] = token; headers["Idempotency-Key"] = mutation.key; headers["If-Match-Version"] = String(mutation.version);
    if (mutation.file) { const form = new FormData(); form.append("file", mutation.file, mutation.file.name); body = form; }
    else if (mutation.body !== undefined) { headers["Content-Type"] = "application/json"; body = JSON.stringify(mutation.body); }
  }
  const response = await fetch("/api/v1/repairs" + path, { credentials: "include", method: mutation ? "POST" : "GET", headers, body });
  if (!response.ok) {
    const data = await response.json().catch(() => ({})) as { code?: unknown; fieldErrors?: Record<string, unknown> };
    throw new RepairApiError(response.status, typeof data.code === "string" ? data.code : "REPAIR_REQUEST_FAILED",
      Object.fromEntries(Object.entries(data.fieldErrors ?? {}).filter((pair): pair is [string, string] => typeof pair[1] === "string")));
  }
  return await response.json() as T;
}
export const repairStates: Record<string, string> = { PENDING_ADMIN_DECISION: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管领取",
  WAREHOUSE_PROCESSING: "库管处理中", COMPLETED: "已完成原地修复", REJECTED: "已驳回", CANCELLED: "已取消" };
export function repairProblem(error: unknown): string {
  if (error instanceof RepairApiError) {
    if ([401, 403, 404].includes(error.status)) return "当前身份、权限或报修记录已失效，请重新进入核对。";
    if (error.code === "REPAIR_BORROWING_EXPIRED_INBOUND_REQUIRED") return "原借用已到期，需要验收入库并处理借用，当前维修与资产锁继续保留。";
    if (error.code === "REPAIR_RETURN_TARGET_INACTIVE") return "原责任人或位置已失效，需要回库或经批准变更目标，不能原地恢复。";
    if (error.status === 409) return error.fields.processedBy ? `当前结果已由 ${error.fields.processedBy} 处理，请刷新核对。` : "单据、实物、处理权或原借用已变化，请刷新核对。";
    if ([400, 413, 422].includes(error.status)) return "请核对实际故障、发生时间和照片，图片不超过10MB。";
  }
  return "结果尚未确认，请保留本次操作并使用原请求重试。";
}
