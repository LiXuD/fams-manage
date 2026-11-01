import { apiUrl, fileForm, requestBlob, requestJson, type Profile } from "@/api/http";
import { RepairApiError, type RepairMutation } from "./api";
export interface TechnicalEvidence { objectId: string; filename: string; contentType: string; sizeBytes: number; scanStatus: string }
export interface TechnicalOpinion { id: string; invitation_id: string; judgement: string; method: string; opinion_result: string; occurred_at: string; attachment_ids_json: string[]; actor_username: string; recorded_at: string }
export interface RepairInvitation { id: string; technician_account_id: string; technician_username: string; invitation_status: string; reason: string; version: number; invited_at: string; end_reason: string | null }
export interface CollaborationBoard { repairId: string; requestVersion: number; canInvite: boolean; invitations: RepairInvitation[]; invitationTotal: number; opinions: TechnicalOpinion[]; opinionTotal: number }
export interface AssistedRepair { invitationId: string; repairId: string; version: number; invitedAt: string; asset: { assetCode: string; name: string }; fault: string; impact: string; faultOccurredAt: string; status: string; opinions?: TechnicalOpinion[]; opinionTotal?: number; photosAvailable?: boolean; faultPhotos?: TechnicalEvidence[]; attachments?: TechnicalEvidence[] }
export const opinionResults: Record<string,string> = { RESTORED: "判断已恢复（待库管核实）", FACTORY_SUGGESTED: "建议返厂（待实际后续）", SCRAP_SUGGESTED: "建议报废（须独立决定）", NEEDS_WORK: "仍需处理" };
const profile: Profile<RepairApiError> = {
  codeFallback: "REPAIR_COLLABORATION_FAILED",
  failure: ({ status, code, fields }) => new RepairApiError(status, code, fields),
  csrfMissing: () => new RepairApiError(401, "AUTH_SESSION_INVALID", {}),
};
export async function collaborationRequest<T>(path: string, mutation?: RepairMutation): Promise<T> {
  if (!/^\/(repairs\/[1-9][0-9]*\/(collaborations|eligible-technicians)|repair-collaborations)([/?][A-Za-z0-9_/?=&.-]*)?$/.test(path)) throw new Error("Invalid repair collaboration path");
  return requestJson<T, RepairApiError>(path, profile, {
    method: mutation ? "POST" : "GET",
    key: mutation?.key,
    version: mutation?.version,
    json: mutation?.file ? undefined : mutation?.body,
    form: mutation?.file ? fileForm(mutation.file) : undefined,
  });
}
/** 授权令牌已视图侧按原口径校验；依据原件内容仍走同源会话下载，不进入 JSON 门面。 */
export function collaborationEvidenceContent(invitationId: string, kind: string, objectId: string, grant: string, signal?: AbortSignal): Promise<Blob> {
  return requestBlob(apiUrl(`/repair-collaborations/${invitationId}/${kind}/${objectId}/content?grant=${grant}`),
    (status) => new RepairApiError(status, "REPAIR_EVIDENCE_READ_FAILED", {}), signal);
}
