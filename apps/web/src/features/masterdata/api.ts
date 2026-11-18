import { requestJson, type Profile } from "@/api/http";

export interface MasterDataMutation {
  path: string; method: "POST" | "PATCH"; key: string; version: number; body: unknown;
}
export class MasterDataApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

/** 本域的失败三元组形状：错误码不合形时落回主数据请求兜底码。 */
const profile: Profile<MasterDataApiError> = {
  codeFallback: "MASTER_DATA_REQUEST_FAILED",
  failure: ({ status, code }) => new MasterDataApiError(status, code),
  csrfMissing: () => new MasterDataApiError(401, "AUTH_SESSION_INVALID"),
};

/**
 * 主数据读写。GET 不带写头；带 mutation 即写三件套（CSRF + 幂等键 + If-Match-Version），
 * 方法由 mutation.method 决定（POST/PATCH），重试必须复用同一把键。
 */
export function masterDataRequest<T>(path: string, mutation?: MasterDataMutation): Promise<T> {
  return requestJson<T, MasterDataApiError>(path, profile, mutation
    ? { method: mutation.method, key: mutation.key, version: mutation.version, json: mutation.body }
    : {});
}

/** 身份 CSV 导入的失败形状：额外保留服务端 message 供 400 分支原样播报。 */
export class IdentityImportApiError extends Error {
  constructor(readonly status: number, readonly code: string, readonly serverMessage: string | null) { super(code); }
}

const importProfile: Profile<IdentityImportApiError> = {
  codeFallback: "IDENTITY_IMPORT_REQUEST_FAILED",
  failure: ({ status, code, message }) => new IdentityImportApiError(status, code, message),
  csrfMissing: () => new IdentityImportApiError(401, "AUTH_SESSION_INVALID", null),
};

/** 身份 CSV 导入：text/csv 原文作请求体，幂等键由调用方提交底座持有、重试不换键；不带版本头。 */
export function identityImportCsv<T>(csv: string, key: string): Promise<T> {
  return requestJson<T, IdentityImportApiError>("/identity-imports", importProfile,
    { method: "POST", key, text: csv, textContentType: "text/csv;charset=utf-8" });
}
