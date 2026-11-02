import type { MiniServices } from "./pages.ts";
import { MiniRequestError, type FactoryEvidenceFile } from "./session.ts";
import type { FactoryDetail, FactoryRoundDetail } from "./repair-factory-types.ts";

type Kind = "category" | "organization" | "warehouse" | "location";
interface Reference { id: string; code: string; name: string; version: number; warehouse?: { id: string } }
interface Catalog { kind: Kind; label: string; page: number; total: number; items: Reference[]; selected: string }
interface Replacements { canRecord: boolean; items: { id: string; originalDisposition: string; replacementAssetId: string | null; inbound: { id: string; receiptNumber: string; status: string } | null }[] }
interface Intent { readonly path: string; readonly key: string; readonly version: number; readonly body?: string; readonly file?: FactoryEvidenceFile;
    readonly form?: Readonly<{ roundVersion: number; assetVersion: number; ownershipVersion: number; lockVersion: number; purpose: string }>; readonly label: string }
interface Data { header: FactoryDetail | null; round: FactoryRoundDetail | null; records: Replacements | null; references: Catalog[]; busy: boolean; stale: boolean; retry: boolean; confirmation: string; pendingLabel: string; error: string; notice: string;
    name: string; serial: string; model: string; unit: string; businessDate: string; actualDate: string; actualTime: string; reason: string; checked: boolean; disposition: string; evidenceIds: string[]; fileName: string;
    originals: { id: string; name: string }[]; dispositions: { id: string; name: string }[] }
interface Context { data: Data; setData(update: Partial<Data>): void }
const idPattern = /^[1-9][0-9]{0,18}$/;
const kinds: [Kind, string][] = [["category", "资产分类"], ["organization", "归属组织"], ["warehouse", "计划入库仓库"], ["location", "计划入库库位"]];
const dispositions = [{ id: "FACTORY_RETAINED", name: "厂家已收走旧机，责任待办理" }, { id: "FACTORY_DESTROYED", name: "厂家确认销毁旧机，待关联报废" }, { id: "CONTINUES_REPAIR", name: "旧机暂留维修，责任继续保留" }];
const blank = (): Data => ({ header: null, round: null, records: null, references: [], busy: false, stale: true, retry: false, confirmation: "", pendingLabel: "", error: "", notice: "", name: "", serial: "", model: "", unit: "", businessDate: "", actualDate: "", actualTime: "", reason: "", checked: false, disposition: "", evidenceIds: [], originals: [], dispositions: [], fileName: "" });
const day = (value: Date) => [value.getFullYear(), String(value.getMonth() + 1).padStart(2, "0"), String(value.getDate()).padStart(2, "0")].join("-");
const clock = (value: Date) => [value.getHours(), value.getMinutes(), value.getSeconds()].map(x => String(x).padStart(2, "0")).join(":");
function actualTime(date: string, time: string) {
    const value = new Date(date + "T" + time);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}:\d{2}$/.test(time) || !Number.isFinite(value.getTime()) || day(value) !== date || clock(value) !== time) throw new Error("Invalid physical time");
    return value.toISOString();
}

/** Independent replacement creator; unknown outcomes retain the exact intent through hide/show. */
export function createRepairReplacementPage(getServices: () => MiniServices) {
    let services: MiniServices, repairId = "", roundId = "", identity = "", live = false, generation = 0;
    let pending: Intent | null = null, prepared: Intent | null = null, instant = "", chosen: { path: string; name: string; size: number } | null = null;
    const owner = () => JSON.stringify([services.sessions.identity, services.sessions.identityRevision]);
    const current = (epoch: number) => live && epoch === generation && identity === owner();
    const base = () => "/api/v1/mini/repairs/" + repairId + "/factory";
    const route = (suffix: string) => base() + "/rounds/" + roundId + suffix;
    const discard = (file?: FactoryEvidenceFile) => { if (file) services.sessions.discardFactoryEvidence(file.path); };
    const wipe = (page: Context) => { const files = new Set([pending?.file, prepared?.file]); pending = null; prepared = null; chosen = null; instant = ""; page.setData(blank()); for (const file of files) discard(file); };
    const ensure = (page: Context) => { if (!live) return false; if (identity === owner()) return true; try { wipe(page); } finally { page.setData({ error: "身份已变化，请重新进入；原办理资料已隐藏。" }); } return false; };
    const editable = (page: Context) => ensure(page) && !page.data.busy && !page.data.stale && !pending && !prepared && !!page.data.records?.canRecord;
    const failure = (page: Context, error: unknown) => {
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) { try { wipe(page); } finally { page.setData({ error: "身份、处理权或记录已失效，原资料已隐藏。" }); } }
        else if (error instanceof MiniRequestError && [400, 409, 413, 422].includes(error.status)) { const file = pending?.file; pending = null; try { discard(file); } finally { page.setData({ retry: false, stale: true, error: "办理已拒绝，请刷新核对新旧机身份、版本、入库目标和原件后重新填写。" }); } }
        else page.setData({ stale: true, retry: !!pending, error: pending ? "结果尚未确认，请只读核对后用原操作重试。" : "读取或原件处理未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => { if (!live || epoch !== generation) return; if (identity !== owner()) { try { wipe(page); } finally { page.setData({ error: "身份已变化，资料已隐藏。" }); } } else page.setData({ busy: false }); };
    const readState = async (page: Context, epoch: number) => {
        const header = await services.sessions.request("GET", base()) as FactoryDetail;
        if (!current(epoch)) return false;
        const round = await services.sessions.request("GET", route("")) as FactoryRoundDetail;
        if (!current(epoch)) return false;
        const records = await services.sessions.request("GET", route("/replacements")) as Replacements;
        if (!current(epoch)) return false;
        const references: Catalog[] = [];
        if (records.canRecord) for (const [kind, label] of kinds) {
            const result = await services.sessions.request("GET", route("/replacement-references/" + kind + "?page=1&pageSize=20")) as { page: number; total: number; items: Reference[] };
            if (!current(epoch)) return false;
            references.push({ kind, label, ...result, selected: "" });
        }
        const clean = new Map(round.files.filter(file => file.scanStatus === "CLEAN").map(file => [file.objectId, file.filename]));
        page.setData({ header, round, records, references, stale: false, retry: !!pending, pendingLabel: pending?.label ?? "",
            originals: round.evidence.filter(item => item.purpose === "REPLACEMENT" && clean.has(item.object_id)).map(item => ({ id: item.object_id, name: clean.get(item.object_id)! })),
            dispositions: round.roundStatus === "RECEIVED" ? dispositions.filter(item => item.id === "CONTINUES_REPAIR") : dispositions });
        return true;
    };
    const read = async (page: Context) => {
        if (!ensure(page) || page.data.busy || prepared) return;
        const epoch = ++generation; chosen = null; instant = ""; page.setData({ ...blank(), busy: true, retry: !!pending });
        try { if (!repairId || !roundId || !services.sessions.identity) throw new MiniRequestError(401, "AUTH_SESSION_INVALID"); await readState(page, epoch); }
        catch (error) { if (current(epoch)) failure(page, error); } finally { finish(page, epoch); }
    };
    const send = async (page: Context) => {
        if (!ensure(page) || page.data.busy || !pending) return;
        const operation = pending, epoch = ++generation; page.setData({ busy: true, error: "" });
        try {
            if (operation.file && operation.form) await services.sessions.uploadFactoryEvidence(operation.path, operation.file, { key: operation.key, version: operation.version, ...operation.form });
            else await services.sessions.request("POST", operation.path, JSON.parse(operation.body!), operation);
            if (!current(epoch) || !await readState(page, epoch) || !current(epoch)) return;
            pending = null; chosen = null; instant = "";
            const saved = { header: page.data.header, round: page.data.round, records: page.data.records, references: page.data.references, originals: page.data.originals, dispositions: page.data.dispositions };
            page.setData({ ...blank(), ...saved, stale: false, notice: "本次资料已保存。新机仍须独立提交、批准和实际验收入库；旧机责任继续保留。" }); discard(operation.file);
        } catch (error) { if (current(epoch)) failure(page, error); } finally { finish(page, epoch); }
    };
    const prepare = (page: Context, upload: boolean) => {
        if (!editable(page) || !page.data.header?.photosAvailable || !page.data.round || page.data.header.lockVersion === null) return;
        const header = page.data.header, round = page.data.round;
        const guards = { roundVersion: round.version, assetVersion: header.assetVersion, ownershipVersion: header.ownershipVersion, lockVersion: header.lockVersion! };
        let file: FactoryEvidenceFile | undefined;
        try {
            let body: unknown, form: Intent["form"], path: string, label: string;
            if (upload) { if (!chosen) throw new Error("Missing original"); file = services.sessions.stageFactoryEvidence(chosen); form = Object.freeze({ ...guards, purpose: "REPLACEMENT" }); path = route("/evidence"); label = "追加厂家换机原件：" + file.name; }
            else {
                const data = page.data, reference = (kind: Kind) => { const catalog = data.references.find(item => item.kind === kind); return catalog?.items.find(item => item.id === catalog.selected); };
                const category = reference("category"), organization = reference("organization"), warehouse = reference("warehouse"), location = reference("location");
                const observed = actualTime(data.actualDate, data.actualTime); actualTime(data.businessDate, "00:00:00");
                const selected = [...data.evidenceIds], allowed = new Set(data.originals.map(item => item.id));
                if (!data.name.trim() || !data.serial.trim() || !data.model.trim() || !data.unit.trim() || data.reason.trim().length < 10 || !data.checked || !data.dispositions.some(item => item.id === data.disposition)
                    || !category || !organization || !warehouse || !location || location.warehouse?.id !== warehouse.id || !selected.length || selected.length > 14 || !selected.every(item => allowed.has(item))) throw new Error("Incomplete replacement");
                const ref = (item: Reference) => ({ id: item.id, version: item.version });
                body = { ...guards, originalDisposition: data.disposition, observedAt: instant && day(new Date(instant)) === data.actualDate && clock(new Date(instant)) === data.actualTime ? instant : observed,
                    evidenceIds: selected, reason: data.reason.trim(), replacementPhysicalIdentityChecked: true, newAsset: { name: data.name.trim(), serialNumber: data.serial.trim(), model: data.model.trim(), unit: data.unit.trim(), category: ref(category), organization: ref(organization) },
                    businessDate: data.businessDate, warehouseId: warehouse.id, warehouseVersion: warehouse.version, locationId: location.id, locationVersion: location.version };
                path = route("/replacements"); label = "为新机 " + data.name.trim() + "（" + data.serial.trim() + "）建立独立新卡及入库草稿。旧机：" + data.dispositions.find(item => item.id === data.disposition)!.name + "。新机还须独立提交、批准和实际验收入库。";
            }
            prepared = Object.freeze({ path, key: services.createKey(), version: header.version, body: body === undefined ? undefined : JSON.stringify(body), file, form, label }); page.setData({ confirmation: label, error: "" });
        } catch { try { discard(file); } finally { page.setData({ error: "请核对新实物资料、真实日期时刻、旧机去向、入库目标和本轮厂家换机原件；图片10MB、PDF20MB以内。" }); } }
    };
    return {
        data: blank(),
        onLoad(options: { id?: string; round?: string }) { repairId = idPattern.test(options.id ?? "") ? options.id! : ""; roundId = idPattern.test(options.round ?? "") ? options.round! : ""; },
        async onShow(this: Context) { live = true; const next = getServices(); if (services && identity !== JSON.stringify([next.sessions.identity, next.sessions.identityRevision])) { try { wipe(this); } catch { this.setData(blank()); } } services = next; identity = owner(); await read(this); },
        onHide(this: Context) { live = false; generation++; chosen = null; instant = ""; const file = prepared?.file; prepared = null; try { discard(file); } finally { this.setData(blank()); } },
        onUnload(this: Context) { live = false; generation++; try { wipe(this); } finally { repairId = ""; roundId = ""; } },
        async onRefresh(this: Context) { await read(this); },
        onField(this: Context, event: { currentTarget: { dataset: { field: string } }; detail: { value: string } }) { const field = event.currentTarget.dataset.field; if (editable(this) && ["name", "serial", "model", "unit", "businessDate", "actualDate", "actualTime", "reason"].includes(field)) { if (["actualDate", "actualTime"].includes(field)) instant = ""; this.setData({ [field]: event.detail.value }); } },
        onNow(this: Context) { if (editable(this)) { const now = new Date(); instant = now.toISOString(); this.setData({ actualDate: day(now), actualTime: clock(now) }); } },
        onChecked(this: Context, event: { detail: { value: string[] } }) { if (editable(this)) this.setData({ checked: event.detail.value.includes("checked") }); },
        onEvidence(this: Context, event: { detail: { value: string[] } }) { if (editable(this)) this.setData({ evidenceIds: event.detail.value.filter(id => this.data.originals.some(item => item.id === id)) }); },
        onDisposition(this: Context, event: { currentTarget: { dataset: { id: string } } }) { const id = event.currentTarget.dataset.id; if (editable(this) && this.data.dispositions.some(item => item.id === id)) this.setData({ disposition: id }); },
        onReference(this: Context, event: { currentTarget: { dataset: { kind: Kind; id: string } } }) { if (!editable(this)) return; const { kind, id } = event.currentTarget.dataset, warehouse = this.data.references.find(item => item.kind === "warehouse")?.selected; const catalog = this.data.references.find(item => item.kind === kind), item = catalog?.items.find(item => item.id === id); if (!item || kind === "location" && item.warehouse?.id !== warehouse) return; this.setData({ references: this.data.references.map(item => ({ ...item, selected: item.kind === kind ? id : kind === "warehouse" && item.kind === "location" ? "" : item.selected })) }); },
        async onReferences(this: Context, event: { currentTarget: { dataset: { kind: Kind; delta: string } } }) { if (!editable(this)) return; const { kind, delta } = event.currentTarget.dataset, catalog = this.data.references.find(item => item.kind === kind), change = Number(delta), next = (catalog?.page ?? 1) + change; if (!catalog || ![-1, 1].includes(change) || next < 1 || (next - 1) * 20 >= catalog.total) return; const epoch = ++generation; this.setData({ busy: true }); try { const result = await services.sessions.request("GET", route("/replacement-references/" + kind + "?page=" + next + "&pageSize=20")) as Catalog; if (current(epoch)) this.setData({ references: this.data.references.map(item => item.kind === kind ? { ...catalog, ...result, selected: "" } : kind === "warehouse" && item.kind === "location" ? { ...item, selected: "" } : item) }); } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); } },
        async onChoose(this: Context) { if (!editable(this) || !services.chooseEvidence) return; const epoch = generation; this.setData({ busy: true }); try { const file = await services.chooseEvidence(); if (current(epoch)) { chosen = file; this.setData({ fileName: file.name }); } } catch (error) { if (current(epoch)) failure(this, error); } finally { finish(this, epoch); } },
        onPrepare(this: Context, event: { currentTarget: { dataset: { action: string } } }) { if (["create", "upload"].includes(event.currentTarget.dataset.action)) prepare(this, event.currentTarget.dataset.action === "upload"); },
        async onConfirm(this: Context) { if (!ensure(this) || this.data.busy || pending || !prepared) return; pending = prepared; prepared = null; this.setData({ confirmation: "", retry: true, pendingLabel: pending.label }); await send(this); },
        onCancel(this: Context) { if (!ensure(this) || this.data.busy || pending || !prepared) return; const file = prepared.file; prepared = null; try { discard(file); } finally { this.setData({ confirmation: "" }); } },
        async onRetry(this: Context) { await send(this); },
        onBack(this: Context) { if (!this.data.busy && !pending && !prepared) services.navigate("/pages/repair-factory/index?id=" + repairId); },
    };
}
