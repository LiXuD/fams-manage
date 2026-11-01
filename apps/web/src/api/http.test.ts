import { afterEach, describe, expect, it, vi } from "vitest";
import { mutationHeaders, requestBlob, requestJson, type Profile } from "./http";

const state = vi.hoisted(() => ({ session: { csrfToken: "base-csrf" as string | null } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));

class BaseError extends Error {
  constructor(readonly status: number, readonly code: string, readonly detail: string | null) { super(detail ?? code); }
}
const profile: Profile<BaseError> = {
  codeFallback: "BASE_REQUEST_FAILED",
  failure: ({ status, code, message }) => new BaseError(status, code, message),
  csrfMissing: () => new BaseError(401, "AUTH_SESSION_INVALID", null),
};

const reply = (body: unknown, status = 200) => ({
  ok: status >= 200 && status < 300, status,
  json: async () => { if (status === 204) throw new Error("empty body"); return body; },
}) as Response;

const notJson = (ok: boolean, status: number) => ({
  ok, status, headers: new Headers(), redirected: false, statusText: "", type: "basic" as const, url: "",
  json: async () => { throw new Error("响应体不是 JSON"); },
}) as unknown as Response;

type Call = [string, RequestInit];
const calls = (): Call[] => (fetch as unknown as { mock: { calls: Call[] } }).mock.calls;

afterEach(() => { vi.unstubAllGlobals(); state.session.csrfToken = "base-csrf"; });

describe("共享 HTTP 底座的请求头政策", () => {
  it("读请求不携带任何认证或幂等头，但保持同源会话", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply({ items: [] })));
    await requestJson("/things", profile);
    expect(calls()[0]![0]).toBe("/api/v1/things");
    expect(calls()[0]![1]).toMatchObject({ method: "GET", credentials: "include", headers: {} });
  });

  it("域路径前缀拼在 /api/v1 之后", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply({})));
    await requestJson("/9", profile, { prefix: "/requisitions" });
    expect(calls()[0]![0]).toBe("/api/v1/requisitions/9");
  });

  it("缺 CSRF 令牌时按域口径抛错且完全不发请求", async () => {
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher); state.session.csrfToken = null;
    await expect(requestJson("/x", profile, { method: "POST", json: {}, key: "k1" }))
      .rejects.toThrow(/AUTH_SESSION_INVALID/);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("写操作复用调用方原键，版本与请求体缺省时不声明对应头", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply({})));
    await requestJson("/x", profile, { method: "POST", key: "same-key-on-retry" });
    expect(calls()[0]![1].headers).toEqual({ "X-CSRF-Token": "base-csrf", "Idempotency-Key": "same-key-on-retry" });
  });

  it("无请求体的 POST 仍保留三件套但不声明 Content-Type", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply({})));
    await requestJson("/x", profile, { method: "POST", key: "k2", version: 7 });
    const headers = calls()[0]![1].headers as Record<string, string>;
    expect(headers["Content-Type"]).toBeUndefined();
    expect(headers["If-Match-Version"]).toBe("7");
    expect(calls()[0]![1].body).toBeUndefined();
  });

  it("FormData 请求体由浏览器自带边界，不设置 Content-Type", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply({})));
    const form = new FormData(); form.append("file", new File(["x"], "凭证.png"));
    await requestJson("/x", profile, { method: "POST", key: "k3", version: 0, form });
    expect((calls()[0]![1].headers as Record<string, string>)["Content-Type"]).toBeUndefined();
    expect(calls()[0]![1].body).toBe(form);
  });

  it("幂等键缺省时新建，且新建键与调用方键互不影响", () => {
    const given = mutationHeaders({ token: "t", key: "caller" });
    const fresh = mutationHeaders({ token: "t" });
    expect(given["Idempotency-Key"]).toBe("caller");
    expect(fresh["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(fresh["Idempotency-Key"]).not.toBe("caller");
  });
});

describe("共享 HTTP 底座的失败契约", () => {
  it("合形服务端 code 原样进入域错误，message 供域决定是否使用", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply({ code: "BASE_VERSION_CONFLICT", message: "已变更" }, 409)));
    await expect(requestJson("/x", profile, { method: "POST", key: "k", json: {} }))
      .rejects.toMatchObject({ status: 409, code: "BASE_VERSION_CONFLICT", detail: "已变更" });
  });

  it("不合形或非法 JSON 的错误体一律回落到域兜底码", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply({ code: "lowercase-code" }, 500)));
    await expect(requestJson("/x", profile)).rejects.toMatchObject({ code: "BASE_REQUEST_FAILED" });
    vi.stubGlobal("fetch", vi.fn(async () => notJson(false, 502)));
    await expect(requestJson("/x", profile)).rejects.toMatchObject({ status: 502, code: "BASE_REQUEST_FAILED", detail: null });
  });

  it("204 无响应体读为 undefined，不因其不是 JSON 而失败", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => notJson(true, 204)));
    await expect(requestJson("/x", profile)).resolves.toBeUndefined();
  });

  it("二进制下载沿用同源会话、无缓存与中止信号", async () => {
    const controller = new AbortController();
    const blob = { kind: "blob" };
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, status: 200, blob: async () => blob }) as unknown as Response));
    await expect(requestBlob("/api/v1/attachments/9/content?grant=abc", (s) => new BaseError(s, "E", null), controller.signal))
      .resolves.toBe(blob);
    expect(calls()[0]![1]).toMatchObject({ credentials: "include", cache: "no-store", signal: controller.signal });
  });

  it("二进制下载的非 2xx 交给域错误工厂", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: false, status: 404, blob: async () => null }) as unknown as Response));
    await expect(requestBlob("/api/v1/x", (status) => new BaseError(status, "PHOTO_READ_FAILED", null)))
      .rejects.toMatchObject({ status: 404, code: "PHOTO_READ_FAILED" });
  });
});
