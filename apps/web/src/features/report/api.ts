import { requestJson, type Profile } from "@/api/http";

/**
 * 运营报表域的读取底座。
 *
 * 报表端点集中在 /operations-reports 与 /workbench 两个前缀下；本域只有读路径，
 * 错误文案由各面板按功能线口径自持，这里只保证失败带得上状态码。
 */
export class ReportApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

const profile: Profile<ReportApiError> = {
  codeFallback: "REPORT_REQUEST_FAILED",
  failure: ({ status, code }) => new ReportApiError(status, code),
  csrfMissing: () => new ReportApiError(401, "AUTH_SESSION_INVALID"),
};

export function reportRequest<T>(path: string): Promise<T> {
  return requestJson<T, ReportApiError>(path, profile, { noStore: true });
}
