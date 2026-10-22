import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, type RepairMutation } from "./api";
export interface TechnicalEvidence { objectId: string; filename: string; contentType: string; sizeBytes: number; scanStatus: string }
export interface TechnicalOpinion { id: string; invitation_id: string; judgement: string; method: string; opinion_result: string; occurred_at: string; attachment_ids_json: string[]; actor_username: string; recorded_at: string }
export interface RepairInvitation { id: string; technician_account_id: string; technician_username: string; invitation_status: string; reason: string; version: number; invited_at: string; end_reason: string | null }
export interface CollaborationBoard { repairId: string; requestVersion: number; canInvite: boolean; invitations: RepairInvitation[]; invitationTotal: number; opinions: TechnicalOpinion[]; opinionTotal: number }
export interface AssistedRepair { invitationId: string; repairId: string; version: number; invitedAt: string; asset: { assetCode: string; name: string }; fault: string; impact: string; faultOccurredAt: string; status: string; opinions?: TechnicalOpinion[]; opinionTotal?: number; photosAvailable?: boolean; faultPhotos?: TechnicalEvidence[]; attachments?: TechnicalEvidence[] }
export const opinionResults: Record<string,string> = { RESTORED: "判断已恢复（待库管核实）", FACTORY_SUGGESTED: "建议返厂（待实际后续）", SCRAP_SUGGESTED: "建议报废（须独立决定）", NEEDS_WORK: "仍需处理" };
export async function collaborationRequest<T>(path: string, mutation?: RepairMutation): Promise<T> {
  if (!/^\/(repairs\/[1-9][0-9]*\/(collaborations|eligible-technicians)|repair-collaborations)([/?][A-Za-z0-9_/?=&.-]*)?$/.test(path)) throw new Error("Invalid repair collaboration path");
  const headers: Record<string,string> = {}; let body: BodyInit | undefined;
  if (mutation) { const csrf = useSessionStore().csrfToken; if (!csrf) throw new RepairApiError(401,"AUTH_SESSION_INVALID",{});
    headers["X-CSRF-Token"] = csrf; headers["Idempotency-Key"] = mutation.key; headers["If-Match-Version"] = String(mutation.version);
    if (mutation.file) { const form = new FormData(); form.append("file",mutation.file,mutation.file.name); body = form; }
    else if (mutation.body !== undefined) { headers["Content-Type"] = "application/json"; body = JSON.stringify(mutation.body); }
  }
  const response = await fetch("/api/v1" + path,{method:mutation ? "POST" : "GET",credentials:"include",headers,body});
  if (!response.ok) { const data = await response.json().catch(()=>({})) as { code?: unknown; fieldErrors?: Record<string,unknown> };
    throw new RepairApiError(response.status,typeof data.code === "string" ? data.code : "REPAIR_COLLABORATION_FAILED",Object.fromEntries(Object.entries(data.fieldErrors ?? {}).filter((row): row is [string,string]=>typeof row[1] === "string")));
  }
  return await response.json() as T;
}
