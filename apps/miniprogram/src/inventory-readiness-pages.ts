import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
interface MissingLoss { reasonId: string; generation: number; reason: string; canSubmit: boolean;
    report: { reportId: string; status: string; nextReviewDate: string | null } | null }
interface Row { item: { id: string; recheck_generation: number; review_status?: string; reason_kind?: string; reason?: string;
    snapshot_json: { assetCode: string; name: string } }; current: { version: number } | null; canRecord: boolean; physicalMissingOpen: boolean;
    missingLosses?: MissingLoss[] }
interface View { task: { version: number; task_status: string }; items: Row[]; total: number; missingCount: number; notObservedCount: number }
interface History { id: string; recheck_generation: number; reason_kind: string; reason: string; evidence: string; actor_username: string; recorded_at: string }
interface Pending { path: string; key: string; version: number; body: unknown }
interface Data { taskId: string; view: View | null; selected: Row | null; page: number; kindIndex: number; kindNames: string[];
    reason: string; evidence: string; waitingReason: string; busy: boolean; retry: boolean; error: string; notice: string;
    history: History[]; historyItem: string; historyPage: number; historyTotal: number;
    lossSource: { itemId: string; reasonId: string } | null; lossDate: string; lossTime: string; lossLocation: string; lossCircumstances: string;
    lossStates: Record<string, string> }
interface Context { data: Data; setData(value: Partial<Data>): void }
const kinds = ["UNABLE_TO_OBSERVE", "EVIDENCE_UNAVAILABLE", "ASSET_NOT_FOUND"];
const names = ["无法实盘（如现场无法进入）", "证据失效或无法使用，需补盘", "实物未找到，继续查找或正式报失"];
const lossStates = { PENDING_ADMIN_DECISION: "待审批", WAITING_WAREHOUSE_CLAIM: "待认领",
    VERIFYING: "待核实", SEARCHING: "查找中", CONFIRMED_LOST: "已确认遗失", RECOVERED: "已找回",
    REJECTED: "已驳回", CANCELLED: "已取消" };
const blank = (): Data => ({ taskId: "", view: null, selected: null, page: 1, kindIndex: 0, kindNames: names,
    reason: "", evidence: "", waitingReason: "", busy: false, retry: false, error: "", notice: "",
    history: [], historyItem: "", historyPage: 1, historyTotal: 0, lossSource: null,
    lossDate: "", lossTime: "", lossLocation: "", lossCircumstances: "", lossStates });
export function createInventoryReadinessPage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", live = false, generation = 0, pending: Pending | null = null;
    const current = (epoch: number) => live && generation === epoch && identity === JSON.stringify(services.sessions.identity);
    const failure = (page: Context, cause: unknown) => {
        if (cause instanceof MiniRequestError && [401, 403, 404].includes(cause.status)) {
            pending = null; page.setData({ ...blank(), taskId: page.data.taskId, error: "身份、权限或任务关系已失效，请重新进入。" });
        } else if (cause instanceof MiniRequestError && [400, 409].includes(cause.status)) {
            pending = null; page.setData({ retry: false, selected: null, error: cause.fields.processedBy
                ? `该项已由 ${cause.fields.processedBy} 处理，请刷新核对。` : "任务、原始证据或复核已变化，请刷新核对后继续。" });
        } else page.setData({ retry: !!pending, error: pending ? "提交结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => {
        if (current(epoch)) page.setData({ busy: false });
        else if (live && epoch === generation) { pending = null; page.setData({ ...blank(), taskId: page.data.taskId, error: "身份已变化，已隐藏原资料。" }); }
    };
    const read = async (page: Context) => {
        const epoch = ++generation; page.setData({ busy: true, error: "", view: null, selected: null, lossSource: null, history: [], historyItem: "" });
        try { const view = await services.sessions.request("GET", `/api/v1/mini/inventory-readiness/tasks/${page.data.taskId}?page=${page.data.page}&pageSize=20`) as View;
            if (current(epoch)) page.setData({ view, retry: !!pending });
        } catch (cause) { if (current(epoch)) failure(page, cause); }
        finally { finish(page, epoch); }
    };
    const send = async (page: Context, operation: Pending, epoch: number) => {
        try { await services.sessions.request("POST", operation.path, operation.body, operation);
            if (current(epoch)) { pending = null; page.setData({ retry: false, notice: "已保存本次处理，未盘不计正常。" }); await read(page); }
        } catch (cause) { if (current(epoch)) failure(page, cause); }
    };
    const history = async (page: Context, item: string, next: number) => {
        if (!page.data.view?.items.some(row => row.item.id === item)) return;
        const epoch = ++generation; page.setData({ busy: true, error: "", history: [] });
        try { const result = await services.sessions.request("GET", `/api/v1/mini/inventory-readiness/tasks/${page.data.taskId}/items/${item}/unobserved-history?page=${next}&pageSize=20`) as { items: History[]; total: number };
            if (current(epoch)) page.setData({ history: result.items, historyItem: item, historyPage: next, historyTotal: result.total });
        } catch (cause) { if (current(epoch)) failure(page, cause); }
        finally { finish(page, epoch); }
    };
    return {
        data: blank(),
        onLoad(this: Context, options: { taskId?: string }) { if (/^[1-9][0-9]*$/.test(options.taskId ?? "")) this.setData({ taskId: options.taskId! }); },
        async onShow(this: Context) {
            live = true;
            try { services = getServices(); const next = JSON.stringify(services.sessions.identity);
                if (next !== identity) { pending = null; this.setData({ ...blank(), taskId: this.data.taskId }); } identity = next;
                if (this.data.taskId) await read(this);
            } catch { this.setData({ ...blank(), taskId: this.data.taskId, error: "服务未就绪，请重试。" }); }
        },
        onHide(this: Context) { live = false; generation++; this.setData({ ...blank(), taskId: this.data.taskId }); },
        onUnload(this: Context) { live = false; generation++; pending = null; this.setData(blank()); },
        async onRefresh(this: Context) { if (!this.data.busy) await read(this); },
        onSelect(this: Context, event: { currentTarget: { dataset: { id?: string } } }) {
            if (this.data.busy || pending || !current(generation)) return;
            const row = this.data.view?.items.find(item => item.item.id === event.currentTarget.dataset.id && item.canRecord);
            if (row) this.setData({ selected: row, lossSource: null, reason: "", evidence: "", notice: "", error: "" });
        },
        onField(this: Context, event: { currentTarget: { dataset: { field?: string } }; detail: { value: string } }) {
            const field = event.currentTarget.dataset.field;
            if (!this.data.busy && !pending && ["reason", "evidence", "waitingReason", "lossDate", "lossTime", "lossLocation", "lossCircumstances"].includes(field ?? "")) this.setData({ [field!]: event.detail.value });
        },
        onSelectLoss(this: Context, event: { currentTarget: { dataset: { item?: string; reason?: string } } }) {
            if (this.data.busy || pending || !current(generation)) return;
            const { item, reason } = event.currentTarget.dataset;
            const row = this.data.view?.items.find(candidate => candidate.item.id === item);
            const source = row?.missingLosses?.find(candidate => candidate.reasonId === reason && candidate.canSubmit);
            if (row && source) this.setData({ selected: null, lossSource: { itemId: row.item.id, reasonId: source.reasonId },
                lossDate: "", lossTime: "", lossLocation: "", lossCircumstances: "", notice: "", error: "" });
        },
        onOpenLoss(this: Context, event: { currentTarget: { dataset: { id?: string } } }) {
            const id = event.currentTarget.dataset.id ?? "";
            if (!this.data.busy && !pending && current(generation) && /^[1-9][0-9]*$/.test(id)
                && this.data.view?.items.some(row => row.missingLosses?.some(source => source.report?.reportId === id))) {
                services.navigate("/pages/loss-reports/index?id=" + id);
            }
        },
        async onReportLoss(this: Context) {
            const { view, lossSource, lossDate, lossTime, lossLocation, lossCircumstances } = this.data;
            const row = view?.items.find(candidate => candidate.item.id === lossSource?.itemId);
            const observed = new Date(lossDate + "T" + lossTime);
            if (this.data.busy || pending || !current(generation) || !view || !lossSource || !row?.current
                || !row.missingLosses?.some(source => source.reasonId === lossSource.reasonId && source.canSubmit)
                || !Number.isFinite(observed.getTime()) || !lossLocation.trim() || lossCircumstances.trim().length < 10) return;
            const epoch = generation;
            const operation: Pending = { path: `/api/v1/mini/inventory-readiness/tasks/${this.data.taskId}/items/${lossSource.itemId}/missing/${lossSource.reasonId}/loss`,
                key: services.createKey(), version: view.task.version, body: { assetVersion: row.current.version,
                    lastSeenAt: observed.toISOString(), lastLocation: lossLocation.trim(), circumstances: lossCircumstances.trim() } };
            this.setData({ busy: true, error: "" });
            try {
                if (!await services.confirm("盘亏转报失", `${row.item.snapshot_json.assetCode} · 提交关联报失供管理员审批。原盘点记录保留。`) || !current(epoch)) return;
                pending = operation; this.setData({ retry: true }); await send(this, operation, epoch);
            } catch (cause) { if (current(epoch)) failure(this, cause); }
            finally { finish(this, epoch); }
        },
        onKind(this: Context, event: { detail: { value: string } }) {
            const index = Number(event.detail.value); if (!this.data.busy && !pending && Number.isInteger(index) && index >= 0 && index < kinds.length) this.setData({ kindIndex: index });
        },
        async onSave(this: Context) {
            const row = this.data.selected, view = this.data.view;
            if (this.data.busy || pending || !current(generation) || !row?.canRecord || !row.current || !view
                || this.data.reason.trim().length < 3 || this.data.evidence.trim().length < 10) return;
            const epoch = generation;
            const operation: Pending = { path: `/api/v1/mini/inventory-readiness/tasks/${this.data.taskId}/items/${row.item.id}/unobserved`, key: services.createKey(), version: view.task.version,
                body: { recheckGeneration: row.item.recheck_generation, assetVersion: row.current.version, kind: kinds[this.data.kindIndex], reason: this.data.reason.trim(), evidence: this.data.evidence.trim() } };
            this.setData({ busy: true, error: "" });
            try { if (!await services.confirm("记录本轮未盘", `${row.item.snapshot_json.assetCode} · ${names[this.data.kindIndex]}。未盘不计正常；实物未找到仍需真实查找或报失闭环。`)) return;
                if (!current(epoch)) return; pending = operation; this.setData({ retry: true }); await send(this, operation, epoch);
            } catch (cause) { if (current(epoch)) failure(this, cause); }
            finally { finish(this, epoch); }
        },
        async onWaiting(this: Context) {
            const view = this.data.view;
            if (this.data.busy || pending || !current(generation) || !view || view.task.task_status !== "IN_PROGRESS" || view.missingCount || this.data.waitingReason.trim().length < 3) return;
            const epoch = generation, operation: Pending = { path: `/api/v1/mini/inventory-readiness/tasks/${this.data.taskId}/waiting-review`, key: services.createKey(), version: view.task.version, body: { reason: this.data.waitingReason.trim() } };
            this.setData({ busy: true, error: "" });
            try { if (!await services.confirm("进入待复核", `本轮明确未盘 ${view.notObservedCount} 项不计正常。差异、同步和封存仍需分别核实。`)) return;
                if (!current(epoch)) return; pending = operation; this.setData({ retry: true }); await send(this, operation, epoch);
            } catch (cause) { if (current(epoch)) failure(this, cause); }
            finally { finish(this, epoch); }
        },
        async onRetry(this: Context) {
            if (this.data.busy || !pending || !this.data.view || !current(generation)) return;
            const epoch = generation, operation = pending; this.setData({ busy: true, error: "" });
            try { await send(this, operation, epoch); } finally { finish(this, epoch); }
        },
        async onPage(this: Context, event: { currentTarget: { dataset: { delta?: string } } }) {
            const delta = Number(event.currentTarget.dataset.delta), view = this.data.view;
            if (this.data.busy || pending || !view || ![1, -1].includes(delta)) return;
            const page = this.data.page + delta; if (page < 1 || (page - 1) * 20 >= view.total) return; this.setData({ page }); await read(this);
        },
        async onHistory(this: Context, event: { currentTarget: { dataset: { id?: string } } }) {
            const id = event.currentTarget.dataset.id; if (!this.data.busy && !pending && id && current(generation)) await history(this, id, 1);
        },
        async onHistoryPage(this: Context, event: { currentTarget: { dataset: { delta?: string } } }) {
            const delta = Number(event.currentTarget.dataset.delta); if (this.data.busy || pending || !current(generation) || !this.data.historyItem || ![1, -1].includes(delta)) return;
            const next = this.data.historyPage + delta; if (next >= 1 && (next - 1) * 20 < this.data.historyTotal) await history(this, this.data.historyItem, next);
        }
    };
}
