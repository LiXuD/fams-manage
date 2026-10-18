import type { MiniServices } from "./pages.ts";
import type { InventoryQueue } from "./inventory-queue.ts";
interface Data { tasks: ReturnType<InventoryQueue["summaries"]>; busy: boolean; error: string }
interface Context { data: Data; setData(value: Partial<Data>): void }
const outcomes: Record<string, string> = { ACCEPTED: "已收到，仍需管理员复核", DIFFERENCE_REVIEW: "差异待复核", DUPLICATE: "重复，未重复计数", CONFLICT_REVIEW: "冲突待复核", OLD_ASSIGNMENT_REVIEW: "旧分配证据待复核", LATE_REVIEW: "迟到待复核，不改变原报告", FINDING_REVIEW: "现场线索待核实", TIME_REVIEW: "时间异常待复核", EXPIRED: "服务器拒绝过期，需补盘", EXPIRED_RECOLLECT: "本机已到期清理，需补盘", REJECTED: "明确拒绝", AUTHORIZATION_REJECTED: "授权已失效" };
const blank = (): Data => ({ tasks: [], busy: false, error: "" });
export function createInventoryPendingPage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", generation = 0, live = false;
    const current = (epoch: number) => live && epoch === generation && identity === JSON.stringify(services.sessions.identity);
    const read = async (page: Context) => {
        const epoch = ++generation; page.setData({ ...blank(), busy: true });
        try {
            if (!services.sessions.identity || !services.inventory) throw new Error("请先登录以读取本人的待上传记录。");
            const queue = services.inventory().queue;
            await queue.expire();
            if (current(epoch)) page.setData({ tasks: queue.summaries().map(task => ({ ...task, statuses: task.statuses.map(row => ({ ...row, message: outcomes[row.outcome] ?? "回执待核查" })) })) });
        } catch (error) {
            if (current(epoch)) page.setData({ error: error instanceof Error ? error.message : "本机记录读取或到期清理未完成，请重试。" });
        } finally {
            if (current(epoch)) page.setData({ busy: false });
            else if (live && epoch === generation) page.setData({ ...blank(), error: "身份已变化，已隐藏原记录，请重新进入。" });
        }
    };
    return {
        data: blank(),
        async onShow(this: Context) {
            live = true; services = getServices(); identity = JSON.stringify(services.sessions.identity);
            await read(this);
        },
        onHide(this: Context) { live = false; generation++; this.setData(blank()); },
        onUnload(this: Context) { live = false; generation++; this.setData(blank()); },
        async onRefresh(this: Context) { if (!this.data.busy) await read(this); },
        onOpen(this: Context, event: { currentTarget: { dataset: { id?: string } } }) {
            if (!current(generation)) { this.setData({ ...blank(), error: "身份已变化，请重新进入。" }); return; }
            const id = event.currentTarget.dataset.id;
            if (!this.data.busy && id && this.data.tasks.some(task => task.taskId === id)) {
                services.navigate(`/pages/inventory-collection/index?taskId=${encodeURIComponent(id)}`);
            }
        }
    };
}
