import { useSessionStore } from "@/auth/sessionStore";
export interface LossAsset { id: string; assetCode: string; name: string; version: number; lifecycle: string; responsibleEmployeeId?: string; departmentResponsibleEmployeeId?: string }
export interface LossReport { id: string; assetId: string; status: string; source: LossAsset; version: number; assetVersion: number;
  ownershipVersion: number; lockVersion?: number; processorAccountId: string | null; lastSeenAt: string; lastLocation: string;
  circumstances: string; confirmedLostAt?: string; lastFollowUpAt?: string; nextReviewDate: string | null; photosAvailable?: boolean;
  photos?: { objectId: string; filename: string; contentType?: string }[]; actions?: Record<string, boolean>;
  history?: { id: string; action: string; actor_username: string; occurred_at: string }[] }
export interface LossIntent { path: string; key: string; version: number; body?: unknown; file?: File; summary?: string }
export class LossApiError extends Error { constructor(readonly status: number, readonly code: string) { super(code); } }
export async function lossRequest<T>(path: string, intent?: LossIntent): Promise<T> {
  const headers: Record<string, string> = {}; let body: BodyInit | undefined;
  if (intent) {
    const token = useSessionStore().csrfToken;
    if (!token) throw new LossApiError(401, "AUTH_SESSION_INVALID");
    headers["X-CSRF-Token"] = token; headers["Idempotency-Key"] = intent.key; headers["If-Match-Version"] = String(intent.version);
    if (intent.file) { const form = new FormData(); form.append("file", intent.file, intent.file.name); body = form; }
    else if (intent.body !== undefined) { headers["Content-Type"] = "application/json"; body = JSON.stringify(intent.body); }
  }
  const response = await fetch("/api/v1/loss-reports" + path, { credentials: "include", method: intent ? "POST" : "GET", headers, body });
  if (!response.ok) {
    const data = await response.json().catch(() => ({})) as { code?: string };
    throw new LossApiError(response.status, data.code ?? "LOSS_REQUEST_FAILED");
  }
  return await response.json() as T;
}
export const lossStates: Record<string, string> = { PENDING_ADMIN_DECISION: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管领取",
  VERIFYING: "核实中", SEARCHING: "查找中", CONFIRMED_LOST: "已确认遗失·持续跟进", REJECTED: "已驳回", CANCELLED: "已撤回" };
export function lossProblem(error: unknown): string {
  if (error instanceof LossApiError) {
    if ([401, 403, 404].includes(error.status)) return "当前身份、权限或记录已失效，请重新进入。";
    if (error.code === "LOSS_PHOTO_REQUIRED") return "批准前须由报告人上传本次情况图片。";
    if (error.status === 409) return "单据、资产锁或处理权已变化，请刷新核对后重新操作。";
    if ([400, 413, 422].includes(error.status)) return "请核对实际时间、核实证据、复核日期及图片格式。";
  }
  return "本次结果尚未确认，请保留原请求重试，或只读核对最新结果。";
}
