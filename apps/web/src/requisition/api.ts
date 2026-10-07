import { useSessionStore } from "@/auth/sessionStore";

export class RequisitionApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export function requisitionMessage(error: unknown): string {
  if (error instanceof RequisitionApiError) {
    if (error.status === 401) return "登录已失效，请重新登录。";
    if (error.status === 403 || error.status === 404) return "没有此申请或操作的当前权限。";
    if (error.status === 409) return "申请、人员资料、处理权或库存已变更，请刷新后核对。";
    if (error.status === 400) return "请核对必填项、数量、原因和接收目标。";
  }
  return "操作结果尚未确认，请使用重试按钮或刷新查询结果。";
}
export async function requisitionRequest<T>(path: string, method = "GET",
  mutation?: { key: string; version: number; body?: unknown }): Promise<T> {
  const session = useSessionStore();
  const headers: Record<string, string> = {};
  if (mutation) {
    if (!session.csrfToken) throw new RequisitionApiError(401, "AUTH_SESSION_INVALID");
    headers["X-CSRF-Token"] = session.csrfToken;
    headers["Idempotency-Key"] = mutation.key;
    headers["If-Match-Version"] = String(mutation.version);
    if (mutation.body !== undefined) headers["Content-Type"] = "application/json";
  }
  const reply = await fetch("/api/v1/requisitions" + path, { method, credentials: "include", headers,
    body: mutation?.body === undefined ? undefined : JSON.stringify(mutation.body) });
  if (!reply.ok) {
    const data = await reply.json().catch(() => ({})) as { code?: unknown };
    const code = typeof data.code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(data.code)
      ? data.code : "REQUISITION_REQUEST_FAILED";
    throw new RequisitionApiError(reply.status, code);
  }
  return await reply.json() as T;
}
