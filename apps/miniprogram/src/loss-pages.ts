import type { TransferChoice } from "../../../packages/api-types/src/transfer";
import type { MiniServices } from "./pages.ts";
import { MiniRequestError, type FactoryEvidenceFile } from "./session.ts";
interface Asset { id: string; assetCode: string; name: string; version: number; lifecycle?: string; responsibleEmployeeId?: string; departmentResponsibleEmployeeId?: string }
interface Photo { objectId: string; filename: string; contentType: string }
interface Recovery { request?: { destination?: string; facts?: Record<string,unknown> }; id: string; version: number; assetVersion: number; ownershipVersion: number; status: string; processorAccountId: string | null; actions: Record<string,boolean> }
interface RecoveryAsset { lifecycle?: string; version: number; serialNumber: string | null; locationId: string | null; locationVersion: number | null; locationName: string | null }
interface Report { id: string; source: Asset & { factorySource?: { repairId: string; roundId: string };
    inventorySource?: { taskId: string; itemId: string; reasonId: string } }; status: string; version: number; assetVersion: number; ownershipVersion: number; lockVersion: number;
    transferId?: string; returnId?: string | null; repairId?: string | null; recoveredAt?: string; recoveryAsset?: RecoveryAsset | null; recoveries?: Recovery[]; recoveriesTotal?: number; confirmedLostAt?: string; lastFollowUpAt?: string; lastSeenAt: string; lastLocation: string; circumstances: string; processorAccountId: string | null; nextReviewDate: string | null;
    actions?: Record<string, boolean>; photos?: Photo[]; history?: { id: string; action: string; actor_username: string; occurred_at: string }[] }
interface Intent { readonly path: string; readonly lossReportId?: string; readonly key: string; readonly version: number; readonly body?: string; readonly file?: FactoryEvidenceFile; readonly label: string }
interface Place { id: string; name: string; version: number }
interface TransferSelection {
    organizations: TransferChoice[]; people: TransferChoice[]; places: TransferChoice[]; warehouses: TransferChoice[];
    organization: string; owner: string; person: string; place: string; warehouse: string;
    custody: string; checked: boolean; sameCustodian: boolean; reason: string;
}
const blankTransfer = (): TransferSelection => ({
    organizations: [], people: [], places: [], warehouses: [], warehouse: "", organization: "", owner: "", person: "", place: "",
    custody: "PERSONAL", checked: false, sameCustodian: false, reason: "",
});
interface Data { transfer: TransferSelection; locations: Place[]; locationPage: number; locationTotal: number; selectedLocation: string; returnChecked: boolean; rows: Report[]; assets: Asset[]; detail: Report | null; page: number; total: number; assetPage: number; assetTotal: number; selectedAsset: string;
    factoryChecked: boolean; factoryDamaged: boolean; observedAssetCode: string; observedSerialNumber: string; foundDate: string; foundTime: string; recoveryEvidence: string; recoveryChecked: boolean; damagedChecked: boolean; repairFault: string; repairImpact: string; followUpOnly: boolean; relatedClues: string; inabilityReason: string; responsibilityEvidence: string; findings: string; confirmDate: string; confirmTime: string; reviewDate: string; reviewTime: string; busy: boolean; stale: boolean; retry: boolean; confirmation: string; pendingLabel: string; error: string; notice: string; lastDate: string; lastTime: string; lastLocation: string; circumstances: string;
    scrapReason: string; scrapCondition: string; scrapReceiptId: string;
    reason: string; custodianVerification: string; searchedScope: string; searchDate: string; searchTime: string; nextReviewDate: string; photoPath: string; photoName: string }
interface Context { data: Data; setData(update: Partial<Data>): void }
const blank = (): Data => ({ transfer: blankTransfer(), locations: [], locationPage: 0, locationTotal: 0, rows: [], assets: [], detail: null, page: 1, total: 0, assetPage: 1, assetTotal: 0, selectedAsset: "", followUpOnly: false, observedAssetCode: "", observedSerialNumber: "", foundDate: "", foundTime: "", recoveryEvidence: "", recoveryChecked: false, returnChecked: false, selectedLocation: "", damagedChecked: false, factoryChecked: false, factoryDamaged: false, repairFault: "", repairImpact: "MEDIUM", relatedClues: "", inabilityReason: "", responsibilityEvidence: "", findings: "", confirmDate: "", confirmTime: "", reviewDate: "", reviewTime: "", busy: false, stale: true, retry: false, confirmation: "", pendingLabel: "", error: "", notice: "", lastDate: "", lastTime: "", lastLocation: "", circumstances: "", scrapReason: "", scrapCondition: "", scrapReceiptId: "", reason: "", custodianVerification: "", searchedScope: "", searchDate: "", searchTime: "", nextReviewDate: "", photoPath: "", photoName: "" });
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
    let services: MiniServices, identity = "", live = false, generation = 0, selectedId = "", photo = "", followUpOnly = false;
    let pending: Intent | null = null, prepared: Intent | null = null;
    const instants: Record<string, string> = {};
    const base = "/api/v1/mini/loss-reports";
    const owner = () => JSON.stringify([services.sessions.identity, services.sessions.identityRevision]);
    const current = (epoch: number) => live && generation === epoch && identity === owner();
    const discard = (file?: FactoryEvidenceFile) => { if (file) services.sessions.discardFactoryEvidence(file.path); };
    const clearPhoto = () => { if (photo) { const old = photo; photo = ""; services.sessions.discardInventoryPhoto(old); } };
    const wipe = (page: Context) => { const files = new Set([pending?.file, prepared?.file]); pending = null; prepared = null; selectedId = ""; followUpOnly = false; clearPhoto(); page.setData(blank()); for (const file of files) discard(file); };
    const ensure = (page: Context) => { if (!live) return false; if (owner() === identity) return true; wipe(page); page.setData({ error: "身份已变化，原报失资料已隐藏。" }); return false; };
    const editable = (page: Context) => ensure(page) && !page.data.busy && !page.data.stale && !pending && !prepared;
    const failure = (page: Context, error: unknown) => {
        if (error instanceof MiniRequestError && [401,403,404].includes(error.status)) { wipe(page); page.setData({ error: "身份、权限或记录失效，原资料已隐藏。" }); }
        else if (error instanceof MiniRequestError && [400,409,413,422].includes(error.status)) { const file = pending?.file; pending = null; discard(file); page.setData({ stale: true, retry: false, pendingLabel: "", error: error.code === "LOSS_LINKED_RECOVERY_REQUIRED" ? "责任、位置或状况变化需要关联处理，不能直接恢复正常。" : ["LOSS_PHYSICAL_IDENTITY_MISMATCH","LOSS_CUSTODIAN_CHANGED","LOSS_LOCATION_CHANGED","LOSS_LOAN_IDENTITY_CHANGED"].includes(error.code) ? "原机身份、登记位置或原责任不匹配，当前未恢复正常。" : error.code === "LOSS_PHOTO_REQUIRED" ? "批准前须由报告人上传本次情况图片。" : "办理被拒绝，请刷新核对版本、处理权、实际日期及原件。" }); }
        else page.setData({ stale: true, retry: !!pending, error: pending ? "结果尚未确认，请只读刷新后使用原操作重试。" : "读取或原件处理未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => { if (!live || generation !== epoch) return; if (owner() !== identity) { wipe(page); page.setData({ error: "身份已变化，原资料已隐藏。" }); } else page.setData({ busy: false }); };
    const readState = async (page: Context, epoch: number) => {
        const rows = await services.sessions.request("GET", base + (followUpOnly ? "/follow-ups" : "") + `?page=${page.data.page}&pageSize=20`) as { items: Report[]; total: number };
        if (!current(epoch)) return false;
        const assets = followUpOnly ? { items: [], total: 0 } : await services.sessions.request("GET", base + `/eligible?page=${page.data.assetPage}&pageSize=20`) as { items: Asset[]; total: number };
        if (!current(epoch)) return false;
        const detail = selectedId ? await services.sessions.request("GET", base + "/" + selectedId) as Report : null;
        if (!current(epoch)) return false;
        page.setData({ followUpOnly, rows: rows.items, total: rows.total, assets: assets.items, assetTotal: assets.total, detail, stale: false, retry: !!pending, pendingLabel: pending?.label ?? "" }); return true;
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
            if (!current(epoch)) return; selectedId = operation.lossReportId ?? result.id;
            if (!await readState(page,epoch) || !current(epoch)) return;
            pending = null; discard(operation.file); clearPhoto(); delete instants.last; delete instants.search; delete instants.found; page.setData({ transfer: blankTransfer(), observedAssetCode: "", observedSerialNumber: "", foundDate: "", foundTime: "", recoveryEvidence: "", recoveryChecked: false, returnChecked: false, selectedLocation: "", damagedChecked: false, factoryChecked: false, factoryDamaged: false, repairFault: "", repairImpact: "MEDIUM", relatedClues: "", inabilityReason: "", responsibilityEvidence: "", findings: "", confirmDate: "", confirmTime: "", reviewDate: "", reviewTime: "", retry: false, pendingLabel: "", lastDate: "", lastTime: "", lastLocation: "", circumstances: "", selectedAsset: "", reason: "", custodianVerification: "", searchedScope: "", searchDate: "", searchTime: "", nextReviewDate: "", photoPath: "", photoName: "",
                scrapReason: "", scrapCondition: "", scrapReceiptId: operation.lossReportId ? result.id : page.data.scrapReceiptId,
                notice: operation.lossReportId ? "关联报废申请已提交。" : "本次办理已记录。" });
        } catch (error) { if (current(epoch)) failure(page,error); } finally { finish(page,epoch); }
    };
    const prepare = (page: Context, action: string) => {
        if (!editable(page)) return;
        const row = page.data.detail, data = page.data; let path = base, body: unknown, label: string;
        if (action === "requestScrap") {
            const assetVersion = row?.recoveryAsset?.version;
            if (!row?.actions?.requestScrap || assetVersion === undefined
                || !Number.isSafeInteger(assetVersion) || assetVersion < 0) return;
            if (!data.scrapReason.trim() || !data.scrapCondition.trim()) {
                page.setData({ error: "请填写关联报废原因与确认遗失后的实际情况。" }); return;
            }
            label = "提交 " + row.source.assetCode + " 的关联报废申请。资产进入待报废，原保管责任继续保留。";
            prepared = Object.freeze({ path: "/api/v1/mini/scrap-requests/from-loss/" + row.id, lossReportId: row.id,
                key: services.createKey(), version: row.version, label, body: JSON.stringify({
                    assetVersion, reason: data.scrapReason.trim(), observedCondition: data.scrapCondition.trim(),
                    proposedDisposition: "遗失核销",
                }) });
            page.setData({ confirmation: label, error: "" });
            return;
        }
        try {
            if (action === "submit") {
                const asset = data.assets.find(item => item.id === data.selectedAsset);
                if (!asset || !data.lastLocation.trim() || data.circumstances.trim().length < 10) throw new Error("Missing report facts");
                body = { assetId: asset.id, assetVersion: asset.version, lastSeenAt: actual(data.lastDate,data.lastTime,instants.last), lastLocation: data.lastLocation.trim(), circumstances: data.circumstances.trim() };
                label = `提交 ${asset.assetCode} 的报失报告；最后见到 ${actual(data.lastDate,data.lastTime,instants.last)}，位置：${data.lastLocation.trim()}，情况：${data.circumstances.trim()}。只取得锁，尚未确认遗失。`;
            } else {
                const capability = ({ approve: "decide", reject: "decide", cancel: "cancel", claim: "claim", search: "search", confirmLost: "confirmLost", followUp: "followUp" } as Record<string,string>)[action];
                if (!row || !capability || !row.actions?.[capability]) return;
                path += "/" + row.id + "/" + (["approve","reject"].includes(action) ? "decision" : action === "confirmLost" ? "confirm-lost" : action === "followUp" ? "follow-up" : action);
                if (["approve","reject","cancel"].includes(action)) {
                    if (data.reason.trim().length < 3) throw new Error("Missing reason");
                    body = action === "cancel" ? { reason: data.reason.trim() } : { result: action === "approve" ? "APPROVE" : "REJECT", reason: data.reason.trim() };
                }
                if (["search","confirmLost"].includes(action)) {
                    if (data.custodianVerification.trim().length < 10 || data.searchedScope.trim().length < 10 || action === "search" && !/^\d{4}-\d{2}-\d{2}$/.test(data.nextReviewDate) || !Number.isSafeInteger(row.lockVersion)) throw new Error("Missing verification");
                    body = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion,
                        custodianVerification: data.custodianVerification.trim(), searchedScope: data.searchedScope.trim(), searchedAt: actual(data.searchDate,data.searchTime,instants.search), nextReviewDate: data.nextReviewDate };
                    if (action === "confirmLost") {
                        if ([data.relatedClues,data.inabilityReason,data.responsibilityEvidence].some(value => value.trim().length < 10)) throw new Error("Incomplete responsibility evidence");
                        body = { assetVersion: row.assetVersion, ownershipVersion: row.ownershipVersion, lockVersion: row.lockVersion, custodianVerification: data.custodianVerification.trim(), searchedScope: data.searchedScope.trim(), searchedAt: actual(data.searchDate,data.searchTime,instants.search), relatedClues: data.relatedClues.trim(), inabilityReason: data.inabilityReason.trim(), responsibilityEvidence: data.responsibilityEvidence.trim(), confirmedAt: actual(data.confirmDate,data.confirmTime,instants.confirm) };
                    }
                }
                if (action === "followUp") {
                    if (data.findings.trim().length < 10 || data.reason.trim().length < 3) throw new Error("Incomplete review");
                    body = { ownershipVersion: row.ownershipVersion, reviewedAt: actual(data.reviewDate,data.reviewTime,instants.review), findings: data.findings.trim(), reason: data.reason.trim(), nextReviewDate: data.nextReviewDate || undefined };
                }
                label = ({ approve: "批准核实查找，不提前判定遗失。", reject: "驳回独立报告并释放本单锁。", cancel: "撤回未批准的独立报告并释放本单锁。", claim: "取得本单唯一核实处理权。", search: "记录核实并进入查找中；原责任、借用及本单锁保留。", confirmLost: "凭完整证据确认遗失并释放本查找锁；原责任和借用仍保留，进入30日跟进。", followUp: "记录遗失持续跟进，保留原责任与借用。" } as Record<string,string>)[action]!;
                if (["approve","reject","cancel"].includes(action)) label += " 本次原因：" + data.reason.trim();
                if (action === "confirmLost") label += ` 无法找回：${data.inabilityReason}；责任证据：${data.responsibilityEvidence}；确认实际时间 ${actual(data.confirmDate,data.confirmTime,instants.confirm)}。`;
                if (action === "followUp") label += ` 结果：${data.findings}；原因：${data.reason}；下次复核 ${data.nextReviewDate || "默认30自然日后"}。`;
                if (action === "search") label += ` 核实：${data.custodianVerification.trim()}；范围：${data.searchedScope.trim()}；实际查找 ${actual(data.searchDate,data.searchTime,instants.search)}；下次复核 ${data.nextReviewDate}。`;
            }
            prepared = Object.freeze({ path, key: services.createKey(), version: action === "submit" ? 0 : row!.version, body: body === undefined ? undefined : JSON.stringify(body), label });
            page.setData({ confirmation: label, error: "" });
        } catch { page.setData({ error: "请填写完整实际情况、日期时刻、核实证据、复核日及本次原因。" }); }
    };
    const prepareRecovery = (page: Context, action: string, id?: string) => {
        if (!editable(page)) return;
        const row = page.data.detail, data = page.data;
        if (!row) return;
        const attempt = id ? row.recoveries?.find(item => item.id === id) : undefined;
        const capability = ({approve:"decide",reject:"decide",cancel:"cancel",claim:"claim",execute:"execute",executeDamaged:"execute",executeReturn:"execute",executeTransfer:"execute",executeFactory:"execute"} as Record<string,string>)[action];
        if (id ? !attempt || !capability || !attempt.actions[capability] : !["recoverOriginal","recoverDamaged","recoverReturn","recoverTransfer","requestRecovery","requestDamaged","requestReturn","requestTransfer","recoverFactory","requestFactoryRecovery"].includes(action) || !row.actions?.[["recoverDamaged","recoverReturn","recoverTransfer"].includes(action) ? "recoverOriginal" : ["requestDamaged","requestReturn","requestTransfer"].includes(action) ? "requestRecovery" : action]) return;
        if (attempt && ["execute", "executeDamaged", "executeReturn", "executeTransfer", "executeFactory"].includes(action)) {
            const destination = attempt.request?.destination;
            if ((destination === "ASSET_RETURN") !== (action === "executeReturn")
                || (destination === "TRANSFER") !== (action === "executeTransfer")
                || (destination === "FACTORY_REPAIR") !== (action === "executeFactory")) return;
        }
        if (attempt && ["execute","executeDamaged"].includes(action) && ((attempt.request?.facts?.condition === "DAMAGED") !== (action === "executeDamaged"))) return;
        try {
            let body: unknown, label: string;
            if (["recoverTransfer", "requestTransfer", "executeTransfer"].includes(action)) {
                const source = row.recoveryAsset, place = data.locations.find(item => item.id === data.selectedLocation);
                const transfer = data.transfer;
                const stock = row.source.lifecycle === "IN_STOCK";
                if ((!stock && row.source.lifecycle !== "IN_USE") || !source || !place || !transfer.checked
                    || !data.observedAssetCode.trim() || (source.serialNumber && !data.observedSerialNumber.trim())
                    || data.recoveryEvidence.trim().length < 10) throw Error("Missing transfer recovery evidence");
                const facts = {
                    foundAt: actual(data.foundDate, data.foundTime, instants.found),
                    observedAssetCode: data.observedAssetCode.trim(), observedSerialNumber: data.observedSerialNumber.trim(),
                    locationId: place.id, locationVersion: place.version, physicalIdentityVerified: true,
                    condition: "INTACT", stillOriginalCustodian: transfer.sameCustodian, evidence: data.recoveryEvidence.trim(),
                };
                if (action === "requestTransfer") {
                    body = { assetVersion: source.version, facts };
                    label = `申请找回后调拨 ${facts.observedAssetCode}，须重新批准并认领。`;
                } else {
                    const org = transfer.organizations.find(item => item.id === transfer.organization);
                    const owner = transfer.organizations.find(item => item.id === transfer.owner);
                    const person = transfer.people.find(item => item.id === transfer.person);
                    const destination = transfer.places.find(item => item.id === transfer.place);
                    const warehouse = transfer.warehouses.find(item => item.id === transfer.warehouse);
                    if (!destination || (transfer.owner && !owner) || !transfer.reason.trim()
                        || !Number.isSafeInteger(row.lockVersion)
                        || (stock ? !warehouse || destination.warehouseId !== warehouse.id : !org || !person)) {
                        throw Error("Missing transfer target");
                    }
                    const referenceVersions: Record<string, number> = { location: destination.version };
                    if (stock && warehouse) referenceVersions.warehouse = warehouse.version;
                    if (!stock && org && person) {
                        referenceVersions.usingOrganization = org.version;
                        referenceVersions.employee = person.version;
                    }
                    if (owner) referenceVersions.owningOrganization = owner.version;
                    body = {
                        recovery: { assetVersion: attempt?.assetVersion ?? row.assetVersion,
                            ownershipVersion: attempt?.ownershipVersion ?? row.ownershipVersion,
                            lockVersion: row.lockVersion, facts },
                        target: { owningOrganizationId: owner?.id ?? null, custodyType: stock ? "STORAGE" : transfer.custody,
                            usingOrganizationId: stock ? null : org?.id, employeeId: stock ? null : person?.id,
                            warehouseId: stock ? warehouse?.id : null,
                            locationId: destination.id, referenceVersions },
                        reason: transfer.reason.trim(),
                    };
                    label = `提交调拨 ${facts.observedAssetCode}，实际位置 ${place.name}，目标 ${warehouse?.name ?? person?.name} / ${destination.name}。批准并履行后更新责任和位置。`;
                }
            } else if (["recoverFactory", "requestFactoryRecovery", "executeFactory"].includes(action)) {
                const source = row.recoveryAsset, place = data.locations.find(item => item.id === data.selectedLocation);
                if (!source || !place || !data.factoryChecked || !data.observedAssetCode.trim()
                    || source.serialNumber && !data.observedSerialNumber.trim() || data.recoveryEvidence.trim().length < 10) {
                    throw Error("Missing factory recovery evidence");
                }
                const facts = { foundAt: actual(data.foundDate, data.foundTime, instants.found),
                    observedAssetCode: data.observedAssetCode.trim(), observedSerialNumber: data.observedSerialNumber.trim(),
                    locationId: place.id, locationVersion: place.version, physicalIdentityVerified: true,
                    condition: data.factoryDamaged ? "DAMAGED" : "INTACT", stillOriginalCustodian: false, evidence: data.recoveryEvidence.trim() };
                const requesting = action === "requestFactoryRecovery";
                body = requesting ? { assetVersion: source.version, facts } : { assetVersion: attempt?.assetVersion ?? row.assetVersion,
                    ownershipVersion: attempt?.ownershipVersion ?? row.ownershipVersion, lockVersion: row.lockVersion, facts };
                label = `${requesting ? "新建返厂找回申请，须重新批准并认领" : "核实原机并交回原返厂维修"}：${facts.observedAssetCode}；实际位置${place.name}；${data.factoryDamaged ? "损坏" : "完好"}；${facts.evidence}。实际收回仍由原轮次另行登记。`;
            } else if (["recoverReturn","requestReturn","executeReturn"].includes(action)) {
                const source=row.recoveryAsset,place=data.locations.find(item=>item.id===data.selectedLocation);
                if(!source||!place||!data.returnChecked||!data.observedAssetCode.trim()||source.serialNumber&&!data.observedSerialNumber.trim()||data.recoveryEvidence.trim().length<10)throw Error("Missing linked return evidence");
                const facts={foundAt:actual(data.foundDate,data.foundTime,instants.found),observedAssetCode:data.observedAssetCode.trim(),observedSerialNumber:data.observedSerialNumber.trim(),locationId:place.id,locationVersion:place.version,physicalIdentityVerified:true,condition:"INTACT",stillOriginalCustodian:false,evidence:data.recoveryEvidence.trim()};
                body=action==="requestReturn"?{assetVersion:source.version,facts}:{assetVersion:attempt?.assetVersion??row.assetVersion,ownershipVersion:attempt?.ownershipVersion??row.ownershipVersion,lockVersion:row.lockVersion,facts};
                label=`${action==="requestReturn"?"新建归还退库找回申请，须新批准并认领":"找回并申请正式归还退库"}：${facts.observedAssetCode}；实际位置${place.name}；${facts.evidence}。原责任与借用保留至审批及实物验收，原锁继续保留，尚未成为可用库存。`;
            } else if (["recoverOriginal","recoverDamaged","requestRecovery","requestDamaged","execute","executeDamaged"].includes(action)) {
                const source = row.recoveryAsset, damaged = ["recoverDamaged","requestDamaged","executeDamaged"].includes(action), requesting = ["requestRecovery","requestDamaged"].includes(action);
                if (!source || !source.locationId || source.locationVersion == null || !(damaged ? data.damagedChecked : data.recoveryChecked) || !data.observedAssetCode.trim() || source.serialNumber && !data.observedSerialNumber.trim() || data.recoveryEvidence.trim().length < 10) throw Error("Missing original recovery evidence");
                const facts = {foundAt:actual(data.foundDate,data.foundTime,instants.found), observedAssetCode:data.observedAssetCode.trim(), observedSerialNumber:data.observedSerialNumber.trim(), locationId:source.locationId, locationVersion:source.locationVersion, physicalIdentityVerified:true, condition:damaged ? "DAMAGED" : "INTACT", stillOriginalCustodian:true, evidence:data.recoveryEvidence.trim()};
                body = requesting ? {assetVersion:source.version,facts} : {assetVersion:attempt?.assetVersion ?? row.assetVersion,ownershipVersion:attempt?.ownershipVersion ?? row.ownershipVersion,lockVersion:row.lockVersion,facts};
                if (damaged && !requesting) {
                    if (data.repairFault.trim().length < 10 || !["LOW","MEDIUM","HIGH"].includes(data.repairImpact) || !Number.isSafeInteger(row.lockVersion)) throw Error("Missing damage facts");
                    body = {recovery:body,fault:data.repairFault.trim(),impact:data.repairImpact};
                }
                label = `${requesting ? (damaged ? "新建损坏找回申请，批准并重新认领核实后转正式维修" : "新建找回申请，批准认领后才能恢复") : damaged ? "损坏找回并申请正式维修" : "核实原机后恢复原责任"}：${facts.observedAssetCode}，序列号${facts.observedSerialNumber || "未登记"}；实际找回${facts.foundAt}，登记位置${source.locationName}；${facts.evidence}。历史遗失保留，原借用不改期。`;
                if (damaged && !requesting) label += ` 故障：${data.repairFault.trim()}；使用影响：${({LOW:"较低",MEDIUM:"中等",HIGH:"较高"} as Record<string,string>)[data.repairImpact]}。原锁转入维修，须管理员批准及库管实际办理。`;
            } else {
                if (action !== "claim") {
                    if (data.reason.trim().length < 3) throw Error("Missing reason");
                    body = action === "cancel" ? {reason:data.reason.trim()} : {result:action === "approve" ? "APPROVE" : "REJECT",reason:data.reason.trim()};
                }
                label = ({approve:"批准新找回申请，仍须库管重新认领核实。",reject:"驳回找回申请，原遗失责任跟进保留。",cancel:"撤回新找回申请，原遗失责任跟进保留。",claim:"取得新的唯一找回处理权，尚未恢复正常。"} as Record<string,string>)[action]!;
                if (action !== "claim") label += data.reason.trim();
            }
            const path = attempt ? `${base}/recoveries/${attempt.id}/${["approve","reject"].includes(action) ? "decision" : action === "executeDamaged" ? "execute-to-repair" : action === "executeReturn" ? "execute-to-return" : action === "executeTransfer" ? "execute-to-transfer" : action === "executeFactory" ? "execute-to-factory" : action}` : `${base}/${row.id}/${action === "recoverFactory" ? "recover-to-factory" : action === "requestFactoryRecovery" ? "factory-recovery-requests" : action === "recoverOriginal" ? "recover-original" : action === "recoverDamaged" ? "recover-to-repair" : action === "recoverReturn" ? "recover-to-return" : action === "requestReturn" ? "return-recovery-requests" : action === "recoverTransfer" ? "recover-to-transfer" : action === "requestTransfer" ? "transfer-recovery-requests" : action === "requestDamaged" ? "repair-recovery-requests" : "recovery-requests"}`;
            prepared = Object.freeze({path, key:services.createKey(),version:attempt?.version ?? row.version,body:body === undefined ? undefined : JSON.stringify(body),label});
            page.setData({confirmation:label,error:""});
        } catch { page.setData({error:"请填写原机编码/序列号、实际找回时间、至少10字证据及本次原因，并确认对应状况、原保管与登记位置；损坏找回还须至少10字故障及使用影响。"}); }
    };
    const transferCatalog = async (kind: string, epoch: number, organization?: string) => {
        const items: TransferChoice[] = [];
        const scope = organization ? `&organizationId=${encodeURIComponent(organization)}` : "";
        for (let page = 1; current(epoch); page++) {
            const result = await services.sessions.request("GET",
                `/api/v1/mini/transfers/catalog/${kind}?page=${page}&pageSize=200${scope}`) as { items: TransferChoice[]; total: number };
            items.push(...result.items);
            if (items.length >= result.total || result.items.length === 0) break;
        }
        return items;
    };
    return {
        data: blank(),
        async onLoadTransferTargets(this: Context) {
            if (!editable(this)) return;
            const epoch = generation;
            this.setData({ busy: true, error: "" });
            try {
                const stock = this.data.detail?.source.lifecycle === "IN_STOCK";
                const [organizations, places, warehouses] = await Promise.all([
                    transferCatalog("organization", epoch),
                    !stock || this.data.transfer.warehouse ? transferCatalog("location", epoch) : Promise.resolve([]),
                    stock ? transferCatalog("warehouse", epoch) : Promise.resolve([]),
                ]);
                if (current(epoch)) this.setData({ transfer: {
                    ...this.data.transfer, organizations, warehouses,
                    places: places.filter(place => stock
                        ? place.locationType === "BIN" && place.warehouseId === this.data.transfer.warehouse
                        : place.locationType !== "BIN"),
                } });
            } catch (error) { if (current(epoch)) failure(this, error); }
            finally { finish(this, epoch); }
        },
        async onTransferTarget(this: Context, event: {
            currentTarget: { dataset: { field: string } }; detail: { value: string };
        }) {
            if (!editable(this)) return;
            const transfer = this.data.transfer, field = event.currentTarget.dataset.field;
            const index = Number(event.detail.value);
            if (field === "custody") {
                this.setData({ transfer: { ...transfer, custody: index === 1 ? "SHARED" : "PERSONAL",
                    person: "", organization: "", people: [] } });
            } else if (field === "warehouse" && transfer.warehouses[index]) {
                const warehouse = transfer.warehouses[index].id, epoch = generation;
                this.setData({ busy: true, error: "", transfer: { ...transfer, warehouse, place: "", places: [] } });
                try {
                    const places = await transferCatalog("location", epoch);
                    if (current(epoch)) this.setData({ transfer: { ...this.data.transfer,
                        places: places.filter(place => place.locationType === "BIN" && place.warehouseId === warehouse),
                    } });
                } catch (error) { if (current(epoch)) failure(this, error); }
                finally { finish(this, epoch); }
            } else if (field === "person" || field === "place" || field === "owner") {
                const choices = field === "person" ? transfer.people : field === "place" ? transfer.places : transfer.organizations;
                if (choices[index]) this.setData({ transfer: { ...transfer, [field]: choices[index].id } });
            } else if (field === "organization" && transfer.organizations[index]) {
                const organization = transfer.organizations[index].id, epoch = generation;
                this.setData({ busy: true, error: "", transfer: { ...transfer, organization, person: "", people: [] } });
                try {
                    const people = await transferCatalog("employee", epoch, organization);
                    if (current(epoch)) this.setData({ transfer: { ...this.data.transfer,
                        people: people.filter(person => transfer.custody !== "SHARED" || person.sharedEligible),
                    } });
                } catch (error) { if (current(epoch)) failure(this, error); }
                finally { finish(this, epoch); }
            }
        },
        onTransferChecked(this: Context, event: { detail: { value: boolean } }) {
            if (editable(this)) this.setData({ transfer: { ...this.data.transfer, checked: event.detail.value } });
        },
        onSameTransferCustodian(this: Context, event: { detail: { value: boolean } }) {
            if (editable(this)) this.setData({ transfer: { ...this.data.transfer, sameCustodian: event.detail.value } });
        },
        onTransferReason(this: Context, event: { detail: { value: string } }) {
            if (editable(this)) this.setData({ transfer: { ...this.data.transfer, reason: event.detail.value } });
        },
        onLoad(options: { id?: string }) { selectedId = /^[1-9][0-9]{0,18}$/.test(options.id ?? "") ? options.id! : ""; },
        async onShow(this: Context) { live = true; const next = getServices(); if (services && identity !== JSON.stringify([next.sessions.identity,next.sessions.identityRevision])) wipe(this); services = next; identity = owner(); await read(this); },
        onHide(this: Context) { live = false; generation++; const file = prepared?.file; prepared = null; clearPhoto(); discard(file); this.setData(blank()); },
        onUnload(this: Context) { live = false; generation++; wipe(this); },
        async onMode(this: Context, event: { currentTarget: { dataset: { mode: string } } }) { if (!editable(this)) return; followUpOnly = event.currentTarget.dataset.mode === "followups"; selectedId = ""; this.setData({ page: 1 }); await read(this); },
        async onRefresh(this: Context) { await read(this); },
        async onOpen(this: Context, event: { currentTarget: { dataset: { id: string } } }) { if (!editable(this) || !this.data.rows.some(row => row.id === event.currentTarget.dataset.id)) return; selectedId = event.currentTarget.dataset.id; this.setData({returnChecked:false,selectedLocation:"",locations:[],locationPage:0,locationTotal:0}); await read(this); },
        onAsset(this: Context, event: { currentTarget: { dataset: { id: string } } }) { if (editable(this) && this.data.assets.some(row => row.id === event.currentTarget.dataset.id)) this.setData({ selectedAsset: event.currentTarget.dataset.id }); },
        onField(this: Context, event: { currentTarget: { dataset: { field: string } }; detail: { value: string } }) { const field = event.currentTarget.dataset.field; if (!editable(this) || !["lastDate","lastTime","lastLocation","circumstances","scrapReason","scrapCondition","reason","custodianVerification","searchedScope","searchDate","searchTime","nextReviewDate","relatedClues","inabilityReason","responsibilityEvidence","findings","confirmDate","confirmTime","reviewDate","reviewTime","observedAssetCode","observedSerialNumber","foundDate","foundTime","recoveryEvidence","repairFault"].includes(field)) return; if (["lastDate","lastTime"].includes(field)) delete instants.last; if (["searchDate","searchTime"].includes(field)) delete instants.search; if (["confirmDate","confirmTime"].includes(field)) delete instants.confirm; if (["reviewDate","reviewTime"].includes(field)) delete instants.review; if (["foundDate","foundTime"].includes(field)) delete instants.found; this.setData({ [field]: event.detail.value }); },
        onNow(this: Context, event: { currentTarget: { dataset: { kind: string } } }) { const kind = event.currentTarget.dataset.kind; if (!editable(this) || !["last","search","confirm","review","found"].includes(kind)) return; const now = new Date(); instants[kind] = now.toISOString(); this.setData(kind === "last" ? { lastDate: day(now), lastTime: time(now) } : kind === "search" ? { searchDate: day(now), searchTime: time(now) } : kind === "confirm" ? { confirmDate: day(now), confirmTime: time(now) } : kind === "review" ? { reviewDate: day(now), reviewTime: time(now) } : { foundDate: day(now), foundTime: time(now) }); },
        onRecoveryPrepare(this: Context, event: { currentTarget: { dataset: { action: string; id?: string } } }) { prepareRecovery(this,event.currentTarget.dataset.action,event.currentTarget.dataset.id); },
        async onLoadLocations(this: Context) {
            if(!editable(this))return;const epoch=generation;this.setData({busy:true});
            try{const result=await services.sessions.request("GET",base+`/recovery-locations?page=${this.data.locationPage+1}&pageSize=100`) as {items:Place[];total:number};if(current(epoch))this.setData({locations:[...this.data.locations,...result.items],locationPage:this.data.locationPage+1,locationTotal:result.total});}
            catch{if(current(epoch))this.setData({error:"位置未加载，请重试。"});}finally{finish(this,epoch);}
        },
        onReturnLocation(this: Context,event:{detail:{value:string}}){if(editable(this)){const place=this.data.locations[Number(event.detail.value)];if(place)this.setData({selectedLocation:place.id});}},
        onReturnChecked(this: Context,event:{detail:{value:boolean}}){if(editable(this))this.setData({returnChecked:event.detail.value});},
        onFactoryChecked(this: Context, event: { detail: { value: boolean } }) {
            if (editable(this)) this.setData({ factoryChecked: event.detail.value });
        },
        onFactoryDamaged(this: Context, event: { detail: { value: boolean } }) {
            if (editable(this)) this.setData({ factoryDamaged: event.detail.value });
        },
        onRecoveryChecked(this: Context, event: { detail: { value: boolean } }) { if (editable(this)) this.setData({recoveryChecked:event.detail.value}); },
        onDamagedChecked(this: Context, event: { detail: { value: boolean } }) { if (editable(this)) this.setData({damagedChecked:event.detail.value}); },
        onRepairImpact(this: Context, event: { currentTarget: { dataset: { impact: string } } }) { const impact = event.currentTarget.dataset.impact; if (editable(this) && ["LOW","MEDIUM","HIGH"].includes(impact)) this.setData({repairImpact:impact}); },
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
