import { useSessionStore } from "@/auth/sessionStore";

export interface Reference { id: string; version: number; code?: string; name?: string }
export interface InboundItem {
  id: string;
  executionStatus: string;
  asset: Reference;
  assetSnapshot: { assetCode: string; assetName: string };
  targetWarehouse: Reference;
  targetLocation: Reference;
  businessLock?: { lockVersion: number };
}
export interface InboundReceipt {
  id: string;
  receiptNumber: string;
  status: string;
  businessDate: string;
  version: number;
  createdBy: { id: string; username: string };
  items: InboundItem[];
  currentProcessor?: { processor: { id: string; username: string } };
  decision?: { result: string; comment?: string };
  factoryReplacement?: { sourceId: string; originalAssetId: string; replacementAssetId: string; repairId: string; roundId: string };
}
interface Page<T> { page: number; pageSize: number; total: number; items: T[] }

async function request<T>(path: string, method = "GET", body?: unknown, version?: number): Promise<T> {
  const session = useSessionStore();
  const headers: Record<string, string> = {};
  if (method !== "GET") {
    if (!session.csrfToken) throw new Error("会话已失效，请重新登录");
    headers["X-CSRF-Token"] = session.csrfToken;
    headers["Idempotency-Key"] = crypto.randomUUID();
    if (version !== undefined) headers["If-Match-Version"] = String(version);
  }
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch("/api/v1" + path, {
    method, credentials: "include", headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string; code?: string };
    throw new Error(error.message ?? error.code ?? "操作失败，请刷新后重试");
  }
  return await response.json() as T;
}

export function listReceipts(): Promise<Page<InboundReceipt>> {
  return request<Page<InboundReceipt>>("/inbound-receipts?page=1&pageSize=50");
}
export function getReceipt(id: string): Promise<InboundReceipt> {
  return request<InboundReceipt>("/inbound-receipts/" + encodeURIComponent(id));
}
export function createReceipt(payload: unknown): Promise<InboundReceipt> {
  return request<InboundReceipt>("/inbound-receipts", "POST", payload);
}
export function updateReceipt(value: InboundReceipt, payload: unknown): Promise<InboundReceipt> {
  return request<InboundReceipt>(`/inbound-receipts/${value.id}`, "PATCH", payload, value.version);
}
export function submitReceipt(value: InboundReceipt): Promise<InboundReceipt> {
  return request<InboundReceipt>(`/inbound-receipts/${value.id}/submit`, "POST", undefined, value.version);
}
export function cancelReceipt(value: InboundReceipt, reason: string): Promise<InboundReceipt> {
  return request<InboundReceipt>(`/inbound-receipts/${value.id}/cancel`, "POST", { reason }, value.version);
}
export function decideReceipt(value: InboundReceipt, approved: boolean, reason?: string): Promise<InboundReceipt> {
  return request<InboundReceipt>(`/inbound-receipts/${value.id}/decision`, "POST",
    approved ? { decision: "APPROVED" } : { decision: "REJECTED", reason }, value.version);
}
export function claimReceipt(value: InboundReceipt): Promise<InboundReceipt> {
  return request<InboundReceipt>(`/inbound-receipts/${value.id}/claim`, "POST", undefined, value.version);
}
export function acceptItem(value: InboundReceipt, item: InboundItem, assetVersion: number,
  observedAssetCode: string): Promise<{ receipt: InboundReceipt }> {
  return request<{ receipt: InboundReceipt }>(`/inbound-receipts/${value.id}/items/${item.id}/accept`,
    "POST", { assetVersion, lockVersion: item.businessLock?.lockVersion, observedAssetCode }, value.version);
}
