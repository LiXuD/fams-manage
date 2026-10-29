import type { MiniServices } from "./pages.ts";
import { MiniRequestError, type FactoryEvidenceFile } from "./session.ts";
interface Asset { id: string; assetCode: string; name: string; version: number }
interface Photo { objectId: string; filename: string; contentType: string }
interface Report { id: string; source: Asset; status: string; version: number; assetVersion: number; ownershipVersion: number; lockVersion: number;
    lastSeenAt: string; lastLocation: string; circumstances: string; processorAccountId: string | null; nextReviewDate: string | null;
    actions?: Record<string, boolean>; photos?: Photo[]; history?: { id: string; action: string; actor_username: string; occurred_at: string }[] }
interface Intent { readonly path: string; readonly key: string; readonly version: number; readonly body?: string; readonly file?: FactoryEvidenceFile; readonly label: string }
interface Data { rows: Report[]; assets: Asset[]; detail: Report | null; page: number; total: number; assetPage: number; assetTotal: number; selectedAsset: string;
    busy: boolean; stale: boolean; retry: boolean; confirmation: string; pendingLabel: string; error: string; notice: string; lastDate: string; lastTime: string; lastLocation: string; circumstances: string;
    reason: string; custodianVerification: string; searchedScope: string; searchDate: string; searchTime: string; nextReviewDate: string; photoPath: string; photoName: string }
interface Context { data: Data; setData(update: Partial<Data>): void }
const blank = (): Data => ({ rows: [], assets: [], detail: null, page: 1, total: 0, assetPage: 1, assetTotal: 0, selectedAsset: "", busy: false, stale: true, retry: false, confirmation: "", pendingLabel: "", error: "", notice: "", lastDate: "", lastTime: "", lastLocation: "", circumstances: "", reason: "", custodianVerification: "", searchedScope: "", searchDate: "", searchTime: "", nextReviewDate: "", photoPath: "", photoName: "" });
const day = (date: Date) => [date.getFullYear(), String(date.getMonth() + 1).padStart(2, "0"), String(date.getDate()).padStart(2, "0")].join("-");
const time = (date: Date) => [date.getHours(), date.getMinutes()].map(value => String(value).padStart(2, "0")).join(":");
function actual(date: string, clock: string, original?: string) {
    const parsed = new Date(date + "T" + clock);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(clock) || !Number.isFinite(parsed.getTime()) || day(parsed) !== date || time(parsed) !== clock) throw new Error("Invalid actual time");
    if (original && day(new Date(original)) === date && time(new Date(original)) === clock) return original;
    return parsed.toISOString();
}

/** Unknown outcomes retain immutable requests and identity-bound file copies through hide/show. */
export function createLossPage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", live = false, generation = 0, selectedId = "", photo = "";
    let pending: Intent | null = null, prepared: Intent | null = null;
    const instants: Record<string, string> = {};
    const base = "/api/v1/mini/loss-reports";
    const owner = () => JSON.stringify([services.sessions.identity, services.sessions.identityRevision]);
    const current = (epoch: number) => live && generation === epoch && identity === owner();
    const discard = (file?: FactoryEvidenceFile) => { if (file) services.sessions.discardFactoryEvidence(file.path); };
    const clearPhoto = () => { if (photo) { const old = photo; photo = ""; services.sessions.discardInventoryPhoto(old); } };
    const wipe = (page: Context) => { const files = new Set([pending?.file, prepared?.file]); pending = null; prepared = null; selectedId = ""; clearPhoto(); page.setData(blank()); for (const file of files) discard(file); };
    const ensure = (page: Context) => { if (!live) return false; if (owner() === identity) return true; wipe(page); page.setData({ error: "身份已变化，原报失资料已隐藏。" }); return false; };
    const editable = (page: Context) => ensure(page) && !page.data.busy && !page.data.stale && !pending && !prepared;
    const failure = (page: Context, error: unknown) => {
        if (error instanceof MiniRequestError && [401,403,404].includes(error.status)) { wipe(page); page.setData({ error: "身份、权限或记录失效，原资料已隐藏。" }); }
        else if (error instanceof MiniRequestError && [400,409,413,422].includes(error.status)) { const file = pending?.file; pending = null; discard(file); page.setData({ stale: true, retry: false, pendingLabel: "", error: error.code === "LOSS_PHOTO_REQUIRED" ? "批准前须由报告人上传本次情况图片。" : "办理被拒绝，请刷新核对版本、处理权、实际日期及原件。" }); }
        else page.setData({ stale: true, retry: !!pending, error: pending ? "结果尚未确认，请只读刷新后使用原操作重试。" : "读取或原件处理未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => { if (!live || generation !== epoch) return; if (owner() !== identity) { wipe(page); page.setData({ error: "身份已变化，原资料已隐藏。" }); } else page.setData({ busy: false }); };
    const readState = async (page: Context, epoch: number) => {
        const rows = await services.sessions.request("GET", base + `?page=${page.data.page}&pageSize=20`) as { items: Report[]; total: number };
        if (!current(epoch)) return false;
        const assets = await services.sessions.request("GET", base + `/eligible?page=${page.data.assetPage}&pageSize=20`) as { items: Asset[]; total: number };
        if (!current(epoch)) return false;
        const detail = selectedId ? await services.sessions.request("GET", base + "/" + selectedId) as Report : null;
        if (!current(epoch)) return false;
        page.setData({ rows: rows.items, total: rows.total, assets: assets.items, assetTotal: assets.total, detail, stale: false, retry: !!pending, pendingLabel: pending?.label ?? "" }); return true;
    };
    const read = async (page: Context) => {
        if (!ensure(page) || page.data.busy || prepared) return;
        const epoch = ++generation, paging = { page: page.data.page, assetPage: page.data.assetPage }; clearPhoto();
        page.setData({ ...blank(), ...paging, busy: true, retry: !!pending, pendingLabel: pending?.label ?? "" });
        try { if (!services.sessions.identity) throw new MiniRequestError(401,"AUTH_SESSION_INVALID"); await readState(page,epoch); }
        catch (error) { if (current(epoch)) failure(page,error); } finally { finish(page,epoch); }
    };
    const send = async (page: Context) => {
        if (!ensure(page) || page.data.busy || !pending) return;
        const operation = pending, epoch = ++generation; page.setData({ busy: true, error: "" });
        try {
            const result = (operation.file ? await services.sessions.uploadEvidence(operation.path, operation.file.path, operation)
                : await services.sessions.request("POST", operation.path, operation.body === undefined ? undefined : JSON.parse(operation.body), operation)) as { id: string };
            if (!current(epoch)) return; selectedId = result.id;
            if (!await readState(page,epoch) || !current(epoch)) return;
            pending = null; discard(operation.file); clearPhoto(); delete instants.last; delete instants.search; page.setData({ retry: false, pendingLabel: "", lastDate: "", lastTime: "", lastLocation: "", circumstances: "", selectedAsset: "", reason: "", custodianVerification: "", searchedScope: "", searchDate: "", searchTime: "", nextReviewDate: "", photoPath: "", photoName: "", notice: "本次办理已记录。" });
        } catch (error) { if (current(epoch)) failure(page,error); } finally { finish(page,epoch); }
    };
    const prepare = (page: Context, action: string) => {
        if (!editable(page)) return;
        const row = page.data.detail, data = page.data; let path = base, body: unknown, label: string;
        try {
            if (action === "submit") {
                const asset = data.assets.find(item => item.id === data.selectedAsset);
                if (!asset || !data.lastLocation.trim() || data.circumstances.trim().length < 10) throw new Error("Missing report facts");
                body = { assetId: asset.id, assetVersion: asset.version, lastSeenAt: actual(data.lastDate,data.lastTime,instants.last), lastLocation: data.lastLocation.trim(), circumstances: data.circumstances.trim() };
                label = `提交 ${asset.assetCode} 的报失报告；最后见到 ${actual(data.lastDate,data.lastTime,instants.last)}，位置：${data.lastLocation.trim()}，情况：${data.circumstances.trim()}。只取得锁，尚未确认遗失。`;
            } else {
                const capability = ({ approve: "decide", reject: "decide", cancel: "cancel", claim: "claim", search: "search" } as Record<string,string>)[action];
                if (!row || !capability || !row.actions?.[capability]) return;
                path += "/" + row.id + "/" + (["approve","reject"].includes(action) ? "decision" : action);
                if (["approve","reject","cancel"].includes(action)) {
                    if (data.reason.trim().length < 3) throw new Error("Missing reason");
                    body = action === "cancel" ? { reason: data.reason.trim() } : { result: action === "approve" ? "APPROVE" : "REJECT", reason: data.reason.trim() };
                }
                if (action === "search") {
                    if (data.custodianVerification.trim().length < 10 || data.searchedScope.trim().length < 10 || !/^\d{4}-\d{2}-\d{2}$/.test(data.nextReviewDate) || !Number.isSafeInteger(row.lockVersion)) throw new Error("Missing verification");
                    body = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion,
                        custodianVerification: data.custodianVerification.trim(), searchedScope: data.searchedScope.trim(), searchedAt: actual(data.searchDate,data.searchTime,instants.search), nextReviewDate: data.nextReviewDate };
                }
                label = ({ approve: "批准核实查找，不提前判定遗失。", reject: "驳回独立报告并释放本单锁。", cancel: "撤回未批准的独立报告并释放本单锁。", claim: "取得本单唯一核实处理权。", search: "记录核实并进入查找中；原责任、借用及本单锁保留。" } as Record<string,string>)[action]!;
                if (["approve","reject","cancel"].includes(action)) label += " 本次原因：" + data.reason.trim();
                if (action === "search") label += ` 核实：${data.custodianVerification.trim()}；范围：${data.searchedScope.trim()}；实际查找 ${actual(data.searchDate,data.searchTime,instants.search)}；下次复核 ${data.nextReviewDate}。`;
            }
            prepared = Object.freeze({ path, key: services.createKey(), version: action === "submit" ? 0 : row!.version, body: body === undefined ? undefined : JSON.stringify(body), label });
            page.setData({ confirmation: label, error: "" });
        } catch { page.setData({ error: "请填写完整实际情况、日期时刻、核实证据、复核日及本次原因。" }); }
    };
    return {
        data: blank(),
        onLoad(options: { id?: string }) { selectedId = /^[1-9][0-9]{0,18}$/.test(options.id ?? "") ? options.id! : ""; },
        async onShow(this: Context) { live = true; const next = getServices(); if (services && identity !== JSON.stringify([next.sessions.identity,next.sessions.identityRevision])) wipe(this); services = next; identity = owner(); await read(this); },
        onHide(this: Context) { live = false; generation++; const file = prepared?.file; prepared = null; clearPhoto(); discard(file); this.setData(blank()); },
        onUnload(this: Context) { live = false; generation++; wipe(this); },
        async onRefresh(this: Context) { await read(this); },
        async onOpen(this: Context, event: { currentTarget: { dataset: { id: string } } }) { if (!editable(this) || !this.data.rows.some(row => row.id === event.currentTarget.dataset.id)) return; selectedId = event.currentTarget.dataset.id; await read(this); },
        onAsset(this: Context, event: { currentTarget: { dataset: { id: string } } }) { if (editable(this) && this.data.assets.some(row => row.id === event.currentTarget.dataset.id)) this.setData({ selectedAsset: event.currentTarget.dataset.id }); },
        onField(this: Context, event: { currentTarget: { dataset: { field: string } }; detail: { value: string } }) { const field = event.currentTarget.dataset.field; if (!editable(this) || !["lastDate","lastTime","lastLocation","circumstances","reason","custodianVerification","searchedScope","searchDate","searchTime","nextReviewDate"].includes(field)) return; if (["lastDate","lastTime"].includes(field)) delete instants.last; if (["searchDate","searchTime"].includes(field)) delete instants.search; this.setData({ [field]: event.detail.value }); },
        onNow(this: Context, event: { currentTarget: { dataset: { kind: string } } }) { const kind = event.currentTarget.dataset.kind; if (!editable(this) || !["last","search"].includes(kind)) return; const now = new Date(); instants[kind] = now.toISOString(); this.setData(kind === "last" ? { lastDate: day(now), lastTime: time(now) } : { searchDate: day(now), searchTime: time(now) }); },
        onPrepare(this: Context, event: { currentTarget: { dataset: { action: string } } }) { prepare(this,event.currentTarget.dataset.action); },
        async onConfirm(this: Context) { if (!ensure(this) || this.data.busy || pending || !prepared) return; pending = prepared; prepared = null; this.setData({ confirmation: "", retry: true, pendingLabel: pending.label }); await send(this); },
        onCancel(this: Context) { if (!ensure(this) || this.data.busy || pending || !prepared) return; const file = prepared.file; prepared = null; discard(file); this.setData({ confirmation: "" }); },
        async onRetry(this: Context) { await send(this); },
        async onUpload(this: Context) {
            if (!editable(this) || !this.data.detail?.actions?.addPhoto || !services.chooseInventoryPhoto) return;
            const row = this.data.detail, epoch = generation; this.setData({ busy: true }); let copy: FactoryEvidenceFile | undefined;
            try { const file = await services.chooseInventoryPhoto("album"); if (!current(epoch)) return;
                if (file.size <= 0 || file.size > 10 * 1024 * 1024 || !/\.(png|jpe?g|webp)$/i.test(file.name)) throw new Error("Invalid image");
                copy = services.sessions.stageFactoryEvidence(file);
                if (!current(epoch)) { discard(copy); return; }
                const label = "上传情况原件 " + file.name + "，仅服务端确认原件纳入审批。";
                prepared = Object.freeze({ path: base + "/" + row.id + "/photos", key: services.createKey(), version: row.version, file: copy, label }); this.setData({ confirmation: label });
            } catch (error) { discard(copy); if (current(epoch)) failure(this,error); } finally { finish(this,epoch); }
        },
        async onPhoto(this: Context, event: { currentTarget: { dataset: { id: string } } }) {
            const row = this.data.detail, item = row?.photos?.find(photo => photo.objectId === event.currentTarget.dataset.id);
            if (!editable(this) || !row || !item) return; const epoch = generation; clearPhoto(); this.setData({ busy: true, photoPath: "", photoName: "" });
            try { const grant = await services.sessions.request("POST", base + "/" + row.id + "/photos/" + item.objectId + "/download-grant") as { url: string };
                if (!current(epoch)) return;
                const path = await services.sessions.downloadLossPhoto(grant.url,item.contentType);
                if (!current(epoch)) { services.sessions.discardInventoryPhoto(path); return; }
                photo = path; this.setData({ photoPath: path, photoName: item.filename });
            } catch (error) { if (current(epoch)) failure(this,error); } finally { finish(this,epoch); }
        },
        onClosePhoto(this: Context) { clearPhoto(); this.setData({ photoPath: "", photoName: "" }); },
        async onPage(this: Context, event: { currentTarget: { dataset: { delta: string; kind: string } } }) { if (!editable(this)) return; const delta = Number(event.currentTarget.dataset.delta), asset = event.currentTarget.dataset.kind === "assets", next = (asset ? this.data.assetPage : this.data.page) + delta, total = asset ? this.data.assetTotal : this.data.total; if (![-1,1].includes(delta) || next < 1 || (next - 1) * 20 >= total) return; if (!asset) selectedId = ""; this.setData(asset ? { assetPage: next } : { page: next }); await read(this); }
    };
}
