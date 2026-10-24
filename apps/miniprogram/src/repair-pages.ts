import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
interface Asset { id: string; assetCode: string; name: string; version: number; locationId: string | null; locationName: string | null }
interface Repair { inventoryEvidence?: { taskId: string; observationId: string; photoIds: string[] }[]; id: string; source: Asset; fault: string; status: string; version: number; assetVersion: number; ownershipVersion: number; lockVersion: number;
    loanVersion?: number | null; physicalHandling?: { occurred_at: string; evidence: string }[]; inbound?: { occurred_at: string; evidence: string }[];
    loanExpectedReturnDate?: string | null; returnHandovers?: ReturnHandover[]; returnRecollections?: { handover_id: string; occurred_at: string; evidence: string }[];
    actions: Record<string, boolean>; photos?: { objectId: string; filename: string; contentType: string }[];
    history?: { id: string; action: string; actor_username: string; occurred_at: string }[]; historyTotal?: number }
interface Pending { path: string; key: string; version: number; body?: unknown; file?: string }
interface PhysicalPlace { id: string; name: string; version: number; warehouse_id?: string | null; location_type?: string }
interface ReturnTarget { employeeName: string; organizationName: string; locationName: string; employeeVersion: number; organizationVersion: number; locationVersion: number }
interface ReturnHandover { id: string; handover_status: string; target_snapshot_json: ReturnTarget; delivered_at: string; delivery_evidence: string; receipt_at?: string; receipt_evidence?: string }
type ReturnMode = "delivery" | "receipt" | "refusal" | "recollection";
interface Data { rows: Repair[]; assets: Asset[]; detail: Repair | null; selectedAsset: string; assetPage: number; assetTotal: number;
    page: number; total: number; busy: boolean; retry: boolean; error: string; fault: string; impact: string; occurredAt: string;
    reason: string; observedCode: string; evidence: string; locationConfirmed: boolean; neverLeft: boolean; photoPath: string; photoName: string;
    physicalMode: "away" | "inbound"; physicalObservedCode: string; physicalAt: string; physicalEvidence: string;
    warehouses: PhysicalPlace[]; physicalLocations: PhysicalPlace[]; physicalWarehouseId: string; physicalLocationId: string;
    warehousePage: number; warehouseTotal: number; physicalLocationPage: number; physicalLocationTotal: number;
    returnMode: ReturnMode; returnTarget: ReturnTarget | null; returnObservedCode: string; returnAt: string; returnEvidence: string; returnChecked: boolean;
    returnLocations: PhysicalPlace[]; returnLocationId: string; returnLocationPage: number; returnLocationTotal: number; reassignment: Reassignment | null; reassignTarget: string; reassignReason: string; reassignEvidence: string; reassignChecked: boolean }
interface Reassignment { snapshot: { version: number; acceptedAt: string | null; physicalHandling: unknown[]; outstandingReturn: { handover_status: string } | null }; operators: { id: string; name: string }[] }
interface Context { data: Data; setData(data: Partial<Data>): void }
const blank = (): Data => ({ rows: [], assets: [], detail: null, selectedAsset: "", assetPage: 1, assetTotal: 0, page: 1, total: 0,
    busy: false, retry: false, error: "", fault: "", impact: "MEDIUM", occurredAt: "", reason: "", observedCode: "", evidence: "",
    locationConfirmed: false, neverLeft: false, photoPath: "", photoName: "", physicalMode: "away", physicalObservedCode: "", physicalAt: "", physicalEvidence: "",
    warehouses: [], physicalLocations: [], physicalWarehouseId: "", physicalLocationId: "", warehousePage: 1, warehouseTotal: 0, physicalLocationPage: 1, physicalLocationTotal: 0,
    returnMode: "delivery", returnTarget: null, returnObservedCode: "", returnAt: "", returnEvidence: "", returnChecked: false,
    returnLocations: [], returnLocationId: "", returnLocationPage: 1, returnLocationTotal: 0, reassignment: null, reassignTarget: "", reassignReason: "", reassignEvidence: "", reassignChecked: false });
export function createRepairPage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", live = false, generation = 0, pending: Pending | null = null, selectedId = "", photo = "";
    const current = (epoch: number) => live && epoch === generation && identity === JSON.stringify(services.sessions.identity);
    const clearPhoto = () => { if (photo) { services.sessions.discardInventoryPhoto(photo); photo = ""; } };
    const failure = (page: Context, error: unknown) => {
        if (error instanceof MiniRequestError && [401,403,404].includes(error.status)) {
            pending = null; selectedId = ""; clearPhoto(); page.setData({ ...blank(), error: "当前身份、权限或记录失效，已隐藏原资料。" });
        } else if (error instanceof MiniRequestError && [400,409,413,422].includes(error.status)) {
            pending = null; page.setData({ retry: false, error: error.code === "REPAIR_BORROWING_EXPIRED_INBOUND_REQUIRED"
                ? "原借用已到期，请先在借用台账获批延期后再返还，或实际验收入库关闭借用；维修及资产锁继续保留。"
                : "请刷新核对单据、实物、处理权和原借用；照片应为10MB以内的图片。" });
        } else page.setData({ retry: !!pending, error: pending ? "结果尚未确认，请使用原操作重试。" : "读取未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => {
        if (current(epoch)) page.setData({ busy: false });
        else if (live && epoch === generation) { pending = null; selectedId = ""; clearPhoto(); page.setData({ ...blank(), error: "身份已变化，请重新进入。" }); }
    };
    const read = async (page: Context) => {
        const epoch = ++generation; clearPhoto(); page.setData({ busy: true, error: "", detail: null, rows: [], assets: [], photoPath: "", photoName: "",
            warehouses: [], physicalLocations: [], physicalWarehouseId: "", physicalLocationId: "", physicalObservedCode: "", physicalAt: "", physicalEvidence: "",
            returnTarget: null, returnObservedCode: "", returnAt: "", returnEvidence: "", returnChecked: false,
            returnLocations: [], returnLocationId: "", returnLocationPage: 1, returnLocationTotal: 0, reassignment: null, reassignTarget: "", reassignReason: "", reassignEvidence: "", reassignChecked: false });
        try {
            const rows = await services.sessions.request("GET", `/api/v1/mini/repairs?page=${page.data.page}&pageSize=20`) as { items: Repair[]; total: number };
            if (!current(epoch)) return;
            const assets = await services.sessions.request("GET", `/api/v1/mini/repairs/eligible?page=${page.data.assetPage}&pageSize=20`) as { items: Asset[]; total: number };
            if (!current(epoch)) return;
            const detail = selectedId ? await services.sessions.request("GET", `/api/v1/mini/repairs/${selectedId}`) as Repair : null;
            if (current(epoch)) page.setData({ rows: rows.items, total: rows.total, assets: assets.items, assetTotal: assets.total, detail,
                selectedAsset: "", retry: !!pending, observedCode: "", evidence: "", locationConfirmed: false, neverLeft: false });
        } catch (error) { if (current(epoch)) failure(page, error); } finally { finish(page, epoch); }
    };
    const send = async (page: Context, operation: Pending, epoch: number) => {
        try {
            const result = (operation.file ? await services.sessions.uploadEvidence(operation.path, operation.file, operation)
                : await services.sessions.request("POST", operation.path, operation.body, operation)) as { id?: string; repairId?: string };
            if (!current(epoch)) return;
            if (result.id || result.repairId) selectedId = result.id ?? result.repairId!;
            // Keep the immutable operation until the authoritative reread succeeds.
            const detail = selectedId ? await services.sessions.request("GET", `/api/v1/mini/repairs/${selectedId}`) as Repair : null;
            if (!current(epoch)) return;
            pending = null; page.setData({ detail, retry: false, reason: "", fault: "" }); await read(page);
        } catch (error) { if (current(epoch)) failure(page, error); }
    };
    const confirm = async (page: Context, operation: Pending, text: string) => {
        const epoch = generation; page.setData({ busy: true, error: "" });
        try { if (!await services.confirm("核对报修维修", text) || !current(epoch)) return;
            pending = operation; page.setData({ retry: true }); await send(page, operation, epoch);
        } catch (error) { if (current(epoch)) failure(page, error); } finally { finish(page, epoch); }
    };
    return {
        data: blank(),
        onLoad(_options: { id?: string }) { if (/^[1-9][0-9]*$/.test(_options.id ?? "")) selectedId = _options.id!; },
        async onShow(this: Context) { live = true; services = getServices(); const next = JSON.stringify(services.sessions.identity);
            if (next !== identity) { pending = null; if (identity) selectedId = ""; clearPhoto(); this.setData(blank()); } identity = next; await read(this); },
        onHide(this: Context) { live = false; generation++; clearPhoto(); this.setData(blank()); },
        onUnload(this: Context) { live = false; generation++; clearPhoto(); pending = null; selectedId = ""; this.setData(blank()); },
        async onRefresh(this: Context) { if (!this.data.busy) await read(this); },
        async onOpen(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            if (this.data.busy || pending || !current(generation) || !this.data.rows.some(row => row.id === event.currentTarget.dataset.id)) return;
            selectedId = event.currentTarget.dataset.id; await read(this);
        },
        onAsset(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            if (!this.data.busy && !pending && this.data.assets.some(row => row.id === event.currentTarget.dataset.id)) this.setData({ selectedAsset: event.currentTarget.dataset.id });
        },
        onField(this: Context, event: { currentTarget: { dataset: { field: string } }; detail: { value: string } }) {
            const field = event.currentTarget.dataset.field;
            if (!this.data.busy && !pending && ["fault","occurredAt","reason","observedCode","evidence","physicalObservedCode","physicalAt","physicalEvidence","returnObservedCode","returnAt","returnEvidence","reassignReason","reassignEvidence"].includes(field)) this.setData({ [field]: event.detail.value });
        },
        onImpact(this: Context, event: { detail: { value: string } }) { const impact = ["LOW","MEDIUM","HIGH"][Number(event.detail.value)]; if (!this.data.busy && !pending && impact) this.setData({ impact }); },
        onPhysical(this: Context, event: { detail: { value: string[] } }) { if (!this.data.busy && !pending) this.setData({ neverLeft: event.detail.value.includes("neverLeft"), locationConfirmed: event.detail.value.includes("location") }); },
        onPhysicalMode(this: Context, event: { currentTarget: { dataset: { mode: string } } }) {
            const mode = event.currentTarget.dataset.mode;
            if (this.data.busy || pending || !current(generation) || !["away", "inbound"].includes(mode)) return;
            this.setData({ physicalMode: mode as "away" | "inbound", physicalLocationId: "", physicalAt: new Date().toISOString() });
        },
        async onPhysicalCatalog(this: Context, event: { currentTarget: { dataset: { kind: string; delta?: string } } }) {
            const kind = event.currentTarget.dataset.kind, delta = Number(event.currentTarget.dataset.delta ?? "0");
            if (this.data.busy || pending || !current(generation) || !["warehouse", "location"].includes(kind) || ![-1,0,1].includes(delta)) return;
            const store = kind === "warehouse", next = (store ? this.data.warehousePage : this.data.physicalLocationPage) + delta;
            if (next < 1 || delta > 0 && (next-1)*20 >= (store ? this.data.warehouseTotal : this.data.physicalLocationTotal)) return;
            const epoch = generation; this.setData({ busy: true, error: "" });
            try { const result = await services.sessions.request("GET", `/api/v1/mini/repairs/physical-catalog/${kind}?page=${next}&pageSize=20`) as { items: PhysicalPlace[]; total: number };
                if (current(epoch)) this.setData(store ? { warehouses: result.items, warehouseTotal: result.total, warehousePage: next, physicalWarehouseId: "", physicalLocationId: "" }
                    : { physicalLocations: result.items, physicalLocationTotal: result.total, physicalLocationPage: next, physicalLocationId: "" });
            } catch (error) { if (current(epoch)) failure(this,error); } finally { finish(this,epoch); }
        },
        onPhysicalWarehouse(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            const id = event.currentTarget.dataset.id;
            if (!this.data.busy && !pending && current(generation) && this.data.warehouses.some(row => row.id === id)) this.setData({ physicalWarehouseId: id, physicalLocationId: "" });
        },
        onPhysicalLocation(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            const row = this.data.physicalLocations.find(row => row.id === event.currentTarget.dataset.id);
            if (!this.data.busy && !pending && current(generation) && row && (this.data.physicalMode === "away" || row.location_type === "BIN" && row.warehouse_id === this.data.physicalWarehouseId)) this.setData({ physicalLocationId: row.id });
        },
        async onPhysicalAction(this: Context) {
            const row = this.data.detail, mode = this.data.physicalMode;
            if (this.data.busy || pending || !row || !current(generation) || !row.actions[mode === "away" ? "acceptAway" : "closeInbound"]) return;
            const place = this.data.physicalLocations.find(row => row.id === this.data.physicalLocationId), warehouse = this.data.warehouses.find(row => row.id === this.data.physicalWarehouseId), at = new Date(this.data.physicalAt);
            if (!place || !Number.isFinite(at.getTime()) || !/(Z|[+-][0-9]{2}:[0-9]{2})$/i.test(this.data.physicalAt) || !this.data.physicalObservedCode.trim() || this.data.physicalEvidence.trim().length < 10
                || mode === "inbound" && (!warehouse || place.location_type !== "BIN" || place.warehouse_id !== warehouse.id)) { this.setData({ error: "请核对实物编码、带时区实际时间、有效位置及至少10字依据；回库须选仓库和其所属库位。" }); return; }
            const body: Record<string,unknown> = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion, loanVersion: row.loanVersion ?? null,
                observedCode: this.data.physicalObservedCode.trim(), occurredAt: at.toISOString(), evidence: this.data.physicalEvidence.trim(), locationVersion: place.version };
            if (mode === "away") body.actualLocationId = place.id;
            else { body.warehouseId = warehouse!.id; body.warehouseVersion = warehouse!.version; body.locationId = place.id; }
            await confirm(this, { path: `/api/v1/mini/repairs/${row.id}/${mode === "away" ? "accept-away" : "close-inbound"}`, key: services.createKey(), version: row.version, body },
                mode === "away" ? "记录实物离开原保管人的实际接收，维修及资产锁继续保留，之后不能原地关闭。" : "已实际核码和核实修复结果并验收入库，同事务结束原未结借用和维修并释放本单资产锁。");
        },
        async onReturnMode(this: Context, event: { currentTarget: { dataset: { mode: string } } }) {
            const value = event.currentTarget.dataset.mode as ReturnMode;
            const action = { delivery: "deliverReturn", receipt: "receiveReturn", refusal: "refuseReturn", recollection: "recollectReturn" }[value];
            if (this.data.busy || pending || !current(generation) || !action || !this.data.detail?.actions[action]) return;
            this.setData({ returnMode: value, returnTarget: null, returnChecked: false, returnLocationId: "", returnAt: new Date().toISOString(), error: "" });
            if (value !== "delivery") return;
            const epoch = generation; this.setData({ busy: true });
            try { const target = await services.sessions.request("GET", `/api/v1/mini/repairs/${this.data.detail.id}/return-target`) as ReturnTarget;
                if (current(epoch)) this.setData({ returnTarget: target });
            } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); }
        },
        onReturnChecked(this: Context, event: { detail: { value: string[] } }) {
            if (!this.data.busy && !pending && current(generation)) this.setData({ returnChecked: event.detail.value.includes("physical") });
        },
        async onReturnCatalog(this: Context, event: { currentTarget: { dataset: { delta?: string } } }) {
            const delta = Number(event.currentTarget.dataset.delta ?? "0"), next = this.data.returnLocationPage + delta;
            if (this.data.busy || pending || !current(generation) || !this.data.detail?.actions.recollectReturn || ![-1, 0, 1].includes(delta) || next < 1
                || delta > 0 && (next - 1) * 20 >= this.data.returnLocationTotal) return;
            const epoch = generation; this.setData({ busy: true });
            try { const result = await services.sessions.request("GET", `/api/v1/mini/repairs/physical-catalog/location?page=${next}&pageSize=20`) as { items: PhysicalPlace[]; total: number };
                if (current(epoch)) this.setData({ returnLocations: result.items, returnLocationTotal: result.total, returnLocationPage: next, returnLocationId: "" });
            } catch (error) { if (current(epoch)) failure(this,error); } finally { finish(this,epoch); }
        },
        onReturnLocation(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            const id = event.currentTarget.dataset.id;
            if (!this.data.busy && !pending && current(generation) && this.data.returnLocations.some(row => row.id === id)) this.setData({ returnLocationId: id });
        },
        async onReturnAction(this: Context) {
            const row = this.data.detail, mode = this.data.returnMode;
            const action = { delivery: "deliverReturn", receipt: "receiveReturn", refusal: "refuseReturn", recollection: "recollectReturn" }[mode];
            if (this.data.busy || pending || !current(generation) || !row || !row.actions[action]) return;
            const at = new Date(this.data.returnAt), place = this.data.returnLocations.find(row => row.id === this.data.returnLocationId),
                handover = row.returnHandovers?.find(row => ["WAITING_RECEIPT", "REFUSED"].includes(row.handover_status)), target = this.data.returnTarget;
            if (!Number.isFinite(at.getTime()) || !/(Z|[+-][0-9]{2}:[0-9]{2})$/i.test(this.data.returnAt) || !this.data.returnObservedCode.trim() || this.data.returnEvidence.trim().length < 10
                || mode === "delivery" && !target || mode !== "delivery" && !handover || mode === "recollection" && !place
                || ["receipt", "refusal"].includes(mode) && !this.data.returnChecked) {
                this.setData({ error: "请核对实物编码、带时区实际时间和至少10字依据；接收或拒收须明确实物核实，收回须选有效实际位置。" }); return;
            }
            const body: Record<string, unknown> = { assetVersion: row.assetVersion, lockVersion: row.lockVersion, observedCode: this.data.returnObservedCode.trim(), occurredAt: at.toISOString(), evidence: this.data.returnEvidence.trim() };
            if (mode === "delivery") Object.assign(body, { ownershipVersion: row.ownershipVersion, loanVersion: row.loanVersion ?? null,
                employeeVersion: target!.employeeVersion, organizationVersion: target!.organizationVersion, locationVersion: target!.locationVersion });
            else { body.handoverId = handover!.id;
                if (mode === "recollection") Object.assign(body, { ownershipVersion: row.ownershipVersion, actualLocationId: place!.id, locationVersion: place!.version });
                else Object.assign(body, { loanVersion: row.loanVersion ?? null, physicalChecked: true });
            }
            await confirm(this, { path: `/api/v1/mini/repairs/${row.id}/return-${mode}`, key: services.createKey(), version: row.version, body },
                "按实物事实保存本次返还办理：交出保持维修与锁，实际接收才恢复原责任和有效借用；拒收后须库管实际收回才可回库。");
        },
        async onReassignment(this: Context) {
            if (this.data.busy || pending || !current(generation) || !this.data.detail?.actions.reassign) return;
            const epoch = generation; this.setData({ busy: true, error: "" });
            try { const result = await services.sessions.request("GET", `/api/v1/mini/repairs/${this.data.detail.id}/reassignment`) as Reassignment;
                if (current(epoch)) this.setData({ reassignment: result, reassignTarget: "", reassignChecked: false });
            } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); }
        },
        onReassignTarget(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            const id = event.currentTarget.dataset.id;
            if (!this.data.busy && !pending && current(generation) && this.data.reassignment?.operators.some(row => row.id === id)) this.setData({ reassignTarget: id });
        },
        onReassignChecked(this: Context, event: { detail: { value: string[] } }) {
            if (!this.data.busy && !pending && current(generation)) this.setData({ reassignChecked: event.detail.value.includes("checked") });
        },
        async onReassign(this: Context) {
            const row = this.data.detail, context = this.data.reassignment;
            if (this.data.busy || pending || !current(generation) || !row?.actions.reassign || !context) return;
            if (!this.data.reassignChecked || !context.operators.some(row => row.id === this.data.reassignTarget)
                || this.data.reassignReason.trim().length < 3 || this.data.reassignEvidence.trim().length < 10) {
                this.setData({ error: "请选择有效接任库管，核对未同步结果并填写原因和实际交接凭证。" }); return;
            }
            await confirm(this, { path: `/api/v1/mini/repairs/${row.id}/reassignment`, key: services.createKey(), version: context.snapshot.version,
                body: { processorAccountId: this.data.reassignTarget, snapshot: JSON.parse(JSON.stringify(context.snapshot)) as unknown,
                    reason: this.data.reassignReason.trim(), evidence: this.data.reassignEvidence.trim(), unsynchronizedResultsChecked: true } },
                "确认转移唯一处理权；保留原审批、资产锁及所有实物交接，已交出设备仍须实际接收或收回。");
        },
        async onSubmit(this: Context) {
            const asset = this.data.assets.find(row => row.id === this.data.selectedAsset), at = new Date(this.data.occurredAt);
            if (this.data.busy || pending || !asset || !current(generation)) return;
            if (this.data.fault.trim().length < 3 || !Number.isFinite(at.getTime())) { this.setData({ error: "请填写实际故障和带时区的发生时间。" }); return; }
            await confirm(this, { path: "/api/v1/mini/repairs", key: services.createKey(), version: 0, body: { assetId: asset.id, assetVersion: asset.version,
                fault: this.data.fault.trim(), impact: this.data.impact, occurredAt: at.toISOString() } }, `提交 ${asset.assetCode} 的实际故障并锁定资产，等待管理员确认。`);
        },
        async onAction(this: Context, event: { currentTarget: { dataset: { action: string } } }) {
            const row = this.data.detail, action = event.currentTarget.dataset.action;
            const permissions: Record<string, string> = { approve: "approve", reject: "approve", cancel: "cancel", claim: "claim", accept: "acceptInPlace", complete: "completeInPlace" };
            if (this.data.busy || pending || !row || !current(generation) || !permissions[action] || !row.actions[permissions[action]!]) return;
            let path = action, body: unknown;
            if (["approve","reject","cancel"].includes(action)) {
                if (this.data.reason.trim().length < 3) { this.setData({ error: "请填写至少3字的实际处理依据。" }); return; }
                body = { reason: this.data.reason.trim() };
                if (action !== "cancel") { path = "decision"; body = { reason: this.data.reason.trim(), result: action === "approve" ? "APPROVE" : "REJECT", allowedMethod: action === "approve" ? "INTERNAL_NO_COST" : null }; }
            }
            if (["accept","complete"].includes(action)) {
                if (!this.data.locationConfirmed || !this.data.neverLeft || !row.source.locationId || !this.data.observedCode.trim() || this.data.evidence.trim().length < 10) { this.setData({ error: "请实际核码、核对原位置、确认未离开保管方，并填写至少10字依据。" }); return; }
                path = action === "accept" ? "accept-in-place" : "complete-in-place";
                body = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion, observedCode: this.data.observedCode.trim(),
                    actualLocationId: row.source.locationId, neverLeftCustodian: true, evidence: this.data.evidence.trim() };
            }
            const descriptions: Record<string,string> = { approve: "仅批准内部零费用维修，尚未接收或修复。", reject: "驳回申请并释放本单资产锁。", cancel: "取消尚未批准的报修。", claim: "领取实际处理权。", accept: "实物仍在原位置，实际接修并标为维修中。", complete: "确认实际修复并恢复原保管状态，结束维修并释放本单资产锁。" };
            await confirm(this, { path: `/api/v1/mini/repairs/${row.id}/${path}`, key: services.createKey(), version: row.version, body }, descriptions[action]!);
        },
        async onUpload(this: Context) {
            const row = this.data.detail; if (this.data.busy || pending || !row?.actions.addFaultPhoto || !services.chooseInventoryPhoto || !current(generation)) return;
            const epoch = generation; this.setData({ busy: true });
            try { const file = await services.chooseInventoryPhoto("album"); if (!current(epoch)) return;
                if (file.size <= 0 || file.size > 10 * 1024 * 1024 || !/\.(png|jpe?g|webp)$/i.test(file.name)) { this.setData({ error: "请选择10MB以内的PNG、JPEG或WebP故障照片。" }); return; }
                await confirm(this, { path: `/api/v1/mini/repairs/${row.id}/photos`, key: services.createKey(), version: row.version, file: file.path }, `上传实际故障照片 ${file.name}；只有服务端确认的照片才进入审批快照。`);
            } catch (error) { if (current(epoch)) failure(this,error); } finally { finish(this,epoch); }
        },
        async onPhoto(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            const row = this.data.detail, item = row?.photos?.find(item => item.objectId === event.currentTarget.dataset.id);
            if (this.data.busy || pending || !row || !item || !current(generation)) return;
            const epoch = generation; this.setData({ busy: true }); clearPhoto();
            try { const grant = await services.sessions.request("POST", `/api/v1/mini/repairs/${row.id}/photos/${item.objectId}/download-grant`) as { url: string };
                if (!current(epoch)) return;
                const path = await services.sessions.downloadRepairPhoto(grant.url,item.contentType);
                if (!current(epoch)) { services.sessions.discardInventoryPhoto(path); return; }
                photo = path; this.setData({ photoPath: path, photoName: item.filename });
            } catch(error) { if(current(epoch)) failure(this,error); } finally { finish(this,epoch); }
        },
        onClosePhoto(this: Context) { clearPhoto(); this.setData({ photoPath: "", photoName: "" }); },
        async onRetry(this: Context) { if (this.data.busy || !pending || !current(generation)) return; const epoch = generation;
            this.setData({ busy: true }); try { await send(this,pending,epoch); } finally { finish(this,epoch); } },
        async onPage(this: Context, event: { currentTarget: { dataset: { delta: string; kind?: string } } }) {
            const delta = Number(event.currentTarget.dataset.delta), assets = event.currentTarget.dataset.kind === "assets", field = assets ? "assetPage" : "page";
            const next = this.data[field] + delta, total = assets ? this.data.assetTotal : this.data.total;
            if (this.data.busy || pending || !current(generation) || ![1,-1].includes(delta) || next < 1 || (next-1)*20 >= total) return;
            this.setData({ [field]: next }); await read(this);
        }
    };
}
