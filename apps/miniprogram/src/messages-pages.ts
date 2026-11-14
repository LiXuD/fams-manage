import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import { proxyMessage, proxyTime } from "./proxy-confirmation-pages.ts";

interface Message {
    id: string; kind: "TODO" | "REMINDER"; businessType: string; businessId: string; stage: string;
    objectStage: string; poolType: string; assigneeAccountId: string; openedAt: string; deadlineAt: string; read: boolean;
}
interface Row extends Message { label: string; businessDisplay: string; openedDisplay: string;
    deadlineDisplay: string; link: string }
interface Data { rows: Row[]; page: number; total: number; unread: number; busy: boolean; retryable: boolean;
    error: string; notice: string; exception: boolean }
interface Context { data: Data; setData(value: Partial<Data>): void; onRefresh(): Promise<void> }
interface Event { currentTarget: { dataset: { id: string; kind: string } } }

const TIERS: Record<string, string> = { DUE_IN_3_DAYS: "提前3日", DUE_IN_1_DAY: "提前1日",
    DUE_IN_24_HOURS: "截止前24小时", DUE_TODAY: "今日到期", OVERDUE: "已逾期",
    OVERDUE_EVERY_7_DAYS: "逾期每7日跟进", UNRESOLVED_AFTER_1_WORKDAY: "1个工作日未处理" };
const STAGES: Record<string, string> = { ADMIN_DECISION: "管理员确认", WAREHOUSE_CLAIM: "库管领取",
    FIRST_EXECUTION: "首次执行", HANDOVER_RECEIPT: "实物接收", INVENTORY_COMPLETION: "盘点完成",
    FACTORY_RETURN: "返厂收回", LOSS_REVIEW: "报失复核", SCRAP_DISPOSITION: "报废处置" };
const TODOS: Record<string, string> = { APPROVAL: "待管理员确认", CLAIM: "待库管领取", PROCESS: "待维修处理",
    RECEIPT: "待实物接收", OPINION: "待补充技术意见", VERIFY: "待核实", SEARCH: "待查找跟进",
    HANDOVER_EXCEPTION: "交接异常待处理", RETURN_EXCEPTION: "返还异常待处理", OVERDUE: "逾期跟进",
    FACTORY_DISPATCH: "待寄出返厂", FACTORY_SCOPE_APPROVAL: "待返厂范围确认", RESTORE_REQUIRED: "待恢复保管",
    DISPOSITION: "待实际处置", LOST_FOLLOW_UP: "待遗失跟进" };
const BUSINESSES: Record<string, string> = { INBOUND: "入库单", REQUISITION: "领用申请",
    REQUISITION_ALLOCATION: "领用分配", LOAN: "借用", LOAN_EXTENSION: "借用延期", ASSET_RETURN: "退还",
    TRANSFER: "调拨", TRANSFER_ITEM: "调拨明细", REQUISITION_HANDOVER: "领用交接",
    INVENTORY_TASK: "盘点任务", REPAIR: "报修单", REPAIR_FACTORY_ROUND: "返厂轮次",
    LOST_REPORT: "报失单", SCRAP: "报废申请", ITEM_TARGET_CHANGE: "目标变更" };
// Deep links are only offered for the native pages that revalidate a business identity from its own query id.
const LINKS: Record<string, string> = { SCRAP: "/pages/scrap-requests/index", LOST_REPORT: "/pages/loss-reports/index" };

const empty = (exception: boolean): Data => ({ rows: [], page: 1, total: 0, unread: 0, busy: false,
    retryable: false, error: "", notice: "", exception });

function label(row: Message): string {
    if (row.kind === "TODO") return `待办 · ${TODOS[row.stage] ?? row.stage}`;
    return `提醒 · ${STAGES[row.objectStage] ?? row.objectStage} ${TIERS[row.stage] ?? row.stage}`;
}
function link(row: Message): string {
    const base = LINKS[row.businessType];
    return base ? `${base}?id=${row.businessId}` : "";
}

export function createMessagesPage(getServices: () => MiniServices, view: "INBOX" | "EXCEPTION" = "INBOX") {
    const exception = view === "EXCEPTION";
    let services: MiniServices | null = null, identity = "", live = false, generation = 0;
    let pending: { path: string; key: string; version: number } | null = null;
    const valid = (epoch: number) => live && epoch === generation && identity === JSON.stringify(services?.sessions.identity);
    const clear = (page: Context) => { generation++; pending = null; page.setData(empty(exception)); };
    const authorized = (page: Context) => {
        if (valid(generation) && services?.sessions.identity) return true;
        if (live) clear(page);
        return false;
    };
    const finish = (page: Context, epoch: number) => {
        if (valid(epoch)) page.setData({ busy: false });
        else if (live && epoch === generation) {
            clear(page); page.setData({ error: "身份已变化，请重新进入消息页面。" });
            if (!services?.sessions.identity) services?.navigate("/pages/login/index");
        }
    };
    const load = async (page: Context) => {
        const value = await services!.sessions.request("GET",
            `/api/v1/mini/messages?view=${view}&page=${page.data.page}&pageSize=20`) as { items: Message[]; total: number; unread: number };
        return { rows: value.items.map(row => ({ ...row, label: label(row), link: link(row),
            businessDisplay: BUSINESSES[row.businessType] ?? row.businessType,
            openedDisplay: proxyTime(row.openedAt), deadlineDisplay: proxyTime(row.deadlineAt) })),
            total: value.total, unread: value.unread };
    };
    const failed = (page: Context, error: unknown, mutation = false) => {
        if (error instanceof MiniRequestError) {
            if ([401, 403, 404].includes(error.status)) clear(page);
            else if (mutation && error.status >= 400 && error.status < 500) pending = null;
        }
        page.setData({ error: proxyMessage(error), retryable: pending !== null });
        if (!services!.sessions.identity) services!.navigate("/pages/login/index");
    };
    const perform = async (page: Context) => {
        if (!authorized(page) || !services || !pending || page.data.busy) return;
        const current = generation, operation = pending;
        page.setData({ busy: true, error: "", notice: "" });
        try {
            await services.sessions.request("POST", operation.path, undefined, operation);
            if (!valid(current)) return;
            const value = await load(page);
            if (valid(current)) {
                pending = null;
                page.setData({ ...value, retryable: false,
                    notice: "已读登记只记录本人状态，业务办理仍以原单据为准。" });
            }
        } catch (error) { if (valid(current)) failed(page, error, true); }
        finally { finish(page, current); }
    };
    return {
        data: empty(exception),
        async onShow(this: Context) {
            live = true; const current = ++generation; services = getServices();
            const next = JSON.stringify(services.sessions.identity);
            if (next !== identity) { identity = next; pending = null; this.setData(empty(exception)); }
            if (!services.sessions.identity) { clear(this); services.navigate("/pages/login/index"); return; }
            this.setData({ busy: false, retryable: pending !== null });
            if (pending) return;
            this.setData({ busy: true, error: "" });
            try { const value = await load(this); if (valid(current)) this.setData(value); }
            catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
        },
        onHide(this: Context) { live = false; generation++; this.setData(empty(exception)); },
        onUnload(this: Context) { live = false; clear(this); },
        async onRefresh(this: Context) {
            if (!authorized(this) || !services || this.data.busy) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try { const value = await load(this); if (valid(current)) this.setData({ ...value, retryable: pending !== null }); }
            catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
        },
        async onRead(this: Context, event: Event) {
            const id = event.currentTarget.dataset.id;
            if (!authorized(this) || !services || this.data.busy || pending
                || !this.data.rows.some(row => row.id === id && !row.read && row.kind === event.currentTarget.dataset.kind)) return;
            pending = { path: `/api/v1/mini/messages/${event.currentTarget.dataset.kind}/${id}/read`,
                key: services.createKey(), version: 0 };
            await perform(this);
        },
        onOpen(this: Context, event: Event) {
            const row = this.data.rows.find(entry => entry.id === event.currentTarget.dataset.id);
            if (!authorized(this) || !services || this.data.busy || pending || !row?.link) return;
            services.navigate(row.link);
        },
        async onRetry(this: Context) { await perform(this); },
        async onNext(this: Context) {
            if (!this.data.busy && !pending && this.data.page * 20 < this.data.total) {
                this.setData({ page: this.data.page + 1 }); await this.onRefresh();
            }
        },
        async onPrevious(this: Context) {
            if (!this.data.busy && !pending && this.data.page > 1) {
                this.setData({ page: this.data.page - 1 }); await this.onRefresh();
            }
        },
    };
}
