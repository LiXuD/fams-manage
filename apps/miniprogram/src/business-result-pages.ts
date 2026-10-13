import type { MiniServices } from "./pages.ts";
import { proxyMessage } from "./proxy-confirmation-pages.ts";

interface Notice {
    id: string; businessType: string; businessId: string; itemId: string; read: boolean; reviewRequested: boolean;
    content: { actualSigner: string; submittedBy: string; approvedBy: string; executedBy: string;
        result: { assetCode: string }; metadata: { actualReceivedAt: string; cannotConfirmReason: string; contactRecord: string } };
}
interface Review { noticeId: string; executionId: string; businessType: string; businessId: string; recipient: string; reason: string; createdAt: string }
interface Data { rows: Notice[]; reviews: Review[]; page: number; total: number; unread: number; reasons: Record<string, string>;
    busy: boolean; retryable: boolean; error: string; notice: string; administrator: boolean }
interface Context { data: Data; setData(value: Partial<Data>): void; onRefresh(): Promise<void> }
interface Event { detail: { value: string }; currentTarget: { dataset: { id: string } } }
const base = "/api/v1/mini/business-result-notices";
const empty = (administrator: boolean): Data => ({ rows: [], reviews: [], page: 1, total: 0, unread: 0, reasons: {},
    busy: false, retryable: false, error: "", notice: "", administrator });

export function createBusinessResultPage(getServices: () => MiniServices, administrator = false) {
    let services: MiniServices | null = null, identity = "", live = false, generation = 0;
    let pending: { path: string; key: string; version: number; body?: { reason: string } } | null = null;
    const load = async (page: Context) => {
        if (administrator) {
            const value = await services!.sessions.request("GET", `${base}/review-requests?page=${page.data.page}&pageSize=20`) as { items: Review[]; total: number };
            return { reviews: value.items, total: value.total };
        }
        const value = await services!.sessions.request("GET", `${base}?page=${page.data.page}&pageSize=20`) as { items: Notice[]; total: number; unread: number };
        return { rows: value.items, total: value.total, unread: value.unread };
    };
    const failed = (page: Context, error: unknown) => {
        page.setData({ error: proxyMessage(error) });
        if (!services!.sessions.identity) services!.navigate("/pages/login/index");
    };
    const perform = async (page: Context) => {
        if (!services || !pending || page.data.busy) return;
        const current = generation, operation = pending;
        page.setData({ busy: true, error: "", notice: "" });
        try {
            await services.sessions.request("POST", operation.path, operation.body, operation);
            const value = await load(page);
            if (live && current === generation) {
                pending = null; page.setData({ ...value, retryable: false,
                    notice: "结果已保存。已读或提出核查都不会撤销已经生效的资产责任。" });
            }
        } catch (error) { if (live && current === generation) { failed(page, error); page.setData({ retryable: true }); } }
        finally { if (live && current === generation) page.setData({ busy: false }); }
    };
    return {
        data: empty(administrator),
        async onShow(this: Context) {
            live = true; const current = ++generation; services = getServices();
            const next = JSON.stringify(services.sessions.identity);
            if (next !== identity) { identity = next; pending = null; this.setData(empty(administrator)); }
            this.setData({ busy: false, retryable: pending !== null });
            if (pending) return;
            this.setData({ busy: true, error: "" });
            try { const value = await load(this); if (live && current === generation) this.setData(value); }
            catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        onHide() { live = false; generation++; },
        onUnload() { live = false; generation++; pending = null; },
        async onRefresh(this: Context) {
            if (!services || this.data.busy) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try { const value = await load(this); if (live && current === generation) { pending = null; this.setData({ ...value, retryable: false }); } }
            catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        onReason(this: Context, event: Event) {
            if (!this.data.busy && !pending) this.setData({ reasons: { ...this.data.reasons, [event.currentTarget.dataset.id]: event.detail.value } });
        },
        async onRead(this: Context, event: Event) {
            if (!services || this.data.busy || pending || administrator || !this.data.rows.some(row => String(row.id) === event.currentTarget.dataset.id && !row.read)) return;
            pending = { path: `${base}/${event.currentTarget.dataset.id}/read`, key: services.createKey(), version: 0 };
            await perform(this);
        },
        async onReview(this: Context, event: Event) {
            if (!services || this.data.busy || pending || administrator) return;
            const id = event.currentTarget.dataset.id, reason = this.data.reasons[id]?.trim();
            if (!reason || reason.length > 1000 || !this.data.rows.some(row => String(row.id) === id && !row.reviewRequested)) {
                this.setData({ error: "请填写本次签收结果需要核查的事实。" }); return;
            }
            pending = { path: `${base}/${id}/objection`, key: services.createKey(), version: 0, body: { reason } };
            await perform(this);
        },
        onOpen(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !administrator || !/^[1-9][0-9]*$/.test(event.currentTarget.dataset.id)) return;
            services.navigate(`/pages/proxy-confirmations/index?id=${event.currentTarget.dataset.id}`);
        },
        async onRetry(this: Context) { await perform(this); },
        async onNext(this: Context) { if (!this.data.busy && !pending && this.data.page * 20 < this.data.total) { this.setData({ page: this.data.page + 1 }); await this.onRefresh(); } },
        async onPrevious(this: Context) { if (!this.data.busy && !pending && this.data.page > 1) { this.setData({ page: this.data.page - 1 }); await this.onRefresh(); } },
    };
}
