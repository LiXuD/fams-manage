export const loanLabels: Record<string, string> = { OPEN: "借用中", RETURNED: "已归还", VOID: "期初已撤销", LOST_TERMINATED: "遗失终止", PENDING: "待审批",
  APPROVED: "已批准", REJECTED: "已驳回", PENDING_APPROVAL: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管领取",
  WAREHOUSE_PROCESSING: "待实物验收", COMPLETED: "验收完成", TRANSFERRED: "已转后续处理" };
export const reminderLabels: Record<string, string> = { DUE_IN_3_DAYS: "到期前 3 日提醒", DUE_IN_1_DAY: "到期前 1 日提醒",
  DUE_TODAY: "到期日提醒", OVERDUE: "逾期提醒", LOST_LIABILITY: "遗失责任跟踪" };
