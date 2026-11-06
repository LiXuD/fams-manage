import type { MiniServices } from "./pages.ts";
import { MiniRequestError, type FactoryEvidenceFile } from "./session.ts";

interface Choice { id: string; name: string; version: number }
interface Asset extends Choice { assetCode: string }
interface Scrap {
    id: string; version: number; assetVersion: number; ownershipVersion: number; lockVersion: number | null;
    status: string; processorAccountId: string | null; reason: string; observedCondition: string; proposedDisposition: string;
    source: { assetCode: string; name: string; serialNumber: string };
    actions: { decide: boolean; claim: boolean; execute: boolean; dispose?: boolean };
    decision: { decision?: { approvedDisposition?: string } } | null;
    disposition: { disposition_status: string; due_date: string; version?: number;
        completion?: { occurredAt: string; method: string; recipient: string; operatorName: string } } | null;
}
type AttachmentKind = "request" | "disposition";
interface Attachment { objectId: string; filename: string; contentType: string; sizeBytes: number }
interface Capabilities { canRead: boolean; canUploadRequest: boolean; canUploadDisposition: boolean; requestVersion: number; dispositionVersion: number | null }
interface Intent { readonly path: string; readonly key: string; readonly version?: number; readonly body: string; readonly summary: string; readonly file?: FactoryEvidenceFile }
interface Pick { id: string; kind: AttachmentKind; owner: string; version: number; file?: { path: string; name: string; size: number } }
const attachmentKind = (kind: string): kind is AttachmentKind => kind === "request" || kind === "disposition";
const attachmentLabel = { request: "申请附件", disposition: "处置凭证" };
interface Data {
    rows: Scrap[]; detail: Scrap | null; assets: Asset[]; selectedAsset: string;
    page: number; total: number; assetPage: number; assetTotal: number;
    places: Choice[]; people: Choice[]; location: string; custodian: string;
    locationPage: number; locationTotal: number; peoplePage: number; peopleTotal: number;
    reason: string; condition: string; proposed: string; decisionReason: string; approved: string;
    observed: string; serial: string; actualDate: string; actualTime: string; physical: boolean; evidence: string; due: string;
    recipient: string; dispositionEvidence: string[];
    capabilities: Capabilities | null; requestFiles: Attachment[]; dispositionFiles: Attachment[];
    previewPath: string; previewName: string; previewMime: string;
    busy: boolean; stale: boolean; retry: boolean; confirmation: string; pendingSummary: string; error: string; notice: string;
}
interface Context { data: Data; setData(update: Partial<Data>): void }
const emptyAttachments = () => ({ capabilities: null, requestFiles: [], dispositionFiles: [],
    previewPath: "", previewName: "", previewMime: "" });
const blank = (): Data => ({
    ...emptyAttachments(), recipient: "", dispositionEvidence: [],
    rows: [], detail: null, assets: [], selectedAsset: "", page: 1, total: 0, assetPage: 1, assetTotal: 0,
    places: [], people: [], location: "", custodian: "", locationPage: 0, locationTotal: 0, peoplePage: 0, peopleTotal: 0,
    reason: "", condition: "", proposed: "", decisionReason: "", approved: "", observed: "", serial: "",
    actualDate: "", actualTime: "", physical: false, evidence: "", due: "", busy: false, stale: true, retry: false,
    confirmation: "", pendingSummary: "", error: "", notice: "",
});
const day = (value: Date) => [value.getFullYear(), String(value.getMonth() + 1).padStart(2, "0"), String(value.getDate()).padStart(2, "0")].join("-");
const time = (value: Date) => [value.getHours(), value.getMinutes(), value.getSeconds()].map(item => String(item).padStart(2, "0")).join(":");
export function createScrapPage(getServices: () => MiniServices) {
    const base = "/api/v1/mini/scrap-requests";
    let services: MiniServices, identity = "", selectedId = "", live = false, generation = 0, actualInstant = "";
    let pending: Intent | null = null, prepared: Intent | null = null, pick: Pick | null = null, previewPath = "";
    const owner = () => JSON.stringify([services.sessions.identity, services.sessions.identityRevision]);
    const current = (epoch: number) => live && generation === epoch && identity === owner();
    const discard = (page: Context, path?: string) => {
        if (!path) return;
        try { services.sessions.discardFactoryEvidence(path); }
        catch { if (live) page.setData({ error: "资料已隐藏，原件清理待重新进入后重试。" }); }
    };
    const closePreview = (page: Context) => {
        const path = previewPath; previewPath = "";
        page.setData({ previewPath: "", previewName: "", previewMime: "" });
        discard(page, path);
    };
    const wipe = (page: Context) => {
        const preparedFile = prepared?.file?.path, pendingFile = pending?.file?.path;
        generation++; selectedId = ""; pending = null; prepared = null; pick = null; actualInstant = "";
        page.setData(blank()); closePreview(page); discard(page, preparedFile); discard(page, pendingFile);
    };
    const ensure = (page: Context) => {
        if (!live) return false;
        if (owner() === identity) return true;
        wipe(page); page.setData({ error: "身份已变化，原资料已隐藏。" }); return false;
    };
    const editable = (page: Context) => ensure(page) && !page.data.busy && !page.data.stale && !pending && !prepared && !pick;
    const failure = (page: Context, error: unknown) => {
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) {
            wipe(page); page.setData({ error: "身份、权限或记录已失效。" });
        } else if (error instanceof MiniRequestError && error.status >= 400 && error.status < 500) {
            const file = pending?.file?.path; pending = null;
            page.setData({ stale: true, retry: false, pendingSummary: "", error: "资料或处理权已变化，请刷新后重新核对。" });
            discard(page, file);
        } else page.setData({ stale: true, retry: !!pending, error: pending ? "结果尚未确认，请刷新进度并用原操作重试。" : "读取未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => {
        if (!live || generation !== epoch) return;
        if (owner() !== identity) { wipe(page); page.setData({ error: "身份已变化，原资料已隐藏。" }); }
        else page.setData({ busy: false });
    };
    const readState = async (page: Context, epoch: number) => {
        const rows = await services.sessions.request("GET", base + "?page=" + page.data.page + "&pageSize=20") as { items: Scrap[]; total: number };
        if (!current(epoch)) return false;
        const detail = selectedId ? await services.sessions.request("GET", base + "/" + selectedId) as Scrap : null;
        if (!current(epoch)) return false;
        page.setData({ rows: rows.items, total: rows.total, detail, stale: false, retry: !!pending, pendingSummary: pending?.summary ?? "" });
        if (detail) await readAttachments(page, detail, epoch);
        return current(epoch);
    };
    const readAttachments = async (page: Context, row: Scrap, epoch: number) => {
        const path = base + "/" + row.id;
        try {
            const capabilities = await services.sessions.request("GET", path + "/attachment-capabilities") as Capabilities;
            if (!current(epoch)) return;
            const files = capabilities.canRead ? await Promise.all([
                services.sessions.request("GET", path + "/attachments/request") as Promise<Attachment[]>,
                row.disposition ? services.sessions.request("GET", path + "/attachments/disposition") as Promise<Attachment[]> : Promise.resolve([]),
            ]) : [[], []];
            if (current(epoch)) page.setData({ capabilities, requestFiles: files[0] ?? [], dispositionFiles: files[1] ?? [] });
        } catch (error) {
            if (!current(epoch)) return;
            if (error instanceof MiniRequestError && error.status === 401) throw error;
            if (!(error instanceof MiniRequestError && error.status === 404)) page.setData({ error: "附件暂不可读取，请刷新报废进度。" });
        }
    };
    const uploadVersion = (page: Context, kind: AttachmentKind): number | null => {
        const caps = page.data.capabilities;
        if (kind === "request") return caps?.canUploadRequest ? caps.requestVersion : null;
        return caps?.canUploadDisposition ? caps.dispositionVersion : null;
    };
    const preparePicked = (page: Context) => {
        if (!live || page.data.busy || !pick?.file) return;
        const chosen = pick, original = pick.file; pick = null;
        if (owner() !== chosen.owner || selectedId !== chosen.id || page.data.detail?.id !== chosen.id
            || page.data.stale || uploadVersion(page, chosen.kind) !== chosen.version) {
            page.setData({ error: "报废单据或权限已变化，请刷新并重新选择附件。" }); return;
        }
        let file: FactoryEvidenceFile | undefined;
        try {
            file = services.sessions.stageFactoryEvidence(original);
            const summary = "上传" + attachmentLabel[chosen.kind] + "「" + file.name + "」。";
            prepared = Object.freeze({ path: base + "/" + chosen.id + "/attachments/" + chosen.kind,
                key: services.createKey(), version: chosen.version, body: "{}", file, summary });
            page.setData({ confirmation: summary, error: "" });
        } catch {
            discard(page, file?.path);
            page.setData({ error: "请选择有效的图片或 PDF。图片最多 10 MB，PDF 最多 20 MB。" });
        }
    };
    const read = async (page: Context) => {
        if (!ensure(page) || page.data.busy || prepared) return;
        const paging = { page: page.data.page }, epoch = ++generation;
        actualInstant = ""; page.setData({ ...blank(), ...paging, busy: true, retry: !!pending }); closePreview(page);
        try { await readState(page, epoch); } catch (error) { if (current(epoch)) failure(page, error); }
        finally { finish(page, epoch); }
    };
    const send = async (page: Context) => {
        if (!ensure(page) || !pending || page.data.busy) return;
        const operation = pending, epoch = ++generation; page.setData({ busy: true, error: "" });
        closePreview(page); page.setData(emptyAttachments());
        try {
            const mutation = { key: operation.key, version: operation.version ?? 0 };
            const result = (operation.file ? await services.sessions.uploadScrapEvidence(operation.path, operation.file, mutation)
                : await services.sessions.request("POST", operation.path, JSON.parse(operation.body) as unknown, mutation)) as { id: string };
            if (!current(epoch)) return;
            selectedId = result.id;
            if (!await readState(page, epoch)) return;
            const saved = { rows: page.data.rows, total: page.data.total, detail: page.data.detail, page: page.data.page,
                capabilities: page.data.capabilities, requestFiles: page.data.requestFiles, dispositionFiles: page.data.dispositionFiles };
            pending = null; actualInstant = "";
            page.setData({ ...blank(), ...saved, stale: false, busy: true, notice: "本次办理已保存，请核对当前进度。" });
            discard(page, operation.file?.path);
        } catch (error) { if (current(epoch)) failure(page, error); } finally { finish(page, epoch); }
    };
    const prepare = (page: Context, action: string) => {
        if (!editable(page) || !["submit", "approve", "reject", "claim", "execute"].includes(action)) return;
        const data = page.data, row = data.detail;
        let path = base, body: Record<string, unknown>, version: number | undefined, summary: string;
        try {
            if (action === "submit") {
                const asset = data.assets.find(item => item.id === data.selectedAsset);
                if (!asset || !data.reason.trim() || !data.condition.trim() || !data.proposed.trim()) throw Error();
                body = { assetId: asset.id, assetVersion: asset.version, reason: data.reason.trim(),
                    observedCondition: data.condition.trim(), proposedDisposition: data.proposed.trim() };
                summary = "提交 " + asset.assetCode + " 报废申请，等待管理员确认。";
            } else {
                const capability = action === "approve" || action === "reject" ? "decide" : action;
                if (!row || row.lockVersion == null || !["decide", "claim", "execute"].includes(capability)
                    || !row.actions[capability as keyof Scrap["actions"]]) return;
                path += "/" + row.id + "/" + (capability === "decide" ? "decision" : action);
                version = row.version; body = { assetVersion: row.assetVersion, lockVersion: row.lockVersion };
                if (capability === "decide") {
                    if (!data.decisionReason.trim() || action === "approve" && !data.approved.trim()) throw Error();
                    body = { ...body, result: action === "approve" ? "APPROVE" : "REJECT", reason: data.decisionReason.trim(),
                        ...(action === "approve" ? { approvedDisposition: data.approved.trim() } : {}) };
                    summary = action === "approve" ? "批准处置范围 " + data.approved.trim() + "，仍须库管实物核验。" : "驳回报废并核对原保管关系后恢复。";
                } else if (action === "claim") summary = "取得本单唯一处理权，核验实物后执行。";
                else {
                    const place = data.places.find(item => item.id === data.location), person = data.people.find(item => item.id === data.custodian);
                    const actual = new Date(data.actualDate + "T" + data.actualTime), method = row.decision?.decision?.approvedDisposition;
                    if (!place || !person || !data.physical || !data.observed.trim() || row.source.serialNumber && !data.serial.trim()
                        || data.evidence.trim().length < 10 || !method || !Number.isFinite(actual.getTime())
                        || day(actual) !== data.actualDate || time(actual) !== data.actualTime) throw Error();
                    body = { ...body, ownershipVersion: row.ownershipVersion, observedCode: data.observed.trim(), observedSerial: data.serial.trim(),
                        locationId: place.id, locationVersion: place.version, custodianEmployeeId: person.id, custodianVersion: person.version,
                        occurredAt: actualInstant || actual.toISOString(), physicalIdentityVerified: true, evidence: data.evidence.trim(),
                        dispositionMethod: method, ...(data.due ? { plannedDisposalDate: data.due } : {}) };
                    summary = "执行 " + row.source.assetCode + " 报废，实物在 " + place.name + " 由 " + person.name + " 保管，建立独立待处置记录。";
                }
            }
            prepared = Object.freeze({ path, key: services.createKey(), version, body: JSON.stringify(body), summary });
            page.setData({ confirmation: summary, error: "" });
        } catch { page.setData({ error: "请核对资产、原因、实际时间和原机事实，明确位置、处置保管人和依据。" }); }
    };
    return {
        data: blank(),
        onLoad(options: { id?: string }) { selectedId = /^[1-9][0-9]{0,18}$/.test(options.id ?? "") ? options.id! : ""; },
        async onShow(this: Context) {
            live = true; const next = getServices();
            if (services && identity !== JSON.stringify([next.sessions.identity, next.sessions.identityRevision])) wipe(this);
            services = next; identity = owner(); await read(this); preparePicked(this);
        },
        onHide(this: Context) {
            live = false; generation++; const file = prepared?.file?.path;
            prepared = null; actualInstant = ""; this.setData(blank()); closePreview(this); discard(this, file);
        },
        onUnload(this: Context) { live = false; wipe(this); },
        async onRefresh(this: Context) { await read(this); },
        async onOpen(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            if (!editable(this) || !this.data.rows.some(row => row.id === event.currentTarget.dataset.id)) return;
            selectedId = event.currentTarget.dataset.id; await read(this);
        },
        async onPage(this: Context, event: { currentTarget: { dataset: { delta: string } } }) {
            const next = this.data.page + Number(event.currentTarget.dataset.delta);
            if (!editable(this) || !Number.isInteger(next) || next < 1 || (next - 1) * 20 >= this.data.total) return;
            selectedId = ""; this.setData({ page: next }); await read(this);
        },
        async onAssets(this: Context, event?: { currentTarget: { dataset: { delta?: string } } }) {
            if (!editable(this)) return;
            const delta = Number(event?.currentTarget.dataset.delta ?? 0), next = delta ? this.data.assetPage + delta : 1;
            if (!Number.isInteger(next) || next < 1 || delta > 0 && (next - 1) * 20 >= this.data.assetTotal) return;
            const epoch = ++generation; this.setData({ busy: true });
            try {
                const result = await services.sessions.request("GET", base + "/eligible?page=" + next + "&pageSize=20") as { items: Asset[]; total: number };
                if (current(epoch)) this.setData({ assets: result.items, assetPage: next, assetTotal: result.total, selectedAsset: "" });
            } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); }
        },
        onAsset(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            if (editable(this) && this.data.assets.some(item => item.id === event.currentTarget.dataset.id)) this.setData({ selectedAsset: event.currentTarget.dataset.id });
        },
        onField(this: Context, event: { currentTarget: { dataset: { field: string } }; detail: { value: string } }) {
            const field = event.currentTarget.dataset.field;
            if (!editable(this) || !["reason", "condition", "proposed", "decisionReason", "approved", "observed", "serial", "actualDate", "actualTime", "evidence", "due", "recipient"].includes(field)) return;
            if (["actualDate", "actualTime"].includes(field)) actualInstant = "";
            this.setData({ [field]: event.detail.value });
        },
        onChecked(this: Context, event: { detail: { value: boolean } }) { if (editable(this)) this.setData({ physical: event.detail.value }); },
        onNow(this: Context) {
            if (!editable(this)) return;
            const actual = new Date(); actualInstant = actual.toISOString();
            this.setData({ actualDate: day(actual), actualTime: time(actual) });
        },
        async onCatalog(this: Context, event: { currentTarget: { dataset: { kind: string } } }) {
            const kind = event.currentTarget.dataset.kind;
            if (!editable(this) || !["location", "employee"].includes(kind)
                || !(this.data.detail?.actions.execute || kind === "employee" && this.data.detail?.actions.dispose)) return;
            const next = (kind === "location" ? this.data.locationPage : this.data.peoplePage) + 1, epoch = ++generation;
            this.setData({ busy: true });
            try {
                const result = await services.sessions.request("GET", base + "/" + this.data.detail.id
                    + "/execution-catalog/" + kind + "?page=" + next + "&pageSize=100") as { items: Choice[]; total: number };
                if (!current(epoch)) return;
                this.setData(kind === "location" ? { places: [...this.data.places, ...result.items], locationPage: next, locationTotal: result.total }
                    : { people: [...this.data.people, ...result.items], peoplePage: next, peopleTotal: result.total });
            } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); }
        },
        onChoice(this: Context, event: { currentTarget: { dataset: { kind: string } }; detail: { value: string } }) {
            if (!editable(this)) return;
            const index = Number(event.detail.value), kind = event.currentTarget.dataset.kind;
            const row = (kind === "location" ? this.data.places : this.data.people)[index];
            if (row && ["location", "employee"].includes(kind)) this.setData(kind === "location" ? { location: row.id } : { custodian: row.id });
        },
        async onPickAttachment(this: Context, event: { currentTarget: { dataset: { kind: string } } }) {
            const kind = event.currentTarget.dataset.kind, row = this.data.detail;
            if (!editable(this) || !row || !attachmentKind(kind) || !services.chooseEvidence) return;
            const version = uploadVersion(this, kind);
            if (version == null) return;
            const ticket: Pick = { id: row.id, kind, owner: identity, version }, epoch = generation;
            pick = ticket; this.setData({ busy: true, error: "" });
            try {
                const file = await services.chooseEvidence();
                if (pick === ticket && owner() === ticket.owner) ticket.file = file;
            } catch { if (pick === ticket) pick = null; }
            finally { finish(this, epoch); }
            preparePicked(this);
        },
        async onReadAttachment(this: Context, event: { currentTarget: { dataset: { kind: string; id: string } } }) {
            const { kind, id } = event.currentTarget.dataset, row = this.data.detail;
            if (!editable(this) || !row || !this.data.capabilities?.canRead || !attachmentKind(kind)
                || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id)) return;
            const file = (kind === "request" ? this.data.requestFiles : this.data.dispositionFiles).find(item => item.objectId === id);
            if (!file) return;
            const path = base + "/" + row.id + "/attachments/" + kind + "/" + id, epoch = ++generation;
            this.setData({ busy: true, error: "" }); closePreview(this);
            try {
                const grant = await services.sessions.request("POST", path + "/download-grant") as { url: string };
                if (!current(epoch)) return;
                if (typeof grant.url !== "string" || !grant.url.startsWith(path + "/content?grant=")) throw new Error("Invalid grant scope");
                const privatePath = await services.sessions.downloadScrapEvidence(grant.url, file.contentType, file.sizeBytes);
                if (!current(epoch)) { discard(this, privatePath); return; }
                previewPath = privatePath;
                this.setData({ previewPath, previewName: file.filename, previewMime: file.contentType });
            } catch (error) {
                if (current(epoch)) {
                    this.setData(emptyAttachments());
                    if (error instanceof MiniRequestError && error.status === 401) failure(this, error);
                    else this.setData({ error: "附件读取未完成，请刷新并核对访问权限。" });
                }
            } finally { finish(this, epoch); }
        },
        onCloseAttachment(this: Context) { if (ensure(this) && !this.data.busy) closePreview(this); },
        async onOpenAttachment(this: Context) {
            if (!editable(this) || !previewPath || !services.openEvidence) return;
            const epoch = generation;
            this.setData({ busy: true });
            try { await services.openEvidence(previewPath, this.data.previewName); }
            catch { if (current(epoch)) this.setData({ error: "原件未打开，请重试。" }); }
            finally { finish(this, epoch); }
        },
        onDispositionEvidence(this: Context, event: { detail: { value: string[] } }) {
            if (!editable(this) || !this.data.detail?.actions.dispose || !this.data.capabilities?.canRead) return;
            this.setData({ dispositionEvidence: event.detail.value.filter(id => this.data.dispositionFiles.some(file => file.objectId === id)) });
        },
        onPrepareDisposition(this: Context) {
            const data = this.data, row = data.detail;
            if (!editable(this) || !row?.actions.dispose || !data.capabilities?.canRead || row.disposition?.version == null) return;
            const person = data.people.find(item => item.id === data.custodian), method = row.decision?.decision?.approvedDisposition;
            const actual = new Date(data.actualDate + "T" + data.actualTime);
            if (!person || !method || !data.recipient.trim() || !data.observed.trim() || row.source.serialNumber && !data.serial.trim()
                || !data.dispositionEvidence.length || !Number.isFinite(actual.getTime())
                || day(actual) !== data.actualDate || time(actual) !== data.actualTime) {
                this.setData({ error: "请核对原机、实际处置时间、接收或处置对象、经办人及凭证。" }); return;
            }
            const body = { requestVersion: row.version, ownershipVersion: row.ownershipVersion, observedCode: data.observed.trim(),
                observedSerial: data.serial.trim(), occurredAt: actualInstant || actual.toISOString(), method, recipient: data.recipient.trim(),
                operatorEmployeeId: person.id, operatorVersion: person.version, evidenceObjectIds: data.dispositionEvidence };
            const summary = "确认 " + row.source.assetCode + " 已实际处置。处置对象 " + body.recipient
                + "，经办人 " + person.name + "，凭证 " + data.dispositionEvidence.length + " 份。";
            prepared = Object.freeze({ path: base + "/" + row.id + "/disposition/complete", key: services.createKey(),
                version: row.disposition.version, body: JSON.stringify(body), summary });
            this.setData({ confirmation: summary, error: "" });
        },
        onPrepare(this: Context, event: { currentTarget: { dataset: { action: string } } }) { prepare(this, event.currentTarget.dataset.action); },
        async onConfirm(this: Context) {
            if (!ensure(this) || !prepared || pending || this.data.busy) return;
            pending = prepared; prepared = null; this.setData({ confirmation: "", retry: true, pendingSummary: pending.summary }); await send(this);
        },
        onCancel(this: Context) {
            if (!ensure(this) || pending || this.data.busy) return;
            const file = prepared?.file?.path; prepared = null;
            this.setData({ confirmation: "" }); discard(this, file);
        },
        async onRetry(this: Context) { await send(this); },
    };
}
