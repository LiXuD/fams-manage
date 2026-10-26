export type FactoryCostPolicy = "NO_COST" | "LIMITED" | "UNLIMITED";
export interface FactoryCostSummary { costPolicy: FactoryCostPolicy; costLimit: string | null; confirmedTotal: string; unassessedCount: number; overrun: boolean; followupOpen: boolean }
export interface FactoryMoneyFact { amount: string; evidenceIds: string[] }
export interface FactoryScope {
  id: string; repairId: string; scopeStatus: "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";
  allowedMethod: "FACTORY_NO_COST" | "FACTORY_COST_ALLOWED"; costPolicy?: FactoryCostPolicy; costLimit?: string | null; requestReason: string; requestedBy: string; requestedAt: string;
  decisionReason: string | null; decidedBy: string | null; decidedAt: string | null; version: number;
  requestBasis: Record<string, unknown>; decisionBasis: Record<string, unknown> | null;
}
export interface FactoryRound {
    quotes?: FactoryMoneyFact[]; costFact?: FactoryMoneyFact | null; dispatchScopeId?: string | null;
  id: string; repairId: string; assetId: string; scopeId: string; roundNumber: number;
  roundStatus: "PENDING_DISPATCH" | "DISPATCHED" | "RECEIVED" | "CANCELLED";
  providerName: string; serviceContact: string; servicePhone: string; expectedReturnDate: string;
  fault: string; createdBy: string; createdAt: string; dispatchedAt: string | null;
  dispatchedBy: string | null; dispatchEvidence: string | null; receivedAt: string | null;
  receivedBy: string | null; receiveLocationId: string | null; receiveLocationVersion: number | null;
  conditionResult: "RESTORED" | "STILL_FAULTY" | null; treatmentResult: string | null;
  actualCost: string | null; cancelReason: string | null; version: number;
  originalIdentity: { assetId: string; assetCode: string; name: string; serialNumber: string | null; version: number; lifecycle: string };
}
export interface FactorySummary {
  scope: { id: string; scopeStatus: FactoryScope["scopeStatus"]; version: number } | null;
  round: Pick<FactoryRound, "id" | "roundNumber" | "roundStatus" | "expectedReturnDate" | "dispatchedAt" | "receivedAt" | "version"> | null;
  actualDispatchCount: number; followupOpen: boolean;
}
export interface FactoryReply {
  repairId: string; version: number; assetVersion: number; ownershipVersion: number;
  status: string; processorAccountId: string | null; lockVersion: number | null;
  scope?: FactoryScope; round?: FactoryRound; objectId?: string;
}
export interface FactoryDetail extends FactoryReply {
  pendingScope: FactoryScope | null; approvedScope: FactoryScope | null; activeRound: FactoryRound | null;
  summary: FactorySummary | null; photosAvailable: boolean; costSummary?: FactoryCostSummary;
  actions: Record<"requestScope" | "decideScope" | "withdrawScope" | "createRound" | "dispatch" | "cancelRound" | "receive" | "uploadEvidence", boolean> & { requestCostScope?: boolean };
}
export interface FactoryFile {
  objectId: string; filename: string; contentType: string; sizeBytes: number;
  scanStatus: string; businessVersion: number; createdAt: string;
}
export interface FactoryRoundDetail extends FactoryRound {
    actions?: Record<string, boolean>;
  evidence: { object_id: string; purpose: "FAULT" | "FACTORY_ACK" | "RECEIPT" | "QUOTE" | "COST_RECEIPT" | "PARTS_CHANGE"; content_sha256: string; uploader_account_id: number; recorded_at: string }[];
  files: FactoryFile[];
}
export interface FactoryHistory {
  id: number; action: string; actor_username: string; actor_role: string; recorded_at: string;
  detail_json: Record<string, unknown>; before_version: number; after_version: number;
}
export interface FactoryPage<T> { items: T[]; total: number; page: number; pageSize: number }
export interface FactoryPartsFact { id: string; roundId: string; assetId: string; partName: string; beforeInfo: string; afterInfo: string; observedAt: string; recordedAt: string; evidenceIds: string[]; recordedBy: string }
export interface FactoryPartsPage extends FactoryPage<FactoryPartsFact> { canRecord: boolean }
