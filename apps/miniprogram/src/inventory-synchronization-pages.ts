import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
interface Status { taskVersion: number; taskStatus: string; assignmentVersion: number; observationWatermark: string;
    device: { current: boolean; reason: string; declaredAt?: string } }
interface Data { taskId: string; status: Status | null; pending: number; cleanup: number; unresolved: number;
    busy: boolean; retry: boolean; error: string; notice: string }
interface Context { data: Data; setData(value: Partial<Data>): void }
interface Pending { key: string; version: number; body: { deviceId: string; assignmentVersion: number;
    observationWatermark: string; queueSnapshot: string; unresolvedCount: number; pendingEmpty: true; cleanupEmpty: true } }
const blank = (): Data => ({ taskId: "", status: null, pending: 0, cleanup: 0, unresolved: 0,
    busy: false, retry: false, error: "", notice: "" });
/** This is an executor assertion for the current device, not proof about unknown offline devices. */
export function createInventorySynchronizationPage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", live = false, generation = 0, pending: Pending | null = null;
    const current = (epoch: number) => live && epoch === generation && identity === JSON.stringify(services.sessions.identity);
    const local = (task: string) => {
        const inventory = services.inventory?.();
        if (!inventory) throw new Error("本机队列服务不可用，不能确认同步。");
        const events = inventory.queue.list().filter(row => row.taskId === task);
        const cleanup = events.filter(row => inventory.queue.receipt(row.eventId) !== null).length;
        const statuses = inventory.queue.statuses(task);
        const failures = inventory.queue.failures().filter(row => row.taskId === task && row.deviceId === inventory.deviceId);
        const unresolved = new Set([...failures.map(row => row.eventId), ...statuses.filter(row =>
            ["EXPIRED", "REJECTED", "EXPIRED_RECOLLECT", "AUTHORIZATION_REJECTED"].includes(row.outcome)).map(row => row.eventId)]).size;
        return { deviceId: inventory.deviceId, pending: events.length - cleanup, cleanup, unresolved,
            snapshot: JSON.stringify({ deviceId: inventory.deviceId, taskId: task, events, statuses, failures }) };
    };
    const failure = (page: Context, error: unknown) => {
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) {
            pending = null; page.setData({ status: null, retry: false, error: "身份或任务关系已失效，请重新登录或进入任务。" });
        } else if (error instanceof MiniRequestError && [400, 409].includes(error.status)) {
            pending = null; page.setData({ status: null, retry: false, error: "任务、分配或观察证据已变化，请刷新核对后重新确认。" });
        } else page.setData({ error: pending ? "提交结果未确认，保留原操作；请刷新核对后原键重试。"
            : error instanceof Error ? error.message : "读取未完成，请重试。" });
    };
    const read = async (page: Context) => {
        const epoch = ++generation; page.setData({ busy: true, status: null, error: "", notice: "" });
        try {
            const queue = local(page.data.taskId);
            const status = await services.sessions.request("GET", `/api/v1/mini/inventory-synchronization/tasks/${page.data.taskId}/devices/${queue.deviceId}`) as Status;
            if (current(epoch)) page.setData({ status, pending: queue.pending, cleanup: queue.cleanup,
                unresolved: queue.unresolved, retry: !!pending });
        } catch (error) { if (current(epoch)) failure(page, error); }
        finally { if (current(epoch)) page.setData({ busy: false });
            else if (live && epoch === generation) { pending = null; page.setData({ ...blank(), taskId: page.data.taskId, error: "身份已变化，已隐藏原资料。" }); } }
    };
    const send = async (page: Context, operation: Pending, epoch: number) => {
        try {
            await services.sessions.request("POST", `/api/v1/mini/inventory-synchronization/tasks/${page.data.taskId}/declarations`, operation.body, operation);
            if (!current(epoch)) return;
            pending = null;
            const now = local(page.data.taskId);
            page.setData({ retry: false, notice: now.snapshot === operation.body.queueSnapshot
                ? "已收到本次声明；请刷新核对当前状态。" : "提交后本机队列已变化，不能视为当前已同步，请完成同步后重新确认。", status: null });
        } catch (error) {
            if (current(epoch)) {
                if (error instanceof MiniRequestError && [400, 409].includes(error.status)) pending = null;
                page.setData({ retry: !!pending }); failure(page, error);
            }
        }
    };
    return {
        data: blank(),
        onLoad(this: Context, options: { taskId?: string }) {
            if (/^[1-9][0-9]*$/.test(options.taskId ?? "")) this.setData({ taskId: options.taskId! });
        },
        async onShow(this: Context) {
            live = true; services = getServices(); const next = JSON.stringify(services.sessions.identity);
            if (identity !== next) { pending = null; this.setData({ ...blank(), taskId: this.data.taskId }); }
            identity = next; if (this.data.taskId) await read(this);
        },
        onHide(this: Context) { live = false; generation++; this.setData({ ...blank(), taskId: this.data.taskId }); },
        onUnload(this: Context) { live = false; generation++; pending = null; this.setData(blank()); },
        async onRefresh(this: Context) { if (!this.data.busy) await read(this); },
        async onDeclare(this: Context) {
            if (this.data.busy || pending || !this.data.status || !current(generation)) return;
            if (!["IN_PROGRESS", "WAITING_REVIEW"].includes(this.data.status.taskStatus)) {
                this.setData({ error: "当前任务不再接受同步声明，请联系管理员核对后续补证。" }); return;
            }
            const epoch = generation, status = this.data.status; this.setData({ busy: true, error: "", notice: "" });
            try {
                const before = local(this.data.taskId);
                if (before.pending || before.cleanup) throw new Error("本任务仍有待上传记录或照片待清理，请先完成同步和清理。");
                const operation: Pending = { key: services.createKey(), version: status.taskVersion,
                    body: { deviceId: before.deviceId, assignmentVersion: status.assignmentVersion,
                        observationWatermark: status.observationWatermark, queueSnapshot: before.snapshot,
                        unresolvedCount: before.unresolved, pendingEmpty: true, cleanupEmpty: true } };
                if (!await services.confirm("声明本机已同步", `确认本任务在当前设备没有待上传或待清理记录。仍有 ${before.unresolved} 条缺失或拒绝提示；声明不能代替管理员复核，也不能证明其他离线设备为空。`)) return;
                if (!current(epoch)) return;
                if (local(this.data.taskId).snapshot !== before.snapshot) throw new Error("确认期间本机队列已变化，请刷新后重新核对。");
                pending = operation; this.setData({ retry: true }); await send(this, operation, epoch);
            } catch (error) { if (current(epoch)) failure(this, error); }
            finally { if (current(epoch)) this.setData({ busy: false });
                else if (live && epoch === generation) { pending = null; this.setData({ ...blank(), taskId: this.data.taskId, error: "身份已变化，已隐藏原资料。" }); } }
        },
        async onRetry(this: Context) {
            if (this.data.busy || !pending || !this.data.status || !current(generation)) return;
            const epoch = generation, operation = pending; this.setData({ busy: true, error: "" });
            try {
                const queue = local(this.data.taskId);
                if (queue.snapshot !== operation.body.queueSnapshot || queue.pending || queue.cleanup) {
                    this.setData({ status: null, error: "原声明后本机状态已变化，不能重发。请完成同步并重新进入本页核对当前状态。" }); return;
                }
                await send(this, operation, epoch);
            } catch (error) { if (current(epoch)) failure(this, error); }
            finally { if (current(epoch)) this.setData({ busy: false });
                else if (live && epoch === generation) { pending = null; this.setData({ ...blank(), taskId: this.data.taskId, error: "身份已变化，已隐藏原资料。" }); } }
        }
    };
}
