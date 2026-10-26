import type { MiniServices } from "./pages.ts";
import { MiniRequestError, type FactoryEvidenceFile } from "./session.ts";
import type { FactoryDetail, FactoryFile, FactoryHistory, FactoryList, FactoryRound, FactoryRoundDetail } from "./repair-factory-types.ts";

interface Place { id: string; name: string; version: number }
interface Intent { readonly path: string; readonly key: string; readonly version: number; readonly body?: string;
    readonly file?: FactoryEvidenceFile; readonly form?: Readonly<{ roundVersion: number; assetVersion: number; ownershipVersion: number; lockVersion: number; purpose: string }>;
    readonly label: string }
interface Data {
    detail: FactoryDetail | null; rows: FactoryRound[]; selected: FactoryRoundDetail | null; history: FactoryHistory[];
    page: number; total: number; historyPage: number; historyTotal: number; busy: boolean; stale: boolean; retry: boolean;
    error: string; notice: string; confirmation: string; pendingLabel: string;
    reason: string; provider: string; contact: string; phone: string; due: string; fault: string;
    observedCode: string; serial: string; actualDate: string; actualTime: string; evidence: string;
    originalChecked: boolean; noCostChecked: boolean; condition: string; conditionIndex: number;
    locations: Place[]; locationId: string; locationPage: number; locationTotal: number; evidenceIds: string[];
    eligibleFiles: { object_id: string; filename: string }[]; activeSelected: boolean;
    fileName: string; purposeIndex: number; purposeLabels: string[];
    costPolicyIndex: number; costPolicyLabels: string[]; costLimit: string; approvedCostLabel: string; pendingCostLabel: string;
    moneyAmount: string; moneyReason: string; moneyChecked: boolean; moneyEvidenceIds: string[]; costUnassessed: boolean;
    financialFiles: { object_id: string; filename: string; purpose: string }[]; purposeKinds: string[];
    previewPath: string; previewName: string; previewMime: string;
}
interface Context { data: Data; setData(update: Partial<Data>): void }
type Action = "requestCostScope" | "quote" | "cost" | "requestScope" | "approveScope" | "rejectScope" | "withdrawScope" | "createRound" | "cancelRound" | "dispatch" | "receive" | "upload";
class FactoryFormError extends Error {}
const moneyPattern = /^(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,2})?$/;
const purposeNames: Record<string, string> = { FAULT: "实际故障", FACTORY_ACK: "厂家接收依据", RECEIPT: "原机收回依据", QUOTE: "本轮报价原件", COST_RECEIPT: "本轮实际费用凭证" };
const costPolicies = ["NO_COST", "LIMITED", "UNLIMITED"] as const;
const costText = (policy?: string, limit?: string | null) => policy === "UNLIMITED" ? "费用不限" : policy === "LIMITED" ? "所有轮次累计限额 " + (limit ?? "待核对") + " 元" : "无费用";
const idPattern = /^[1-9][0-9]{0,18}$/;
const blank = (): Data => ({ detail: null, rows: [], selected: null, history: [], page: 1, total: 0, historyPage: 1, historyTotal: 0,
    busy: false, stale: true, retry: false, error: "", notice: "", confirmation: "", pendingLabel: "", reason: "", provider: "", contact: "",
    phone: "", due: "", fault: "", observedCode: "", serial: "", actualDate: "", actualTime: "", evidence: "", originalChecked: false,
    noCostChecked: false, condition: "", conditionIndex: 0, locations: [], locationId: "", locationPage: 1, locationTotal: 0, evidenceIds: [],
    eligibleFiles: [], activeSelected: false, fileName: "", purposeIndex: 1, purposeLabels: ["实际故障", "厂家接收依据", "本轮报价原件", "本轮实际费用凭证"],
    costPolicyIndex: 0, costPolicyLabels: ["无费用", "所有轮次累计限额", "不限费用"], costLimit: "", approvedCostLabel: "", pendingCostLabel: "",
    moneyAmount: "", moneyReason: "", moneyChecked: false, moneyEvidenceIds: [], costUnassessed: false, financialFiles: [],
    purposeKinds: ["FAULT", "FACTORY_ACK", "QUOTE", "COST_RECEIPT"],
    previewPath: "", previewName: "", previewMime: "" });
const clock = (date: Date) => [date.getHours(), date.getMinutes(), date.getSeconds()].map(n => String(n).padStart(2, "0")).join(":");
const day = (date: Date) => [date.getFullYear(), String(date.getMonth() + 1).padStart(2, "0"), String(date.getDate()).padStart(2, "0")].join("-");
function physicalTime(date: string, time: string): string {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}:\d{2}$/.test(time)) throw new FactoryFormError("实际日期和含秒时刻尚未核实。");
    const parsed = new Date(date + "T" + time);
    if (!Number.isFinite(parsed.getTime()) || day(parsed) !== date || clock(parsed) !== time) throw new FactoryFormError("实际日期或时刻无效。");
    return parsed.toISOString();
}

/** Each Page owns one frozen intent. Readonly refresh/hide never turns it into a new decision. */
export function createRepairFactoryPage(getServices: () => MiniServices) {
    let services: MiniServices, repairId = "", selectedId = "", identity = "", live = false, generation = 0;
    let pending: Intent | null = null, prepared: Intent | null = null, preview = "", actualInstant = "", chosen: { path: string; name: string; size: number } | null = null;
    const owner = () => JSON.stringify([services.sessions.identity, services.sessions.identityRevision]);
    const current = (epoch: number) => live && epoch === generation && identity === owner();
    const route = (path = "") => "/api/v1/mini/repairs/" + repairId + "/factory" + path;
    const discard = (file?: FactoryEvidenceFile) => { if (file) services.sessions.discardFactoryEvidence(file.path); };
    const clearPreview = () => { const old = preview; preview = ""; if (old) services.sessions.discardFactoryEvidence(old); };
    const wipe = (page: Context, message: string) => {
        const files = new Set([pending?.file, prepared?.file]); pending = null; prepared = null; chosen = null; selectedId = ""; actualInstant = "";
        page.setData({ ...blank(), error: message });
        try { clearPreview(); } finally { for (const file of files) discard(file); }
    };
    const ensureCurrent = (page: Context) => {
        if (!live) return false;
        if (identity === owner()) return true;
        try { wipe(page, "身份已变化，原资料已隐藏，请重新进入。"); }
        catch { page.setData({ ...blank(), error: "资料已隐藏，原件清理待重新启动重试。" }); }
        return false;
    };
    const failure = (page: Context, error: unknown) => {
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) {
            try { wipe(page, "当前身份、处理权或记录已失效，原资料已隐藏。"); } catch { page.setData({ error: "资料已隐藏；原件清理未完成，请稍后重试或重新启动。" }); }
        } else if (error instanceof MiniRequestError && [400, 409, 413, 422].includes(error.status)) {
            const file = pending?.file; pending = null;
            try { discard(file); } catch { /* The file owner registry retains cleanup for retry. */ } finally { page.setData({ retry: false, pendingLabel: "", stale: true,
                error: "办理已拒绝。请刷新核对当前范围、版本、原机身份、真实时间与原件，再重新填写。" }); }
        } else page.setData({ stale: true, retry: !!pending, error: pending ? "结果尚未确认，请核对只读进度后用原操作重试。" : "读取或原件处理未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => {
        if (!live || epoch !== generation) return;
        if (identity !== owner()) {
            try { wipe(page, "身份已变化，原资料已隐藏，请重新进入。"); } catch { page.setData({ ...blank(), error: "身份已变化，原件清理待重新启动重试。" }); }
        } else page.setData({ busy: false });
    };
    const readState = async (page: Context, epoch: number): Promise<boolean> => {
        const detail = await services.sessions.request("GET", route()) as FactoryDetail;
        if (!current(epoch)) return false;
        const list = await services.sessions.request("GET", route("/rounds?page=" + page.data.page + "&pageSize=20")) as FactoryList<FactoryRound>;
        if (!current(epoch)) return false;
        const events = await services.sessions.request("GET", route("/history?page=" + page.data.historyPage + "&pageSize=20")) as FactoryList<FactoryHistory>;
        if (!current(epoch)) return false;
        const next = selectedId || detail.activeRound?.id || list.items[0]?.id || "";
        const selected = next ? await services.sessions.request("GET", route("/rounds/" + next)) as FactoryRoundDetail : null;
        if (!current(epoch)) return false;
        selectedId = next;
        const purpose = detail.activeRound?.roundStatus === "DISPATCHED" ? "RECEIPT" : "FACTORY_ACK";
        const activeSelected = !!selected && selected.id === detail.activeRound?.id;
        const purposeKinds = activeSelected ? ["FAULT", purpose, "QUOTE", "COST_RECEIPT"] : ["QUOTE", "COST_RECEIPT"];
        const files = new Map(selected?.files.filter(f => f.scanStatus === "CLEAN").map(f => [f.objectId, f.filename]) ?? []);
        page.setData({ detail, rows: list.items, total: list.total, history: events.items, historyTotal: events.total, selected,
            activeSelected, stale: false, retry: !!pending, pendingLabel: pending?.label ?? "",
            eligibleFiles: selected?.evidence.filter(f => f.purpose === purpose && files.has(f.object_id)).map(f => ({ object_id: f.object_id, filename: files.get(f.object_id)! })) ?? [],
            approvedCostLabel: detail.approvedScope ? costText(detail.approvedScope.costPolicy, detail.approvedScope.costLimit) : "尚无批准范围",
            pendingCostLabel: detail.pendingScope ? costText(detail.pendingScope.costPolicy, detail.pendingScope.costLimit) : "",
            financialFiles: selected?.evidence.filter(f => ["QUOTE", "COST_RECEIPT"].includes(f.purpose) && files.has(f.object_id)).map(f => ({ object_id: f.object_id, filename: files.get(f.object_id)!, purpose: f.purpose })) ?? [],
            purposeKinds, purposeLabels: purposeKinds.map(p => purposeNames[p]!), purposeIndex: 1 });
        return true;
    };
    const read = async (page: Context) => {
        const epoch = ++generation, pagination = { page: page.data.page, historyPage: page.data.historyPage };
        chosen = null; actualInstant = "";
        page.setData({ ...blank(), ...pagination, busy: true, retry: !!pending, pendingLabel: pending?.label ?? "" });
        try { clearPreview(); if (!repairId || !services.sessions.identity) throw new MiniRequestError(401, "AUTH_SESSION_INVALID"); await readState(page, epoch); }
        catch (error) { if (current(epoch)) failure(page, error); } finally { finish(page, epoch); }
    };
    const editable = (page: Context) => ensureCurrent(page) && !page.data.busy && !page.data.stale && !pending && !prepared;
    const send = async (page: Context) => {
        if (!pending || !ensureCurrent(page) || page.data.busy) return;
        const operation = pending, epoch = ++generation; page.setData({ busy: true, error: "" });
        try {
            const result = (operation.file && operation.form
                ? await services.sessions.uploadFactoryEvidence(operation.path, operation.file, { key: operation.key, version: operation.version, ...operation.form })
                : await services.sessions.request("POST", operation.path, operation.body === undefined ? undefined : JSON.parse(operation.body) as unknown, operation)) as { round?: { id: string } };
            if (!current(epoch)) return;
            if (result.round?.id) selectedId = result.round.id;
            if (!await readState(page, epoch) || !current(epoch)) return;
            pending = null; chosen = null; actualInstant = "";
            const saved = { detail: page.data.detail, rows: page.data.rows, selected: page.data.selected, history: page.data.history,
                page: page.data.page, total: page.data.total, historyPage: page.data.historyPage, historyTotal: page.data.historyTotal,
                eligibleFiles: page.data.eligibleFiles, activeSelected: page.data.activeSelected, purposeLabels: page.data.purposeLabels,
                purposeKinds: page.data.purposeKinds, financialFiles: page.data.financialFiles,
                approvedCostLabel: page.data.approvedCostLabel, pendingCostLabel: page.data.pendingCostLabel };
            page.setData({ ...blank(), ...saved, stale: false, notice: "本次办理结果已核对，实际进度以当前单据为准。" });
            discard(operation.file); clearPreview();
        } catch (error) { if (current(epoch)) failure(page, error); } finally { finish(page, epoch); }
    };
    const prepare = (page: Context, action: Action) => {
        if (!editable(page) || !page.data.detail || !["requestScope", "requestCostScope", "quote", "cost", "approveScope", "rejectScope", "withdrawScope", "createRound", "cancelRound", "dispatch", "receive", "upload"].includes(action)) return;
        const d = page.data.detail, row = page.data.selected, permission = ["approveScope", "rejectScope"].includes(action) ? "decideScope" : action === "upload" ? "uploadEvidence" : action;
        const financialPurpose = ["QUOTE", "COST_RECEIPT"].includes(page.data.purposeKinds[page.data.purposeIndex] ?? "");
        const financial = action === "quote" || action === "cost" || action === "upload" && financialPurpose;
        const permitted = action === "quote" ? row?.actions?.recordQuote : action === "cost" ? row?.actions?.recordCost
            : action === "upload" && financialPurpose ? row?.actions?.uploadFinancialEvidence : d.actions[permission];
        if (!permitted) return;
        const guards = { assetVersion: d.assetVersion, ownershipVersion: d.ownershipVersion, lockVersion: d.lockVersion };
        let path: string, body: unknown, file: FactoryEvidenceFile | undefined, form: Intent["form"], label: string;
        try {
            const reason = page.data.reason.trim();
            if (["requestScope", "requestCostScope", "approveScope", "rejectScope", "withdrawScope", "cancelRound"].includes(action) && reason.length < 3) throw new FactoryFormError("请填写明确办理原因。");
            if (action === "requestCostScope") {
                const policy = costPolicies[page.data.costPolicyIndex], limit = page.data.costLimit.trim();
                if (!policy || policy === "LIMITED" && (!moneyPattern.test(limit) || !/[1-9]/.test(limit))) throw new FactoryFormError("累计限额须为正金额，最多两位小数。");
                body = { ...guards, reason, costPolicy: policy, costLimit: policy === "LIMITED" ? limit : policy === "NO_COST" ? "0.00" : null };
                path = "/scopes"; label = "申请本维修返厂费用范围：" + costText(policy, limit) + "；" + reason;
            } else if (action === "requestScope") { path = "/scopes"; body = { ...guards, reason }; label = "申请必要的无费用返厂范围：" + reason; }
            else if (["approveScope", "rejectScope", "withdrawScope"].includes(action)) {
                const scope = d.pendingScope; if (!scope) return;
                path = action === "withdrawScope" ? "/scope-withdrawal" : "/scope-decision";
                const result = action === "approveScope" ? "APPROVE" : action === "rejectScope" ? "REJECT" : "WITHDRAW";
                body = { scopeId: scope.id, scopeVersion: scope.version, result, reason }; label = (result === "APPROVE" ? "批准返厂范围（" + costText(scope.costPolicy, scope.costLimit) + "）：" : result === "REJECT" ? "驳回范围申请：" : "撤回范围申请：") + reason;
            } else if (action === "createRound") {
                if (!d.approvedScope || !page.data.provider.trim() || !page.data.contact.trim() || page.data.phone.trim().length < 3 || page.data.fault.trim().length < 3) throw new FactoryFormError("请填写本轮厂家、联系人、电话及实际故障。");
                physicalTime(page.data.due, "00:00:00");
                path = "/rounds"; body = { ...guards, scopeId: d.approvedScope.id, scopeVersion: d.approvedScope.version, providerName: page.data.provider.trim(),
                    serviceContact: page.data.contact.trim(), servicePhone: page.data.phone.trim(), expectedReturnDate: page.data.due, fault: page.data.fault.trim() };
                label = "新建待寄出轮次：" + page.data.provider.trim() + "；预计收回 " + page.data.due;
            } else {
                if (!row || !financial && (row.id !== d.activeRound?.id || row.version !== d.activeRound.version) || d.lockVersion === null) throw new FactoryFormError("请刷新并选中当前活动轮次。");
                path = "/rounds/" + row.id;
                if (action === "quote" || action === "cost") {
                    const amount = page.data.moneyAmount.trim(), selected = [...page.data.moneyEvidenceIds], purpose = action === "quote" ? "QUOTE" : "COST_RECEIPT";
                    const allowed = new Set(page.data.financialFiles.filter(f => f.purpose === purpose).map(f => f.object_id));
                    if (!moneyPattern.test(amount) || !page.data.moneyChecked || page.data.moneyReason.trim().length < 3
                        || !selected.every(id => allowed.has(id)) || (action === "quote" || /[1-9]/.test(amount)) && !selected.length) throw new FactoryFormError("请核对精确金额和原因，明确选用本轮报价或费用对应用途原件，并确认已核对。");
                    path += action === "quote" ? "/quotes" : "/cost";
                    body = { ...guards, roundVersion: row.version, amount, evidenceIds: selected, reason: page.data.moneyReason.trim(), finalAmountChecked: true };
                    label = (action === "quote" ? "登记本轮报价 " : "确认本轮最终费用 ") + amount + " 元；" + page.data.moneyReason.trim();
                } else if (action === "cancelRound") { path += "/cancel"; body = { ...guards, roundVersion: row.version, reason }; label = "取消尚未寄出的本轮：" + reason; }
                else if (action === "upload") {
                    if (!chosen) throw new FactoryFormError("请先选择本轮原件。");
                    file = services.sessions.stageFactoryEvidence(chosen);
                    form = Object.freeze({ roundVersion: row.version, assetVersion: d.assetVersion, ownershipVersion: d.ownershipVersion, lockVersion: d.lockVersion,
                        purpose: page.data.purposeKinds[page.data.purposeIndex]! });
                    path += "/evidence"; label = "追加本轮" + page.data.purposeLabels[page.data.purposeIndex] + "：" + file.name;
                } else {
                    let occurredAt = physicalTime(page.data.actualDate, page.data.actualTime);
                    if (actualInstant) {
                        const actual = new Date(actualInstant);
                        if (day(actual) === page.data.actualDate && clock(actual) === page.data.actualTime) occurredAt = actualInstant;
                    }
                    const evidenceIds = [...page.data.evidenceIds], allowed = new Set(page.data.eligibleFiles.map(f => f.object_id));
                    if (!page.data.observedCode.trim() || row.originalIdentity.serialNumber && !page.data.serial.trim() || !page.data.originalChecked
                        || !evidenceIds.length || !evidenceIds.every(id => allowed.has(id)) || page.data.evidence.trim().length < 10) throw new FactoryFormError("请亲自核实原机编码与序列号、选用本轮对应原件并填写实际依据。");
                    const physical = { ...guards, roundVersion: row.version, observedCode: page.data.observedCode.trim(), observedSerial: page.data.serial.trim() || null, occurredAt, evidenceIds };
                    if (action === "dispatch") { path += "/dispatch"; body = { ...physical, evidence: page.data.evidence.trim() }; label = "登记原机实际寄出"; }
                    else {
                        const location = page.data.locations.find(p => p.id === page.data.locationId);
                        if (!location || !["RESTORED", "STILL_FAULTY"].includes(page.data.condition)
                            || !page.data.noCostChecked && !page.data.costUnassessed || page.data.noCostChecked && page.data.costUnassessed
                            || page.data.costUnassessed && row.actualCost != null) throw new FactoryFormError("请选择实际地点和原机状况，并明确核对已记录费用、实际无费用或费用待核实；整机换新不能按原机收回。");
                        path += "/receive-original"; body = { ...physical, originalPhysicalIdentityChecked: true, actualLocationId: location.id, locationVersion: location.version,
                            conditionResult: page.data.condition, actualCost: page.data.costUnassessed ? null : row.actualCost ?? "0.00", ...(page.data.costUnassessed ? { costUnassessed: true } : {}), treatmentResult: page.data.evidence.trim() }; label = "登记原机实际收回，维修继续持锁";
                    }
                    label += "；" + page.data.observedCode.trim() + "；" + page.data.actualDate + " " + page.data.actualTime;
                }
            }
            prepared = Object.freeze({ path: route(path), key: services.createKey(), version: d.version, body: body === undefined ? undefined : JSON.stringify(body), file, form, label });
            page.setData({ confirmation: label, error: "" });
        } catch (error) { try { discard(file); } finally { page.setData({ error: error instanceof FactoryFormError ? error.message : "原件副本或办理资料尚未核实，请重新选择并核对图片10MB、PDF20MB限制。" }); } }
    };
    return {
        data: blank(),
        onLoad(options: { id?: string }) { repairId = idPattern.test(options.id ?? "") ? options.id! : ""; },
        async onShow(this: Context) {
            live = true; const next = getServices();
            if (services && identity !== JSON.stringify([next.sessions.identity, next.sessions.identityRevision])) {
                try { wipe(this, ""); } catch { this.setData({ ...blank(), error: "原件清理未完成，请重新启动后重试。" }); }
            }
            services = next; identity = owner(); await read(this);
        },
        onHide(this: Context) { live = false; generation++; chosen = null; actualInstant = ""; const old = prepared; prepared = null;
            try { try { clearPreview(); } finally { discard(old?.file); } } catch { /* Retry uses the registered owner cleanup. */ } finally { this.setData(blank()); } },
        onUnload(this: Context) { live = false; generation++; try { wipe(this, ""); } catch { this.setData(blank()); } repairId = ""; },
        async onRefresh(this: Context) { if (ensureCurrent(this) && !this.data.busy && !prepared) await read(this); },
        onField(this: Context, e: { currentTarget: { dataset: { field: string } }; detail: { value: string } }) {
            const field = e.currentTarget.dataset.field;
            if (editable(this) && ["reason", "provider", "contact", "phone", "due", "fault", "observedCode", "serial", "actualDate", "actualTime", "evidence", "costLimit", "moneyAmount", "moneyReason"].includes(field)) {
                if (["actualDate", "actualTime"].includes(field)) actualInstant = "";
                this.setData({ [field]: e.detail.value });
            }
        },
        onNow(this: Context) { if (editable(this)) { const now = new Date(); actualInstant = now.toISOString(); this.setData({ actualDate: day(now), actualTime: clock(now) }); } },
        onChecked(this: Context, e: { detail: { value: string[] } }) { if (editable(this)) this.setData({ originalChecked: e.detail.value.includes("original"), noCostChecked: e.detail.value.includes("noCost"), costUnassessed: e.detail.value.includes("unassessed") }); },
        onEvidence(this: Context, e: { detail: { value: string[] } }) { if (editable(this)) this.setData({ evidenceIds: e.detail.value.filter(id => this.data.eligibleFiles.some(f => f.object_id === id)) }); },
        onCondition(this: Context, e: { detail: { value: string } }) { const index = Number(e.detail.value); if (editable(this) && [0, 1, 2].includes(index)) this.setData({ conditionIndex: index, condition: ["", "RESTORED", "STILL_FAULTY"][index]! }); },
        onPurpose(this: Context, e: { detail: { value: string } }) { const index = Number(e.detail.value); if (editable(this) && Number.isInteger(index) && index >= 0 && index < this.data.purposeKinds.length) this.setData({ purposeIndex: index }); },
        onCostPolicy(this: Context, e: { detail: { value: string } }) { const index = Number(e.detail.value); if (editable(this) && [0, 1, 2].includes(index)) this.setData({ costPolicyIndex: index, costLimit: "" }); },
        onMoneyChecked(this: Context, e: { detail: { value: string[] } }) { if (editable(this)) this.setData({ moneyChecked: e.detail.value.includes("checked") }); },
        onMoneyEvidence(this: Context, e: { detail: { value: string[] } }) { if (editable(this)) this.setData({ moneyEvidenceIds: e.detail.value.filter(id => this.data.financialFiles.some(f => f.object_id === id)) }); },
        async onChoose(this: Context) {
            if (!editable(this) || !services.chooseEvidence) return;
            const epoch = generation; this.setData({ busy: true, error: "" });
            try { const file = await services.chooseEvidence(); if (current(epoch)) { chosen = file; this.setData({ fileName: file.name }); } }
            catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); }
        },
        onPrepare(this: Context, e: { currentTarget: { dataset: { action: Action } } }) { prepare(this, e.currentTarget.dataset.action); },
        async onConfirm(this: Context) { if (!ensureCurrent(this) || !prepared || pending || this.data.busy) return; pending = prepared; prepared = null; this.setData({ confirmation: "", retry: true, pendingLabel: pending.label }); await send(this); },
        onCancel(this: Context) { if (!ensureCurrent(this) || this.data.busy || pending || !prepared) return; const old = prepared; prepared = null; try { discard(old.file); } catch { this.setData({ error: "原件清理未完成，请重新启动后重试。" }); } finally { this.setData({ confirmation: "" }); } },
        async onRetry(this: Context) { await send(this); },
        async onRound(this: Context, e: { currentTarget: { dataset: { id: string } } }) { if (!editable(this) || !this.data.rows.some(r => r.id === e.currentTarget.dataset.id)) return; selectedId = e.currentTarget.dataset.id; await read(this); },
        async onPage(this: Context, e: { currentTarget: { dataset: { kind: string; delta: string } } }) {
            if (!editable(this)) return; const events = e.currentTarget.dataset.kind === "history", delta = Number(e.currentTarget.dataset.delta);
            const next = (events ? this.data.historyPage : this.data.page) + delta, total = events ? this.data.historyTotal : this.data.total;
            if (![1, -1].includes(delta) || next < 1 || (next - 1) * 20 >= total) return;
            if (events) this.setData({ historyPage: next }); else { selectedId = ""; this.setData({ page: next }); } await read(this);
        },
        async onLocations(this: Context, e: { currentTarget: { dataset: { delta?: string } } }) {
            if (!editable(this)) return; const delta = Number(e.currentTarget.dataset.delta ?? "0"), next = this.data.locationPage + delta;
            if (![-1, 0, 1].includes(delta) || next < 1 || delta > 0 && (next - 1) * 20 >= this.data.locationTotal) return;
            const epoch = generation; this.setData({ busy: true });
            try { const result = await services.sessions.request("GET", "/api/v1/mini/repairs/physical-catalog/location?page=" + next + "&pageSize=20") as FactoryList<Place>;
                if (current(epoch)) this.setData({ locations: result.items, locationTotal: result.total, locationPage: next, locationId: "" });
            } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); }
        },
        onLocation(this: Context, e: { currentTarget: { dataset: { id: string } } }) { if (editable(this) && this.data.locations.some(p => p.id === e.currentTarget.dataset.id)) this.setData({ locationId: e.currentTarget.dataset.id }); },
        async onFile(this: Context, e: { currentTarget: { dataset: { id: string } } }) {
            if (!editable(this)) return; const item: FactoryFile | undefined = this.data.selected?.files.find(f => f.objectId === e.currentTarget.dataset.id);
            if (!item || item.scanStatus !== "CLEAN" || !this.data.selected) return;
            const epoch = generation, base = route("/rounds/" + this.data.selected.id + "/evidence/" + item.objectId); this.setData({ busy: true });
            try {
                clearPreview(); this.setData({ previewPath: "", previewName: "", previewMime: "" });
                const grant = await services.sessions.request("GET", base + "/grant") as { token: string };
                if (!current(epoch)) return;
                const file = await services.sessions.downloadFactoryEvidence(base + "/content?token=" + encodeURIComponent(grant.token), item.contentType, item.sizeBytes);
                if (!current(epoch)) { services.sessions.discardFactoryEvidence(file); return; }
                preview = file; this.setData({ previewPath: file, previewName: item.filename, previewMime: item.contentType });
            } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); }
        },
        onCloseFile(this: Context) { if (!ensureCurrent(this)) return; try { clearPreview(); } finally { this.setData({ previewPath: "", previewName: "", previewMime: "" }); } },
        async onViewFile(this: Context) { if (!editable(this) || !preview || !services.openEvidence) return; const epoch = generation; this.setData({ busy: true });
            try { await services.openEvidence(preview, this.data.previewName); } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); } },
        onBack(this: Context) { if (!this.data.busy && !pending && !prepared) services.navigate("/pages/repairs/index?id=" + repairId); },
    };
}
