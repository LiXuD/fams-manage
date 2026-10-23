import { useSessionStore } from "@/auth/sessionStore";
import { LoanApiError } from "@/loan/api";
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
export async function uploadProxyEvidence(id: string, version: number, file: File, key: string): Promise<Attachment> {
  const token = useSessionStore().csrfToken;
  if (!token) throw new LoanApiError(401, "AUTH_SESSION_INVALID");
  const body = new FormData(); body.append("file", file);
  const reply = await fetch(`/api/v1/proxy-confirmations/${id}/attachments`, {
    credentials: "include", method: "POST", body,
    headers: { "X-CSRF-Token": token, "Idempotency-Key": key, "If-Match-Version": String(version) },
  });
  if (!reply.ok) {
    const error = await reply.json().catch(() => ({})) as { code?: string };
    throw new LoanApiError(reply.status, error.code ?? "ATTACHMENT_REQUEST_FAILED");
  }
  return await reply.json() as Attachment;
}
