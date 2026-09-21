import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "./api";
import { useSessionStore } from "./sessionStore";
import type { SessionResponse } from "./types";

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return {
    ...actual,
    changeInitialPassword: vi.fn(),
    getCurrentUser: vi.fn(),
    getMyPermissions: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
  };
});

const session: SessionResponse = {
  csrfToken: "csrf-1",
  idleExpiresAt: "2026-09-21T02:00:00Z",
  absoluteExpiresAt: "2026-09-21T09:30:00Z",
  requiresInitialPasswordChange: false,
  user: {
    id: "1001",
    username: "system.admin",
    status: "ENABLED",
    roles: ["SYSTEM_ADMIN"],
    requiresInitialPasswordChange: false,
  },
};

describe("session store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    sessionStorage.clear();
  });

  it("restores only from the server cookie and permission endpoints", async () => {
    sessionStorage.setItem("fams.csrf", "csrf-1");
    vi.mocked(api.getCurrentUser).mockResolvedValue(session.user);
    vi.mocked(api.getMyPermissions).mockResolvedValue({
      userId: "1001",
      permissions: ["ACCOUNT_ADMIN"],
      dataScopes: [],
      fieldPermissions: [],
      authorizationVersion: 3,
    });

    const store = useSessionStore();
    await store.restore();

    expect(store.user).toEqual(session.user);
    expect(store.permissions?.authorizationVersion).toBe(3);
    expect(localStorage.length).toBe(0);
  });

  it("requires a fresh login when a cookie exists but the tab has no CSRF token", async () => {
    vi.mocked(api.getCurrentUser).mockResolvedValue(session.user);
    vi.mocked(api.getMyPermissions).mockResolvedValue({
      userId: "1001", permissions: ["ACCOUNT_ADMIN"], dataScopes: [],
      fieldPermissions: [], authorizationVersion: 3,
    });

    const store = useSessionStore();
    await store.restore();

    expect(store.state).toBe("anonymous");
    expect(store.user).toBeNull();
    expect(store.error?.code).toBe("AUTH_CSRF_REQUIRED");
  });

  it("clears all client state when the server rejects the session", async () => {
    vi.mocked(api.getCurrentUser).mockRejectedValue(new api.AuthApiError(401, "AUTH_SESSION_INVALID", "会话已失效"));

    const store = useSessionStore();
    await store.restore();

    expect(store.user).toBeNull();
    expect(store.permissions).toBeNull();
    expect(store.state).toBe("anonymous");
  });

  it("forces the password-change route for an initial credential", async () => {
    vi.mocked(api.login).mockResolvedValue({
      ...session,
      requiresInitialPasswordChange: true,
      user: { ...session.user, requiresInitialPasswordChange: true },
    });

    const store = useSessionStore();
    await store.signIn("system.admin", "initial-password");

    expect(store.state).toBe("password-change-required");
    expect(store.csrfToken).toBe("csrf-1");
  });

  it("clears an expired session after a password-change rejection", async () => {
    vi.mocked(api.login).mockResolvedValue({
      ...session,
      requiresInitialPasswordChange: true,
      user: { ...session.user, requiresInitialPasswordChange: true },
    });
    const store = useSessionStore();
    await store.signIn("system.admin", "initial-password");
    vi.mocked(api.changeInitialPassword).mockRejectedValue(
      new api.AuthApiError(401, "AUTH_SESSION_INVALID", "会话已失效"),
    );

    await expect(store.completeInitialPasswordChange("old", "new-password-2026"))
      .rejects.toMatchObject({ code: "AUTH_SESSION_INVALID" });
    expect(store.state).toBe("anonymous");
    expect(sessionStorage.getItem("fams.csrf")).toBeNull();
  });

  it("does not pretend logout succeeded when CSRF is unavailable", async () => {
    vi.mocked(api.login).mockResolvedValue(session);
    vi.mocked(api.getMyPermissions).mockResolvedValue({
      userId: "1001", permissions: ["ACCOUNT_ADMIN"], dataScopes: [],
      fieldPermissions: [], authorizationVersion: 3,
    });
    const store = useSessionStore();
    await store.signIn("system.admin", "password");
    vi.mocked(api.logout).mockRejectedValue(
      new api.AuthApiError(403, "AUTH_CSRF_INVALID", "安全令牌无效"),
    );

    await expect(store.signOut()).rejects.toMatchObject({ code: "AUTH_CSRF_INVALID" });
    expect(store.state).toBe("authenticated");
  });
});
