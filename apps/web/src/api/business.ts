import { requestJson, type Profile } from "@/api/http";

/**
 * 跨业务域共用的「带乐观版本与幂等键的写请求」内核。
 *
 * 入库、调拨、领用、借用、退还退库、盘点、维修与代确认都按同一形状提交写操作：POST + 调用方持有的
 * Idempotency-Key + If-Match-Version。它原本寄生在 loan/api.ts 里并被 6 个域跨域引用，
 * 现独立成内核；各域的中文文案字典不在此处，归属各自的域模块。
 */

export interface BusinessMutation { path: string; key: string; version: number; body?: unknown }

export class BusinessApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

export function businessMessage(error: unknown): string {
  if (error instanceof BusinessApiError) {
    if (error.status === 401) return "登录已失效，请重新登录。";
    if (error.status === 403 || error.status === 404) return "没有当前记录或操作的权限。";
    if (error.status === 409) return "责任、处理权、日期或资料已变更，请刷新核对。";
    if (error.status === 400) return "请核对日期、原因、实物编码和仓位。";
  }
  return "操作结果尚未确认，请重试上次操作或刷新查询。";
}

const profile: Profile<BusinessApiError> = {
  codeFallback: "BUSINESS_REQUEST_FAILED",
  failure: ({ status, code }) => new BusinessApiError(status, code),
  csrfMissing: () => new BusinessApiError(401, "AUTH_SESSION_INVALID"),
};

export async function businessRequest<T>(path: string, mutation?: BusinessMutation): Promise<T> {
  return requestJson<T, BusinessApiError>(path, profile, mutation
    ? { method: "POST", key: mutation.key, version: mutation.version, json: mutation.body }
    : {});
}
