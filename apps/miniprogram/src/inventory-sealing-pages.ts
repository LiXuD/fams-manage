import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import { sealedRecordLines, synchronizationReasonText, sealBlockerText } from "./inventory-sealing-display.ts";
interface Report { report_sha256: string; accepted_count: number; not_observed_count: number; actor_username: string; created_at: string; missing_sync_reason: string | null }
interface View { task: { version: number; task_status: string }; sealed: boolean; report?: Report; blockerCount?: number;
    blockers?: { code: string; itemId?: string; observationId?: string }[]; synchronizationDigest?: string;
    missingSynchronizationCount?: number; synchronization?: { accountId: string; name: string; current: boolean; reason: string;
        devices: { deviceId: string; current: boolean; reason: string }[] }[] }
interface Pending { path: string; key: string; version: number; body: unknown }
interface Data { taskId: string; view: View | null; busy: boolean; retry: boolean; error: string; notice: string; reason: string; waiver: string;
    blockers: { id: string; text: string }[]; sectionIndex: number; sectionNames: string[]; page: number; total: number; records: { id: string; text: string }[] }
interface Context { data: Data; setData(value: Partial<Data>): void }
const sections = ["items", "evidence", "synchronization"];
const blank = (): Data => ({ taskId: "", view: null, busy: false, retry: false, error: "", notice: "", reason: "", waiver: "", blockers: [],
    sectionIndex: 0, sectionNames: ["应盘项与日期依据", "原证据与独立决定", "同步声明与待补证名单"], page: 1, total: 0, records: [] });
export function createInventorySealingPage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", live = false, generation = 0, pending: Pending | null = null;
    const current = (epoch: number) => live && generation === epoch && identity === JSON.stringify(services.sessions.identity);
    const failure = (page: Context, cause: unknown) => {
        if (cause instanceof MiniRequestError && [401, 403, 404].includes(cause.status)) {
            pending = null; page.setData({ ...blank(), taskId: page.data.taskId, error: "身份、权限或任务失效，已隐藏冻结报告和原操作。" });
        } else if (cause instanceof MiniRequestError && [400, 409].includes(cause.status)) {
            pending = null; page.setData({ retry: false, error: cause.fields.processedBy ? `已由 ${cause.fields.processedBy} 处理，请刷新核对。`
                : "封存条件、任务或同步名单已变化，请刷新后逐项核对。" });
        } else page.setData({ retry: !!pending, error: pending ? "封存结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => {
        if (current(epoch)) page.setData({ busy: false });
        else if (live && epoch === generation) { pending = null; page.setData({ ...blank(), taskId: page.data.taskId, error: "身份已变化，已隐藏原资料。" }); }
    };
    const read = async (page: Context) => {
        const epoch = ++generation; page.setData({ busy: true, error: "", view: null, records: [], blockers: [] });
        try { const view = await services.sessions.request("GET", `/api/v1/mini/inventory-sealing/tasks/${page.data.taskId}`) as View;
            if (current(epoch)) page.setData({ view: { ...view, synchronization: view.synchronization?.map(row => ({ ...row, reason: synchronizationReasonText(row.reason), devices: row.devices.map(device => ({ ...device, reason: synchronizationReasonText(device.reason) })) })) }, retry: !!pending, blockers: (view.blockers ?? []).map((item, index) => ({ id: String(index),
                text: `${sealBlockerText(item.code)} · ${item.itemId || item.observationId || '任务'}` })) });
        } catch (cause) { if (current(epoch)) failure(page, cause); }
        finally { finish(page, epoch); }
    };
    const send = async (page: Context, operation: Pending, epoch: number) => {
        try { await services.sessions.request("POST", operation.path, operation.body, operation);
            if (current(epoch)) { pending = null; page.setData({ retry: false, notice: "已封存，原报告及已采信日期共同保存。" }); await read(page); }
        } catch (cause) { if (current(epoch)) failure(page, cause); }
    };
    const records = async (page: Context, next: number) => {
        const epoch = ++generation; page.setData({ busy: true, error: "", records: [] });
        try { const result = await services.sessions.request("GET", `/api/v1/mini/inventory-sealing/tasks/${page.data.taskId}/report/${sections[page.data.sectionIndex]}?page=${next}&pageSize=20`) as { items: { snapshot_json: unknown; snapshot_sha256: string }[]; total: number };
            if (current(epoch)) page.setData({ page: next, total: result.total, records: result.items.map((item, index) => ({ id: String(index), text: [...sealedRecordLines(item.snapshot_json), `冻结校验 ${item.snapshot_sha256}`].join("\n") })) });
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
        onField(this: Context, event: { currentTarget: { dataset: { field?: string } }; detail: { value: string } }) {
            const field = event.currentTarget.dataset.field;
            if (!this.data.busy && !pending && ["reason", "waiver"].includes(field ?? "")) this.setData({ [field!]: event.detail.value });
        },
        async onSeal(this: Context) {
            const view = this.data.view;
            if (this.data.busy || pending || !current(generation) || !view || view.sealed || view.task.task_status !== "WAITING_REVIEW"
                || view.blockerCount || !view.synchronizationDigest || this.data.reason.trim().length < 3
                || ((view.missingSynchronizationCount ?? 0) > 0 && this.data.waiver.trim().length < 10)) return;
            const epoch = generation, reason = this.data.reason.trim(), waiver = (view.missingSynchronizationCount ?? 0) > 0 ? this.data.waiver.trim() : null;
            const operation: Pending = { path: `/api/v1/mini/inventory-sealing/tasks/${this.data.taskId}/seal`, key: services.createKey(), version: view.task.version,
                body: { reason, synchronizationDigest: view.synchronizationDigest, missingSynchronizationReason: waiver } };
            this.setData({ busy: true, error: "" });
            try { if (!await services.confirm("确认冻结报告与待补证名单", `${reason}。缺少同步确认 ${view.missingSynchronizationCount ?? 0} 人。${waiver || '无需豁免'}。未盘不计正常；封存后只追加迟到或关联复盘，不改原报告。`)) return;
                if (!current(epoch)) return; pending = operation; this.setData({ retry: true }); await send(this, operation, epoch);
            } catch (cause) { if (current(epoch)) failure(this, cause); }
            finally { finish(this, epoch); }
        },
        async onRetry(this: Context) {
            if (this.data.busy || !pending || !this.data.view || !current(generation)) return;
            const epoch = generation, operation = pending; this.setData({ busy: true, error: "" });
            try { await send(this, operation, epoch); } finally { finish(this, epoch); }
        },
        onSection(this: Context, event: { detail: { value: string } }) {
            const index = Number(event.detail.value);
            if (!this.data.busy && !pending && Number.isInteger(index) && index >= 0 && index < sections.length) this.setData({ sectionIndex: index, page: 1, total: 0, records: [] });
        },
        async onReadReport(this: Context) { if (!this.data.busy && !pending && current(generation) && this.data.view?.sealed) await records(this, 1); },
        async onPage(this: Context, event: { currentTarget: { dataset: { delta?: string } } }) {
            const delta = Number(event.currentTarget.dataset.delta), next = this.data.page + delta;
            if (this.data.busy || pending || !current(generation) || !this.data.view?.sealed || ![1, -1].includes(delta) || next < 1 || (next - 1) * 20 >= this.data.total) return;
            await records(this, next);
        }
    };
}
