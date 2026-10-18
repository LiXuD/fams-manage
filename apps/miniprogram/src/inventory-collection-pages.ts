import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import type { InventoryCachedItem, InventoryCachedTask, InventoryObservation } from "./inventory-queue.ts";

interface Data {
    task: InventoryCachedTask | null; selected: InventoryCachedItem | null; candidates: InventoryCachedItem[];
    locations: { id: string; name: string }[]; people: { id: string; name: string }[];
    roomId: string | null; roomName: string; query: string; manualReason: string; unknownReason: string;
    roomDraft: string;
    responsibilityHint: string; description: string; personId: string | null; personName: string; custodyType: string | null;
    condition: "GOOD" | "DAMAGED" | "UNKNOWN"; difference: boolean; photos: InventoryObservation["photos"];
    pending: { eventId: string; label: string; state: string }[]; receipts: { eventId: string; message: string }[]; busy: boolean; offline: boolean; error: string; message: string;
}
interface Context { data: Data; setData(value: Partial<Data>): void }
interface Input { detail: { value: string }; currentTarget: { dataset: { field?: string; id?: string } } }
const blank = (): Data => ({ task: null, selected: null, candidates: [], locations: [], people: [], roomId: null,
    roomName: "", roomDraft: "", query: "", manualReason: "", unknownReason: "", responsibilityHint: "", description: "", personId: null, personName: "",
    custodyType: null, condition: "UNKNOWN", difference: false, photos: [], pending: [], receipts: [], busy: false, offline: false, error: "", message: "" });
const base = "/api/v1/mini/inventory-observations";
const receiptText: Record<string, string> = { ACCEPTED: "正常观察已记录，待管理员复核", DIFFERENCE_REVIEW: "差异已记录，待复核",
    DUPLICATE: "重复证据已保留，未重复计数", CONFLICT_REVIEW: "事实冲突，待复核", LATE_REVIEW: "迟到证据已追加，未改原报告",
    OLD_ASSIGNMENT_REVIEW: "旧分配证据待复核，未计数", FINDING_REVIEW: "发现线索待核实，未认定盘盈", TIME_REVIEW: "时间异常待复核，未自动采信",
    EXPIRED: "服务器明确过期，请安排补盘", REJECTED: "证据被明确拒绝，请安排补盘",
    EXPIRED_RECOLLECT: "本机记录已到保留期限，尚未取得回执，请管理员核查或安排补盘",
    AUTHORIZATION_REJECTED: "授权已失效，本机记录已安全清理，请安排补盘" };
const authorizationDenied = (error: unknown) => error instanceof MiniRequestError && (error.status === 401
    || error.status === 403 && /^(AUTH_|INVENTORY_PARTICIPATION|INVENTORY_CALIBRATION_IDENTITY)/.test(error.code));

export function createInventoryCollectionPage(getServices: () => MiniServices) {
    let services: MiniServices; let taskId = ""; let identity = ""; let live = false; let generation = 0;
    let eventId = ""; let source: InventoryObservation["source"] = "CODE";
    let scannedAt: string | null = null;
    const valid = (epoch: number) => live && epoch === generation && identity === JSON.stringify(services.sessions.identity);
    const runtime = () => {
        if (identity !== JSON.stringify(services.sessions.identity) || !services.inventory) throw new Error("身份已改变，请重新进入盘点任务。");
        return services.inventory();
    };
    const pending = (page: Context) => {
        const queue = runtime().queue;
        page.setData({ pending: queue.list().filter(event => event.taskId === taskId).map(event => ({ eventId: event.eventId,
            label: event.itemId ? event.assetCode || "已分配资产" : "待核实现场线索", state: queue.receipt(event.eventId) ? "回执已确认，待本机清理" : "待上传" })),
            receipts: queue.statuses(taskId).map(row => ({ eventId: row.eventId, message: receiptText[row.outcome] || "记录状态待核查" })) });
    };
    const select = (page: Context, item: InventoryCachedItem) => {
        if (page.data.photos.length) { page.setData({ error: "请先保存当前照片对应的观察，或明确放弃未保存记录。" }); return; }
        eventId = services.createKey();
        page.setData({ selected: item, candidates: [], photos: [], difference: false, personId: null, personName: "", custodyType: null,
            condition: "UNKNOWN", manualReason: source === "SCAN" ? "" : page.data.manualReason, unknownReason: "", responsibilityHint: "", description: "", message: "请核对现场实物，扫码本身不代表一致。" });
    };
    const catalog = (page: Context, task: InventoryCachedTask) => {
        const locations = new Map<string, string>(); const people = new Map<string, string>();
        for (const item of task.items) {
            const s = item.snapshot_json;
            if (s.locationId) locations.set(String(s.locationId), String(s.locationName || "账面位置"));
            if (s.responsibleEmployeeId) people.set(String(s.responsibleEmployeeId), String(s.responsibleEmployeeName || "任务责任人"));
            if (s.departmentResponsibleEmployeeId) people.set(String(s.departmentResponsibleEmployeeId), String(s.departmentResponsibleEmployeeName || "公用责任人"));
        }
        page.setData({ task, locations: [...locations].map(([id, name]) => ({ id, name })), people: [...people].map(([id, name]) => ({ id, name })) });
    };
    const identityChanged = (page: Context, epoch: number) => {
        if (live && epoch === generation && identity !== JSON.stringify(services.sessions.identity)) {
            page.setData({ ...blank(), error: "身份已变化，已隐藏原任务资料，请重新进入授权任务。" });
            if (!services.sessions.identity) services.navigate("/pages/login/index");
        }
    };
    const revoke = async (page: Context, epoch: number, message: string) => {
        const photos = page.data.photos; page.setData({ ...blank(), error: message });
        if (!valid(epoch) || !services.sessions.identity) return;
        const local = runtime();
        try {
            await local.queue.rejectTask(taskId);
            for (const photo of photos) await local.files.storage.removePhoto(photo.path);
        } catch {
            if (valid(epoch)) {
                try { local.files.clear(); }
                catch { if (identity === JSON.stringify(services.sessions.identity)) { try { services.sessions.clear(); } catch { /* identity is invalidated before cleanup */ } } }
                page.setData({ ...blank(), error: "访问已停止，本机清理需重试；请重新登录核对。" });
            }
        }
    };
    const lookup = async (page: Context, value: string, method: "SCAN" | "CODE") => {
        if (page.data.photos.length) { page.setData({ error: "请先保存或放弃当前未保存照片。" }); return; }
        const epoch = generation; source = method; scannedAt = method === "SCAN" ? new Date().toISOString() : null; page.setData({ busy: true, error: "", candidates: [], selected: null });
        try {
            const found = await services.sessions.request("POST", `${base}/tasks/${taskId}/identification`, { source: method, value }) as { kind: string; itemId?: string; message?: string };
            if (!valid(epoch)) { identityChanged(page, epoch); return; }
            const item = page.data.task?.items.find(row => row.id === found.itemId);
            if (found.kind === "ASSIGNED" && item) select(page, item);
            else { eventId = services.createKey(); page.setData({ query: value, difference: true, message: found.message || "任务外已登记资产仅记录发现线索，不认定盘盈。" }); }
        } catch (error) {
            if (!valid(epoch)) { identityChanged(page, epoch); return; }
            if (authorizationDenied(error)) {
                await revoke(page,epoch,"身份或任务授权已失效，停止采集。"); return;
            }
            eventId = services.createKey(); page.setData({ query: value, difference: true, offline: true, message: "离线暂无法核实标签，只能保存待核实线索；不能据此判盘盈。" });
        } finally { if (valid(epoch)) page.setData({ busy: false }); else identityChanged(page, epoch); }
    };
    return {
        data: blank(),
        onLoad(options: { taskId?: string }) { taskId = options.taskId || ""; },
        async onShow(this: Context) {
            live = true; const epoch = ++generation; services = getServices(); identity = JSON.stringify(services.sessions.identity);
            if (!/^[1-9][0-9]*$/.test(taskId) || !services.sessions.identity) { this.setData({ error: "请先登录并从授权任务进入。" }); return; }
            this.setData({ ...blank(), busy: true });
            try {
                const local = runtime(); await local.queue.expire();
                if (epoch === 1 && local.files.prune(local.queue.photoPaths()) > 0) this.setData({ message: "发现未保存的照片草稿，已清理；对应现场事实需重新采集。" });
                const cached = local.queue.cached(taskId); if (cached) catalog(this, cached); pending(this);
                let savingSnapshot = false;
                try {
                    let page = 1; let fetched = 0; let version: string | null = null; const items: InventoryCachedItem[] = []; let task: InventoryCachedTask;
                    while (true) {
                        const response = await services.sessions.request("GET", `/api/v1/mini/inventory-tasks/${taskId}?page=${page++}&pageSize=100`) as { task: InventoryCachedTask & { version: number; assignment_version: number }; items: (InventoryCachedItem & { executor_account_id?: string })[]; total: number };
                        if (!valid(epoch)) { identityChanged(this, epoch); return; } task = { id: response.task.id, name: response.task.name, task_status: response.task.task_status, items: [] };
                        const scopeVersion = `${response.task.assignment_version}:${response.task.task_status}`;
                        if (!Number.isSafeInteger(response.task.assignment_version) || version !== null && version !== scopeVersion) throw new Error("Task assignment changed during snapshot download");
                        version = scopeVersion; fetched += response.items.length;
                        items.push(...response.items.filter(item => item.executor_account_id === services.sessions.identity?.accountId));
                        if (fetched >= response.total) break;
                        if (!response.items.length) throw new Error("Task snapshot pagination incomplete");
                    }
                    task!.items = items; savingSnapshot = true; local.queue.cache(task!); savingSnapshot = false; catalog(this, task!);
                    const clientTime = new Date().toISOString();
                    try {
                        const calibration = await services.sessions.request("POST", `${base}/calibrations`, { deviceId: local.deviceId, clientTime }) as { calibrationId: string; clientTime: string; serverTime: string };
                        if (valid(epoch)) local.queue.calibrate(calibration);
                    } catch (error) {
                        if (authorizationDenied(error)) throw error;
                        if (valid(epoch)) this.setData({ message: "当前时间校准不可用，保留已有记录；新观察可能进入时间异常复核。" });
                    }
                } catch (error) {
                    if (!valid(epoch)) { identityChanged(this, epoch); return; }
                    if (authorizationDenied(error) || error instanceof MiniRequestError && error.status === 404 && error.code === "INVENTORY_TASK_NOT_FOUND") {
                        await revoke(this,epoch,"任务或身份已失效，已停止采集。");
                    } else if (savingSnapshot) this.setData({ offline: true, error: "任务快照持久保存失败，可能空间不足；仅保留此前已保存内容。" });
                    else this.setData({ offline: true, message: "当前离线，使用此前授权快照；上传时仍由服务器重新校验。" });
                }
            } catch { if (valid(epoch)) this.setData({ error: "持久存储不可用，不能开始采集。" }); }
            finally { if (valid(epoch)) this.setData({ busy: false }); else identityChanged(this, epoch); }
        },
        onHide() { live = false; generation++; },
        onUnload() { live = false; generation++; },
        onInput(this: Context, event: Input) {
            const field = event.currentTarget.dataset.field;
            if (["query", "roomDraft", "manualReason", "unknownReason", "responsibilityHint", "description"].includes(field || "")) this.setData({ [field!]: event.detail.value } as Partial<Data>);
        },
        async onRoom(this: Context, event: Input) {
            if (this.data.busy) return; const next = this.data.locations[Number(event.detail.value)]; if (!next) return;
            const epoch = generation;
            if (await services.confirm("确认现场位置", `现在进入“${next.name}”？后续逐件确认将使用此位置。`) && valid(epoch)) this.setData({ roomId: next.id, roomName: next.name });
        },
        async onRoomHint(this: Context) {
            const hint = this.data.roomDraft.trim(); if (!hint || this.data.busy) return; const epoch = generation;
            if (await services.confirm("确认待核实现场位置", `将“${hint}”记录为待核实位置线索？不能按正常项确认。`) && valid(epoch)) this.setData({ roomId: null, roomName: hint, difference: true });
        },
        async onDiscard(this: Context) {
            if (this.data.busy || !await services.confirm("放弃未保存记录", "仅删除当前尚未保存的照片与编辑，不影响已保存待上传记录。")) return;
            for (const photo of this.data.photos) await runtime().files.storage.removePhoto(photo.path);
            eventId = ""; this.setData({ photos: [], selected: null, difference: false, error: "", message: "未保存编辑已放弃。" });
        },
        onPerson(this: Context, event: Input) { const person = this.data.people[Number(event.detail.value)]; this.setData({ personId: person?.id ?? null, personName: person?.name || "" }); },
        onMode(this: Context, event: Input) { this.setData({ custodyType: ["STORAGE", "PERSONAL", "SHARED", null][Number(event.detail.value)] ?? null }); },
        onCondition(this: Context, event: Input) { this.setData({ condition: (["GOOD", "DAMAGED", "UNKNOWN"] as const)[Number(event.detail.value)] || "UNKNOWN" }); },
        onDifference(this: Context) { this.setData({ difference: true }); },
        async onScan(this: Context) {
            if (this.data.busy || !services.scan) return; const epoch = generation; this.setData({ busy: true, error: "" });
            try {
                const value = await services.scan();
                if (!valid(epoch)) { identityChanged(this, epoch); return; }
                this.setData({ busy: false }); await lookup(this, value, "SCAN");
            } catch { if (valid(epoch)) this.setData({ error: "扫码未完成，可按编码或名称查找并填写原因。" }); }
            finally { if (valid(epoch)) this.setData({ busy: false }); else identityChanged(this, epoch); }
        },
        async onSearch(this: Context) {
            if (this.data.busy || !this.data.query.trim()) return;
            const query = this.data.query.trim(); source = "CODE"; scannedAt = null;
            const candidates = this.data.task?.items.filter(item => String(item.snapshot_json.assetCode).includes(query) || String(item.snapshot_json.name).includes(query)) || [];
            if (candidates.length) this.setData({ candidates, selected: null, message: "请选择实际实物，手工查询确认需填写原因。" });
            else await lookup(this, query, "CODE");
        },
        onManual(this: Context) {
            if (this.data.busy || this.data.photos.length) { this.setData({ error: "请先保存或放弃当前未保存照片。" }); return; }
            source = "MANUAL"; scannedAt = null; eventId = services.createKey();
            this.setData({ selected: null, candidates: [], query: "", custodyType: null, personId: null, personName: "", condition: "UNKNOWN", unknownReason: "", description: "", manualReason: "", difference: true, message: "手工记录待核实实物，须填写原因、描述和照片；不自动建卡或认定盘盈。" });
        },
        onSelect(this: Context, event: Input) { const item = this.data.candidates.find(row => row.id === event.currentTarget.dataset.id); if (item) select(this, item); },
        async onPhoto(this: Context, event?: Input) {
            if (this.data.busy || !services.chooseInventoryPhoto && !services.chooseEvidence || this.data.photos.length >= 9) return;
            this.setData({ busy: true }); const epoch = generation;
            let selecting = true;
            try {
                const photo = services.chooseInventoryPhoto ? await services.chooseInventoryPhoto(event?.currentTarget.dataset.field === "album" ? "album" : "camera") : await services.chooseEvidence!();
                if (!valid(epoch)) { identityChanged(this, epoch); return; } selecting = false;
                if (this.data.photos.reduce((sum, value) => sum + value.size, 0) + photo.size > 50 * 1024 * 1024) throw new Error("此条观察的照片合计不能超过50MB。");
                eventId ||= services.createKey(); const durable = runtime().files.persist(photo, eventId, services.createKey());
                this.setData({ photos: [...this.data.photos, durable] });
            } catch (error) { if (valid(epoch)) this.setData({ error: selecting && error instanceof Error ? error.message : "照片未能持久保存，可能空间不足；尚未保存现场记录。" }); }
            finally { if (valid(epoch)) this.setData({ busy: false }); else identityChanged(this, epoch); }
        },
        async onPreviewPhoto(this: Context, event: Input) {
            if (this.data.busy || !services.openEvidence || !valid(generation)) return;
            const photo = this.data.photos.find(row => row.path === event.currentTarget.dataset.id); if (!photo) return;
            const epoch = generation;
            try { await services.openEvidence(photo.path, photo.name); }
            catch { if (valid(epoch)) this.setData({ error: "照片无法打开，可能已丢失；请核查并重新采集，尚未因此保存现场记录。" }); }
        },
        async onSave(this: Context, event: Input) {
            if (this.data.busy || !this.data.task || !["IN_PROGRESS", "WAITING_REVIEW"].includes(this.data.task.task_status)) { this.setData({ error: "任务尚未开始或已结束，停止新增采集。" }); return; }
            const normal = event.currentTarget.dataset.field === "normal"; const item = this.data.selected; const snapshot = item?.snapshot_json;
            if (!this.data.roomName || normal && (!this.data.roomId || !snapshot || this.data.roomId !== snapshot.locationId)) { this.setData({ error: "请先确认现场房间；位置不一致请记录差异。" }); return; }
            if (source !== "SCAN" && !this.data.manualReason.trim()) { this.setData({ error: "按编码或名称确认需填写手工原因。" }); return; }
            const epoch = generation; this.setData({ busy: true, error: "" });
            try {
                const local = runtime(); const now = new Date().toISOString(); eventId ||= services.createKey();
                const actual = normal ? { locationId: this.data.roomId, locationHint: null, custodyType: snapshot!.custodyType ? String(snapshot!.custodyType) : null,
                    responsibleEmployeeId: (snapshot!.responsibleEmployeeId || snapshot!.departmentResponsibleEmployeeId || null) as string | null,
                    responsibilityHint: null, condition: "GOOD" as const, unknownReason: null, description: null }
                    : { locationId: this.data.roomId, locationHint: this.data.roomId ? null : this.data.roomName, custodyType: this.data.custodyType, responsibleEmployeeId: this.data.personId,
                        responsibilityHint: this.data.responsibilityHint || null, condition: this.data.condition,
                        unknownReason: this.data.unknownReason || null, description: this.data.description || null };
                if ((!actual.locationId || !actual.custodyType || actual.condition === "UNKNOWN" || actual.custodyType !== "STORAGE" && !actual.responsibleEmployeeId) && !actual.unknownReason) throw new Error("不确定字段需填写待核实原因。");
                if ((!item || actual.condition === "DAMAGED") && !this.data.photos.length) throw new Error("发现线索或实物损坏需保存现场照片。");
                const saved = await local.queue.save({ eventId, taskId, itemId: item?.id || null, assignmentVersion: item?.assignment_version || 0,
                    recheckGeneration: item?.recheck_generation || 0, assetVersion: item?.asset_version ?? null, source,
                    assetCode: String(snapshot?.assetCode || this.data.query || "") || null, manualReason: this.data.manualReason || null,
                    actual, rawScannedAt: scannedAt, rawObservedAt: now, calibrationId: local.queue.calibration()?.calibrationId || null, photos: this.data.photos });
                if (!valid(epoch)) { identityChanged(this, epoch); return; }
                this.setData({ selected: null, photos: [], difference: false, message: `已在本机保存，待上传（${saved.expiresAt}前同步）。` }); eventId = ""; pending(this);
            } catch (error) { if (valid(epoch)) this.setData({ error: error instanceof Error ? error.message : "保存失败（可能空间不足），尚未记录。" }); }
            finally { if (valid(epoch)) this.setData({ busy: false }); else identityChanged(this, epoch); }
        },
        async onSync(this: Context) {
            if (this.data.busy) return; const epoch = generation; let activeEvent: string | null = null; this.setData({ busy: true, error: "" });
            try {
                const local = runtime();
                for (const entry of local.queue.list().filter(row => row.taskId === taskId)) {
                    activeEvent = entry.eventId;
                    const known = local.queue.receipt(entry.eventId);
                    if (known) { await local.queue.acknowledge(entry.eventId, known); pending(this); continue; }
                    const event = await local.queue.uploadable(entry.eventId); if (!valid(epoch)) { identityChanged(this, epoch); return; }
                    const photoIds: string[] = [];
                    for (const photo of event.photos) {
                        let objectId = local.queue.photoReceipt(event.eventId, photo.path);
                        if (!objectId) {
                            const key = photo.path.match(/-([0-9a-f-]{36})\.[^.]+$/i)?.[1]; if (!key) throw new Error("照片标识丢失，请补盘。");
                            const receipt = await services.sessions.uploadEvidence(`${base}/tasks/${taskId}/events/${event.eventId}/photos`, photo.path, { key, version: 0, deviceId: local.deviceId }) as { objectId: string };
                            if (!valid(epoch)) { identityChanged(this, epoch); return; } local.queue.recordPhotoReceipt(event.eventId, photo.path, receipt.objectId); objectId = receipt.objectId;
                        }
                        photoIds.push(objectId);
                    }
                    const payload: Partial<typeof event> = { ...event };
                    delete payload.photos; delete payload.taskId;
                    const receipt = await services.sessions.request("POST", `${base}/tasks/${taskId}/events`, { ...payload, photoIds }, { key: event.eventId, version: event.assignmentVersion }) as { eventId: string; outcome: string };
                    if (!valid(epoch)) { identityChanged(this, epoch); return; }
                    if (receipt.eventId !== event.eventId || !receiptText[receipt.outcome]) throw new Error("回执无法核实，保留原记录重试。");
                    await local.queue.acknowledge(event.eventId, receipt.outcome); this.setData({ message: receiptText[receipt.outcome] }); pending(this);
                }
            } catch (error) {
                if (!valid(epoch)) { identityChanged(this, epoch); return; }
                if (authorizationDenied(error)) {
                    await revoke(this,epoch,"身份或任务权限失效，已停止上传并清理对应缓存。");
                } else if (activeEvent && error instanceof MiniRequestError && ["INVENTORY_PHOTO_REJECTED", "INVENTORY_PHOTO_NOT_AUTHORIZED", "INVENTORY_PHOTO_EXPIRED", "INVALID_ATTACHMENT", "ATTACHMENT_TOO_LARGE"].includes(error.code)) {
                    await runtime().queue.acknowledge(activeEvent, "REJECTED"); pending(this);
                    this.setData({ error: "该项照片被明确拒绝，已清理对应缓存，请安排补盘；其他待上传记录保留。" });
                } else this.setData({ error: error instanceof MiniRequestError ? "上传未完成，保留原记录重试。" : error instanceof Error ? error.message : "上传未完成。" });
            } finally { if (valid(epoch)) this.setData({ busy: false }); else identityChanged(this, epoch); }
        },
    };
}
