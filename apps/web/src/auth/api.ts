import type { CurrentUser, ErrorResponse, PermissionSnapshot, SessionResponse } from "./types";

const JSON_HEADERS = { "Content-Type": "application/json" } as const;

export class AuthApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  readonly fieldErrors: Record<string, string>;

  constructor(
    status: number,
    code: string,
    message: string,
    requestId?: string,
    fieldErrors: Record<string, string> = {},
  ) {
    super(message);
    this.name = "AuthApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.fieldErrors = fieldErrors;
  }
}

async function parseFailure(response: Response): Promise<AuthApiError> {
  let body: Partial<ErrorResponse> = {};
  try {
    body = await response.json() as Partial<ErrorResponse>;
  } catch {
    // Preserve a stable client-side failure without exposing an HTML/server body.
  }
  return new AuthApiError(
    response.status,
    body.code ?? "AUTH_REQUEST_FAILED",
    body.message ?? "请求未完成，请稍后重试。",
    body.requestId,
    body.fieldErrors ?? {},
  );
}

async function requestJson<T>(path: string, init: RequestInit): Promise<T> {
  const response = await fetch(path, { ...init, credentials: "include" });
  if (!response.ok) {
    throw await parseFailure(response);
  }
  return await response.json() as T;
}

export function login(request: { username: string; password: string }): Promise<SessionResponse> {
  return requestJson<SessionResponse>("/api/v1/auth/login", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify(request),
  });
}

export function changeInitialPassword(
  request: { currentPassword: string; newPassword: string },
  csrfToken: string,
  idempotencyKey: string,
): Promise<SessionResponse> {
  if (!csrfToken) {
    return Promise.reject(new AuthApiError(403, "AUTH_CSRF_REQUIRED", "当前会话缺少安全令牌，请重新登录。"));
  }
  return requestJson<SessionResponse>("/api/v1/auth/password", {
    method: "POST",
    headers: {
      ...JSON_HEADERS,
      "X-CSRF-Token": csrfToken,
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(request),
  });
}

export function getCurrentUser(): Promise<CurrentUser> {
  return requestJson<CurrentUser>("/api/v1/auth/me", { method: "GET" });
}

export function getMyPermissions(): Promise<PermissionSnapshot> {
  return requestJson<PermissionSnapshot>("/api/v1/auth/permissions", { method: "GET" });
}

export async function logout(csrfToken: string): Promise<void> {
  if (!csrfToken) {
    throw new AuthApiError(403, "AUTH_CSRF_REQUIRED", "当前会话缺少安全令牌，请重新登录。");
  }
  const response = await fetch("/api/v1/auth/logout", {
    method: "POST",
    credentials: "include",
    headers: { "X-CSRF-Token": csrfToken },
  });
  if (!response.ok) {
    throw await parseFailure(response);
  }
}
