import { useSessionStore } from "@/auth/sessionStore";
import type { LoanMutation } from "@/loan/api";
export interface Snapshot {
  assetCode: string; name: string; version: number; locationId: string | null; locationName?: string;
  custodyType: string | null; responsibleEmployeeName?: string; departmentResponsibleEmployeeName?: string;
}
export interface Observation {
  id: string; client_event_id: string; outcome: string; recheck_generation: number; received_at: string;
  facts_json: { actual?: { locationId?: string | null; locationHint?: string | null; custodyType?: string | null;
    responsibleEmployeeId?: string | null; responsibilityHint?: string | null; condition?: string;
    unknownReason?: string | null; description?: string | null }; photoIds?: string[]; rawObservedAt?: string };
}
export interface Review {
  version: number; review_status?: string; reviewer_username?: string; reviewed_at?: string; reason?: string;
}
export interface ReviewItem {
  item: { id: string; asset_id: string; recheck_generation: number; snapshot_json: Snapshot };
  current: Snapshot | null; candidate: Observation | null; review: Review; canAcceptNormal: boolean;
  blockedReason: string | null; movementEvents: { type: string; occurredAt: string }[];
}
export interface Board {
  task: { id: string; name: string; task_status: string; version: number }; items: ReviewItem[];
  total: number; page: number; pageSize: number; reviewedCount: number;
}
export interface BatchResult {
  accepted: number; conflicts: number; items: { itemId: string; status: string; code?: string;
    current?: Review; review?: Review }[];
}
export class ReviewApiError extends Error {
  constructor(readonly status: number, readonly code: string, readonly fields: Record<string, string>) { super(code); }
}
export async function reviewRequest<T>(path: string, mutation?: LoanMutation): Promise<T> {
  const session = useSessionStore();
  const headers: Record<string, string> = {};
  if (mutation) {
    if (!session.csrfToken) throw new ReviewApiError(401, "AUTH_SESSION_INVALID", {});
    headers["X-CSRF-Token"] = session.csrfToken; headers["Idempotency-Key"] = mutation.key;
    headers["If-Match-Version"] = String(mutation.version); headers["Content-Type"] = "application/json";
  }
  const response = await fetch("/api/v1" + path, { credentials: "include", method: mutation ? "POST" : "GET",
    headers, body: mutation ? JSON.stringify(mutation.body) : undefined });
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { code?: unknown; fieldErrors?: Record<string, unknown> };
    const fields = Object.fromEntries(Object.entries(body.fieldErrors ?? {}).filter((row): row is [string, string] => typeof row[1] === "string"));
    throw new ReviewApiError(response.status, typeof body.code === "string" ? body.code : "INVENTORY_REVIEW_REQUEST_FAILED", fields);
  }
  return await response.json() as T;
}
export const reviewProblems: Record<string, string> = {
  ALREADY_REVIEWED: "本轮已有复核结论", INVENTORY_REVIEW_VERSION_CONFLICT: "该项已有处理结果或复盘版本已变更",
  INVENTORY_REVIEW_ASSET_CHANGED: "当前资产资料已变化，请重新核对", INVENTORY_REVIEW_PERIOD_MOVEMENT: "存在期间流转，须独立复核",
  INVENTORY_REVIEW_EVIDENCE_CONFLICT: "存在冲突、旧分配或待核实证据", INVENTORY_REVIEW_OBSERVATION_REQUIRED: "尚无可正常采信的本轮观察",
  INVENTORY_REVIEW_ASSET_UNAVAILABLE: "当前资产不可用于正常复核", INVENTORY_ITEM_NOT_FOUND: "该项不属于当前任务",
};
