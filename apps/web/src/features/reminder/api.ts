import { requestJson, type Profile } from "@/api/http";

export interface DutyAccount { id: string; name: string; active: boolean }
export interface DutyAssignment {
  roleCode: "ASSET_ADMIN" | "WAREHOUSE_OPERATOR"; version: string;
  primary: DutyAccount; backup: DutyAccount | null; effectiveAt: string
}
export interface DutyList { items: DutyAssignment[] }
export interface DutyMutation { key: string; version: number;
  body: { primaryAccountId: string; backupAccountId: string | null } }

/** 消息中心的一条可见行：待办与提醒共用同一形状，两类各自的档位与阶段字段只在一侧有值。 */
export interface ReminderMessage {
  kind: "TODO" | "REMINDER"; id: string; businessType: string; businessId: string; stage: string;
  objectStage: string; poolType: string; assigneeAccountId: string; openedAt: string; deadlineAt: string; read: boolean
}
export interface ReminderPage { items: ReminderMessage[]; total: number; unread: number; view: string }

/** 档位、阶段、待办类型与业务对象名的中文读法逐字取自功能线，归本域模块单点维护。 */
export const messageTierLabels: Record<string, string> = { DUE_IN_3_DAYS: "提前3日", DUE_IN_1_DAY: "提前1日",
  DUE_IN_24_HOURS: "截止前24小时", DUE_TODAY: "今日到期", OVERDUE: "已逾期",
  OVERDUE_EVERY_7_DAYS: "逾期每7日跟进", UNRESOLVED_AFTER_1_WORKDAY: "1个工作日未处理",
  DUTY_UNREAD_ESCALATION: "升级值班提醒", OVERDUE_UNRESOLVED_WORKDAY: "超时1个工作日仍未处理" };
export const messageStageLabels: Record<string, string> = { ADMIN_DECISION: "管理员确认", WAREHOUSE_CLAIM: "库管领取",
  FIRST_EXECUTION: "首次执行", HANDOVER_RECEIPT: "实物接收", INVENTORY_COMPLETION: "盘点完成",
  FACTORY_RETURN: "返厂收回", LOSS_REVIEW: "报失复核", SCRAP_DISPOSITION: "报废处置" };
export const messageTodoLabels: Record<string, string> = { APPROVAL: "待管理员确认", CLAIM: "待库管领取",
  PROCESS: "待维修处理", RECEIPT: "待实物接收", OPINION: "待补充技术意见", VERIFY: "待核实", SEARCH: "待查找跟进",
  HANDOVER_EXCEPTION: "交接异常待处理", RETURN_EXCEPTION: "返还异常待处理", OVERDUE: "逾期跟进",
  FACTORY_DISPATCH: "待寄出返厂", FACTORY_SCOPE_APPROVAL: "待返厂范围确认", RESTORE_REQUIRED: "待恢复保管",
  DISPOSITION: "待实际处置", LOST_FOLLOW_UP: "待遗失跟进" };
export const messageBusinessLabels: Record<string, string> = { INBOUND: "入库单", REQUISITION: "领用申请",
  REQUISITION_ALLOCATION: "领用分配", LOAN: "借用", LOAN_EXTENSION: "借用延期", ASSET_RETURN: "退还",
  TRANSFER: "调拨", TRANSFER_ITEM: "调拨明细", REQUISITION_HANDOVER: "领用交接",
  INVENTORY_TASK: "盘点任务", REPAIR: "报修单", REPAIR_FACTORY_ROUND: "返厂轮次",
  LOST_REPORT: "报失单", SCRAP: "报废申请", ITEM_TARGET_CHANGE: "目标变更" };

/** 维修及其派生单据统一落到报修详情；其余业务类型按表落到各自列表路由并带 id 查询串。 */
export const messageRepairSegmentTypes: ReadonlySet<string> = new Set([
  "REPAIR", "REPAIR_FACTORY_ROUND", "REPAIR_OPINION", "REPAIR_TECH", "PROXY_CONFIRMATION"]);
export const messageDestinations: Record<string, string> = { INBOUND: "/inbound", REQUISITION: "/requisitions",
  REQUISITION_ALLOCATION: "/requisitions", LOAN: "/loans", LOAN_EXTENSION: "/loans", ASSET_RETURN: "/loans",
  TRANSFER: "/transfers", TRANSFER_ITEM: "/transfers", REQUISITION_HANDOVER: "/requisitions",
  INVENTORY_TASK: "/inventory-tasks", LOST_REPORT: "/loss-reports", SCRAP: "/scrap-requests",
  ITEM_TARGET_CHANGE: "/requisition-target-changes" };

export class ReminderApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

export function reminderMessage(error: unknown): string {
  if (error instanceof ReminderApiError) {
    if (error.status === 401) return "登录已失效，请重新登录。";
    if (error.status === 403) return "只有系统管理员可以登记值班提醒人。";
    if (error.status === 404) return "未找到当前值班配置。";
    if (error.status === 409) return "值班版本或人员有效性已变化，请刷新后重新登记。";
    if (error.status === 400) return "请核对值班提醒人与替补的账号编号。";
  }
  return "登记结果尚未确认，请重试上次登记或刷新查询。";
}

/** 本域的失败三元组形状：错误码不合形时落回值班请求兜底码。 */
const profile: Profile<ReminderApiError> = {
  codeFallback: "DUTY_REQUEST_FAILED",
  failure: ({ status, code }) => new ReminderApiError(status, code),
  csrfMissing: () => new ReminderApiError(401, "AUTH_SESSION_INVALID"),
};

/**
 * 值班提醒人配置读写。路径由调用方给出完整形态（`/duty-assignments…`），本域不叠加前缀；
 * 带意图即按写三件套（CSRF + 幂等键 + 乐观版本）以 PUT 提交，重试必须复用同一把键。
 */
export function dutyRequest<T>(path: string, mutation?: DutyMutation): Promise<T> {
  return requestJson<T, ReminderApiError>(path, profile, mutation
    ? { method: "PUT", key: mutation.key, version: mutation.version, json: mutation.body }
    : {});
}
