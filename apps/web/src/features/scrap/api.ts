import { useSessionStore } from "@/auth/sessionStore";
export interface ScrapChoice { id: string; name: string; version: number }
export interface ScrapAsset extends ScrapChoice { assetCode: string }
export interface ScrapRequest {
  id: string; assetId: string; version: number; assetVersion: number; ownershipVersion: number;
  lockVersion: number | null; processorAccountId: string | null; status: string;
  reason: string; observedCondition: string; proposedDisposition: string;
  source: { assetCode: string; name: string; serialNumber: string };
  actions: { decide: boolean; claim: boolean; execute: boolean };
  decision: { decision?: { approvedDisposition?: string } } | null;
  disposition: { disposition_status: string; due_date: string } | null;
}
export interface ScrapIntent {
  path: string; key: string; version?: number; body: Readonly<Record<string, unknown>>; summary: string;
}
export class ScrapApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export async function scrapRequest<T>(path: string, intent?: ScrapIntent): Promise<T> {
  const headers: Record<string, string> = {};
  let body: string | undefined;
  if (intent) {
    const token = useSessionStore().csrfToken;
    if (!token) throw new ScrapApiError(401, "AUTH_SESSION_INVALID");
    headers["X-CSRF-Token"] = token;
    headers["Idempotency-Key"] = intent.key;
    if (intent.version !== undefined) headers["If-Match-Version"] = String(intent.version);
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(intent.body);
  }
  const response = await fetch("/api/v1/scrap-requests" + path, {
    credentials: "include", method: intent ? "POST" : "GET", headers, body,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { code?: string };
    throw new ScrapApiError(response.status, error.code ?? "SCRAP_REQUEST_FAILED");
  }
  return await response.json() as T;
}
export const scrapStates: Record<string, string> = {
  PENDING_ADMIN_DECISION: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管认领",
  PROCESSING: "待实物核验与执行", RESTORE_REQUIRED: "原保管关系失效，待纠正",
  REJECTED: "已驳回并恢复", CANCELLED: "已取消", EXECUTED: "已执行报废",
};
