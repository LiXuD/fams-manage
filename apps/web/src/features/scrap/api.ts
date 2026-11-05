import { requestJson, type Profile } from "@/api/http";
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
/**
 * 视图起草的「待提交意图」：只带路径、版本、请求体与确认文案。
 * 幂等键属于视图的确认式提交底座，子组件不得自行生成，否则重试换键。
 */
export interface ScrapIntent {
  path: string; version?: number; body: Readonly<Record<string, unknown>>; summary: string;
}
/** 底座确认后交给 scrapRequest 的完整意图。 */
export interface ScrapSubmission extends ScrapIntent { key: string }
export class ScrapApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

/** 本域在 /api/v1 下的路径前缀，读写两端同源。 */
const PREFIX = "/scrap-requests";

/** 本域的失败三元组形状：错误码不合形时落回本域兜底码。 */
const profile: Profile<ScrapApiError> = {
  codeFallback: "SCRAP_REQUEST_FAILED",
  failure: ({ status, code }) => new ScrapApiError(status, code),
  csrfMissing: () => new ScrapApiError(401, "AUTH_SESSION_INVALID"),
};

/** 读走 GET；带意图即按写三件套（CSRF + 幂等键 + 可选乐观版本）提交。 */
export function scrapRequest<T>(path: string, intent?: ScrapSubmission): Promise<T> {
  return requestJson<T, ScrapApiError>(path, profile, intent
    ? { prefix: PREFIX, method: "POST", key: intent.key, version: intent.version, json: intent.body }
    : { prefix: PREFIX });
}
export const scrapStates: Record<string, string> = {
  PENDING_ADMIN_DECISION: "待管理员确认", WAITING_WAREHOUSE_CLAIM: "待库管认领",
  PROCESSING: "待实物核验与执行", RESTORE_REQUIRED: "原保管关系失效，待纠正",
  REJECTED: "已驳回并恢复", CANCELLED: "已取消", EXECUTED: "已执行报废",
};
export function scrapRequestState(row: Pick<ScrapRequest, "status">): string {
  return scrapStates[row.status] ?? row.status;
}
