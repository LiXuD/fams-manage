export interface BusinessPage<T> { items: T[]; total: number; page: number; pageSize: number }
export interface Loan {
  id: string; assetId: string; assetCode: string; name: string; version: number; currentAssetVersion: number;
  borrowerEmployeeId: string; usingOrganizationId: string; sourceType: "RECEIPT" | "INITIAL_IMPORT";
  borrowedDate: string; originalReturnDate: string; expectedReturnDate: string;
  relationStatus: "OPEN" | "RETURNED" | "VOID"; lifecycleStatus: string; exceptionStatus: string;
  overdue: boolean; lostLiability?: boolean; extensions?: LoanExtension[]; reminders?: LoanReminder[];
}
export interface LoanReminder {
  id: string; expectedReturnDate: string; reminderBusinessDate: string;
  reminderType: "DUE_IN_3_DAYS" | "DUE_IN_1_DAY" | "DUE_TODAY" | "OVERDUE" | "LOST_LIABILITY";
  overdueFrom: string | null;
}
export interface ReturnAssetChoice { id: string; assetCode: string; name: string; version: number; lifecycleStatus: string }
export interface BorrowRequestChoice { id: string; version: number; expectedReturnDate: string; requestStatus: string }
export interface LoanExtension {
  id: string; loanId: string | null; requestId: string | null; targetVersion: number;
  originalDueDate: string; requestedDueDate: string; reason: string; proposedBy: string;
  extensionStatus: "PENDING" | "APPROVED" | "REJECTED"; version: number;
  decisionReason: string | null; overdueFrom: string | null; overdueThrough: string | null;
}
export interface AssetReturn {
  id: string; assetId: string; loanId: string | null; assetVersion: number; version: number;
  proposedBy: string; responsibleEmployeeId: string; reason: string;
  returnStatus: "PENDING_APPROVAL" | "REJECTED" | "WAITING_WAREHOUSE_CLAIM" | "WAREHOUSE_PROCESSING" | "COMPLETED" | "TRANSFERRED";
  processorAccountId: string | null; inspectionResult: "NORMAL" | "DAMAGED" | "MISSING" | null;
  inspectionReason: string | null; lockVersion?: number | null;
  asset?: { id: string; assetCode: string; name: string; version: number; lifecycleStatus: string; exceptionStatus: string };
  followups?: { id: string; businessType: string; followupStatus: string; linkedRepairId?: string | null; lastRepairId?: string | null; repairStatus?: string | null }[];
}
export interface ReturnChoice { id: string; name: string; version: number; warehouseId?: string }
