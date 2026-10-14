import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
interface Task { id: string; name: string; task_status: string; starts_at: string; due_at: string; overdue?: boolean; statusLabel?: string; startsText?: string; dueText?: string }
interface Item { id: string; assignment_version: number; snapshot_json: { assetCode: string; name: string; locationName?: string;
    responsibleEmployeeName?: string; departmentResponsibleEmployeeName?: string; custodyType?: string } }
interface Data { tasks: Task[]; task: Task | null; items: Item[]; page: number; total: number; itemPage: number; itemTotal: number; busy: boolean; error: string }
interface Context { data: Data; setData(value: Partial<Data>): void }
interface Event { currentTarget: { dataset: { id?: string; delta?: string } } }
const empty = (): Data => ({ tasks: [], task: null, items: [], page: 1, total: 0, itemPage: 1, itemTotal: 0, busy: false, error: "" });
const base = "/api/v1/mini/inventory-tasks";
const statusLabels: Record<string, string> = { DRAFT: "草稿", READY: "待开始", IN_PROGRESS: "进行中", WAITING_REVIEW: "待复核", COMPLETED: "已完成", CANCELLED: "已取消", TERMINATED: "已终止" };
function displayTime(value: string) {
    const date = new Date(value); if (!Number.isFinite(date.getTime())) return "待确认";
    const pad = (part: number) => String(part).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
function displayTask(task: Task): Task {
    return { ...task, statusLabel: statusLabels[task.task_status] ?? "状态待确认", startsText: displayTime(task.starts_at), dueText: displayTime(task.due_at) };
}
export function createInventoryPage(getServices: () => MiniServices) {
    let generation = 0, live = false, identity = "";
    let services: MiniServices;
    const current = (epoch: number) => live && epoch === generation && identity === JSON.stringify(services.sessions.identity);
    const failure = (page: Context, error: unknown) => {
        page.setData({ error: error instanceof MiniRequestError && [401, 403, 404].includes(error.status)
            ? "身份已失效或任务不再授权，请刷新或重新登录。" : "读取失败，请重试。" });
        if (!services.sessions.identity) { services.navigate("/pages/login/index"); }
    };
    const read = async (page: Context, id?: string) => {
        const epoch = ++generation; page.setData({ busy: true, error: "" });
        try {
            if (id) {
                const result = await services.sessions.request("GET", `${base}/${encodeURIComponent(id)}?page=${page.data.itemPage}&pageSize=20`) as { task: Task; items: Item[]; total: number };
                if (current(epoch)) page.setData({ task: displayTask(result.task), items: result.items, itemTotal: result.total });
            } else {
                const result = await services.sessions.request("GET", `${base}?page=${page.data.page}&pageSize=20`) as { items: Task[]; total: number };
                if (current(epoch)) page.setData({ tasks: result.items.map(displayTask), total: result.total, task: null, items: [], itemTotal: 0 });
            }
        } catch (error) { if (current(epoch)) { page.setData({ task: null, items: [], itemTotal: 0 }); failure(page, error); } }
        finally { if (current(epoch)) page.setData({ busy: false }); }
    };
    return {
        data: empty(),
        async onShow(this: Context) {
            live = true; services = getServices(); const next = JSON.stringify(services.sessions.identity);
            if (next !== identity) { identity = next; this.setData(empty()); }
            await read(this);
        },
        onHide() { live = false; generation++; },
        onUnload() { live = false; generation++; },
        async onRefresh(this: Context) { if (!this.data.busy) await read(this, this.data.task?.id); },
        async onOpen(this: Context, event: Event) {
            if (this.data.busy || !event.currentTarget.dataset.id) return;
            this.setData({ itemPage: 1 }); await read(this, event.currentTarget.dataset.id);
        },
        async onPage(this: Context, event: Event) {
            if (this.data.busy) return;
            const delta = Number(event.currentTarget.dataset.delta); if (delta !== 1 && delta !== -1) return;
            const page = this.data.page + delta; if (page < 1 || (page - 1) * 20 >= this.data.total) return;
            this.setData({ page }); await read(this);
        },
        async onItemPage(this: Context, event: Event) {
            if (this.data.busy || !this.data.task) return;
            const delta = Number(event.currentTarget.dataset.delta); if (delta !== 1 && delta !== -1) return;
            const itemPage = this.data.itemPage + delta; if (itemPage < 1 || (itemPage - 1) * 20 >= this.data.itemTotal) return;
            this.setData({ itemPage }); await read(this, this.data.task.id);
        }
    };
}
