export type FactoryCostPolicy = "NO_COST" | "LIMITED" | "UNLIMITED";
export interface FactoryCostSummary { costPolicy: FactoryCostPolicy; costLimit: string | null; confirmedTotal: string; unassessedCount: number; overrun: boolean; followupOpen: boolean }
export interface FactoryMoneyFact { amount: string; evidenceIds: string[] }
export interface FactoryScope { costPolicy?: FactoryCostPolicy; costLimit?: string | null; id: string; scopeStatus: string; version: number; requestReason: string; decisionReason: string | null }
export interface FactoryRound {
    quotes?: FactoryMoneyFact[]; costFact?: FactoryMoneyFact | null; dispatchScopeId?: string | null;
    id: string; roundNumber: number; roundStatus: string; version: number;
    providerName: string; serviceContact: string; servicePhone: string; expectedReturnDate: string; fault: string;
    dispatchedAt: string | null; receivedAt: string | null; conditionResult: string | null; treatmentResult: string | null; cancelReason: string | null;
    actualCost: string | null; originalIdentity: { assetCode: string; serialNumber: string | null };
}
export interface FactorySummary {
    scope: { id: string; scopeStatus: string; version: number } | null;
    round: { id: string; roundNumber: number; roundStatus: string; expectedReturnDate: string; dispatchedAt: string | null; receivedAt: string | null } | null;
    actualDispatchCount: number; followupOpen: boolean;
}
export interface FactoryDetail {
    repairId: string; version: number; assetVersion: number; ownershipVersion: number; lockVersion: number | null; status: string;
    pendingScope: FactoryScope | null; approvedScope: FactoryScope | null; activeRound: FactoryRound | null;
    summary: FactorySummary | null; photosAvailable: boolean; costSummary?: FactoryCostSummary;
    actions: Record<string, boolean>;
}
export interface FactoryFile { objectId: string; filename: string; contentType: string; sizeBytes: number; scanStatus: string }
export interface FactoryRoundDetail extends FactoryRound {
    actions?: Record<string, boolean>;
    files: FactoryFile[];
    evidence: { object_id: string; purpose: string }[];
}
export interface FactoryHistory { id: number; action: string; actor_username: string; recorded_at: string }
export interface FactoryList<T> { items: T[]; total: number }
export interface FactoryPartsFact { id: string; roundId: string; assetId: string; partName: string; beforeInfo: string; afterInfo: string; observedAt: string; recordedAt: string; evidenceIds: string[]; recordedBy: string }
export interface FactoryPartsPage extends FactoryList<FactoryPartsFact> { page: number; pageSize: number; canRecord: boolean }
