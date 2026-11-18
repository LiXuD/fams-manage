import { useSessionStore } from "@/auth/sessionStore";

export interface MasterDataMutation {
  path: string; method: "POST" | "PATCH"; key: string; version: number; body: unknown;
}
export class MasterDataApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export async function masterDataRequest<T>(path: string, mutation?: MasterDataMutation): Promise<T> {
  const headers: Record<string, string> = {};
  if (mutation) {
    const token = useSessionStore().csrfToken;
    if (!token) throw new MasterDataApiError(401, "AUTH_SESSION_INVALID");
    headers["Content-Type"] = "application/json";
    headers["X-CSRF-Token"] = token;
    headers["Idempotency-Key"] = mutation.key;
    headers["If-Match-Version"] = String(mutation.version);
  }
  const response = await fetch("/api/v1" + path, {
    credentials: "include", method: mutation?.method ?? "GET", headers,
    body: mutation ? JSON.stringify(mutation.body) : undefined,
  });
  if (!response.ok) {
    const result = await response.json().catch(() => ({})) as { code?: unknown };
    const code = typeof result.code === "string" && /^[A-Z][A-Z0-9_]{0,79}$/.test(result.code)
      ? result.code : "MASTER_DATA_REQUEST_FAILED";
    throw new MasterDataApiError(response.status, code);
  }
  return await response.json() as T;
}
