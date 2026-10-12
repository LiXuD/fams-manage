import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";

interface Snapshot {
    id: string; version: number; ownershipVersion: number; processorAccountId: string;
    operators: { id: string; name: string }[];
    items: { id: string; version: number; itemStatus: string; lockVersion: number;
        destination?: string; deliveryEvidence?: string }[];
}
interface Data {
    rows: { id: string; requestNo: string; requestStatus: string }[]; page: number; total: number;
    requestId: string; snapshot: Snapshot | null; target: string; reason: string; evidence: string;
    checked: boolean; busy: boolean; retryable: boolean; error: string; notice: string;
}
interface Context { data: Data; setData(value: Partial<Data>): void; onList(): Promise<void>; onInspect(): Promise<void> }
interface Input { detail: { value: string | string[] } }
const empty = (): Data => ({ rows: [], page: 1, total: 0, requestId: "", snapshot: null, target: "", reason: "", evidence: "",
    checked: false, busy: false, retryable: false, error: "", notice: "" });
function message(error: unknown) {
    if (error instanceof MiniRequestError) {
        if (error.status === 401) return "登录已失效，请重新登录。";
        if (error.status === 403 || error.status === 404) return "当前账号无管理员转派权限或单据不可见。";
        if (error.status === 409) return "处理权、实物或资产锁已变更，请重新加载并核对。";
        if (error.status === 400) return "请填写完整交接记录并核对单据。";
    }
    return "结果尚未确认，请原请求重试或重新加载查询。";
}

/** Administrator entry uses the same server-side ownership transaction as PC. */
export function createTransferReassignmentPage(getServices: () => MiniServices) {
    let live = false, generation = 0, identity = "";
    let services: MiniServices | null = null;
    let pending: { path: string; key: string; version: number; body: unknown } | null = null;
    const perform = async (page: Context) => {
        if (!services || !pending || page.data.busy) return;
        const operation = pending, current = generation;
        page.setData({ busy: true, error: "", notice: "" });
        try {
            await services.sessions.request("POST", operation.path, operation.body, operation);
            if (!live || current !== generation) return;
            pending = null;
            page.setData({ snapshot: null, target: "", reason: "", evidence: "", checked: false,
                retryable: false, notice: "整单处理权已转派；实物阶段、正式责任及资产锁保持。" });
        } catch (error) {
            if (live && current === generation) {
                page.setData({ error: message(error), retryable: true });
                if (!services.sessions.identity) services.navigate("/pages/login/index");
            }
        } finally { if (live && current === generation) page.setData({ busy: false }); }
    };
    return {
        data: empty(),
        onShow(this: Context) {
            live = true; generation++; services = getServices();
            const next = JSON.stringify(services.sessions.identity);
            if (next !== identity) { identity = next; pending = null; this.setData(empty()); }
            else this.setData({ busy: false, retryable: pending !== null });
        },
        onHide() { live = false; generation++; },
        onUnload() { live = false; generation++; pending = null; },
        async onList(this: Context) {
            if (!services || this.data.busy || pending) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try {
                const result = await services.sessions.request("GET", `/api/v1/mini/transfers?page=${this.data.page}&pageSize=20`) as
                    { items: Data["rows"]; total: number };
                if (live && current === generation) this.setData({ rows: result.items, total: result.total });
            } catch (error) { if (live && current === generation) this.setData({ error: message(error) }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onOpen(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            if (this.data.busy || pending) return;
            this.setData({ requestId: event.currentTarget.dataset.id, snapshot: null, checked: false });
            await this.onInspect();
        },
        async onNext(this: Context) {
            if (this.data.busy || pending || this.data.page * 20 >= this.data.total) return;
            this.setData({ page: this.data.page + 1 }); await this.onList();
        },
        async onPrevious(this: Context) {
            if (this.data.busy || pending || this.data.page <= 1) return;
            this.setData({ page: this.data.page - 1 }); await this.onList();
        },
        onRequest(this: Context, event: Input) {
            if (!this.data.busy && !pending) this.setData({ requestId: String(event.detail.value), snapshot: null, checked: false });
        },
        onTarget(this: Context, event: Input) {
            if (this.data.busy || pending) return;
            const operator = this.data.snapshot?.operators[Number(event.detail.value)];
            this.setData({ target: operator?.id ?? "" });
        },
        onReason(this: Context, event: Input) { if (!this.data.busy && !pending) this.setData({ reason: String(event.detail.value) }); },
        onEvidence(this: Context, event: Input) { if (!this.data.busy && !pending) this.setData({ evidence: String(event.detail.value) }); },
        onCheck(this: Context, event: Input) {
            if (!this.data.busy && !pending) this.setData({ checked: Array.isArray(event.detail.value) && event.detail.value.includes("checked") });
        },
        async onInspect(this: Context) {
            if (!services || this.data.busy) return;
            if (!/^[1-9][0-9]*$/.test(this.data.requestId.trim())) { this.setData({ error: "请填写调拨单据ID。" }); return; }
            const current = generation;
            this.setData({ busy: true, error: "", notice: "" });
            try {
                const snapshot = await services.sessions.request("GET", `/api/v1/mini/transfers/${this.data.requestId.trim()}/reassignment`) as Snapshot;
                if (!live || current !== generation) return;
                pending = null;
                this.setData({ snapshot, target: "", checked: false, reason: "", evidence: "", retryable: false });
            } catch (error) { if (live && current === generation) this.setData({ error: message(error) }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onSubmit(this: Context) {
            if (!services || this.data.busy || pending || !this.data.snapshot) return;
            if (!this.data.target || !this.data.reason.trim() || !this.data.evidence.trim() || !this.data.checked) {
                this.setData({ error: "请选择接任库管，填写原因和凭证，并核对未同步结果。" }); return;
            }
            const current = generation;
            this.setData({ busy: true, error: "" });
            try {
                const confirmed = await services.confirm("转派整单处理权", "已核对全部实物阶段、资产锁与未同步结果，交给接任库管继续处理？");
                if (!live || current !== generation || !confirmed) return;
                const snapshot = this.data.snapshot;
                pending = { path: `/api/v1/mini/transfers/${snapshot.id}/reassignment`, key: services.createKey(), version: snapshot.version,
                    body: { processorAccountId: this.data.target, ownershipVersion: snapshot.ownershipVersion,
                        reason: this.data.reason.trim(), evidence: this.data.evidence.trim(), unsynchronizedResultsChecked: true,
                        items: snapshot.items.map(item => ({ id: item.id, version: item.version, status: item.itemStatus, lockVersion: item.lockVersion })) } };
            } catch { if (live && current === generation) this.setData({ error: "确认未完成，请重新核对后操作。" }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
            if (live && current === generation) await perform(this);
        },
        async onRetry(this: Context) { await perform(this); },
    };
}
