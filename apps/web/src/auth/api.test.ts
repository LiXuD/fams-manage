import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { changeInitialPassword, login, logout } from "./api";

describe("auth API", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses the real cookie channel without persisting credentials in JavaScript", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({
      csrfToken: "csrf-1",
      idleExpiresAt: "2026-09-21T02:00:00Z",
      absoluteExpiresAt: "2026-09-21T09:30:00Z",
      requiresInitialPasswordChange: false,
      user: {
        id: "1001",
        username: "asset.admin",
        status: "ENABLED",
        roles: ["ASSET_ADMIN"],
        requiresInitialPasswordChange: false,
      },
    }), { status: 200, headers: { "Content-Type": "application/json" } }));

    await login({ username: "asset.admin", password: "secret" });

    expect(fetch).toHaveBeenCalledWith("/api/v1/auth/login", expect.objectContaining({
      method: "POST",
      credentials: "include",
    }));
    expect(vi.mocked(fetch).mock.calls[0]?.[1]?.headers).not.toHaveProperty("Authorization");
  });

  it("sends CSRF and idempotency headers for the initial password change", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({
      csrfToken: "csrf-2",
      idleExpiresAt: "2026-09-21T02:00:00Z",
      absoluteExpiresAt: "2026-09-21T09:30:00Z",
      requiresInitialPasswordChange: false,
      user: {
        id: "1001",
        username: "asset.admin",
        status: "ENABLED",
        roles: ["ASSET_ADMIN"],
        requiresInitialPasswordChange: false,
      },
    }), { status: 200, headers: { "Content-Type": "application/json" } }));

    await changeInitialPassword({
      currentPassword: "old-password",
      newPassword: "new-password-123",
    }, "csrf-1", "00000000-0000-4000-8000-000000000041");

    expect(fetch).toHaveBeenCalledWith("/api/v1/auth/password", expect.objectContaining({
      method: "POST",
      credentials: "include",
      headers: expect.objectContaining({
        "X-CSRF-Token": "csrf-1",
        "Idempotency-Key": "00000000-0000-4000-8000-000000000041",
      }),
    }));
  });

  it("rejects logout without a CSRF token before contacting the server", async () => {
    await expect(logout("")).rejects.toEqual(expect.objectContaining({ code: "AUTH_CSRF_REQUIRED" }));
    expect(fetch).not.toHaveBeenCalled();
  });

  it("preserves the stable server error code", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({
      code: "AUTHENTICATION_FAILED",
      message: "Authentication failed.",
      requestId: "00000000-0000-4000-8000-000000000099",
      fieldErrors: {},
    }), { status: 401, headers: { "Content-Type": "application/json" } }));

    await expect(login({ username: "unknown", password: "wrong" }))
      .rejects.toMatchObject({
        code: "AUTHENTICATION_FAILED",
        status: 401,
      });
  });
});
