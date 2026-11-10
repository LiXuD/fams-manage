import { useSessionStore } from "@/auth/sessionStore";

export interface DutyAccount { id: string; name: string; active: boolean }
export interface DutyAssignment {
  roleCode: "ASSET_ADMIN" | "WAREHOUSE_OPERATOR"; version: string;
  primary: DutyAccount; backup: DutyAccount | null; effectiveAt: string
}
export interface DutyList { items: DutyAssignment[] }
export interface DutyMutation { key: string; version: number;
  body: { primaryAccountId: string; backupAccountId: string | null } }

export class ReminderApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

export function reminderMessage(error: unknown): string {
  if (error instanceof ReminderApiError) {
    if (error.status === 401) return "登录已失效，请重新登录。";
    if (error.status === 403) return "只有系统管理员可以登记值班提醒人。";
    if (error.status === 404) return "未找到当前值班配置。";
    if (error.status === 409) return "值班版本或人员有效性已变化，请刷新后重新登记。";
    if (error.status === 400) return "请核对值班提醒人与替补的账号编号。";
  }
  return "登记结果尚未确认，请重试上次登记或刷新查询。";
}

export async function dutyRequest<T>(path: string, mutation?: DutyMutation): Promise<T> {
  const session = useSessionStore();
  const headers: Record<string, string> = {};
  if (mutation) {
    if (!session.csrfToken) throw new ReminderApiError(401, "AUTH_SESSION_INVALID");
    headers["X-CSRF-Token"] = session.csrfToken;
    headers["Idempotency-Key"] = mutation.key;
    headers["If-Match-Version"] = String(mutation.version);
    headers["Content-Type"] = "application/json";
  }
  const reply = await fetch("/api/v1" + path, {
    credentials: "include", method: mutation ? "PUT" : "GET", headers,
    body: mutation ? JSON.stringify(mutation.body) : undefined });
  if (!reply.ok) {
    const error = await reply.json().catch(() => ({})) as { code?: unknown };
    const code = typeof error.code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code)
      ? error.code : "DUTY_REQUEST_FAILED";
    throw new ReminderApiError(reply.status, code);
  }
  return await reply.json() as T;
}
