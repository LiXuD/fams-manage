export interface FactoryScope {
  id: string; repairId: string; scopeStatus: "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";
  allowedMethod: "FACTORY_NO_COST"; requestReason: string; requestedBy: string; requestedAt: string;
  decisionReason: string | null; decidedBy: string | null; decidedAt: string | null; version: number;
  requestBasis: Record<string, unknown>; decisionBasis: Record<string, unknown> | null;
}
export interface FactoryRound {
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
  summary: FactorySummary | null; photosAvailable: boolean;
  actions: Record<"requestScope" | "decideScope" | "withdrawScope" | "createRound" | "dispatch" | "cancelRound" | "receive" | "uploadEvidence", boolean>;
}
export interface FactoryFile {
  objectId: string; filename: string; contentType: string; sizeBytes: number;
  scanStatus: string; businessVersion: number; createdAt: string;
}
export interface FactoryRoundDetail extends FactoryRound {
  evidence: { object_id: string; purpose: "FAULT" | "FACTORY_ACK" | "RECEIPT"; content_sha256: string; uploader_account_id: number; recorded_at: string }[];
  files: FactoryFile[];
}
export interface FactoryHistory {
  id: number; action: string; actor_username: string; actor_role: string; recorded_at: string;
  detail_json: Record<string, unknown>; before_version: number; after_version: number;
}
export interface FactoryPage<T> { items: T[]; total: number; page: number; pageSize: number }
