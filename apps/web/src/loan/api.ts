import { requestJson, type Profile } from "@/api/http";

export interface LoanMutation { path: string; key: string; version: number; body?: unknown }
export const loanLabels: Record<string, string> = { OPEN: "借用中", RETURNED: "已归还", VOID: "期初已撤销", PENDING: "待审批",
  APPROVED: "已批准", REJECTED: "已驳回", PENDING_APPROVAL: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管领取",
  WAREHOUSE_PROCESSING: "待实物验收", COMPLETED: "验收完成", TRANSFERRED: "已转后续处理" };
export const transferLabels: Record<string, string> = { DRAFT: "草稿", PENDING_APPROVAL: "待管理员确认", REJECTED: "已驳回",
  WAITING_WAREHOUSE_CLAIM: "待库管领取", PROCESSING: "库管处理中", WAITING_HANDOVER: "待实际交接", COMPLETED: "已完成",
  PARTIALLY_COMPLETED: "部分完成", CANCELLED: "已取消", READY: "未交出", DELIVERED: "待目标人接收", REFUSED: "实物不符待核查",
  RECOVERY_PENDING: "待实物收回" };
export const reminderLabels: Record<string, string> = { DUE_IN_3_DAYS: "到期前 3 日提醒", DUE_IN_1_DAY: "到期前 1 日提醒",
  DUE_TODAY: "到期日提醒", OVERDUE: "逾期提醒", LOST_LIABILITY: "遗失责任跟踪" };
export const inspectionLabels: Record<string, string> = { NORMAL: "实物正常", DAMAGED: "实物损坏", MISSING: "实物缺失" };

export class LoanApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export function loanMessage(error: unknown): string {
  if (error instanceof LoanApiError) {
    if (error.status === 401) return "登录已失效，请重新登录。";
    if (error.status === 403 || error.status === 404) return "没有当前记录或操作的权限。";
    if (error.status === 409) return "责任、处理权、日期或资料已变更，请刷新核对。";
    if (error.status === 400) return "请核对日期、原因、实物编码和仓位。";
  }
  return "操作结果尚未确认，请重试上次操作或刷新查询。";
}
const profile: Profile<LoanApiError> = {
  codeFallback: "LOAN_REQUEST_FAILED",
  failure: ({ status, code }) => new LoanApiError(status, code),
  csrfMissing: () => new LoanApiError(401, "AUTH_SESSION_INVALID"),
};
export async function loanRequest<T>(path: string, mutation?: LoanMutation): Promise<T> {
  return requestJson<T, LoanApiError>(path, profile, mutation
    ? { method: "POST", key: mutation.key, version: mutation.version, json: mutation.body }
    : {});
}
