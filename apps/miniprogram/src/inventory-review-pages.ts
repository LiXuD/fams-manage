import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import { listReviewPhotos, readReviewPhoto, ReviewPhotoUnavailable, photoReferences, type ReviewPhotoReference } from "./inventory-review-photos.ts";
import type { Transfer, TransferChoice, TransferTarget } from "../../../packages/api-types/src/transfer";

interface Snapshot { assetCode: string; name: string; version: number; locationId: string | null; locationName?: string;
    custodyType: string | null; responsibleEmployeeName?: string; departmentResponsibleEmployeeName?: string }
interface Observation { id: string; client_event_id: string; outcome: string; recheck_generation: number; received_at: string;
    facts_json: { rawObservedAt?: string; photoIds?: string[]; actual?: { locationId?: string | null; locationHint?: string;
        custodyType?: string | null; condition?: string; description?: string; unknownReason?: string; responsibilityHint?: string } };
    outcomeLabel?: string; timeText?: string; receivedText?: string; eligible?: boolean }
interface Review { version: number; observation_id?: string; review_status?: string; reviewer_username?: string; reviewed_at?: string; reason?: string }
interface Item { item: { id: string; asset_id: string; recheck_generation: number; snapshot_json: Snapshot };
    current: Snapshot | null; candidate: Observation | null; review: Review; canAcceptNormal: boolean; blockedReason: string | null;
    movementEvents?: { id: string; type: string; occurredAt: string }[];
    stateLabel?: string; modesLabel?: string; rowId?: string; normalChecked?: boolean }
interface Board { task: { id: string; name: string; version: number; task_status: string }; items: Item[]; total: number; reviewedCount: number }
interface Selection { taskVersion: number; recheckGeneration: number; review: Review; contextDigest: string;
    history: { item_id: string; recheck_generation: number; review_version: number; observation_id: string; review_status: string;
        actor_username: string; recorded_at: string; verified_observed_at: string; reason: string; evidence: string; key?: string; timeText?: string }[]; total: number }
interface Subject { observation_id: string; dimension: string; version: number; subject_status: string; method: string | null;
    checked?: boolean;
    reviewer_username: string | null; reviewed_at: string | null; reason: string | null; evidence: string | null; label?: string; stateLabel?: string;
    decisions?: { after_version: number; actor_username: string; method: string; subject_status: string; reason: string; evidence: string }[];
    links?: { transfer_id: string; decision_version: number }[]; repairLinks?: { repair_id: string; decision_version: number }[] }
interface Disposition { observation_id: string; counter_observation_id: string; counter_review_version: number; disposition: string;
    actor_username: string; recorded_at: string; reason: string; evidence: string }
interface Catalog { kind: "organization" | "warehouse" | "location" | "employee"; rows: TransferChoice[]; page: number; total: number }
interface Scope { page: number; itemId: string; observationPage: number; observationId: string }
interface Pending extends Scope { path: string; version: number; key: string; body: unknown; label: string }
interface DamageRepair { id: string; assetId: string; version: number; status: string }
interface DamageData { damageDate: string; damageTime: string; damageSecond: string; damageFault: string; damageImpact: string;
    damagePhysicalChecked: boolean; damageTimeChecked: boolean; damageRepairId: string; damageLinked: DamageRepair | null }
const blankDamage = (): DamageData => ({ damageDate: "", damageTime: "", damageSecond: "00.000", damageFault: "", damageImpact: "HIGH",
    damagePhysicalChecked: false, damageTimeChecked: false, damageRepairId: "", damageLinked: null });
interface Data extends DamageData {
    photos: ReviewPhotoReference[]; photoObservationId: string; photoTotal: number; photoPath: string; photoName: string; photoError: string; photoCleanupPending: boolean;
    taskId: string; board: Board | null; item: Item | null; observations: Observation[]; observation: Observation | null;
    selection: Selection | null; subjects: Subject[]; dispositions: Disposition[]; linked: Transfer | null;
    page: number; observationPage: number; observationTotal: number; historyPage: number; dispositionPage: number; dispositionTotal: number;
    normalIds: string[]; dimensions: string[]; reason: string; evidence: string; verifiedDate: string; verifiedTime: string; verifiedSecond: string;
    busy: boolean; retryable: boolean; error: string; notice: string; conflict: Readonly<Record<string, string>>;
    physicalMethod: number; evidenceMethod: number; physicalMethods: string[]; evidenceMethods: string[];
    catalog: Catalog; owning: TransferChoice | null; using: TransferChoice | null; warehouse: TransferChoice | null;
    location: TransferChoice | null; employee: TransferChoice | null; custody: "PERSONAL" | "SHARED";
}
interface Context { data: Data; setData(value: Partial<Data>): void }
interface Event { detail?: { value?: unknown }; currentTarget?: { dataset?: { id?: string; field?: string; delta?: string; kind?: string } } }
const root = "/api/v1/mini/inventory-reviews/tasks/";
const dimensions: Record<string, string> = { NORMAL: "正常", LOCATION: "位置不符", USAGE_MODE: "使用方式不符", RESPONSIBILITY: "责任不符",
    CONDITION: "状况待核实", UNKNOWN: "未知事实", PERIOD_MOVEMENT: "期间流转", CONFLICT: "观察冲突", OLD_ASSIGNMENT: "旧分配", TIME: "时间异常", FINDING: "任务外线索", LATE: "迟到线索" };
const statuses: Record<string, string> = { OPEN: "待处理", PROCESSING: "处理中", CLOSED: "已关闭", TRANSFERRED: "已转后续业务" };
const modes: Record<string, string> = { STORAGE: "仓库保管", PERSONAL: "个人使用", SHARED: "部门公用" };
const outcomes: Record<string, string> = { ACCEPTED: "本轮观察候选", DIFFERENCE_REVIEW: "差异待复核", CONFLICT_REVIEW: "冲突待复核", OLD_ASSIGNMENT_REVIEW: "旧分配证据",
    TIME_REVIEW: "时间待核实", DUPLICATE: "重复，未重复计数", EXPIRED: "过期，需补盘", LATE_REVIEW: "迟到补充线索", FINDING_REVIEW: "任务外线索" };
const physicalCodes = ["BOOK_CORRECT", "CONTINUE_SEARCH", "FORMAL_TRANSFER", "REFRESH_TRANSFER", "FORMAL_REPAIR", "LINK_REPAIR", "REFRESH_REPAIR"];
const physicalLabels = ["有明确证据确认账面正确", "继续查找与核实", "转正式调拨", "核对关联调拨进度", "转正式维修", "关联已有维修单", "核对关联维修进度"];
const special = (dimension: string) => ["CONFLICT", "OLD_ASSIGNMENT", "TIME"].includes(dimension);
const id = (value: unknown): value is string => typeof value === "string" && /^[1-9][0-9]{0,18}$/.test(value);
const pad = (value: number, length = 2) => String(value).padStart(length, "0");
function localParts(value: string) {
    const date = new Date(value);
    return Number.isFinite(date.getTime()) ? [`${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
        `${pad(date.getHours())}:${pad(date.getMinutes())}`, `${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`] : ["", "", ""];
}
function displayTime(value: string) { const parts = localParts(value); return parts[0] ? `${parts[0]} ${parts[1]}:${parts[2]}` : "时间待核实"; }
function blank(taskId = ""): Data {
    return { ...blankDamage(), taskId, photos: [], photoObservationId: "", photoTotal: 0, photoPath: "", photoName: "", photoError: "", photoCleanupPending: false,
        board: null, item: null, observations: [], observation: null, selection: null, subjects: [], dispositions: [], linked: null,
        page: 1, observationPage: 1, observationTotal: 0, historyPage: 1, dispositionPage: 1, dispositionTotal: 0,
        normalIds: [], dimensions: [], reason: "", evidence: "", verifiedDate: "", verifiedTime: "", verifiedSecond: "00.000",
        busy: false, retryable: false, error: "", notice: "", conflict: {}, physicalMethod: 0, evidenceMethod: 0,
        physicalMethods: physicalLabels, evidenceMethods: ["以明确反证拒绝未选证据", "保留补充线索，物理差异继续处理"],
        catalog: { kind: "organization", rows: [], page: 1, total: 0 }, owning: null, using: null, warehouse: null, location: null, employee: null, custody: "PERSONAL" };
}
function message(error: unknown) {
    if (!(error instanceof MiniRequestError)) return "操作结果尚未确认，请重试原操作；读取失败时可刷新核对。";
    if (error.status === 401) return "登录已失效，请重新登录。";
    if ([403, 404].includes(error.status)) return "当前身份没有这项复核权限或记录已失效，原资料已隐藏。";
    if (error.fields.processedBy) return `${error.fields.processedBy} 已于 ${displayTime(error.fields.processedAt ?? "")} 处理，结果：${statuses[error.fields.result ?? ""] ?? "请查看最新结论"}。`;
    const known: Record<string, string> = {
        INVENTORY_REVIEW_RECOLLECTION_REQUIRED: "原始首次持久化或离线期限无法可靠还原，请安排补盘；修改观察时间不能延长期限。",
        INVENTORY_REVIEW_PREPARATION_REQUIRED: "仍有原始差异未列入，请先列入当前页证据。",
        INVENTORY_REVIEW_UNDECIDED_EVIDENCE: "仍有未选证据缺少反证或补充处置，请逐条核对。",
        INVENTORY_REVIEW_UNRESOLVED_DIMENSIONS: "独立差异尚未闭环，请沿事实或正式业务继续处理。",
        INVENTORY_REVIEW_LINKED_EVIDENCE_REQUIRED: "已有正式业务关联，请沿关联业务继续，不能用反证覆盖。",
        INVENTORY_REVIEW_RECHECK_REQUIRED: "所选证据已用于生效决定，新证据请在任务管理中发起复盘。",
        INVENTORY_REVIEW_CONTEXT_CHANGED: "资产发生新的流转或物理信息变化，请重新核对或安排复盘。",
    };
    return known[error.code] ?? (error.status === 409 ? "任务、资料或处理版本已变化，请查看最新只读结果。" : "请核对所选项目、处理方式及必填依据。");
}

/** Native review uses only opaque sessions and the same inventory-owned decisions as PC. */
export function createInventoryReviewPage(getServices: () => MiniServices) {
    let services: MiniServices | null = null, live = false, generation = 0, identity = "";
    let pending: Pending | null = null;
    const downloaded = new Set<string>();
    const discardPhoto = (path: string) => {
        try { services!.sessions.discardInventoryPhoto(path); downloaded.delete(path); return true; }
        catch { return false; }
    };
    const clearDownloaded = (page: Context) => {
        page.setData({ photoPath: "", photoName: "" });
        for (const path of [...downloaded]) discardPhoto(path);
        page.setData({ photoCleanupPending: downloaded.size > 0,
            photoError: downloaded.size ? "本机照片文件清理尚未完成，请重试清理。" : "" });
        return downloaded.size === 0;
    };
    const clearPhotoArea = (page: Context) => {
        page.setData({ photos: [], photoObservationId: "", photoTotal: 0 }); clearDownloaded(page);
    };
    const owner = () => JSON.stringify(services?.sessions.identity ?? null);
    const current = (epoch: number, who: string) => live && epoch === generation && identity === who && owner() === who;
    const locked = (page: Context) => page.data.busy || pending !== null;
    const scope = (page: Context): Scope => ({ page: page.data.page, itemId: page.data.item?.item.id ?? "",
        observationPage: page.data.observationPage, observationId: page.data.observation?.id ?? "" });
    const base = (page: Context) => root + page.data.taskId;
    const itemBase = (page: Context) => `${base(page)}/items/${page.data.item!.item.id}`;
    const changed = (page: Context, epoch: number, who: string) => {
        if (!live || epoch !== generation || owner() === who) return;
        generation++; identity = owner(); pending = null;
        page.setData({ ...blank(page.data.taskId), error: "身份或绑定已变化，请重新进入当前任务。" });
        clearDownloaded(page);
        if (!services?.sessions.identity) services?.navigate("/pages/login/index");
    };
    const failure = (page: Context, error: unknown, epoch: number, who: string) => {
        if (!current(epoch, who)) { changed(page, epoch, who); return; }
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) {
            pending = null; page.setData({ ...blank(page.data.taskId), error: message(error) });
            clearDownloaded(page);
        } else page.setData({ error: message(error), retryable: pending !== null,
            conflict: error instanceof MiniRequestError ? error.fields : {} });
        if (!services?.sessions.identity) services?.navigate("/pages/login/index");
    };
    const photoFailure = (page: Context, error: unknown, epoch: number, who: string) => {
        if (!current(epoch, who)) { changed(page, epoch, who); return; }
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) { failure(page, error, epoch, who); return; }
        if (error instanceof ReviewPhotoUnavailable) page.setData({ photos: photoReferences(page.data.photos.map(row => row.objectId), []), photoError: error.message });
        else page.setData({ photoError: "照片读取暂未完成，请刷新原始照片资料后重试。" });
    };
    const readSubjects = async (page: Context, observation: Observation, epoch: number, who: string) => {
        clearPhotoArea(page);
        const response = await services!.sessions.request("GET", `${itemBase(page)}/evidence-subjects?observationId=${observation.id}`) as { items: Subject[] };
        if (!current(epoch, who)) { changed(page, epoch, who); return; }
        const chosen = page.data.selection?.review.observation_id === observation.id;
        const parts = localParts(observation.facts_json.rawObservedAt ?? "");
        page.setData({ ...blankDamage(), physicalMethod: 0, observation, subjects: response.items.map(row => ({ ...row, checked: false, label: dimensions[row.dimension] ?? "待核实差异", stateLabel: statuses[row.subject_status] ?? "待核实" })),
            dimensions: [], evidenceMethod: 0, evidenceMethods: chosen ? ["核实所选证据的冲突、旧分配或时间依据"] : ["以明确反证拒绝未选证据", "保留补充线索，物理差异继续处理"],
            verifiedDate: parts[0]!, verifiedTime: parts[1]!, verifiedSecond: parts[2] || "00.000" });
    };
    const load = async (page: Context, wanted: Scope, epoch: number, who: string) => {
        clearPhotoArea(page);
        const board = await services!.sessions.request("GET", `${base(page)}?page=${wanted.page}&pageSize=20`) as Board;
        if (!current(epoch, who)) { changed(page, epoch, who); return; }
        const selected = board.items.find(row => row.item.id === wanted.itemId) ?? null;
        page.setData({ board: { ...board, items: board.items.map(row => ({ ...row, rowId: row.item.id, normalChecked: false, modesLabel: modes[row.item.snapshot_json.custodyType ?? ""] ?? "待核实",
            stateLabel: row.review.review_status === "ACCEPTED" ? "已采信" : row.review.review_status === "SELECTED" ? "待采信选择" : "待复核" })) },
            page: wanted.page, item: selected, normalIds: [], linked: null, retryable: pending !== null });
        if (!selected) { page.setData({ observations: [], selection: null, subjects: [], observation: null, dispositions: [] }); return; }
        const prefix = itemBase(page);
        const [raw, selection, dispositions] = await Promise.all([
            services!.sessions.request("GET", `${prefix}/observations?page=${wanted.observationPage}&pageSize=20`) as Promise<{ items: Observation[]; total: number }>,
            services!.sessions.request("GET", `${prefix}/selection?page=${page.data.historyPage}&pageSize=20`) as Promise<Selection>,
            services!.sessions.request("GET", `${prefix}/evidence-dispositions?page=${page.data.dispositionPage}&pageSize=20`) as Promise<{ items: Disposition[]; total: number }>]);
        if (!current(epoch, who)) { changed(page, epoch, who); return; }
        const rows = raw.items.map(row => ({ ...row, outcomeLabel: outcomes[row.outcome] ?? "待核实", timeText: displayTime(row.facts_json.rawObservedAt ?? ""), receivedText: displayTime(row.received_at),
            eligible: row.recheck_generation === selection.recheckGeneration && ["ACCEPTED", "DIFFERENCE_REVIEW", "CONFLICT_REVIEW", "OLD_ASSIGNMENT_REVIEW", "TIME_REVIEW"].includes(row.outcome) }));
        page.setData({ observations: rows, observationPage: wanted.observationPage, observationTotal: raw.total,
            selection: { ...selection, history: selection.history.map(row => ({ ...row, key: `${row.recheck_generation}:${row.review_version}`, timeText: displayTime(row.verified_observed_at) })) },
            dispositions: dispositions.items, dispositionTotal: dispositions.total, subjects: [], observation: null, dimensions: [] });
        const observation = rows.find(row => row.id === wanted.observationId && row.eligible);
        if (observation) await readSubjects(page, observation, epoch, who);
    };
    const read = async (page: Context, wanted = scope(page)) => {
        if (!services || !id(page.data.taskId) || page.data.busy) return;
        const epoch = ++generation, who = identity; page.setData({ busy: true, error: "", conflict: {} });
        try { await load(page, wanted, epoch, who); }
        catch (error) { failure(page, error, epoch, who); }
        finally { if (current(epoch, who)) page.setData({ busy: false }); }
    };
    const perform = async (page: Context) => {
        if (!services || !pending || page.data.busy) return;
        const original = pending, epoch = ++generation, who = identity; page.setData({ busy: true, error: "", conflict: {} });
        try {
            const reply = await services.sessions.request("POST", original.path, original.body, original) as { accepted?: number; conflicts?: number };
            if (!current(epoch, who)) { changed(page, epoch, who); return; }
            pending = null;
            page.setData({ retryable: false, reason: "", evidence: "", notice: reply.accepted === undefined ? `${original.label}已保存，其他独立差异仍须继续闭环。` : `本次采信 ${reply.accepted} 项，冲突或待核实 ${reply.conflicts ?? 0} 项。` });
            await load(page, original, epoch, who);
        } catch (error) {
            if (current(epoch, who) && error instanceof MiniRequestError && error.status >= 400 && error.status < 500) pending = null;
            failure(page, error, epoch, who);
        } finally { if (current(epoch, who)) page.setData({ busy: false, retryable: pending !== null }); }
    };
    const propose = async (page: Context, path: string, version: number, body: unknown, label: string, content: string) => {
        if (!services || locked(page) || !current(generation, identity)) return;
        const captured: Pending = { ...scope(page), path, version, key: services.createKey(), body: JSON.parse(JSON.stringify(body)) as unknown, label };
        const epoch = generation, who = identity; page.setData({ busy: true, error: "" });
        try {
            const confirmed = await services.confirm(label, content);
            if (!current(epoch, who)) { changed(page, epoch, who); return; }
            page.setData({ busy: false }); if (!confirmed) return;
            pending = captured; page.setData({ retryable: true }); await perform(page);
        } catch (error) { failure(page, error, epoch, who); }
        finally { if (current(epoch, who)) page.setData({ busy: false }); }
    };
    const common = (page: Context) => {
        const selected = page.data.selection, item = page.data.item;
        if (!selected || !item?.current || page.data.reason.trim().length < 3 || page.data.evidence.trim().length < 10) return null;
        return { recheckGeneration: selected.recheckGeneration, reviewVersion: selected.review.version, assetVersion: item.current.version,
            contextDigest: selected.contextDigest, reason: page.data.reason.trim(), evidence: page.data.evidence.trim() };
    };
    const catalog = async (page: Context, kind: Catalog["kind"], number = 1) => {
        if (!services || locked(page)) return;
        if (kind === "employee" && !page.data.using) { page.setData({ error: "请先选择目标使用部门。" }); return; }
        const epoch = ++generation, who = identity; page.setData({ busy: true, error: "" });
        try {
            const result = await services.sessions.request("GET", `/api/v1/mini/transfers/catalog/${kind}?page=${number}&pageSize=200${kind === "employee" ? `&organizationId=${page.data.using!.id}` : ""}`) as { items: TransferChoice[]; total: number };
            if (current(epoch, who)) page.setData({ catalog: { kind, page: number, rows: result.items, total: result.total } }); else changed(page, epoch, who);
        } catch (error) { failure(page, error, epoch, who); }
        finally { if (current(epoch, who)) page.setData({ busy: false }); }
    };
    const target = (page: Context): TransferTarget | null => {
        const stock = page.data.item?.current?.custodyType === "STORAGE";
        const { owning, using, warehouse, location, employee, custody } = page.data;
        if (!location || stock && (!warehouse || location.locationType !== "BIN" || location.warehouseId !== warehouse.id)
            || !stock && (!using || !employee || location.locationType === "BIN" || custody === "SHARED" && !employee.sharedEligible)) return null;
        const referenceVersions: Record<string, number> = { location: location.version };
        if (owning) referenceVersions.owningOrganization = owning.version;
        if (stock) referenceVersions.warehouse = warehouse!.version;
        else { referenceVersions.usingOrganization = using!.version; referenceVersions.employee = employee!.version; }
        return { owningOrganizationId: owning?.id ?? null, custodyType: stock ? "STORAGE" : custody, usingOrganizationId: stock ? null : using!.id,
            employeeId: stock ? null : employee!.id, warehouseId: stock ? warehouse!.id : null, locationId: location.id, referenceVersions };
    };
    return {
        data: blank(),
        onLoad(this: Context, options: { taskId?: string }) { this.setData({ taskId: id(options.taskId) ? options.taskId : "", error: id(options.taskId) ? "" : "任务编号无效，请从盘点任务重新进入。" }); },
        async onShow(this: Context) {
            live = true; generation++;
            try { services = getServices(); const next = owner(); if (next !== identity) { identity = next; pending = null; this.setData(blank(this.data.taskId)); }
                await read(this, pending ?? scope(this));
            } catch { this.setData({ ...blank(this.data.taskId), error: "服务暂未就绪，请联系管理员。" }); }
        },
        onHide(this: Context) { live = false; generation++; this.setData(blank(this.data.taskId)); clearDownloaded(this); },
        onUnload(this: Context) { live = false; generation++; pending = null; this.setData(blank(this.data.taskId)); clearDownloaded(this); },
        onBack(this: Context) { if (!locked(this)) services?.navigate("/pages/inventory-tasks/index"); },
        async onRefresh(this: Context) { await read(this, pending ?? scope(this)); },
        async onRetry(this: Context) { await perform(this); },
        onPhotoCleanup(this: Context) { if (!this.data.busy) clearDownloaded(this); },
        onClosePhoto(this: Context) { if (!this.data.busy) clearDownloaded(this); },
        async onPhotos(this: Context, event: Event) {
            if (!services || locked(this)) return;
            const observation = this.data.observations.find(row => row.id === event.currentTarget?.dataset?.id);
            if (!observation?.facts_json.photoIds?.length || !clearDownloaded(this)) return;
            const epoch = ++generation, who = identity;
            this.setData({ photos: [], photoObservationId: observation.id, photoTotal: observation.facts_json.photoIds.length, photoError: "", busy: true });
            try {
                const photos = await listReviewPhotos(services.sessions, this.data.taskId, observation.client_event_id, observation.facts_json.photoIds, () => current(epoch, who));
                if (current(epoch, who)) this.setData({ photos }); else changed(this, epoch, who);
            } catch (error) { photoFailure(this, error, epoch, who); }
            finally { if (current(epoch, who)) this.setData({ busy: false }); }
        },
        async onReadPhoto(this: Context, event: Event) {
            if (!services || locked(this)) return;
            const row = this.data.photos.find(value => value.objectId === event.currentTarget?.dataset?.id && value.readable);
            const observation = this.data.observations.find(value => value.id === this.data.photoObservationId);
            if (!row?.photo || !observation || !clearDownloaded(this)) return;
            const epoch = ++generation, who = identity, task = this.data.taskId;
            this.setData({ busy: true, photoError: "" });
            try {
                const path = await readReviewPhoto(services.sessions, task, observation.client_event_id, row.photo, () => services!.createKey(), () => current(epoch, who));
                downloaded.add(path);
                if (!current(epoch, who)) {
                    if (!discardPhoto(path) && live) this.setData({ photoCleanupPending: true, photoError: "本机照片文件清理尚未完成，请重试清理。" });
                    changed(this, epoch, who); return;
                }
                this.setData({ photoPath: path, photoName: row.photo.filename });
            } catch (error) { photoFailure(this, error, epoch, who); }
            finally { if (current(epoch, who)) this.setData({ busy: false }); }
        },
        onField(this: Context, event: Event) {
            if (locked(this) || typeof event.detail?.value !== "string") return;
            const value = event.detail.value;
            switch (event.currentTarget?.dataset?.field) {
                case "reason": this.setData({ reason: value }); break;
                case "evidence": this.setData({ evidence: value }); break;
                case "verifiedDate": this.setData({ verifiedDate: value }); break;
                case "verifiedTime": this.setData({ verifiedTime: value }); break;
                case "verifiedSecond": this.setData({ verifiedSecond: value }); break;
                case "damageDate": this.setData({ damageDate: value }); break;
                case "damageTime": this.setData({ damageTime: value }); break;
                case "damageSecond": this.setData({ damageSecond: value }); break;
                case "damageFault": this.setData({ damageFault: value }); break;
                case "damageImpact": if (["LOW", "MEDIUM", "HIGH"].includes(value)) this.setData({ damageImpact: value }); break;
                case "damageRepairId": this.setData({ damageRepairId: value, damageLinked: null }); break;
            }
        },
        onNormalSelection(this: Context, event: Event) {
            if (locked(this) || !Array.isArray(event.detail?.value)) return;
            const values = event.detail.value;
            const normalIds = this.data.board?.items.filter(row => row.canAcceptNormal && values.includes(row.item.id)).map(row => row.item.id) ?? [];
            this.setData({ normalIds, board: this.data.board ? { ...this.data.board, items: this.data.board.items.map(row => ({ ...row, normalChecked: normalIds.includes(row.item.id) })) } : null });
        },
        onDimensions(this: Context, event: Event) {
            if (locked(this) || !Array.isArray(event.detail?.value)) return;
            const values = event.detail.value;
            const selected = this.data.subjects.filter(row => !["CLOSED", "TRANSFERRED"].includes(row.subject_status) && values.includes(row.dimension)).map(row => row.dimension);
            this.setData({ dimensions: selected, subjects: this.data.subjects.map(row => ({ ...row, checked: selected.includes(row.dimension) })) });
        },
        async onOpenItem(this: Context, event: Event) {
            const itemId = event.currentTarget?.dataset?.id;
            if (locked(this) || !id(itemId) || !this.data.board?.items.some(row => row.item.id === itemId)) return;
            this.setData({ historyPage: 1, dispositionPage: 1, reason: "", evidence: "", owning: null, using: null, warehouse: null, location: null, employee: null });
            await read(this, { page: this.data.page, itemId, observationPage: 1, observationId: "" });
        },
        async onOpenObservation(this: Context, event: Event) {
            if (!services || locked(this) || !this.data.item) return;
            const observation = this.data.observations.find(row => row.id === event.currentTarget?.dataset?.id && row.eligible); if (!observation) return;
            const epoch = ++generation, who = identity; this.setData({ busy: true, error: "" });
            try { await readSubjects(this, observation, epoch, who); }
            catch (error) { failure(this, error, epoch, who); }
            finally { if (current(epoch, who)) this.setData({ busy: false }); }
        },
        async onPage(this: Context, event: Event) {
            const delta = Number(event.currentTarget?.dataset?.delta), page = this.data.page + delta;
            if (locked(this) || !this.data.board || ![-1, 1].includes(delta) || page < 1 || (page - 1) * 20 >= this.data.board.total) return;
            await read(this, { page, itemId: "", observationPage: 1, observationId: "" });
        },
        async onObservationPage(this: Context, event: Event) {
            const delta = Number(event.currentTarget?.dataset?.delta), observationPage = this.data.observationPage + delta;
            if (locked(this) || !this.data.item || ![-1, 1].includes(delta) || observationPage < 1 || (observationPage - 1) * 20 >= this.data.observationTotal) return;
            await read(this, { ...scope(this), observationPage, observationId: "" });
        },
        async onHistoryPage(this: Context, event: Event) {
            const delta = Number(event.currentTarget?.dataset?.delta), page = this.data.historyPage + delta;
            if (locked(this) || !this.data.selection || ![-1, 1].includes(delta) || page < 1 || (page - 1) * 20 >= this.data.selection.total) return;
            this.setData({ historyPage: page }); await read(this);
        },
        async onDispositionPage(this: Context, event: Event) {
            const delta = Number(event.currentTarget?.dataset?.delta), page = this.data.dispositionPage + delta;
            if (locked(this) || ![-1, 1].includes(delta) || page < 1 || (page - 1) * 20 >= this.data.dispositionTotal) return;
            this.setData({ dispositionPage: page }); await read(this);
        },
        async onNormalAcceptance(this: Context) {
            const board = this.data.board; if (!board || this.data.reason.trim().length < 3) return;
            const rows = board.items.filter(row => row.canAcceptNormal && this.data.normalIds.includes(row.item.id)); if (!rows.length) return;
            await propose(this, `${base(this)}/normal-acceptance`, board.task.version, { reason: this.data.reason.trim(), items: rows.map(row => ({ itemId: row.item.id,
                recheckGeneration: row.item.recheck_generation, reviewVersion: row.review.version, assetVersion: row.current!.version })) }, "逐项采信正常观察", `提交 ${rows.length} 项正常观察，服务端逐项返回成功或冲突，不自动采信差异。`);
        },
        async onPrepare(this: Context) {
            if (!this.data.board || !this.data.observations.length) return;
            await propose(this, `${base(this)}/differences/preparation`, this.data.selection?.taskVersion ?? this.data.board.task.version,
                { observationIds: this.data.observations.map(row => row.id) }, "列入原始证据差异", "明确列入当前页的独立差异，不增加采信数量或覆盖原始事件。");
        },
        async onSelectEvidence(this: Context) {
            const value = common(this), raw = this.data.observation; if (!value || !raw?.eligible || !this.data.selection) return;
            let verifiedObservedAt: string | undefined;
            if (raw.outcome === "TIME_REVIEW") {
                const { verifiedDate: day, verifiedTime: time, verifiedSecond: seconds } = this.data;
                if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || !/^\d{2}:\d{2}$/.test(time) || !/^[0-5]\d(?:\.\d{1,3})?$/.test(seconds)) { this.setData({ error: "请核对本机时区的核实日期、时分和秒（含毫秒）。" }); return; }
                const date = new Date(`${day}T${time}:${seconds}`); if (!Number.isFinite(date.getTime())) { this.setData({ error: "核实时间无效。" }); return; } verifiedObservedAt = date.toISOString();
            }
            await propose(this, itemBase(this) + "/selection", this.data.selection.taskVersion, { ...value, observationId: raw.id,
                ...(verifiedObservedAt ? { verifiedObservedAt } : {}) }, "保存待采信选择", `明确选择证据 ${raw.id}；保留原始时间和全部历史，不增加已采信数量。`);
        },
        onEvidenceMethod(this: Context, event: Event) { const value = Number(event.detail?.value); if (!locked(this) && Number.isInteger(value) && value >= 0 && value < this.data.evidenceMethods.length) this.setData({ evidenceMethod: value }); },
        async onEvidenceDecision(this: Context) {
            const value = common(this), raw = this.data.observation, selection = this.data.selection; if (!value || !raw || !selection) return;
            const chosen = raw.id === selection.review.observation_id;
            const rows = chosen ? this.data.subjects.filter(row => special(row.dimension)) : this.data.subjects;
            if (chosen && (!rows.length || selection.review.review_status !== "SELECTED")) return;
            const method = chosen ? "VERIFY_SELECTED" : this.data.evidenceMethod === 0 ? "REJECT_EVIDENCE" : "SUPPLEMENTAL";
            await propose(this, itemBase(this) + "/evidence-decisions", selection.taskVersion, { ...value, observationId: raw.id, method,
                subjects: rows.map(row => ({ dimension: row.dimension, version: row.version })) }, "共同提交证据处置", `对证据 ${raw.id} 的 ${rows.length} 个相关维度共同提交。已有正式关联不得覆盖；补充线索不关闭物理差异。`);
        },
        async onFinalAcceptance(this: Context) {
            const value = common(this), selection = this.data.selection; if (!value || !selection || selection.review.review_status !== "SELECTED") return;
            await propose(this, itemBase(this) + "/acceptance", selection.taskVersion, value, "最终采信所选证据", `采信当前选择 ${selection.review.observation_id}？服务端将重新核对全部原始证据、差异、流转和原始离线期限。`);
        },
        async onPhysicalMethod(this: Context, event: Event) {
            const value = Number(event.detail?.value); if (locked(this) || !Number.isInteger(value) || value < 0 || value >= physicalCodes.length) return;
            this.setData({ physicalMethod: value }); if (value === 2) await catalog(this, "organization");
        },
        async onPhysicalDecision(this: Context) {
            const value = common(this), raw = this.data.observation, selection = this.data.selection; if (!value || !raw || !selection) return;
            const rows = this.data.subjects.filter(row => this.data.dimensions.includes(row.dimension)); if (!rows.length) return;
            const method = physicalCodes[this.data.physicalMethod]!, transferTarget = method === "FORMAL_TRANSFER" ? target(this) : null;
            if (method === "FORMAL_TRANSFER" && !transferTarget) { this.setData({ error: "请明确选择有效目标组织、部门、责任人、仓库和位置。" }); return; }
            let repair: unknown;
            if (["FORMAL_REPAIR", "LINK_REPAIR", "REFRESH_REPAIR"].includes(method)) {
                if (rows.length !== 1 || rows[0]?.dimension !== "CONDITION") { this.setData({ error: "维修只处理一条损坏维度，其他差异分别核实。" }); return; }
                if (method !== "REFRESH_REPAIR") {
                    const { damageDate: day, damageTime: time, damageSecond: seconds } = this.data;
                    const discovered = new Date(`${day}T${time}:${seconds}`);
                    if (!this.data.damagePhysicalChecked || !this.data.damageTimeChecked || !/^\d{4}-\d{2}-\d{2}$/.test(day)
                        || !/^\d{2}:\d{2}$/.test(time) || !/^[0-5]\d(?:\.\d{1,3})?$/.test(seconds)
                        || !Number.isFinite(discovered.getTime()) || discovered.getTime() > Date.now()) { this.setData({ error: "请独立核实实际损坏及发现日期、时分和秒，再勾选确认。" }); return; }
                    if (method === "FORMAL_REPAIR" && (this.data.damageFault.trim().length < 3 || this.data.damageFault.length > 2000)) { this.setData({ error: "请填写实际故障。" }); return; }
                    const linked = this.data.damageLinked;
                    if (method === "LINK_REPAIR" && (!linked || linked.id !== this.data.damageRepairId.trim() || linked.assetId !== this.data.item?.item.asset_id)) { this.setData({ error: "请先核对同一资产的当前正式维修单。" }); return; }
                    repair = { verifiedDiscoveryAt: discovered.toISOString(), physicalDamageChecked: true, discoveryTimeVerified: true,
                        ...(method === "FORMAL_REPAIR" ? { fault: this.data.damageFault.trim(), impact: this.data.damageImpact } : { repairId: linked!.id, repairVersion: linked!.version }) };
                }
            }
            await propose(this, `${base(this)}/differences/decisions`, selection.taskVersion, { subjects: rows.map(row => ({ observationId: raw.id, dimension: row.dimension, version: row.version })),
                assetVersion: value.assetVersion, method, reason: value.reason, evidence: value.evidence, ...(transferTarget ? { transferTarget } : {}), ...(repair ? { repair } : {}) }, this.data.physicalMethods[this.data.physicalMethod]!,
                `共同提交 ${rows.length} 个差异，任一版本变化均不保存。正式调拨或维修仍须正式审批及实际办理，未完成前不关闭物理差异。`);
        },
        onDamageChecked(this: Context, event: Event) {
            if (locked(this) || !Array.isArray(event.detail?.value)) return;
            this.setData({ damagePhysicalChecked: event.detail.value.includes("physical"), damageTimeChecked: event.detail.value.includes("time") });
        },
        async onInspectDamageRepair(this: Context) {
            if (!services || locked(this) || !id(this.data.damageRepairId.trim()) || !this.data.item) return;
            const requested = this.data.damageRepairId.trim(), asset = this.data.item.item.asset_id;
            const epoch = ++generation, who = identity; this.setData({ busy: true, error: "", damageLinked: null });
            try {
                const row = await services.sessions.request("GET", `/api/v1/mini/repairs/${requested}`) as DamageRepair;
                if (!current(epoch, who)) { changed(this, epoch, who); return; }
                if (row.id !== requested || this.data.damageRepairId.trim() !== requested || row.assetId !== asset
                    || !["PENDING_ADMIN_DECISION", "WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING"].includes(row.status)) this.setData({ error: "请选择同一资产的有效维修单。" });
                else this.setData({ damageLinked: row });
            } catch (error) { failure(this, error, epoch, who); }
            finally { if (current(epoch, who)) this.setData({ busy: false }); }
        },
        onViewRepair(this: Context, event: Event) {
            const repair = event.currentTarget?.dataset?.id;
            if (services && !locked(this) && id(repair) && this.data.subjects.some(row => row.repairLinks?.some(link => link.repair_id === repair))) services.navigate(`/pages/repairs/index?id=${repair}`);
        },
        async onCatalog(this: Context, event: Event) { const kind = event.currentTarget?.dataset?.kind; if (["organization", "warehouse", "location", "employee"].includes(kind ?? "")) await catalog(this, kind as Catalog["kind"]); },
        async onCatalogPage(this: Context, event: Event) {
            const delta = Number(event.currentTarget?.dataset?.delta), page = this.data.catalog.page + delta;
            if (![-1, 1].includes(delta) || page < 1 || (page - 1) * 200 >= this.data.catalog.total) return;
            await catalog(this, this.data.catalog.kind, page);
        },
        onTargetChoice(this: Context, event: Event) {
            if (locked(this)) return;
            const row = this.data.catalog.rows.find(value => value.id === event.currentTarget?.dataset?.id); if (!row) return;
            switch (event.currentTarget?.dataset?.field) {
                case "owning": if (this.data.catalog.kind === "organization") this.setData({ owning: row }); break;
                case "using": if (this.data.catalog.kind === "organization") this.setData({ using: row, employee: null }); break;
                case "warehouse": if (this.data.catalog.kind === "warehouse") this.setData({ warehouse: row, location: null }); break;
                case "location": if (this.data.catalog.kind === "location") this.setData({ location: row }); break;
                case "employee": if (this.data.catalog.kind === "employee") this.setData({ employee: row }); break;
            }
        },
        onClearOwning(this: Context) { if (!locked(this)) this.setData({ owning: null }); },
        onCustody(this: Context, event: Event) { if (!locked(this)) this.setData({ custody: event.detail?.value === "1" ? "SHARED" : "PERSONAL" }); },
        async onViewTransfer(this: Context, event: Event) {
            const transfer = event.currentTarget?.dataset?.id;
            if (!services || locked(this) || !id(transfer) || !this.data.subjects.some(row => row.links?.some(link => link.transfer_id === transfer))) return;
            const epoch = ++generation, who = identity; this.setData({ busy: true, error: "" });
            try { const linked = await services.sessions.request("GET", `/api/v1/mini/transfers/${transfer}`) as Transfer;
                if (current(epoch, who)) this.setData({ linked }); else changed(this, epoch, who);
            } catch (error) { failure(this, error, epoch, who); }
            finally { if (current(epoch, who)) this.setData({ busy: false }); }
        },
    };
}
