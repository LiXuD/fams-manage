import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, repairProblem, repairRequest, type RepairMutation } from "./api";
export interface FactoryMutation extends RepairMutation {
  repairId: string;
  form?: Readonly<Record<string, string>>;
  label: string;
}
export async function factoryRequest<T>(repair: string, path = "", mutation?: FactoryMutation): Promise<T> {
  const route = `/${repair}/factory${path}`;
  if (!mutation?.file) return repairRequest<T>(route, mutation);
  const token = useSessionStore().csrfToken;
  if (!token) throw new RepairApiError(401, "AUTH_SESSION_INVALID", {});
  const body = new FormData();
  body.append("file", mutation.file, mutation.file.name);
  for (const [key, value] of Object.entries(mutation.form ?? {})) body.append(key, value);
  const response = await fetch("/api/v1/repairs" + route, {
    credentials: "include", method: "POST", body,
    headers: { "X-CSRF-Token": token, "Idempotency-Key": mutation.key, "If-Match-Version": String(mutation.version) },
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({})) as { code?: unknown; fieldErrors?: Record<string, unknown> };
    throw new RepairApiError(response.status, typeof data.code === "string" ? data.code : "REPAIR_FACTORY_UPLOAD_FAILED",
      Object.fromEntries(Object.entries(data.fieldErrors ?? {}).filter((pair): pair is [string, string] => typeof pair[1] === "string")));
  }
  return await response.json() as T;
}
export const factoryRoundStates: Record<string, string> = {
  PENDING_DISPATCH: "待寄出", DISPATCHED: "已寄出，待原机收回", RECEIVED: "原机已实际收回", CANCELLED: "待寄出轮次已取消",
};
export const factoryScopeStates: Record<string, string> = {
  PENDING: "待管理员确认返厂范围", APPROVED: "已批准返厂范围", REJECTED: "范围变更已驳回", WITHDRAWN: "范围申请已撤回",
};
export const factoryEventNames: Record<string, string> = {
  FACTORY_SCOPE_REQUESTED: "库管已提出返厂范围变更", FACTORY_SCOPE_APPROVED: "管理员已批准返厂范围",
  FACTORY_SCOPE_REJECTED: "管理员已驳回范围变更", FACTORY_SCOPE_WITHDRAWN: "范围变更已撤回",
  FACTORY_ROUND_CREATED: "已建立待寄出轮次", FACTORY_ROUND_CANCELLED: "已取消待寄出轮次",
  FACTORY_ROUND_DISPATCHED: "实物已实际寄出", FACTORY_ROUND_RECEIVED: "原实物已核实收回",
  FACTORY_QUOTE_RECORDED: "已登记本轮报价", FACTORY_COST_RECORDED: "已确认本轮最终费用", FACTORY_FOLLOWUP_RECORDED: "已登记返厂后续资料",
  FACTORY_EVIDENCE_ADDED: "已追加厂家凭证", FACTORY_CONFLICT_REJECTED: "过期办理已拒绝",
};
export function factoryProblem(cause: unknown): string {
  if (cause instanceof RepairApiError) {
    const messages: Record<string, string> = {
      REPAIR_FACTORY_FOLLOWUP_OPEN: "返厂实物或费用仍有未结事项，请先核对当前进度；维修锁继续保留。",
      REPAIR_FACTORY_COST_POLICY_INVALID: "请选择明确费用约束：无费用、累计正限额或不限费用。",
      REPAIR_FACTORY_QUOTE_REQUIRED: "累计限额返厂须先登记本轮报价并选用报价原件。",
      REPAIR_FACTORY_FINAL_COST_ALREADY_RECORDED: "本轮最终费用已确认，请刷新核对原记录，不能重复累计或覆盖。",
      REPAIR_FACTORY_COST_ORIGINAL_REQUIRED: "报价和正费用必须选用本轮对应用途的原始凭证。",
      REPAIR_FACTORY_COST_ASSESSMENT_REQUIRED: "请明确费用已核实或待核实，并亲自核对最终费用。",
      REPAIR_FACTORY_SCOPE_BASIS_CHANGED: "范围申请依据已变化，请撤回原未决申请，按当前事实重新申请。",
      REPAIR_FACTORY_ORIGINAL_SERIAL_MISMATCH: "现场原机序列号不匹配。整机换新需要独立新卡，不能当作原机收回。",
      REPAIR_FACTORY_ORIGINAL_CHANGED: "原机身份资料已变化，请保留原证据并核对，不能覆盖原轮次。",
      REPAIR_FACTORY_ORIGINAL_RECEIPT_REQUIRED: "请核实确为原实物，选择实际状况并填写处理结果。",
      REPAIR_FACTORY_PHYSICAL_TIME_INVALID: "请填写真实办理时间，不得早于本轮已有事实或填写未来时间。",
      REPAIR_FACTORY_EXPECTED_DATE_INVALID: "预计收回日期不能早于实际寄出日期，请取消尚未寄出的轮次后重新填写。",
      REPAIR_FACTORY_COST_SCOPE_REQUIRED: "报价或累计费用不在现批准范围，或原费用尚待核实。请核对实际费用并提出明确范围变更；实际原机可按费用待核实先行收回。",
      REPAIR_FACTORY_EVIDENCE_CHANGED: "所选凭证不属于本轮对应办理阶段或已不可用，请重新核对原件。",
      REPAIR_FACTORY_EVIDENCE_UNAVAILABLE: "原件服务暂不可用，请保留实物及原始凭证后重试。",
      REPAIR_FACTORY_SCAN_UNAVAILABLE: "扫描服务暂不可用，凭证尚未登记，请保留原文件重试。",
      REPAIR_FACTORY_EVIDENCE_REJECTED: "凭证未通过安全检查，请核实原文件；当前轮次没有因此完成。",
      REPAIR_FACTORY_SCOPE_NOT_AVAILABLE: "须先由当前处理库管实际接修，且没有未结返还或范围申请，才能提出返厂变更。",
      REPAIR_FACTORY_ROUND_NOT_AVAILABLE: "须有明确批准范围且没有活动轮次，才能新建待寄出轮次。",
    };
    if (messages[cause.code]) return messages[cause.code];
    if ([400, 413, 422].includes(cause.status)) return "请核对厂家、实际日期、原机身份和凭证；图片不超过10MB，PDF不超过20MB。";
  }
  return repairProblem(cause);
}
