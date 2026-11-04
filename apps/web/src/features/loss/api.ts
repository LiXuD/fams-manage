import { fileForm, requestJson, type Profile } from "@/api/http";
export interface LossAsset { id: string; assetCode: string; name: string; version: number; lifecycle: string; responsibleEmployeeId?: string; departmentResponsibleEmployeeId?: string }
export interface LossReport { id: string; assetId: string; status: string; source: LossAsset; version: number; assetVersion: number;
  ownershipVersion: number; lockVersion?: number; processorAccountId: string | null; lastSeenAt: string; lastLocation: string;
  circumstances: string; transferId?: string; returnId?: string | null; repairId?: string | null; recoveredAt?: string; recoveryAsset?: LossRecoveryAsset | null; recoveries?: LossRecovery[]; recoveriesTotal?: number; confirmedLostAt?: string; lastFollowUpAt?: string; nextReviewDate: string | null; photosAvailable?: boolean;
  photos?: { objectId: string; filename: string; contentType?: string }[]; actions?: Record<string, boolean>;
  history?: { id: string; action: string; actor_username: string; occurred_at: string }[] }
export interface LossRecoveryAsset { assetCode: string; serialNumber: string | null; version: number; locationId: string | null; locationName: string | null; locationVersion: number | null; eligible: boolean }
export interface LossRecovery { id: string; version: number; assetVersion: number; ownershipVersion: number; status: string; creatorAccountId: string; processorAccountId: string | null; actions: Record<string,boolean>; request?: { destination?: string; facts?: Record<string,unknown> } }
/**
 * 面板起草、视图确认的「待提交意图」：只带路径、版本、请求体与确认文案。
 * 幂等键属于视图的确认式提交底座，子组件不得自行生成，否则重试换键。
 */
export interface LossIntent { path: string; version: number; body?: unknown; file?: File; summary?: string }
/** 底座确认后交给 lossRequest 的完整意图。 */
export interface LossSubmission extends LossIntent { key: string }
export class LossApiError extends Error { constructor(readonly status: number, readonly code: string) { super(code); } }

/** 本域在 /api/v1 下的路径前缀，读写两端同源。 */
const PREFIX = "/loss-reports";

/** 本域的失败三元组形状：错误码不合形时落回本域兜底码，文案仍由 lossProblem 统一给。 */
const profile: Profile<LossApiError> = {
  codeFallback: "LOSS_REQUEST_FAILED",
  failure: ({ status, code }) => new LossApiError(status, code),
  csrfMissing: () => new LossApiError(401, "AUTH_SESSION_INVALID"),
};

/** 读走 GET；带意图即按写三件套（CSRF + 幂等键 + 乐观版本）提交，文件走 multipart 表单。 */
export function lossRequest<T>(path: string, intent?: LossSubmission): Promise<T> {
  return requestJson<T, LossApiError>(path, profile, intent
    ? { prefix: PREFIX, method: "POST", key: intent.key, version: intent.version,
      ...(intent.file ? { form: fileForm(intent.file) } : { json: intent.body }) }
    : { prefix: PREFIX });
}
export const lossStates: Record<string, string> = { PENDING_ADMIN_DECISION: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管领取",
  VERIFYING: "核实中", SEARCHING: "查找中", RECOVERED: "查找中已找回", CONFIRMED_LOST: "已确认遗失·持续跟进", REJECTED: "已驳回", CANCELLED: "已撤回" };
export function lossReportState(report: Pick<LossReport,"status"|"recoveredAt">): string {
  return report.status === "CONFIRMED_LOST" && report.recoveredAt ? "遗失事实保留·已找回" : lossStates[report.status] ?? report.status;
}
export function lossProblem(error: unknown): string {
  if (error instanceof LossApiError) {
    if ([401, 403, 404].includes(error.status)) return "当前身份、权限或记录已失效，请重新进入。";
    if (error.code === "LOSS_PHOTO_REQUIRED") return "批准前须由报告人上传本次情况图片。";
    if (error.code === "LOSS_LINKED_RECOVERY_REQUIRED") return "当前责任、位置或状况需要关联处理，不能直接恢复正常。";
    if (["LOSS_PHYSICAL_IDENTITY_MISMATCH","LOSS_CUSTODIAN_CHANGED","LOSS_LOCATION_CHANGED","LOSS_LOAN_IDENTITY_CHANGED"].includes(error.code)) return "请重新核对原机身份、登记位置和原责任是否合法，当前未恢复正常。";
    if (error.status === 409) return "单据、资产锁或处理权已变化，请刷新核对后重新操作。";
    if ([400, 413, 422].includes(error.status)) return "请核对实际时间、核实证据、复核日期及图片格式。";
  }
  return "本次结果尚未确认，请保留原请求重试，或只读核对最新结果。";
}
