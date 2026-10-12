export interface TransferChoice { id: string; name: string; version: number; organizationId?: string; warehouseId?: string; locationType?: string; sharedEligible?: boolean | number }
export interface TransferAsset { id: string; assetCode: string; name: string; version: number; lifecycleStatus: string; custodyType: string;
  owningOrganizationId: string | null; usingOrganizationId: string | null; responsibleEmployeeId: string | null;
  departmentResponsibleEmployeeId: string | null; storageWarehouseId: string | null; currentLocationId: string }
export interface TransferTarget { owningOrganizationId: string | null; custodyType: string; usingOrganizationId: string | null;
  employeeId: string | null; warehouseId: string | null; locationId: string; referenceVersions: Record<string, number> }
export interface TransferItem { id: string; requestId: string; assetId: string; assetVersion: number; itemStatus: string; version: number;
  receiptRequired: boolean | number; sourceSnapshot: Record<string, unknown>; targetSnapshot: TransferTarget;
  destination?: string; deliveryEvidence?: string; receiptDeadlineAt?: string; afterSnapshot?: Record<string, unknown> }
export interface Transfer { id: string; creatorAccountId: string; reason: string; requestStatus: string; version: number;
  ownershipVersion: number; processorAccountId: string | null; totalQuantity: number; completedQuantity: number; cancelledQuantity: number;
  pendingQuantity: number; sourceGroups: number; targetGroups: number; items: TransferItem[];
  events: { id: string; eventType: string; occurredAt: string; detail: unknown }[] }
export interface TransferReceipt { id: string; requestId: string; assetCode: string; name: string; itemStatus: string;
  version: number; requestVersion: number; destination: string; deliveredAt: string; receiptDeadlineAt: string }
