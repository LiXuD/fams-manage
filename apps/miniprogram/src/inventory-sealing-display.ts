/** Present only frozen report values; never look up current business rows. */
const record = (value: unknown): Record<string, unknown> => value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
const text = (value: unknown) => typeof value === "string" || typeof value === "number" ? String(value) : "未记录";
const list = (value: unknown): unknown[] => Array.isArray(value) ? value : [];
const synchronizationReasons: Record<string, string> = {
    DECLARED: "当前已声明", PARTICIPATION_REVOKED: "参与关系已撤销", IDENTITY_DISABLED: "身份已停用或失效", BINDING_MISSING: "当前没有有效绑定",
    NO_DEVICE_DECLARATION: "尚无设备声明", DEVICE_CONFIRMATION_MISSING: "仍有设备缺少确认", UNBOUND_OBSERVATION: "原观察没有绑定依据",
    BINDING_CHANGED: "绑定已变化", NO_DECLARATION: "尚未声明", ASSIGNMENT_CHANGED: "任务分配已变化", NEW_OBSERVATION: "声明后有新上传", LOCAL_EVIDENCE_MISSING: "本机有缺失证据"
};
export const synchronizationReasonText = (value: string) => synchronizationReasons[value] || value;
const dimensions: Record<string, string> = { NORMAL: "正常复核", LOCATION: "位置", USAGE_MODE: "使用模式", RESPONSIBILITY: "责任", CONDITION: "实物状况",
    UNKNOWN: "未知信息", PERIOD_MOVEMENT: "期间流转", CONFLICT: "证据冲突", OLD_ASSIGNMENT: "旧分配", TIME: "时间异常", FINDING: "盘盈或任务外线索", LATE: "迟到" };
const states: Record<string, string> = { ACCEPTED: "已采信", NOT_OBSERVED: "明确未盘，不计正常", SELECTED: "待采信", OPEN: "待处理", PROCESSING: "处理中", CLOSED: "已闭环", TRANSFERRED: "已转后续业务" };
const outcomes: Record<string, string> = { ACCEPTED: "正常候选", DIFFERENCE_REVIEW: "差异待复核", CONFLICT_REVIEW: "冲突待复核", OLD_ASSIGNMENT_REVIEW: "旧分配待复核",
    TIME_REVIEW: "时间待复核", FINDING_REVIEW: "盘盈或任务外线索", LATE_REVIEW: "迟到待复核", DUPLICATE: "重复，不计数", EXPIRED: "过期，不采信" };
const unobserved: Record<string, string> = { UNABLE_TO_OBSERVE: "无法实盘", EVIDENCE_UNAVAILABLE: "证据失效或不可用", ASSET_NOT_FOUND: "实物未找到" };
export function sealedRecordLines(value: unknown): string[] {
    const row = record(value), item = record(row.item), observation = record(row.observation), lines: string[] = [];
    if (item.id) {
        const snapshot = record(item.snapshot_json), review = record(row.review), date = record(row.dateChange);
        lines.push(`${text(snapshot.assetCode)} · ${text(snapshot.name)}`, `冻结复核：${states[text(review.review_status)] || text(review.review_status)}`,
            `处理人：${text(review.reviewer_username)} · ${text(review.reviewed_at)}`, `原因：${text(review.reason)}`, `依据：${text(review.evidence)}`);
        if (date.observedAt) lines.push(`已采信观察时间：${text(date.observedAt)}`, `本次观察业务日期：${text(date.businessDate)} · 最终最近盘点日期：${text(date.effectiveDate)}`);
        for (const prior of list(row.unobservedHistory)) {
            const history = record(prior);
            lines.push(`未盘历史：${unobserved[text(history.reason_kind)] || text(history.reason_kind)} · ${text(history.reason)} · ${text(history.actor_username)} · ${text(history.recorded_at)}`);
        }
        return lines;
    }
    if (observation.id) {
        const facts = record(observation.facts_json), actual = record(facts.actual);
        lines.push(`原始证据 ${text(observation.id)} · ${outcomes[text(observation.outcome)] || text(observation.outcome)}`,
            `原观察时间：${text(observation.raw_observed_at)} · 接收时间：${text(observation.received_at)}`);
        if (actual.condition) lines.push(`现场位置：${text(actual.locationHint || actual.locationId)} · 状况：${({ GOOD: "正常", DAMAGED: "损坏", UNKNOWN: "未知" } as Record<string, string>)[text(actual.condition)] || text(actual.condition)}`);
        if (actual.unknownReason) lines.push(`未知原因：${text(actual.unknownReason)}`);
        for (const value of list(row.subjects)) {
            const subject = record(value);
            lines.push(`${dimensions[text(subject.dimension)] || text(subject.dimension)}：${states[text(subject.subject_status)] || text(subject.subject_status)} · ${text(subject.reviewer_username)}`,
                `处理依据：${text(subject.reason)} · ${text(subject.evidence)}`);
        }
        for (const value of list(row.transfers)) {
            const link = record(value); lines.push(`关联正式调拨：${text(link.request_id)} · 决定版本 ${text(link.decision_version)}`);
        }
        for (const value of list(row.findingLinks)) {
            const link = record(value); lines.push(`盘盈关联资产：${text(link.asset_id)} · 入库单 ${text(link.inbound_receipt_id)}`);
        }
        return lines;
    }
    if (row.accountId) {
        lines.push(`${text(row.name)} · ${row.current ? "当前已声明" : synchronizationReasonText(text(row.reason))}`);
        for (const value of list(row.devices)) {
            const device = record(value); lines.push(`设备 ${text(device.deviceId)} · ${device.current ? "当前已声明" : synchronizationReasonText(text(device.reason))}`);
        }
        return lines;
    }
    return [`冻结记录 ${text(row.id)}；完整依据随报告保留。`];
}
const blockers: Record<string, string> = {
    INVENTORY_SEAL_WAITING_REVIEW_REQUIRED: "尚未进入待复核", INVENTORY_SEAL_FROZEN_RANGE_MISMATCH: "应盘集合与发布数量不一致",
    INVENTORY_SEAL_PHYSICAL_MISSING_OPEN: "存在尚未闭环的实物未找到记录", INVENTORY_SEAL_ASSET_UNAVAILABLE: "资产卡当前不可用于封存",
    INVENTORY_SEAL_REVIEW_REQUIRED: "应盘项尚未完成复核", INVENTORY_SEAL_UNOBSERVED_EVIDENCE_CHANGED: "未盘项已有新证据或原因缺失，须重新核对",
    INVENTORY_SEAL_ACCEPTANCE_REQUIRED: "仍有尚未最终采信的应盘项", INVENTORY_SEAL_OBSERVATION_REQUIRED: "采信未关联本轮有效原观察",
    INVENTORY_SEAL_OBSERVATION_INELIGIBLE: "原观察不符合采信条件", INVENTORY_SEAL_ACCEPTED_CONTEXT_CHANGED: "采信后资产或期间流转已变化，须明确复盘",
    INVENTORY_SEAL_UNDECIDED_EVIDENCE: "存在尚未处置的原始证据", INVENTORY_SEAL_ITEMLESS_TIME_AND_FINDING_REQUIRED: "任务外时间异常与实物线索须分别核实",
    INVENTORY_SEAL_UNRESOLVED_DIMENSIONS: "仍有独立差异尚未闭环", INVENTORY_SEAL_NORMAL_CONTEXT_CHANGED: "正常采信后账实或期间流转发生变化，须复盘",
    INVENTORY_SEAL_NORMAL_LINEAGE_REQUIRED: "正常采信的复核依据不完整"
};
export const sealBlockerText = (value: string) => blockers[value] || "封存条件尚未满足，请核对任务与原始证据";
