import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import type { TransferReceipt } from "../../../packages/api-types/src/transfer";

interface Data { rows: TransferReceipt[]; selected: TransferReceipt | null; page: number; total: number;
    busy: boolean; error: string; notice: string; observedCode: string; checked: boolean; reason: string; retryable: boolean }
interface Context { data: Data; setData(value: Partial<Data>): void; onRefresh(): Promise<void> }
interface Event { detail: { value: string | string[] }; currentTarget: { dataset: { id?: string; request?: string } } }
function message(error: unknown) {
    if (error instanceof MiniRequestError) {
        if (error.status === 401) return "登录已失效，请重新登录。";
        if (error.status === 403 || error.status === 404) return "当前身份没有这项接收权限。";
        if (error.status === 409) return "实物、授权或业务状态已变更，请刷新重新核对。";
        if (error.status === 400) return "请核对实物编码和必填原因。";
    }
    return "结果尚未确认，请重试上次操作或刷新查询。";
}

/** M09 submits only scoped receipt evidence; no warehouse editing or credential stored in page data. */
export function createTransferReceiptPage(getServices: () => MiniServices) {
    let services: MiniServices | null = null; let live = false; let generation = 0; let identity = "";
    let grant: string | null = null;
    let pending: { path: string; key: string; version: number; body: unknown } | null = null;
    const root = "/api/v1/mini/transfers";
    const load = async (page: Context) => {
        const current = generation;
        const result = await services!.sessions.request("GET", `${root}/receipts/pending?page=${page.data.page}&pageSize=20`) as
            { items: TransferReceipt[]; total: number };
        if (live && current === generation) page.setData({ rows: result.items, total: result.total });
    };
    const perform = async (page: Context) => {
        if (!services || !pending || page.data.busy) return;
        page.setData({ busy: true, error: "", notice: "" }); const current = generation;
        try {
            const result = await services.sessions.request("POST", pending.path, pending.body, pending) as { itemStatus: string };
            if (!live || current !== generation) return;
            pending = null; grant = null;
            page.setData({ selected: null, retryable: false, observedCode: "", checked: false,
                notice: result.itemStatus === "COMPLETED" ? "接收已确认，正式责任已更新。" : "已反馈不符，资产仍待交接，库管将核查。" });
            await load(page);
        } catch (error) {
            if (live && current === generation) page.setData({ error: message(error), retryable: true });
            if (live && !services.sessions.identity) services.navigate("/pages/login/index");
        } finally { if (live && current === generation) page.setData({ busy: false }); }
    };
    const submit = async (page: Context, refusal: boolean) => {
        if (!services || !page.data.selected || page.data.busy || pending) return;
        if (!grant || !page.data.checked || !page.data.observedCode.trim() || refusal && !page.data.reason.trim()) {
            page.setData({ error: "请核对实物并填写编码；反馈不符须说明原因。" }); return;
        }
        const current = generation; page.setData({ busy: true, error: "" });
        let confirmed: boolean;
        try {
            confirmed = await services.confirm(refusal ? "反馈实物不符" : "确认实物接收",
                refusal ? "保留交接和资产锁，由库管核查。" : "请确认已实际收到并核对编码，成功后将更新正式责任。");
        } catch {
            if (live && current === generation) page.setData({ busy: false, error: "确认未完成，请重新核对后操作。" });
            return;
        }
        if (!live || current !== generation) return;
        page.setData({ busy: false }); if (!confirmed) return;
        const selected = page.data.selected;
        pending = { path: `${root}/receipts/${selected.id}/${refusal ? "refuse" : "receive"}`,
            key: services.createKey(), version: selected.requestVersion,
            body: { grant, observedCode: page.data.observedCode.trim(), physicalChecked: true,
                ...(refusal ? { reason: page.data.reason } : {}) } };
        await perform(page);
    };
    return {
        data: { rows: [], selected: null, page: 1, total: 0, busy: false, error: "", notice: "",
            observedCode: "", checked: false, reason: "", retryable: false } as Data,
        async onShow(this: Context) {
            live = true; const current = ++generation;
            services = getServices(); const next = JSON.stringify(services.sessions.identity);
            if (identity !== next) {
                identity = next; pending = null; grant = null;
                this.setData({ rows: [], selected: null, observedCode: "", checked: false, reason: "", retryable: false, page: 1 });
            }
            this.setData({ busy: true, error: "" });
            try { await load(this); }
            catch (error) { if (live && current === generation) this.setData({ error: message(error) }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        onHide() { live = false; generation++; },
        onUnload() { live = false; generation++; grant = null; pending = null; },
        async onOpen(this: Context, event: Event) {
            if (!services || this.data.busy || pending) return; const current = generation;
            this.setData({ busy: true, error: "" }); grant = null;
            const selected = this.data.rows.find(row => row.id === event.currentTarget.dataset.id);
            if (!selected) { this.setData({ busy: false, error: "请刷新待接收清单。" }); return; }
            const path = `${root}/receipts/${selected.id}`;
            try {
                const credential = await services.sessions.request("POST", path + "/grant") as { grant: string; requestVersion: number };
                if (!live || current !== generation) return;
                grant = credential.grant;
                this.setData({ selected: { ...selected, requestVersion: credential.requestVersion }, observedCode: "", checked: false, reason: "" });
            } catch (error) { if (live && current === generation) this.setData({ error: message(error) }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        onCode(this: Context, event: Event) { if (!this.data.busy && !pending) this.setData({ observedCode: String(event.detail.value) }); },
        onCheck(this: Context, event: Event) { if (!this.data.busy && !pending) this.setData({ checked: (event.detail.value as string[]).includes("checked") }); },
        onReason(this: Context, event: Event) { if (!this.data.busy && !pending) this.setData({ reason: String(event.detail.value) }); },
        async onScan(this: Context) {
            if (!services || this.data.busy || pending) return; const current = generation;
            this.setData({ busy: true, error: "" });
            try {
                if (!services.scan) throw new Error("Scanner unavailable");
                const observedCode = await services.scan();
                if (live && current === generation) this.setData({ observedCode, checked: false });
            } catch { if (live && current === generation) this.setData({ error: "扫码未完成，可核对实物标签后手工填写资产编码。" }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onReceive(this: Context) { await submit(this, false); },
        async onRefuse(this: Context) { await submit(this, true); },
        async onRetry(this: Context) { await perform(this); },
        async onRefresh(this: Context) {
            if (this.data.busy) return; pending = null; grant = null;
            this.setData({ selected: null, retryable: false, observedCode: "", checked: false });
            this.setData({ busy: true, error: "" }); const current = generation;
            try { await load(this); } catch (error) { if (live && current === generation) this.setData({ error: message(error) }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onNext(this: Context) { if (this.data.busy || pending || this.data.page * 20 >= this.data.total) return;
            this.setData({ page: this.data.page + 1 }); await this.onRefresh(); },
        async onPrevious(this: Context) { if (this.data.busy || pending || this.data.page === 1) return;
            this.setData({ page: this.data.page - 1 }); await this.onRefresh(); },
    };
}
