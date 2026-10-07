/** D6-11 public wire shapes; authoritative schema: contracts/fams-core-v1.yaml. */
export interface RequisitionReference { id: string; version: number }
export interface RequisitionDemandInput {
    category: RequisitionReference; specification: string; preferredBrand?: string; preferredModel?: string;
    purpose: string; expectedDate: string; custodyType: "PERSONAL" | "SHARED";
    organization: RequisitionReference; employee: RequisitionReference; location: RequisitionReference; quantity: number;
}
export interface RequisitionDemand {
    id: string; requestId: string; categoryId: string; categoryVersionSnapshot: number; categoryNameSnapshot: string;
    specification: string; preferredBrand?: string; preferredModel?: string; purpose: string; expectedDate: string;
    custodyType: "PERSONAL" | "SHARED"; targetOrganizationId: string; organizationVersionSnapshot: number;
    targetEmployeeId?: string; targetDepartmentEmployeeId?: string; employeeVersionSnapshot: number;
    targetLocationId: string; locationVersionSnapshot: number; requestedQuantity: number;
    pendingAllocationQuantity: number; pendingHandoverQuantity: number; completedQuantity: number;
    unfulfillableQuantity: number; cancelledQuantity: number; version: number;
}
export interface RequisitionAllocation {
    id: string; demandId: string; assetId: string; allocationStatus: string; version: number; lockVersion: number;
    assetSnapshotJson: { assetCode: string; name: string; version: number }; predecessorAllocationId?: string;
}
export interface Requisition {
    requestType?: "REQUISITION" | "BORROW"; expectedReturnDate?: string | null;
    id: string; requestNo: string; creatorAccountId: string; creatorUsernameSnapshot: string;
    requestStatus: string; requestVersion: number; version: number; sourceRequestId?: string; sourceDemandId?: string;
    handovers?: Array<{ allocationId: string; deliveredAt: string; receiptDeadlineAt: string; destinationText: string;
        temporaryEmployeeId: string; deliveryEvidence: string }>;
    handoverEvents?: Array<{ id: string; allocationId: string; eventType: string; evidenceText: string; occurredAt: string }>;
    demands: RequisitionDemand[]; allocations: RequisitionAllocation[];
    decisions: Array<{ id: string; decisionResult: string; requestVersion: number; commentText?: string }>;
    unfulfillable: Array<{ id: string; demandId: string; quantity: number; reasonCode: string;
        reasonDescription?: string; inventoryQuerySummaryJson: { availableCount: number; queriedAt: string } }>;
    ownership: Array<{ processorAccountId: string; archivedAt?: string }>;
    followups: Array<{ sourceDemandId: string; latestRequestId: string }>;
    changes?: Array<{ proposalStatus: string; proposalJson: { demands: RequisitionDemandInput[] } }>;
}
export interface RequisitionChoice extends RequisitionReference { name: string; organizationId?: string }
export interface RequisitionCatalog {
    items: RequisitionChoice[]; page: number; pageSize: number; employeeId: string; organizationId: string; canApplyShared: boolean;
}
export type RequisitionSummary = Pick<Requisition, "id" | "requestNo" | "creatorAccountId" | "creatorUsernameSnapshot" | "requestStatus" | "requestVersion" | "version" | "sourceRequestId" | "sourceDemandId">;
export interface RequisitionPage { items: RequisitionSummary[]; total: number; page: number; pageSize: number }
export interface RequisitionInventory {
    items: Array<RequisitionReference & { assetCode: string; name: string; model?: string }>;
    total: number; page: number; pageSize: number;
}

export interface PendingReceipt { id: string; requestId: string; requestNo: string; version: number;
    assetSnapshotJson: { assetCode: string; name: string; model?: string }; deliveredAt: string;
    receiptDeadlineAt: string; destinationText: string }
export interface ReceiptDetail { requestId: string; requestNo: string; requestVersion: number;
    requestType?: "REQUISITION" | "BORROW"; expectedReturnDate?: string | null;
    allocationId: string; allocationStatus: string; allocationVersion: number;
    asset: { assetCode: string; name: string; model?: string };
    handover: { deliveredAt: string; receiptDeadlineAt: string; destinationText: string } }
