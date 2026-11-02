import { BusinessApiError } from "@/api/business";
import { requestJson, type Profile } from "@/api/http";
import type { Attachment } from "@/attachment/api";

export interface ProxyConfirmation {
  id: string; version: number; businessType: "REQUISITION" | "TRANSFER" | "REPAIR"; businessId: string; itemId: string;
  confirmationStatus: string; creatorAccountId: string; actualSigner: string;
  actualReceivedAt: string; cannotConfirmReason: string; contactRecord: string;
  delegatedReceipt: boolean; delegationRelationship: string; delegationBasis: string;
  evidenceKind: string; collectionMethod: string; manualReason: string;
  businessSnapshotJson: Record<string, unknown>; approverAccountId?: string;
  executorAccountId?: string; decisionReason?: string;
}
export interface ProxyPage { items: ProxyConfirmation[]; total: number }
export const proxyEvidenceLabels: Record<string, string> = { SIGNED_RECEIPT: "签收单",
  LOGISTICS_RECEIPT: "物流签收", AUTHORIZED_PROXY: "授权代收" };
export const proxyCollectionLabels: Record<string, string> = { SCAN_CONFIRMED: "扫描核对",
  CODE_CONFIRMED: "核对编码", MANUAL_RECORD: "手工记录" };
const profile: Profile<BusinessApiError> = {
  // 本域沿用 BusinessApiError 与附件兜底码，不在本批修正跨域错误类复用。
  codeFallback: "ATTACHMENT_REQUEST_FAILED",
  failure: ({ status, code }) => new BusinessApiError(status, code),
  csrfMissing: () => new BusinessApiError(401, "AUTH_SESSION_INVALID"),
};
export async function uploadProxyEvidence(id: string, version: number, file: File, key: string): Promise<Attachment> {
  const body = new FormData();
  body.append("file", file);
  return requestJson<Attachment, BusinessApiError>(`/proxy-confirmations/${id}/attachments`, profile,
    { method: "POST", form: body, key, version });
}
