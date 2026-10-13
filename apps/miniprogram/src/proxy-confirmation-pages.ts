import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";

type Business = "REQUISITION" | "TRANSFER";
interface Proof { objectId: string; filename: string; scanStatus: string }
interface Proposal {
    id: string; version: number; businessType: Business; businessId: string; itemId: string;
    confirmationStatus: string; actualSigner: string; actualReceivedAt: string;
    cannotConfirmReason: string; contactRecord: string; creatorAccountId: string;
    approverAccountId: string | null; executorAccountId: string | null; decisionReason?: string;
    businessSnapshotJson: { assetCode: string; targetEmployeeId: string; requestNo: string };
}
interface Form {
    actualSigner: string; actualDate: string; actualTime: string; cannotConfirmReason: string;
    contactRecord: string; delegatedReceipt: boolean; delegationRelationship: string;
    delegationBasis: string; evidenceKind: string; collectionMethod: string; manualReason: string;
    observedAssetCode: string; targetOrAuthorizedProxyReceived: boolean;
}
interface Data {
    businessNames: string[]; kindNames: string[]; collectionNames: string[];
    rows: Proposal[]; selected: Proposal | null; attachments: Proof[]; page: number; total: number;
    accountId: string; canApprove: boolean; canExecute: boolean; businessType: Business;
    sources: { id: string; requestNo?: string }[]; sourcePage: number; sourceTotal: number; sourceId: string;
    items: { id: string; assetCode: string }[]; snapshot: Record<string, unknown> | null; form: Form;
    fileName: string; decisionReason: string; physicalChecked: boolean; noDisputeChecked: boolean;
    busy: boolean; retryable: boolean; error: string; notice: string;
}
interface Context { data: Data; setData(value: Partial<Data>): void; onRefresh(): Promise<void>; onSources(): Promise<void>; onStart(event: Event): Promise<void> }
interface Event { detail: { value: string | string[] }; currentTarget: { dataset: { field?: string; id?: string; approved?: string } } }
const base = "/api/v1/mini/proxy-confirmations";
const emptyForm = (): Form => ({ actualSigner: "", actualDate: "", actualTime: "", cannotConfirmReason: "", contactRecord: "",
    delegatedReceipt: false, delegationRelationship: "", delegationBasis: "", evidenceKind: "SIGNED_RECEIPT",
    collectionMethod: "CODE_CONFIRMED", manualReason: "", observedAssetCode: "", targetOrAuthorizedProxyReceived: false });
const empty = (): Data => ({ businessNames: ["领用 / 借用", "调拨"], kindNames: ["签收单", "物流签收", "授权代收"],
    collectionNames: ["核对编码", "扫描核对", "手工记录"], rows: [], selected: null, attachments: [], page: 1, total: 0, canApprove: false,
    canExecute: false, accountId: "", businessType: "REQUISITION", sources: [], sourcePage: 1, sourceTotal: 0, sourceId: "", items: [],
    snapshot: null, form: emptyForm(), fileName: "", decisionReason: "", physicalChecked: false, noDisputeChecked: false,
    busy: false, retryable: false, error: "", notice: "" });
export function proxyMessage(error: unknown): string {
    if (error instanceof MiniRequestError) {
        if (error.status === 401) return "登录已失效，请重新登录。";
        if (error.status === 403 || error.status === 404) return "当前账号无办理权限或记录不可见。";
        if (error.status === 409) return "处理权、目标、实物或凭证已变更，请刷新重新核对，不能继续使用旧许可。";
        if (error.status === 400 || error.status === 413) return "请核对内容与凭证：图片不超过10MB，PDF不超过20MB。";
    }
    return "结果尚未确认，请保留本页并使用原请求重试，或刷新查询后再办理。";
}

/** One pending original request, bound to account/binding and page generation. */
export function createProxyConfirmationPage(getServices: () => MiniServices) {
    let services: MiniServices | null = null, identity = "", live = false, generation = 0, initialId = "", previousId = "";
    let file: { path: string; name: string; size: number } | null = null;
    let fileOwner = "", fileProposal = "", pickerEpoch = 0;
    let pending: { kind: "json" | "file"; path: string; key: string; version: number; body?: unknown; filePath?: string } | null = null;
    const list = async (page: Context) => {
        const value = await services!.sessions.request("GET", `${base}?page=${page.data.page}&pageSize=20`) as
            { items: Proposal[]; total: number; canApprove: boolean; canExecute: boolean };
        return { rows: value.items, total: value.total, canApprove: value.canApprove, canExecute: value.canExecute };
    };
    const detail = async (id: string) => {
        const selected = await services!.sessions.request("GET", `${base}/${id}`) as Proposal;
        const attachments = (await services!.sessions.request("GET", `${base}/${id}/attachments`) as { items: Proof[] }).items;
        return { selected, attachments, snapshot: null, decisionReason: "", physicalChecked: false, noDisputeChecked: false };
    };
    const failed = (page: Context, error: unknown) => {
        page.setData({ error: proxyMessage(error) });
        if (!services!.sessions.identity) services!.navigate("/pages/login/index");
    };
    const perform = async (page: Context) => {
        if (!services || !pending || page.data.busy) return;
        const operation = pending, current = generation;
        page.setData({ busy: true, error: "", notice: "" });
        try {
            const result = operation.kind === "file"
                ? await services.sessions.uploadEvidence(operation.path, operation.filePath!, operation)
                : await services.sessions.request("POST", operation.path, operation.body, operation);
            if (!live || current !== generation) return;
            const id = operation.kind === "file" ? page.data.selected!.id : (result as Proposal).id;
            const value = await detail(id), rows = await list(page);
            if (live && current === generation) {
                pending = null; file = null; fileOwner = ""; fileProposal = ""; previousId = "";
                page.setData({ ...value, ...rows, retryable: false, fileName: "",
                    notice: "办理结果已保存。批准不等于接收完成，只有库管明确执行后才建立责任并通知。" });
            }
        } catch (error) {
            if (live && current === generation) { failed(page, error); page.setData({ retryable: pending !== null }); }
        } finally { if (live && current === generation) page.setData({ busy: false }); }
    };
    const sources = async (page: Context) => {
        const domain = page.data.businessType === "TRANSFER" ? "transfers" : "requisitions";
        const value = await services!.sessions.request("GET", `/api/v1/mini/${domain}?page=${page.data.sourcePage}&pageSize=20`) as
            { items: { id: string; requestNo?: string; requestStatus: string }[]; total: number };
        return { sources: value.items.filter(row => ["WAITING_HANDOVER", "PARTIALLY_COMPLETED"].includes(row.requestStatus)), sourceTotal: value.total };
    };
    return {
        data: empty(),
        onLoad(options: { id?: string }) { if (options.id && /^[1-9][0-9]*$/.test(options.id)) initialId = options.id; },
        async onShow(this: Context) {
            live = true; const current = ++generation; services = getServices();
            const next = JSON.stringify(services.sessions.identity);
            if (next !== identity) {
                if (identity) initialId = "";
                identity = next; pickerEpoch++; pending = null; file = null; fileOwner = ""; fileProposal = ""; previousId = ""; this.setData(empty());
            }
            this.setData({ busy: false, retryable: pending !== null, accountId: services.sessions.identity?.accountId ?? "",
                fileName: file && fileOwner === identity && fileProposal === this.data.selected?.id ? file.name : "" });
            if (pending) return;
            this.setData({ busy: true, error: "" });
            try {
                const rows = await list(this);
                const selectedId = initialId || this.data.selected?.id;
                const value = selectedId ? await detail(selectedId) : {};
                if (live && current === generation) { this.setData({ ...rows, ...value }); initialId = ""; }
            } catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        onHide() { live = false; generation++; },
        onUnload() { live = false; generation++; pickerEpoch++; pending = null; file = null; fileOwner = ""; fileProposal = ""; previousId = ""; },
        async onRefresh(this: Context) {
            if (!services || this.data.busy) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try {
                const value = this.data.selected ? await detail(this.data.selected.id) : {};
                const rows = await list(this);
                if (live && current === generation) { pending = null; file = null; this.setData({ ...rows, ...value, retryable: false, fileName: "" }); }
            } catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onOpen(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !event.currentTarget.dataset.id) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try {
                const value = await detail(event.currentTarget.dataset.id);
                if (live && current === generation) { pickerEpoch++; file = null; this.setData({ ...value, fileName: "" }); }
            }
            catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        onBusiness(this: Context, event: Event) {
            if (!this.data.busy && !pending) this.setData({ businessType: String(event.detail.value) === "1" ? "TRANSFER" : "REQUISITION",
                sources: [], sourceId: "", sourcePage: 1, sourceTotal: 0, items: [], snapshot: null });
        },
        async onSources(this: Context) {
            if (!services || this.data.busy || pending || !this.data.canExecute) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try { const value = await sources(this); if (live && current === generation) this.setData(value); }
            catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onSource(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !event.currentTarget.dataset.id) return;
            const id = event.currentTarget.dataset.id, current = generation;
            const domain = this.data.businessType === "TRANSFER" ? "transfers" : "requisitions";
            this.setData({ busy: true, error: "", sourceId: id, items: [], snapshot: null });
            try {
                const value = await services.sessions.request("GET", `/api/v1/mini/${domain}/${id}`) as
                    { items?: { id: string; itemStatus: string; sourceSnapshot: { asset_code: string } }[];
                        allocations?: { id: string; allocationStatus: string; assetSnapshotJson: { assetCode: string } }[] };
                const items = this.data.businessType === "TRANSFER"
                    ? (value.items ?? []).filter(item => item.itemStatus === "DELIVERED").map(item => ({ id: item.id, assetCode: item.sourceSnapshot.asset_code }))
                    : (value.allocations ?? []).filter(item => item.allocationStatus === "DELIVERED_WAITING_RECEIPT").map(item => ({ id: item.id, assetCode: item.assetSnapshotJson.assetCode }));
                if (live && current === generation) this.setData({ items, notice: items.length ? "请选择已经实际交付的明细。" : "本单没有可申请代确认的待接收明细。" });
            } catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onStart(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !event.currentTarget.dataset.id) return;
            const current = generation, prior = this.data.selected;
            this.setData({ busy: true, error: "" });
            try {
                const snapshot = await services.sessions.request("GET", `${base}/context?businessType=${this.data.businessType}&businessId=${this.data.sourceId}&itemId=${event.currentTarget.dataset.id}`) as Record<string, unknown>;
                if (live && current === generation) {
                    previousId = prior && prior.businessId === snapshot.businessId && prior.itemId === snapshot.itemId && prior.businessType === snapshot.businessType ? prior.id : "";
                    pickerEpoch++; file = null;
                    this.setData({ snapshot, selected: null, attachments: [], fileName: "", form: { ...emptyForm(), observedAssetCode: String(snapshot.assetCode) } });
                }
            } catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onReapply(this: Context) {
            const prior = this.data.selected;
            if (this.data.busy || pending || !this.data.canExecute || prior?.confirmationStatus !== "REJECTED") return;
            this.setData({ businessType: prior.businessType, sourceId: prior.businessId });
            await this.onStart({ detail: { value: "" }, currentTarget: { dataset: { id: prior.itemId } } });
        },
        onField(this: Context, event: Event) {
            if (this.data.busy || pending) return;
            const field = event.currentTarget.dataset.field;
            if (field && Object.hasOwn(this.data.form, field) && typeof this.data.form[field as keyof Form] === "string") {
                this.setData({ form: { ...this.data.form, [field]: String(event.detail.value) } });
            }
        },
        onFlag(this: Context, event: Event) {
            if (this.data.busy || pending) return;
            const field = event.currentTarget.dataset.field;
            const checked = Array.isArray(event.detail.value) && event.detail.value.includes("checked");
            if (field === "delegatedReceipt" || field === "targetOrAuthorizedProxyReceived") this.setData({ form: { ...this.data.form, [field]: checked } });
            else if (field === "physicalChecked" || field === "noDisputeChecked") this.setData({ [field]: checked });
        },
        onDecisionReason(this: Context, event: Event) { if (!this.data.busy && !pending) this.setData({ decisionReason: String(event.detail.value) }); },
        onKind(this: Context, event: Event) {
            if (!this.data.busy && !pending) this.setData({ form: { ...this.data.form,
                evidenceKind: ["SIGNED_RECEIPT", "LOGISTICS_RECEIPT", "AUTHORIZED_PROXY"][Number(event.detail.value)] ?? "SIGNED_RECEIPT" } });
        },
        onCollection(this: Context, event: Event) {
            if (!this.data.busy && !pending) this.setData({ form: { ...this.data.form,
                collectionMethod: ["CODE_CONFIRMED", "SCAN_CONFIRMED", "MANUAL_RECORD"][Number(event.detail.value)] ?? "CODE_CONFIRMED" } });
        },
        async onCreate(this: Context) {
            if (!services || this.data.busy || pending || !this.data.snapshot || !this.data.canExecute) return;
            const f = this.data.form, { actualDate, actualTime, ...proof } = f, received = new Date(`${actualDate}T${actualTime}:00`);
            if (!f.actualSigner.trim() || !f.cannotConfirmReason.trim() || !f.contactRecord.trim()
                || !f.targetOrAuthorizedProxyReceived || !Number.isFinite(received.getTime())
                || f.delegatedReceipt && (!f.delegationRelationship.trim() || !f.delegationBasis.trim())
                || f.collectionMethod === "MANUAL_RECORD" && !f.manualReason.trim()) {
                this.setData({ error: "请填写真实签收人、日期时间、原因、联系记录和必要授权，并确认无接收争议。" }); return;
            }
            const snapshot = this.data.snapshot;
            pending = { kind: "json", path: base, key: services.createKey(), version: 0,
                body: { ...proof, actualReceivedAt: received.toISOString(), actualSignerEmployeeId: f.delegatedReceipt ? null : snapshot.targetEmployeeId,
                    businessId: snapshot.businessId, itemId: snapshot.itemId, snapshot,
                    previousConfirmationId: previousId || null } };
            await perform(this);
        },
        async onPickFile(this: Context) {
            if (!services?.chooseEvidence || this.data.busy || pending || this.data.selected?.confirmationStatus !== "DRAFT") return;
            const current = generation, owner = identity, proposal = this.data.selected.id, selection = pickerEpoch;
            this.setData({ busy: true, error: "" });
            try {
                const chosen = await services.chooseEvidence();
                if (selection !== pickerEpoch || owner !== identity || proposal !== this.data.selected?.id) return;
                const limit = /\.(png|jpe?g|webp)$/i.test(chosen.name) ? 10 : 20;
                if (!chosen.path || chosen.size <= 0 || chosen.size > limit * 1024 * 1024) throw new MiniRequestError(413, "ATTACHMENT_SIZE_INVALID");
                file = chosen; fileOwner = owner; fileProposal = proposal;
                if (live) this.setData({ fileName: chosen.name });
            } catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onUpload(this: Context) {
            if (!services || this.data.busy || pending || !file || !this.data.selected || this.data.selected.confirmationStatus !== "DRAFT" || this.data.selected.creatorAccountId !== this.data.accountId) return;
            pending = { kind: "file", path: `${base}/${this.data.selected.id}/attachments`, key: services.createKey(),
                version: this.data.selected.version, filePath: file.path };
            await perform(this);
        },
        async onSubmit(this: Context) {
            if (!services || this.data.busy || pending || !this.data.selected || this.data.selected.confirmationStatus !== "DRAFT" || this.data.selected.creatorAccountId !== this.data.accountId || !this.data.attachments.some(row => row.scanStatus === "CLEAN")) return;
            pending = { kind: "json", path: `${base}/${this.data.selected.id}/submit`, key: services.createKey(), version: this.data.selected.version };
            await perform(this);
        },
        async onDecision(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !this.data.selected || !this.data.canApprove || this.data.selected.confirmationStatus !== "SUBMITTED" || !this.data.decisionReason.trim()) return;
            const approved = event.currentTarget.dataset.approved === "true";
            if (approved && (!this.data.physicalChecked || !this.data.noDisputeChecked)) {
                this.setData({ error: "批准前请下载核验凭证，并确认无实物或责任争议。" }); return;
            }
            pending = { kind: "json", path: `${base}/${this.data.selected.id}/decision`, key: services.createKey(), version: this.data.selected.version,
                body: { approved, reason: this.data.decisionReason.trim(), physicalEvidenceChecked: this.data.physicalChecked, noDisputeChecked: this.data.noDisputeChecked } };
            await perform(this);
        },
        async onExecute(this: Context) {
            if (!services || this.data.busy || pending || !this.data.selected || !this.data.canExecute || this.data.selected.confirmationStatus !== "APPROVED") return;
            const current = generation; this.setData({ busy: true, error: "" });
            try {
                if (!await services.confirm("执行已批准代确认", "已按批准凭证核验真实签收；执行后正式责任生效并通知原目标责任人员，是否继续？")) return;
                if (!live || current !== generation) return;
                pending = { kind: "json", path: `${base}/${this.data.selected.id}/execute`, key: services.createKey(), version: this.data.selected.version };
            } catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
            if (live && current === generation) await perform(this);
        },
        async onDownload(this: Context, event: Event) {
            if (!services || this.data.busy || pending || !event.currentTarget.dataset.id) return;
            const current = generation; this.setData({ busy: true, error: "" });
            try {
                const grant = await services.sessions.request("POST", `/api/v1/mini/attachments/${event.currentTarget.dataset.id}/download-grant`) as { url: string };
                const path = await services.sessions.downloadEvidence(grant.url);
                if (live && current === generation) {
                    if (!services.openEvidence) throw new Error("Document viewer unavailable");
                    await services.openEvidence(path, this.data.attachments.find(row => row.objectId === event.currentTarget.dataset.id)?.filename);
                }
            } catch (error) { if (live && current === generation) failed(this, error); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onRetry(this: Context) { await perform(this); },
        async onNext(this: Context) { if (!this.data.busy && !pending && this.data.page * 20 < this.data.total) { this.setData({ page: this.data.page + 1 }); await this.onRefresh(); } },
        async onPrevious(this: Context) { if (!this.data.busy && !pending && this.data.page > 1) { this.setData({ page: this.data.page - 1 }); await this.onRefresh(); } },
        async onSourceNext(this: Context) { if (!this.data.busy && !pending && this.data.sourcePage * 20 < this.data.sourceTotal) { this.setData({ sourcePage: this.data.sourcePage + 1 }); await this.onSources(); } },
        async onSourcePrevious(this: Context) { if (!this.data.busy && !pending && this.data.sourcePage > 1) { this.setData({ sourcePage: this.data.sourcePage - 1 }); await this.onSources(); } },
    };
}
