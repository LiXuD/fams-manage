import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";

type Business = "REQUISITION" | "TRANSFER" | "REPAIR";
type Target = Record<string, unknown>;
interface Choice { id: string; name: string; version: number; locationType?: string; warehouseId?: string; sharedEligible?: boolean }
interface Snapshot { businessType: Business; businessId: string; itemId: string; version: number; assetCode: string;
    target: Target; source?: { lifecycleStatus: string }; physical?: { destination?: string; deliveryEvidence?: string };
    handover?: { destinationText?: string }[] }
interface Change { id: string; version: number; business_id: string; item_id: string; change_status: string;
    source_snapshot_json: Snapshot; proposed_target_json: Target; reason: string; physical_verification: string; targetDisplay?: { employeeName: string; organizationName: string; locationName: string }; decision_reason?: string }
interface Data {
    businessType: Business; businessNames: string[]; custodyNames: string[]; rows: Change[]; selected: Change | null;
    page: number; total: number; canApprove: boolean; canExecute: boolean; sources: { id: string; requestNo?: string }[];
    sourceId: string; sourcePage: number; sourceTotal: number; items: { id: string; assetCode: string }[];
    snapshot: Snapshot | null; organizations: Choice[]; owners: Choice[]; warehouses: Choice[]; locations: Choice[]; employees: Choice[];
    places: Choice[]; people: Choice[]; stock: boolean; owning: string; using: string; employee: string; warehouse: string;
    location: string; custody: string; reason: string; code: string; physical: string; checked: boolean; decision: string;
    busy: boolean; retryable: boolean; error: string; notice: string;
}
interface Context { data: Data; setData(value: Partial<Data>): void; onRefresh(): Promise<void>; onSources(): Promise<void> }
interface Event { detail: { value: string | string[] }; currentTarget: { dataset: { id?: string; field?: string; result?: string } } }
const base = "/api/v1/mini/item-target-changes";
const empty = (): Data => ({ businessType: "REQUISITION", businessNames: ["领用 / 借用", "调拨", "维修返还"], custodyNames: ["个人使用", "部门公用"],
    rows: [], selected: null, page: 1, total: 0, canApprove: false, canExecute: false, sources: [], sourceId: "", sourcePage: 1,
    sourceTotal: 0, items: [], snapshot: null, organizations: [], owners: [], warehouses: [], locations: [], employees: [], places: [], people: [],
    stock: false, owning: "", using: "", employee: "", warehouse: "", location: "", custody: "PERSONAL", reason: "", code: "", physical: "",
    checked: false, decision: "", busy: false, retryable: false, error: "", notice: "" });
function message(error: unknown) {
    if (error instanceof MiniRequestError) {
        if (error.status === 401) return "登录已失效，请重新登录。";
        if (error.status === 403 || error.status === 404) return "当前身份无办理权限或明细不可见。";
        if (error.status === 409) return "目标、处理权、实物阶段或版本已变化，请刷新核对。";
        if (error.status === 400) return "请核对有效目标和完整实物记录。";
    }
    return "结果尚未确认，请保留本页并重试原操作，或刷新查询后重新办理。";
}
export function createTargetChangePage(getServices: () => MiniServices) {
    let services: MiniServices | null = null, live = false, generation = 0, identity = "";
    const valid = (epoch: number) => live && epoch === generation && !!services
        && identity === JSON.stringify(services.sessions.identity);
    let pending: { path: string; key: string; version: number; body: unknown } | null = null;
    const finish = (page: Context, epoch: number) => {
        if (valid(epoch)) page.setData({ busy: false });
        else if (live && epoch === generation) {
            pending = null;
            page.setData({ ...empty(), error: "身份已变化，已隐藏原目标及依据，请重新进入。" });
        }
    };
    const list = async (page: Context) => {
        const value = await services!.sessions.request("GET", `${base}?businessType=${page.data.businessType}&page=${page.data.page}&pageSize=20`) as
            { items: Change[]; total: number; canApprove: boolean; canExecute: boolean };
        return { rows: value.items, total: value.total, canApprove: value.canApprove, canExecute: value.canExecute };
    };
    const catalog = async (kind: string, organization?: string) => {
        const all: Choice[] = []; let page = 1, items: Choice[];
        do { items = (await services!.sessions.request("GET", `/api/v1/mini/transfers/catalog/${kind}?page=${page++}&pageSize=200${organization ? `&organizationId=${organization}` : ""}`) as { items: Choice[] }).items; all.push(...items); }
        while (items.length === 200); return all;
    };
    const failed = (page: Context, error: unknown) => {
        if (page.data.businessType === "REPAIR" && error instanceof MiniRequestError
                && [401, 403, 404].includes(error.status)) {
            pending = null;
            page.setData({ ...empty(), businessType: "REPAIR" });
        }
        page.setData({ error: message(error) });
        if (!services!.sessions.identity) services!.navigate("/pages/login/index");
    };
    const choices = (page: Context) => page.setData({ people: page.data.employees.filter(row => page.data.custody !== "SHARED" || row.sharedEligible),
        places: page.data.locations.filter(row => page.data.stock ? row.locationType === "BIN" && row.warehouseId === page.data.warehouse : row.locationType !== "BIN") });
    const perform = async (page: Context) => {
        if (!services || !pending || page.data.busy) return;
        const operation = pending, current = generation; page.setData({ busy: true, error: "" });
        try {
            const result = await services.sessions.request("POST", operation.path, operation.body, operation) as Change;
            if (!valid(current)) return;
            const rows = await list(page);
            if (valid(current)) {
                pending = null; page.setData({ ...rows, selected: result, snapshot: null, retryable: false,
                    notice: "目标更正已保存。批准不代表实际接收，原物理事实及责任继续保留。" });
            }
        } catch (error) { if (valid(current)) { failed(page, error); page.setData({ retryable: pending !== null }); } }
        finally { finish(page, current); }
    };
    return {
        data: empty(),
        async onShow(this: Context) {
            live = true; const current = ++generation; services = getServices();
            const next = JSON.stringify(services.sessions.identity);
            if (next !== identity) { identity = next; pending = null; this.setData(empty()); }
            this.setData({ busy: false, retryable: pending !== null }); if (pending) return;
            this.setData({ busy: true, error: "" });
            try { const rows = await list(this); if (valid(current)) this.setData(rows); }
            catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
        },
        onHide(this: Context) {
            live = false; generation++;
            if (this.data.businessType === "REPAIR") this.setData({ ...empty(), businessType: "REPAIR" });
        },
        onUnload() { live = false; generation++; pending = null; },
        async onRefresh(this: Context) {
            if (!services || this.data.busy) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try {
                const rows = await list(this);
                if (valid(current)) { this.setData({ ...rows, selected: null, snapshot: null, retryable: pending !== null }); }
            } catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
        },
        async onBusiness(this: Context, event: Event) {
            if (this.data.busy || pending) return;
            this.setData({ businessType: event.detail.value === "2" ? "REPAIR" : event.detail.value === "1" ? "TRANSFER" : "REQUISITION", page: 1, selected: null,
                sources: [], sourceId: "", sourcePage: 1, sourceTotal: 0, items: [], snapshot: null }); await this.onRefresh();
        },
        onOpen(this: Context, event: Event) {
            if (this.data.busy || pending) return;
            this.setData({ selected: this.data.rows.find(row => row.id === event.currentTarget.dataset.id) ?? null, decision: "", snapshot: null });
        },
        async onSources(this: Context) {
            if (!services || this.data.busy || pending || !this.data.canExecute) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try {
                const domain = this.data.businessType === "REPAIR" ? "repairs" : this.data.businessType === "TRANSFER" ? "transfers" : "requisitions";
                const value = await services.sessions.request("GET", `/api/v1/mini/${domain}?page=${this.data.sourcePage}&pageSize=20`) as
                    { items: { id: string; requestNo?: string; requestStatus: string; status?: string; source?: { assetCode: string } }[]; total: number };
                if (valid(current)) this.setData({ sources: value.items.filter(row => ["PROCESSING", "WAREHOUSE_PROCESSING", "WAITING_HANDOVER", "PARTIALLY_COMPLETED"].includes(row.status ?? row.requestStatus))
                    .map(row => ({ ...row, requestNo: row.source?.assetCode ?? row.requestNo })), sourceTotal: value.total });
            } catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
        },
        async onSource(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !event.currentTarget.dataset.id) return;
            const id = event.currentTarget.dataset.id, current = generation;
            this.setData({ busy: true, error: "", sourceId: id, items: [], snapshot: null });
            try {
                const domain = this.data.businessType === "REPAIR" ? "repairs" : this.data.businessType === "TRANSFER" ? "transfers" : "requisitions";
                const value = await services.sessions.request("GET", `/api/v1/mini/${domain}/${id}`) as
                    { assetId?: string; source?: { assetCode: string }; actions?: { changeReturnTarget: boolean };
                        items?: { id: string; itemStatus: string; sourceSnapshot: { asset_code: string } }[];
                        allocations?: { id: string; allocationStatus: string; assetSnapshotJson: { assetCode: string } }[] };
                const items = this.data.businessType === "REPAIR"
                    ? value.actions?.changeReturnTarget && value.assetId && value.source
                        ? [{ id: value.assetId, assetCode: value.source.assetCode }] : []
                    : this.data.businessType === "TRANSFER"
                    ? (value.items ?? []).filter(row => ["READY", "DELIVERED", "REFUSED"].includes(row.itemStatus)).map(row => ({ id: row.id, assetCode: row.sourceSnapshot.asset_code }))
                    : (value.allocations ?? []).filter(row => ["WAITING_DELIVERY", "DELIVERED_WAITING_RECEIPT"].includes(row.allocationStatus)).map(row => ({ id: row.id, assetCode: row.assetSnapshotJson.assetCode }));
                if (valid(current)) this.setData({ items });
            } catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
        },
        async onStart(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !this.data.canExecute || !event.currentTarget.dataset.id) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try {
                const snapshot = await services.sessions.request("GET", `${base}/context?businessType=${this.data.businessType}&businessId=${this.data.sourceId}&itemId=${event.currentTarget.dataset.id}`) as Snapshot;
                const [organizations, warehouses, locations] = await Promise.all([catalog("organization"), catalog("warehouse"), catalog("location")]);
                const target = snapshot.target, using = String(target.usingOrganizationId ?? target.organizationId ?? "");
                const employees = using ? await catalog("employee", using) : [];
                if (valid(current)) {
                    this.setData({ snapshot, selected: null, organizations, owners: [{ id: "", name: "未指定", version: 0 }, ...organizations], warehouses, locations, employees,
                        stock: snapshot.businessType === "TRANSFER" && snapshot.source?.lifecycleStatus === "IN_STOCK",
                        owning: String(target.owningOrganizationId ?? ""), using, employee: String(target.employeeId ?? ""),
                        warehouse: String(target.warehouseId ?? ""), location: String(target.locationId ?? ""), custody: String(target.custodyType),
                        reason: "", code: "", physical: "", checked: false }); choices(this);
                }
            } catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
        },
        async onChoice(this: Context, event: Event) {
            if (!services || this.data.busy || pending) return;
            const field = event.currentTarget.dataset.field, index = Number(event.detail.value);
            if (field === "custody") { this.setData({ custody: index === 1 ? "SHARED" : "PERSONAL", employee: "" }); choices(this); return; }
            const rows = field === "owning" ? this.data.owners : field === "using" ? this.data.organizations : field === "employee" ? this.data.people : field === "warehouse" ? this.data.warehouses : this.data.places;
            if (!["using", "owning", "employee", "warehouse", "location"].includes(field ?? "")) return;
            const value = rows[index]?.id ?? ""; this.setData({ [field!]: value });
            if (field === "warehouse") this.setData({ location: "" });
            if (field === "using") {
                this.setData({ employee: "", employees: [], busy: true }); const current = generation;
                try { const employees = value ? await catalog("employee", value) : []; if (valid(current)) this.setData({ employees }); }
                catch (error) { if (valid(current)) failed(this, error); }
                finally { finish(this, current); }
            }
            if (live) choices(this);
        },
        onField(this: Context, event: Event) {
            const field = event.currentTarget.dataset.field;
            if (!this.data.busy && !pending && ["reason", "code", "physical", "decision"].includes(field ?? "")) this.setData({ [field!]: String(event.detail.value) });
        },
        onCheck(this: Context, event: Event) { if (!this.data.busy && !pending) this.setData({ checked: Array.isArray(event.detail.value) && event.detail.value.includes("checked") }); },
        async onPropose(this: Context) {
            if (!services || this.data.busy || pending || !this.data.snapshot || !this.data.canExecute) return;
            const d = this.data, location = d.places.find(row => row.id === d.location), org = d.organizations.find(row => row.id === d.using),
                owner = d.organizations.find(row => row.id === d.owning), employee = d.people.find(row => row.id === d.employee), warehouse = d.warehouses.find(row => row.id === d.warehouse);
            if (!location || d.stock && !warehouse || !d.stock && (!org || !employee) || d.owning && !owner || !d.checked || !d.reason.trim() || !d.code.trim() || !d.physical.trim()) {
                this.setData({ error: "请选择有效目标、填写原因及实物核对，并确认物理事实。" }); return;
            }
            let target: Target;
            if (d.businessType !== "TRANSFER") target = { custodyType: d.custody, organizationId: org!.id, organizationVersion: org!.version,
                employeeId: employee!.id, employeeVersion: employee!.version, locationId: location.id, locationVersion: location.version };
            else {
                const referenceVersions: Record<string, number> = { location: location.version };
                if (owner) referenceVersions.owningOrganization = owner.version;
                if (d.stock) referenceVersions.warehouse = warehouse!.version;
                else { referenceVersions.usingOrganization = org!.version; referenceVersions.employee = employee!.version; }
                target = { owningOrganizationId: owner?.id ?? null, custodyType: d.stock ? "STORAGE" : d.custody,
                    usingOrganizationId: d.stock ? null : org!.id, employeeId: d.stock ? null : employee!.id,
                    warehouseId: d.stock ? warehouse!.id : null, locationId: location.id, referenceVersions };
            }
            const current = generation; this.setData({ busy: true });
            try {
                if (!await services.confirm("提请批准目标更正", "已核对实物；批准不会自动接收、收回或释放业务锁，是否继续？")) return;
                if (!valid(current)) return;
                pending = { path: `${base}?businessType=${d.businessType}`, key: services.createKey(), version: d.snapshot!.version,
                    body: JSON.parse(JSON.stringify({ businessId: d.snapshot!.businessId, itemId: d.snapshot!.itemId, snapshot: d.snapshot,
                        target, reason: d.reason.trim(), observedAssetCode: d.code.trim(), physicalVerification: d.physical.trim(), physicalChecked: true })) as unknown };
            } catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
            if (valid(current)) await perform(this);
        },
        async onDecision(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !this.data.canApprove || this.data.selected?.change_status !== "PENDING" || !this.data.decision.trim()) return;
            const result = event.currentTarget.dataset.result; if (result !== "APPROVED" && result !== "REJECTED") return;
            const current = generation; this.setData({ busy: true });
            try {
                if (!await services.confirm("决定目标更正", result === "APPROVED" ? "批准新目标仍须原业务实际接收，是否继续？" : "驳回此申请？")) return;
                if (!valid(current)) return;
                pending = { path: `${base}/${this.data.selected.id}/decision?businessType=${this.data.businessType}`, key: services.createKey(),
                    version: this.data.selected.version, body: { result, reason: this.data.decision.trim() } };
            } catch (error) { if (valid(current)) failed(this, error); }
            finally { finish(this, current); }
            if (valid(current)) await perform(this);
        },
        async onRetry(this: Context) { await perform(this); },
        async onNext(this: Context) { if (!this.data.busy && !pending && this.data.page * 20 < this.data.total) { this.setData({ page: this.data.page + 1 }); await this.onRefresh(); } },
        async onPrevious(this: Context) { if (!this.data.busy && !pending && this.data.page > 1) { this.setData({ page: this.data.page - 1 }); await this.onRefresh(); } },
        async onSourceNext(this: Context) { if (!this.data.busy && !pending && this.data.sourcePage * 20 < this.data.sourceTotal) { this.setData({ sourcePage: this.data.sourcePage + 1 }); await this.onSources(); } },
        async onSourcePrevious(this: Context) { if (!this.data.busy && !pending && this.data.sourcePage > 1) { this.setData({ sourcePage: this.data.sourcePage - 1 }); await this.onSources(); } },
    };
}
