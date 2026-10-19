import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import { listReviewPhotos, readReviewPhoto, type ReviewPhotoReference } from "./inventory-review-photos.ts";
interface Finding { id: string; client_event_id: string; outcome: string;
    facts_json: { actual?: { description?: string }; photoIds?: string[] } }
interface Target { kind: string; assetId: string; assetCode: string; ownerVersion: number; inboundReceiptId?: string; receiptNumber?: string; status?: string; key?: string }
interface View { taskVersion: number; observation: Finding; subject: { version?: number; subject_status?: string; stateLabel?: string };
    history: { after_version: number; actor_username: string; subject_status: string; reason: string; evidence: string; inboundReceiptId?: string; method?: string; occurred_at?: string; stateLabel?: string; methodLabel?: string; timeText?: string }[]; historyTotal: number }
interface Data { taskId: string; rows: Finding[]; total: number; page: number; detail: View | null; method: number; methods: string[];
    reason: string; evidence: string; assetCode: string; receiptNumber: string; targets: Target[]; target: Target | null;
    photos: ReviewPhotoReference[]; photoPath: string; cleanupPending: boolean; busy: boolean; retryable: boolean; error: string; notice: string }
interface Context { data: Data; setData(value: Partial<Data>): void }
interface Event { currentTarget?: { dataset?: { id?: string; field?: string; delta?: string } }; detail?: { value?: unknown } }
interface Pending { path: string; version: number; key: string; body: unknown; observation: string }
const methods = ["有依据确认非企业资产", "核实为正式卡重复线索", "关联已提交正式入库", "核对既有入库进度"];
const states: Record<string, string> = { OPEN: "待处理", PROCESSING: "处理中", CLOSED: "已关闭" };
const codes = ["NON_ENTERPRISE", "DUPLICATE_ASSET", "LINK_INBOUND", "REFRESH_INBOUND"];
const blank = (taskId = ""): Data => ({ taskId, rows: [], total: 0, page: 1, detail: null, method: 0, methods,
    reason: "", evidence: "", assetCode: "", receiptNumber: "", targets: [], target: null, photos: [], photoPath: "", cleanupPending: false,
    busy: false, retryable: false, error: "", notice: "" });

/** Only authorized original finding evidence and existing formal owners can produce a decision. */
export function createInventoryFindingPage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", generation = 0, live = false, pending: Pending | null = null;
    const files = new Set<string>();
    const owner = () => JSON.stringify(services?.sessions.identity ?? null);
    const current = (epoch: number) => live && generation === epoch && identity === owner();
    const base = (page: Context) => `/api/v1/mini/inventory-reviews/tasks/${page.data.taskId}`;
    const locked = (page: Context) => page.data.busy || pending !== null;
    const cleanup = (page: Context) => {
        page.setData({ photoPath: "" });
        for (const path of [...files]) { try { services.sessions.discardInventoryPhoto(path); files.delete(path); } catch { /* Keep exact retry target. */ } }
        page.setData({ cleanupPending: files.size > 0 }); return files.size === 0;
    };
    const invalidated = (page: Context) => { generation++; pending = null; page.setData({ ...blank(page.data.taskId), error: "身份或权限已变化，请重新进入。" }); cleanup(page); };
    const failure = (page: Context, error: unknown, epoch: number) => {
        if (!current(epoch)) { if (live && epoch === generation) invalidated(page); return; }
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) { invalidated(page); return; }
        if (error instanceof MiniRequestError && error.status === 409) page.setData({ error: error.fields.processedBy
            ? `该差异已由 ${error.fields.processedBy} 处理，请刷新。` : "任务、差异或正式单据已变化，请重新核对。" });
        else page.setData({ error: pending ? "结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。" });
    };
    const read = async (page: Context, observation = "") => {
        const epoch = ++generation; cleanup(page); page.setData({ busy: true, error: "", photos: [] });
        try {
            const response = await services.sessions.request("GET", observation ? `${base(page)}/findings/${observation}`
                : `${base(page)}/findings?page=${page.data.page}&pageSize=20`);
            if (!current(epoch)) { if (live && epoch === generation) invalidated(page); return; }
            if (observation) { const value = response as View;
                const detail = { ...value, subject: { ...value.subject, stateLabel: states[value.subject.subject_status ?? ""] ?? "尚未列入复核" },
                    history: value.history.map(row => ({ ...row, stateLabel: states[row.subject_status] ?? "待核实", methodLabel: methods[codes.indexOf(row.method ?? "")] ?? "历史处理",
                        timeText: row.occurred_at ? new Date(row.occurred_at).toLocaleString() : "时间待核实" })) };
                page.setData({ detail, targets: [], target: null,
                method: detail.subject.subject_status === "PROCESSING" ? 3 : 0, retryable: pending !== null }); }
            else { const list = response as { items: Finding[]; total: number }; page.setData({ rows: list.items, total: list.total, detail: null }); }
        } catch (error) { failure(page, error, epoch); }
        finally { if (current(epoch)) page.setData({ busy: false }); }
    };
    const perform = async (page: Context) => {
        if (!pending || page.data.busy) return;
        const original = pending, epoch = ++generation; page.setData({ busy: true, error: "" });
        try {
            await services.sessions.request("POST", original.path, original.body, original);
            if (!current(epoch)) { if (live && epoch === generation) invalidated(page); return; }
            pending = null; page.setData({ retryable: false, notice: "已保存；正式入库未实际验收不会提前关闭。", busy: false });
            await read(page, original.observation);
        } catch (error) {
            if (current(epoch) && error instanceof MiniRequestError && error.status >= 400 && error.status < 500) pending = null;
            failure(page, error, epoch);
            if (current(epoch)) page.setData({ retryable: pending !== null });
        } finally { if (current(epoch)) page.setData({ busy: false }); }
    };
    return {
        data: blank(),
        onLoad(this: Context, options: { taskId?: string }) { this.setData(blank(/^[1-9][0-9]{0,18}$/.test(options.taskId ?? "") ? options.taskId : "")); },
        async onShow(this: Context) {
            live = true;
            try {
                services = getServices(); const next = owner();
                if (next !== identity) { pending = null; this.setData(blank(this.data.taskId)); } identity = next;
                if (this.data.taskId) await read(this, pending?.observation ?? "");
            } catch { invalidated(this); }
        },
        onHide(this: Context) { live = false; generation++; this.setData(blank(this.data.taskId)); cleanup(this); },
        onUnload(this: Context) { live = false; generation++; pending = null; this.setData(blank(this.data.taskId)); cleanup(this); },
        async onRefresh(this: Context) { if (!this.data.busy) await read(this, pending?.observation ?? this.data.detail?.observation.id ?? ""); },
        async onOpen(this: Context, event: Event) {
            if (locked(this)) return; const row = this.data.rows.find(value => value.id === event.currentTarget?.dataset?.id && value.outcome === "FINDING_REVIEW");
            if (row) { this.setData({ reason: "", evidence: "", assetCode: "", receiptNumber: "" }); await read(this, row.id); }
        },
        onField(this: Context, event: Event) {
            const field = event.currentTarget?.dataset?.field, value = event.detail?.value;
            if (!locked(this) && typeof value === "string" && ["reason", "evidence", "assetCode", "receiptNumber"].includes(field ?? "")) this.setData({ [field!]: value });
        },
        onMethod(this: Context, event: Event) { const method = Number(event.detail?.value); if (!locked(this) && Number.isInteger(method) && codes[method]) this.setData({ method, targets: [], target: null }); },
        async onSearch(this: Context) {
            if (locked(this) || !this.data.detail || !this.data.assetCode.trim() || this.data.method === 0
                || this.data.method > 1 && !this.data.receiptNumber.trim()) return;
            const epoch = ++generation; this.setData({ busy: true, targets: [], target: null, error: "" });
            try {
                const response = await services.sessions.request("GET", `${base(this)}/finding-targets?assetCode=${encodeURIComponent(this.data.assetCode.trim())}`
                    + (this.data.method > 1 ? `&receiptNumber=${encodeURIComponent(this.data.receiptNumber.trim())}` : "")) as { items: Target[] };
                if (current(epoch)) this.setData({ targets: response.items.map(row => ({ ...row, key: `${row.inboundReceiptId ?? "card"}:${row.assetId}` })) });
            } catch (error) { failure(this, error, epoch); }
            finally { if (current(epoch)) this.setData({ busy: false }); }
        },
        onTarget(this: Context, event: Event) { if (!locked(this)) this.setData({ target: this.data.targets.find(row => row.key === event.currentTarget?.dataset?.id) ?? null }); },
        async onSave(this: Context, event?: Event) {
            if (locked(this) || !this.data.detail) return;
            const prepare = event?.currentTarget?.dataset?.field === "prepare", row = this.data.detail.observation;
            if (!prepare && (this.data.reason.trim().length < 3 || this.data.evidence.trim().length < 10 || this.data.method > 0 && !this.data.target)) {
                this.setData({ error: "请明确选择正式依据并填写处理原因、核实证据。" }); return;
            }
            const target = this.data.target;
            const original: Pending = { observation: row.id, path: prepare ? `${base(this)}/differences/preparation` : `${base(this)}/findings/${row.id}/decisions`,
                key: services.createKey(), version: this.data.detail.taskVersion,
                body: prepare ? { observationIds: [row.id] } : { subjectVersion: this.data.detail.subject.version ?? 0,
                    method: codes[this.data.method], reason: this.data.reason.trim(), evidence: this.data.evidence.trim(),
                    ...(this.data.method > 0 && target ? { assetId: target.assetId, ownerVersion: target.ownerVersion,
                        ...(target.inboundReceiptId ? { inboundReceiptId: target.inboundReceiptId } : {}) } : {}) } };
            const epoch = generation; this.setData({ busy: true });
            try {
                if (!await services.confirm("盘盈独立复核", prepare ? "将此原始线索列入独立复核？" : "保存当前明确依据及正式关联？未实际验收不会提前关闭。")) return;
                if (!current(epoch)) { if (live && epoch === generation) invalidated(this); return; }
                pending = original; this.setData({ busy: false, retryable: true }); await perform(this);
            } catch (error) { failure(this, error, epoch); }
            finally { if (current(epoch)) this.setData({ busy: false }); }
        },
        async onRetry(this: Context) { await perform(this); },
        async onPage(this: Context, event: Event) {
            const delta = Number(event.currentTarget?.dataset?.delta), next = this.data.page + delta;
            if (locked(this) || ![1, -1].includes(delta) || next < 1 || (next - 1) * 20 >= this.data.total) return;
            this.setData({ page: next }); await read(this);
        },
        onCleanup(this: Context) { if (!this.data.busy) cleanup(this); },
        async onPhotos(this: Context) {
            const raw = this.data.detail?.observation;
            if (locked(this) || !raw?.facts_json.photoIds?.length || !cleanup(this)) return;
            const epoch = ++generation; this.setData({ busy: true, photos: [] });
            try { const photos = await listReviewPhotos(services.sessions, this.data.taskId, raw.client_event_id, raw.facts_json.photoIds, () => current(epoch)); if (current(epoch)) this.setData({ photos }); }
            catch (error) { failure(this, error, epoch); } finally { if (current(epoch)) this.setData({ busy: false }); }
        },
        async onReadPhoto(this: Context, event: Event) {
            const raw = this.data.detail?.observation, row = this.data.photos.find(value => value.objectId === event.currentTarget?.dataset?.id && value.readable);
            if (locked(this) || !raw || !row?.photo || !cleanup(this)) return;
            const epoch = ++generation; this.setData({ busy: true });
            try {
                const path = await readReviewPhoto(services.sessions, this.data.taskId, raw.client_event_id, row.photo, () => services.createKey(), () => current(epoch)); files.add(path);
                if (current(epoch)) this.setData({ photoPath: path });
                else { try { services.sessions.discardInventoryPhoto(path); files.delete(path); } catch { if (live) this.setData({ cleanupPending: true }); } }
            } catch (error) { failure(this, error, epoch); } finally { if (current(epoch)) this.setData({ busy: false }); }
        },
    };
}
