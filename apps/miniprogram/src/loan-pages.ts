import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import type { AssetReturn, BusinessPage, Loan, ReturnAssetChoice } from "../../../packages/api-types/src/loan";

interface Data {
    busy: boolean; error: string; notice: string; retryable: boolean; loans: Loan[]; returns: AssetReturn[];
    returnProgress: AssetReturn | null; selected: Loan | null; assets: ReturnAssetChoice[]; selectedAsset: ReturnAssetChoice | null;
    reason: string; dueDate: string; page: number; total: number;
    missingDate?: string; missingTime?: string; missingLocation?: string; missingCircumstances?: string;
}
interface Context { data: Data; setData(data: Partial<Data>): void }
interface Input { detail: { value: string } }
interface Selection { currentTarget: { dataset: { id: string } } }
function message(error: unknown) {
    if (error instanceof MiniRequestError) {
        if (error.status === 401) return "登录已失效，请重新登录。";
        if (error.status === 403 || error.status === 404) return "没有当前记录或操作的权限。";
        if (error.status === 409) return "责任、日期或处理状态已变更，请刷新核对。";
        if (error.status === 400) return "请核对归还日期和操作原因。";
    }
    return "操作结果尚未确认，请重试上次操作或刷新查询。";
}

/** M05 native handlers retain the original key, version and payload after an uncertain response. */
export function createLoanPage(getServices: () => MiniServices) {
    let live = false; let generation = 0; let identity = ""; let services: MiniServices | null = null;
    let pending: { path: string; key: string; version: number; body: unknown } | null = null;
    const load = async (page: Context, current: number) => {
        const [loans, returns, assets] = await Promise.all([
            services!.sessions.request("GET", `/api/v1/mini/loans?page=${page.data.page}&pageSize=20`) as Promise<BusinessPage<Loan>>,
            services!.sessions.request("GET", `/api/v1/mini/asset-returns?page=${page.data.page}&pageSize=20`) as Promise<BusinessPage<AssetReturn>>,
            services!.sessions.request("GET", `/api/v1/mini/asset-returns/eligible-assets?page=${page.data.page}&pageSize=20`) as Promise<BusinessPage<ReturnAssetChoice>>]);
        if (live && generation === current) page.setData({ loans: loans.items, returns: returns.items, assets: assets.items,
            total: Math.max(loans.total, returns.total, assets.total) });
    };
    const mutate = async (page: Context, path: string, version: number, body: unknown) => {
        if (!services || page.data.busy) return;
        if (pending && pending.path !== path) { page.setData({ error: "请先重试上次操作或刷新查询。" }); return; }
        pending ??= { path, version, body: JSON.parse(JSON.stringify(body)) as unknown, key: services.createKey() };
        page.setData({ busy: true, error: "", notice: "" }); const current = generation;
        try {
            await services.sessions.request("POST", pending.path, pending.body, pending);
            if (!live || generation !== current) return;
            pending = null; page.setData({ retryable: false, notice: "操作已保存，等待管理员决定。" });
            if (page.data.selected) {
                const selected = await services.sessions.request("GET", "/api/v1/mini/loans/" + page.data.selected.id) as Loan;
                if (live && generation === current) page.setData({ selected });
            }
            await load(page, current);
        } catch (error) {
            if (live && generation === current) page.setData({ error: message(error), retryable: pending !== null });
            if (live && !services.sessions.identity) services.navigate("/pages/login/index");
        } finally { if (live && generation === current) page.setData({ busy: false }); }
    };
    return {
        data: { busy: false, error: "", notice: "", retryable: false, loans: [], returns: [], selected: null,
            assets: [], selectedAsset: null, returnProgress: null, reason: "", dueDate: "", page: 1, total: 0 } as Data,
        async onShow(this: Context) {
            live = true; const current = ++generation;
            services = getServices(); const next = JSON.stringify(services.sessions.identity);
            if (identity !== next) { identity = next; pending = null;
                this.setData({ loans: [], returns: [], assets: [], selected: null, selectedAsset: null, returnProgress: null,
                    reason: "", dueDate: "", page: 1, retryable: false }); }
            this.setData({ busy: true, error: "" });
            try { await load(this, current); }
            catch (error) { if (live && generation === current) this.setData({ error: message(error) });
                if (live && !services.sessions.identity) services.navigate("/pages/login/index"); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        onHide(this: Context) { live = false; generation++; this.setData({ returnProgress: null }); },
        onUnload() { live = false; generation++; pending = null; },
        async onReturnProgress(this: Context, event: Selection) {
            if (!services || !live || this.data.busy || pending || identity !== JSON.stringify(services.sessions.identity)
                || !this.data.returns.some(row => row.id === event.currentTarget.dataset.id)) return;
            const current = generation; this.setData({ busy: true, error: "", returnProgress: null,
                missingDate: "", missingTime: "", missingLocation: "", missingCircumstances: "" });
            try {
                const result = await services.sessions.request("GET", "/api/v1/mini/asset-returns/" + event.currentTarget.dataset.id) as AssetReturn;
                if (live && generation === current && identity === JSON.stringify(services.sessions.identity)) this.setData({ returnProgress: result });
            } catch (error) { if (live && generation === current) this.setData({ error: message(error), returnProgress: null }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        onMissingField(this: Context, event: { currentTarget: { dataset: { field: string } }; detail: { value: string } }) {
            const field = event.currentTarget.dataset.field;
            if (!this.data.busy && !pending && ["missingDate", "missingTime", "missingLocation", "missingCircumstances"].includes(field)) {
                this.setData({ [field]: event.detail.value });
            }
        },
        async onReportMissing(this: Context) {
            if (!services || !live || this.data.busy || pending || identity !== JSON.stringify(services.sessions.identity)) return;
            const row = this.data.returnProgress;
            const followup = row?.followups?.find(item => item.businessType === "LOST_REPORT" && item.missingLossLockVersion != null);
            if (!row?.asset || (!row.canReportMissing && !followup)) return;
            const lastSeenAt = new Date(this.data.missingDate + "T" + this.data.missingTime);
            const lastLocation = this.data.missingLocation?.trim(), circumstances = this.data.missingCircumstances?.trim();
            const reason = this.data.reason.trim();
            if (!Number.isFinite(lastSeenAt.getTime()) || !lastLocation || !circumstances || circumstances.length < 10 || (!followup && !reason)) {
                this.setData({ error: "请填写验收说明、最后见到时间与地点及查找情况。" });
                return;
            }
            const current = generation;
            this.setData({ busy: true });
            try {
                if (!await services.confirm("归还实物缺失", `报失 ${row.asset.assetCode}。原归还及借用责任保留。`)
                    || !live || generation !== current || identity !== JSON.stringify(services.sessions.identity)) return;
            } catch {
                if (live && generation === current) this.setData({ error: "确认未完成，请重新核对。" });
                return;
            } finally {
                if (live && generation === current) this.setData({ busy: false });
            }
            const facts = { lastSeenAt: lastSeenAt.toISOString(), lastLocation, circumstances };
            if (followup && followup.version != null) {
                await mutate(this, `/api/v1/mini/asset-returns/${row.id}/missing-loss`, followup.version,
                    { assetVersion: row.asset.version, lockVersion: followup.missingLossLockVersion, facts });
            } else {
                await mutate(this, `/api/v1/mini/asset-returns/${row.id}/inspect`, row.version,
                    { result: "MISSING", reason, assetVersion: row.asset.version, lockVersion: row.lockVersion, missingLoss: facts });
            }
            if (live && generation === current && !pending) this.setData({ returnProgress: null });
        },
        onOpenMissingLoss(this: Context, event: Selection) {
            const report = event.currentTarget.dataset.id;
            if (!services || !live || this.data.busy || pending || identity !== JSON.stringify(services.sessions.identity)
                || !/^[1-9][0-9]*$/.test(report) || !this.data.returnProgress?.followups?.some(item => item.lossReport?.reportId === report)) return;
            services.navigate("/pages/loss-reports/index?id=" + report);
        },
        async onResubmitLossReturn(this: Context) {
            if(!services||!live||this.data.busy||pending||identity!==JSON.stringify(services.sessions.identity))return;
            const row=this.data.returnProgress;
            if(!row?.lossRecovery||row.returnStatus!=="REJECTED"||!this.data.reason.trim())return;
            await mutate(this,`/api/v1/mini/asset-returns/${row.id}/resubmit-loss`,row.version,{approved:false,reason:this.data.reason.trim()});
            this.setData({returnProgress:null});
        },
        onReason(this: Context, event: Input) { if (!this.data.busy && !pending) this.setData({ reason: event.detail.value }); },
        onDueDate(this: Context, event: Input) { if (!this.data.busy && !pending) this.setData({ dueDate: event.detail.value }); },
        onSelectAsset(this: Context, event: Selection) {
            if (this.data.busy || pending) return;
            const asset = this.data.assets.find(row => row.id === event.currentTarget.dataset.id);
            if (asset) this.setData({ selectedAsset: asset, selected: null, reason: "" });
        },
        async onSelect(this: Context, event: Selection) {
            if (!services || this.data.busy || pending) return;
            this.setData({ busy: true, error: "" }); const current = generation;
            try {
                const selected = await services.sessions.request("GET", "/api/v1/mini/loans/" + event.currentTarget.dataset.id) as Loan;
                if (live && generation === current) this.setData({ selected, selectedAsset: null, dueDate: "", reason: "" });
            } catch (error) { if (live && generation === current) this.setData({ error: message(error) }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        async onExtend(this: Context) {
            const row = this.data.selected;
            if (!row || !this.data.dueDate || !this.data.reason.trim()) { this.setData({ error: "请选择借用并填写新的归还日期和原因。" }); return; }
            await mutate(this, `/api/v1/mini/loans/${row.id}/extensions`, row.version,
                { expectedReturnDate: this.data.dueDate, reason: this.data.reason.trim() });
        },
        async onReturn(this: Context) {
            const row = this.data.selected;
            const asset = this.data.selectedAsset;
            if ((!row && !asset) || !this.data.reason.trim()) { this.setData({ error: "请选择资产并填写归还原因。" }); return; }
            await mutate(this, "/api/v1/mini/asset-returns", 0,
                { assetId: row ? row.assetId : asset!.id, assetVersion: row ? row.currentAssetVersion : asset!.version,
                    reason: this.data.reason.trim() });
        },
        async onRetry(this: Context) { if (pending) await mutate(this, pending.path, pending.version, pending.body); },
        async onRefresh(this: Context) {
            if (!services || this.data.busy) return;
            this.setData({ busy: true, error: "" }); const current = generation;
            try {
                await load(this, current);
                if (this.data.selected) {
                    const selected = await services.sessions.request("GET", "/api/v1/mini/loans/" + this.data.selected.id) as Loan;
                    if (live && generation === current) this.setData({ selected });
                }
                if (live && generation === current) { pending = null; this.setData({ retryable: false }); }
            } catch (error) { if (live && generation === current) this.setData({ error: message(error) }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        async onNext(this: Context) {
            if (!services || this.data.busy || pending || this.data.page * 20 >= this.data.total) return;
            this.setData({ page: this.data.page + 1, busy: true }); const current = generation;
            try { await load(this, current); } catch (error) { if (live && generation === current) this.setData({ error: message(error) }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        async onPrevious(this: Context) {
            if (!services || this.data.busy || pending || this.data.page <= 1) return;
            this.setData({ page: this.data.page - 1, busy: true }); const current = generation;
            try { await load(this, current); } catch (error) { if (live && generation === current) this.setData({ error: message(error) }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
    };
}
