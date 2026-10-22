import { useSessionStore } from "@/auth/sessionStore";
export interface RepairAsset { id: string; assetCode: string; name: string; version: number; lifecycle: string;
  custodyType: string; locationId: string | null; locationName: string | null }
export interface RepairPhoto { objectId: string; filename: string; contentType: string; sizeBytes: number; scanStatus: string }
export interface RepairReturnTarget { custodyType: string; employeeId: string; employeeName: string; employeeVersion: number;
  organizationId: string; organizationName: string; organizationVersion: number; locationId: string; locationName: string; locationVersion: number; receiverAccountId: string }
export interface RepairReturnHandover { id: string; handover_status: string; target_snapshot_json: RepairReturnTarget; delivered_at: string; delivery_evidence: string; receipt_at: string | null; receipt_evidence: string | null }
export interface Repair { id: string; assetId: string; creatorAccountId: string; source: RepairAsset; fault: string; impact: string;
  occurredAt: string; status: string; version: number; assetVersion: number; ownershipVersion: number;
  processorAccountId: string | null; acceptedAt: string | null; completedAt: string | null; lockVersion?: number | null;
  loanVersion?: number | null; physicalHandling?: { handler_employee_id: string; actual_location_id: string; occurred_at: string; evidence: string }[];
  inbound?: { warehouse_id: string; location_id: string; occurred_at: string; evidence: string }[];
  loanExpectedReturnDate?: string | null; returnHandovers?: RepairReturnHandover[];
  returnRecollections?: { handover_id: string; occurred_at: string; evidence: string }[];
  photosAvailable?: boolean; photos?: RepairPhoto[]; actions?: { approve: boolean; claim: boolean; acceptInPlace: boolean; completeInPlace: boolean; addFaultPhoto: boolean; cancel: boolean; acceptAway?: boolean; closeInbound?: boolean; deliverReturn?: boolean; receiveReturn?: boolean; refuseReturn?: boolean; recollectReturn?: boolean };
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
  WAREHOUSE_PROCESSING: "库管处理中", COMPLETED: "维修已完成", REJECTED: "已驳回", CANCELLED: "已取消" };
export function repairProblem(error: unknown): string {
  if (error instanceof RepairApiError) {
    if ([401, 403, 404].includes(error.status)) return "当前身份、权限或报修记录已失效，请重新进入核对。";
    if (error.code === "REPAIR_BORROWING_EXPIRED_INBOUND_REQUIRED") return "原借用已到期，请先在借用台账获批延期后再返还，或实际验收入库关闭借用；当前维修与资产锁继续保留。";
    if (error.code === "REPAIR_RETURN_TARGET_INACTIVE") return "原责任人或位置已失效，请由库管按实物去向收回并入库，或经批准变更目标；当前维修与资产锁继续保留。";
    if (error.code === "REPAIR_RETURN_RECOLLECTION_REQUIRED") return "设备已交出待接收或已被拒收，须先记录库管实际收回，才能重新返还或验收入库。";
    if (error.code === "REPAIR_PHYSICAL_TIME_INVALID") return "请核对实际接收时间，不能早于本单已记录的实物办理，也不能填写未来时间。";
    if (error.code === "REPAIR_INBOUND_TARGET_CHANGED") return "仓库或位置已变化，请重新查询并核对有效仓库及其库位。";
    if (error.code === "REPAIR_LOAN_CHANGED") return "原借用关系或版本已变化，请刷新核对；维修不会自动改写延期或原到期日。";
    if (error.status === 409) return error.fields.processedBy ? `当前结果已由 ${error.fields.processedBy} 处理，请刷新核对。` : "单据、实物、处理权或原借用已变化，请刷新核对。";
    if ([400, 413, 422].includes(error.status)) return "请核对实际故障、发生时间和照片，图片不超过10MB。";
  }
  return "结果尚未确认，请保留本次操作并使用原请求重试。";
}
