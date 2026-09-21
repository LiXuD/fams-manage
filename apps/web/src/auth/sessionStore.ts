import { computed, ref } from "vue";
import { defineStore } from "pinia";
import {
  AuthApiError,
  changeInitialPassword,
  getCurrentUser,
  getMyPermissions,
  login,
  logout,
} from "./api";
import type { CurrentUser, PermissionSnapshot, SessionResponse } from "./types";

const CSRF_SESSION_KEY = "fams.csrf";

type SessionState = "booting" | "anonymous" | "authenticated" | "password-change-required";

function loadCsrfToken(): string | null {
  return sessionStorage.getItem(CSRF_SESSION_KEY);
}

function persistCsrfToken(token: string | null): void {
  if (token) {
    sessionStorage.setItem(CSRF_SESSION_KEY, token);
  } else {
    sessionStorage.removeItem(CSRF_SESSION_KEY);
  }
}

export const useSessionStore = defineStore("session", () => {
  const state = ref<SessionState>("booting");
  const user = ref<CurrentUser | null>(null);
  const permissions = ref<PermissionSnapshot | null>(null);
  const csrfToken = ref<string | null>(loadCsrfToken());
  const error = ref<AuthApiError | null>(null);
  const isBusy = ref(false);

  const isAuthenticated = computed(() =>
    state.value === "authenticated" || state.value === "password-change-required");

  function acceptSession(session: SessionResponse): void {
    user.value = session.user;
    csrfToken.value = session.csrfToken;
    persistCsrfToken(session.csrfToken);
    permissions.value = null;
    state.value = session.requiresInitialPasswordChange
      ? "password-change-required"
      : "authenticated";
  }

  function clearSession(): void {
    user.value = null;
    permissions.value = null;
    csrfToken.value = null;
    persistCsrfToken(null);
    state.value = "anonymous";
  }

  async function restore(): Promise<void> {
    state.value = "booting";
    error.value = null;
    try {
      const [currentUser, permissionSnapshot] = await Promise.all([
        getCurrentUser(),
        getMyPermissions(),
      ]);
      if (!csrfToken.value) {
        clearSession();
        error.value = new AuthApiError(403, "AUTH_CSRF_REQUIRED", "请重新登录以建立安全会话。");
        return;
      }
      user.value = currentUser;
      permissions.value = permissionSnapshot;
      state.value = currentUser.requiresInitialPasswordChange
        ? "password-change-required"
        : "authenticated";
    } catch (cause) {
      if (cause instanceof AuthApiError && (cause.status === 401 || cause.status === 403)) {
        clearSession();
        return;
      }
      clearSession();
      error.value = cause instanceof AuthApiError
        ? cause
        : new AuthApiError(0, "AUTH_NETWORK_FAILURE", "暂时无法连接服务，请稍后重试。");
    }
  }

  async function refreshPermissions(): Promise<void> {
    permissions.value = await getMyPermissions();
  }

  async function signIn(username: string, password: string): Promise<void> {
    isBusy.value = true;
    error.value = null;
    try {
      const session = await login({ username, password });
      acceptSession(session);
      if (!session.requiresInitialPasswordChange) {
        await refreshPermissions();
      }
    } catch (cause) {
      clearSession();
      error.value = cause instanceof AuthApiError
        ? cause
        : new AuthApiError(0, "AUTH_NETWORK_FAILURE", "暂时无法连接服务，请稍后重试。");
      throw error.value;
    } finally {
      isBusy.value = false;
    }
  }

  async function completeInitialPasswordChange(currentPassword: string, newPassword: string): Promise<void> {
    isBusy.value = true;
    error.value = null;
    try {
      const session = await changeInitialPassword(
        { currentPassword, newPassword },
        csrfToken.value ?? "",
        crypto.randomUUID(),
      );
      acceptSession(session);
      await refreshPermissions();
    } catch (cause) {
      if (cause instanceof AuthApiError && (cause.status === 401 || cause.status === 403)) {
        clearSession();
      }
      error.value = cause instanceof AuthApiError
        ? cause
        : new AuthApiError(0, "AUTH_NETWORK_FAILURE", "暂时无法连接服务，请稍后重试。");
      throw error.value;
    } finally {
      isBusy.value = false;
    }
  }

  async function signOut(): Promise<void> {
    isBusy.value = true;
    error.value = null;
    try {
      await logout(csrfToken.value ?? "");
      clearSession();
    } catch (cause) {
      if (cause instanceof AuthApiError && cause.status === 401) {
        clearSession();
        return;
      }
      error.value = cause instanceof AuthApiError
        ? cause
        : new AuthApiError(0, "AUTH_NETWORK_FAILURE", "暂时无法连接服务，请稍后重试。");
      throw error.value;
    } finally {
      isBusy.value = false;
    }
  }

  function hasPermission(permission: string): boolean {
    return permissions.value?.permissions.includes(permission) ?? false;
  }

  return {
    state,
    user,
    permissions,
    csrfToken,
    error,
    isBusy,
    isAuthenticated,
    restore,
    refreshPermissions,
    signIn,
    completeInitialPasswordChange,
    signOut,
    hasPermission,
  };
});
