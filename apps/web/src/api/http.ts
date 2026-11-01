import { useSessionStore } from "@/auth/sessionStore";

/**
 * 各业务域 api 的共用 HTTP 底座。
 *
 * 只做机制：会话令牌、写操作三件套请求头、失败三元组解析、JSON 读取。
 * 不做政策：错误类、状态码到中文文案的映射、路径前缀都留在各域，避免一次改文案就波及全站。
 */

export type HttpMethod = "GET" | "POST" | "PATCH" | "PUT" | "DELETE";

export interface Page<T> { page: number; pageSize: number; total: number; items: T[] }

/** 后端契约的错误码形状；不合形的服务端串不得进入用户可见分支。 */
const STABLE_CODE = /^[A-Z][A-Z0-9_]{0,63}$/;

export interface Failure {
  status: number;
  /** 服务端合规 code，缺省或不合形时为域的 codeFallback；永不为空，供错误类直接携带 */
  code: string;
  /** 服务端原样给出的合规 code；未给出则为 null。仅用于「无 message 时展示错误码」这类既有口径 */
  serverCode: string | null;
  /** 服务端 message；未给出则为 null，由域决定是否使用 */
  message: string | null;
  /** 只保留字符串值的字段错误 */
  fields: Record<string, string>;
}

export interface Profile<E> {
  codeFallback: string;
  failure: (failure: Failure) => E;
  /** 写操作缺 CSRF 令牌时的域错误 */
  csrfMissing: () => E;
}

export interface RequestInit {
  method?: HttpMethod;
  /** 追加在 /api/v1 之后的域路径前缀，如 "/repairs" */
  prefix?: string;
  json?: unknown;
  form?: FormData;
  /** 写操作幂等键。重试必须复用原键；缺省时新建（用于无重试语义的提交） */
  key?: string;
  version?: number;
  /** 附件与照片类读取需要绕过 HTTP 缓存 */
  noStore?: boolean;
}

export function apiUrl(path: string, prefix = ""): string {
  return `/api/v1${prefix}${path}`;
}

export function sessionCsrfToken(): string | null {
  return useSessionStore().csrfToken;
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

/** 写操作请求头；json 为 true 时才声明 Content-Type，FormData 由浏览器自带边界。 */
export function mutationHeaders(input: {
  token: string;
  key?: string;
  version?: number;
  json?: boolean;
}): Record<string, string> {
  const headers: Record<string, string> = {
    "X-CSRF-Token": input.token,
    "Idempotency-Key": input.key ?? newIdempotencyKey(),
  };
  if (input.version !== undefined) headers["If-Match-Version"] = String(input.version);
  if (input.json) headers["Content-Type"] = "application/json";
  return headers;
}

export function fileForm(file: File, fields: Record<string, string> = {}, filename?: string): FormData {
  const form = new FormData();
  form.append("file", file, filename ?? file.name);
  for (const [name, value] of Object.entries(fields)) form.append(name, value);
  return form;
}

/** 非 2xx 的响应体解析。坏 JSON 不得掩盖原始状态码，故一律回落到域兜底码。 */
export async function parseFailure(response: Response, codeFallback: string): Promise<Failure> {
  const body = await response.json().catch(() => ({})) as { code?: unknown; message?: unknown; fieldErrors?: unknown };
  const serverCode = typeof body.code === "string" && STABLE_CODE.test(body.code) ? body.code : null;
  const message = typeof body.message === "string" && body.message.length > 0 ? body.message : null;
  const fields = Object.fromEntries(Object.entries(
    (body.fieldErrors ?? {}) as Record<string, unknown>,
  ).filter((row): row is [string, string] => typeof row[1] === "string"));
  return { status: response.status, code: serverCode ?? codeFallback, serverCode, message, fields };
}

/** 读取 2xx 的 JSON 响应体；空响应体（204 等）返回 undefined。 */
export async function readJson<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  return await response.json() as T;
}

/**
 * 同源会话下的二进制内容下载。授权令牌由调用方（或域函数）按既有口径校验后原样传入，
 * 本函数不重新拼装 URL，以免「校验一个地址、请求另一个地址」。
 * 仅适用于 fetch→状态码→blob 的机械序列；需要在中间穿插活性检查的下载点继续自持。
 */
export async function requestBlob<E>(url: string, failed: (status: number) => E, signal?: AbortSignal): Promise<Blob> {
  const response = await fetch(url, { credentials: "include", cache: "no-store", signal });
  if (!response.ok) throw failed(response.status);
  return await response.blob();
}

export async function requestJson<T, E>(path: string, profile: Profile<E>, init: RequestInit = {}): Promise<T> {
  const method = init.method ?? "GET";
  const headers: Record<string, string> = {};
  let body: BodyInit | undefined;
  if (method !== "GET") {
    const token = sessionCsrfToken();
    if (!token) throw profile.csrfMissing();
    Object.assign(headers, mutationHeaders({
      token,
      key: init.key,
      version: init.version,
      json: init.json !== undefined,
    }));
    if (init.form) body = init.form;
    else if (init.json !== undefined) body = JSON.stringify(init.json);
  }
  const response = await fetch(apiUrl(path, init.prefix), {
    method,
    credentials: "include",
    headers,
    ...(init.noStore ? { cache: "no-store" as const } : {}),
    body,
  });
  if (!response.ok) throw profile.failure(await parseFailure(response, profile.codeFallback));
  return await readJson<T>(response);
}
