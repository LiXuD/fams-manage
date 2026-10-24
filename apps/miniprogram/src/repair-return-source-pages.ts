import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
interface Row { id: string; return_id: string; asset_id: string; followup_status: string; reason: string; linked_repair_id: string | null; last_repair_id: string | null; repair_status: string | null }
interface Source { followupId: string; version: number; returnId: string; closedLoanId: string | null; processorAccountId: string; inspectedAt: string;
    inspectionReason: string; lockVersion: number; asset: { id: string; assetCode: string; name: string; version: number } }
interface Operation { path: string; key: string; version: number; body: { assetVersion: number; lockVersion: number; fault: string; impact: string } }
interface Data { rows: Row[]; source: Source | null; page: number; total: number; fault: string; impact: string; busy: boolean; retry: boolean; prepared: boolean; error: string; repairId: string }
interface Context { data: Data; setData(value: Partial<Data>): void }
type Event = { currentTarget: { dataset: { id?: string; field?: string; delta?: string } }; detail: { value: string } };
const blank = (): Data => ({ rows: [], source: null, page: 1, total: 0, fault: "", impact: "MEDIUM", busy: false, retry: false, prepared: false, error: "", repairId: "" });
const base = "/api/v1/mini/repairs/return-sources";
export function createRepairReturnSourcePage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", generation = 0, live = false, pending: Operation | null = null, prepared: Operation | null = null;
    const current = (epoch: number) => live && generation === epoch && identity === JSON.stringify(services.sessions.identity);
    const authorized = (page: Context) => {
        if (current(generation)) return true;
        pending = null; prepared = null; generation++; page.setData(blank()); return false;
    };
    const failure = (page: Context, cause: unknown, mutation = false) => {
        if (cause instanceof MiniRequestError && [401, 403, 404].includes(cause.status)) {
            pending = null; prepared = null; generation++; page.setData({ ...blank(), error: "当前身份无权办理，已隐藏原资料。" });
        } else {
            if (mutation && cause instanceof MiniRequestError && cause.status >= 400 && cause.status < 500) pending = null;
            page.setData({ retry: !!pending, error: pending ? "提交结果尚未确认，请保留原操作重试。" : "请刷新核对损坏来源和当前版本。" });
        }
    };
    const read = async (page: Context) => {
        if (!authorized(page) || page.data.busy) return;
        const epoch = ++generation; page.setData({ busy: true, error: "" });
        try { const value = await services.sessions.request("GET", `${base}?page=${page.data.page}&pageSize=20`) as { items: Row[]; total: number };
            if (current(epoch)) page.setData({ rows: value.items, total: value.total, retry: !!pending });
        } catch (cause) { if (current(epoch)) failure(page, cause); }
        finally { if (current(epoch)) page.setData({ busy: false }); }
    };
    const send = async (page: Context) => {
        if (!authorized(page) || page.data.busy || !pending) return;
        const epoch = ++generation, operation = pending; page.setData({ busy: true, error: "" });
        try { const result = await services.sessions.request("POST", operation.path, operation.body, operation) as { id: string };
            if (current(epoch)) { pending = null; page.setData({ source: null, retry: false, repairId: result.id, fault: "" }); }
        } catch (cause) { if (current(epoch)) failure(page, cause, true); }
        finally { if (current(epoch)) page.setData({ busy: false }); }
    };
    return {
        data: blank(),
        async onShow(this: Context) {
            live = true; generation++; services = getServices(); const next = JSON.stringify(services.sessions.identity);
            if (identity !== next) { pending = null; prepared = null; this.setData(blank()); }
            identity = next;
            if (!services.sessions.identity) { pending = null; prepared = null; this.setData(blank()); services.navigate("/pages/login/index"); return; }
            this.setData({ busy: false, retry: !!pending, prepared: !!prepared });
            if (!pending && !prepared) await read(this);
        },
        onHide(this: Context) { live = false; generation++; prepared = null; this.setData(blank()); },
        onUnload(this: Context) { live = false; generation++; pending = null; prepared = null; this.setData(blank()); },
        async onRefresh(this: Context) { await read(this); },
        async onSelect(this: Context, event: Event) {
            if (!authorized(this) || this.data.busy || pending || prepared || !this.data.rows.some(row => row.id === event.currentTarget.dataset.id && row.followup_status === "PENDING")) return;
            const epoch = ++generation; this.setData({ busy: true, source: null, error: "" });
            try { const source = await services.sessions.request("GET", `${base}/${event.currentTarget.dataset.id}`) as Source;
                if (current(epoch)) this.setData({ source, fault: source.inspectionReason });
            } catch (cause) { if (current(epoch)) failure(this, cause); }
            finally { if (current(epoch)) this.setData({ busy: false }); }
        },
        onField(this: Context, event: Event) {
            if (!authorized(this) || this.data.busy || pending || prepared) return;
            if (event.currentTarget.dataset.field === "fault") this.setData({ fault: event.detail.value });
            if (event.currentTarget.dataset.field === "impact") this.setData({ impact: ["LOW", "MEDIUM", "HIGH"][Number(event.detail.value)] ?? "MEDIUM" });
        },
        onPrepare(this: Context) {
            if (!authorized(this) || this.data.busy || pending || prepared || !this.data.source || this.data.fault.trim().length < 3) return;
            const source = this.data.source;
            prepared = { path: `${base}/${source.followupId}`, key: services.createKey(), version: source.version,
                body: { assetVersion: source.asset.version, lockVersion: source.lockVersion, fault: this.data.fault.trim(), impact: this.data.impact } };
            this.setData({ prepared: true });
        },
        onEdit(this: Context) { if (authorized(this) && !this.data.busy && !pending) { prepared = null; this.setData({ prepared: false }); } },
        async onConfirm(this: Context) {
            if (!authorized(this) || this.data.busy || pending || !prepared) return;
            pending = prepared; prepared = null; this.setData({ prepared: false, retry: true }); await send(this);
        },
        async onRetry(this: Context) { await send(this); },
        onOpen(this: Context, event: Event) {
            if (!authorized(this) || this.data.busy || pending || prepared) return;
            const row = this.data.rows.find(row => row.id === event.currentTarget.dataset.id), id = row?.linked_repair_id || row?.last_repair_id || this.data.repairId;
            if (id && /^[1-9][0-9]*$/.test(id)) services.navigate(`/pages/repairs/index?id=${id}`);
        },
        async onPage(this: Context, event: Event) {
            const delta = Number(event.currentTarget.dataset.delta), next = this.data.page + delta;
            if (!authorized(this) || this.data.busy || pending || prepared || ![-1, 1].includes(delta) || next < 1 || delta > 0 && (next - 1) * 20 >= this.data.total) return;
            this.setData({ page: next, source: null }); await read(this);
        },
    };
}
