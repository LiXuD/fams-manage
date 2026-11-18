import { BusinessApiError } from "@/api/business";
import { requestJson, type Profile } from "@/api/http";

/**
 * 账号角色授权写底座。
 *
 * PATCH/POST 授权提交走共用 requestJson；错误类沿用全站 BusinessApiError，
 * 状态码到中文文案的映射仍留在调用面板，与本系列其它域 api 模块同一分工。
 */
const authorizationProfile: Profile<BusinessApiError> = {
  codeFallback: "AUTHORIZATION_REQUEST_FAILED",
  failure: ({ status, code }) => new BusinessApiError(status, code),
  csrfMissing: () => new BusinessApiError(401, "AUTH_SESSION_INVALID"),
};

export function authorizationRequest<T>(path: string, input: {
  method: "PATCH" | "POST"; key: string; version: number; body: unknown;
}): Promise<T> {
  return requestJson<T, BusinessApiError>(path, authorizationProfile,
    { method: input.method, key: input.key, version: input.version, json: input.body });
}
