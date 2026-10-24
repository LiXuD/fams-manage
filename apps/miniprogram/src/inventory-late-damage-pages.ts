import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
interface Damage { version: number; damage_status: string; repair_id: string | null; repair_status: string | null }
interface Detail { assetId: string; currentAsset: { version: number; assetCode?: string } | null; damage: Damage;
    originalEvidence: { rawObservedAt: string }; recheckResult: string; todoOpen: boolean;
    history: { items: { after_version: number; method: string; damage_status: string; repair_id: string; actor_username: string; reason: string; evidence: string }[]; page: number; total: number } }
interface Linked { id: string; assetId: string; version: number; status: string }
interface Pending { path: string; key: string; version: number; body: unknown }
interface Data { taskId: string; observationId: string; detail: Detail | null; history: { id: string; text: string }[]; page: number;
    busy: boolean; retry: boolean; error: string; notice: string; methodIndex: number; methodNames: string[];
    reason: string; evidence: string; fault: string; impactIndex: number; impactNames: string[];
    discoveryDate: string; discoveryTime: string; discoverySecond: string; physicalChecked: boolean; timeChecked: boolean;
    repairId: string; linked: Linked | null }
interface Context { data: Data; setData(value: Partial<Data>): void }
const methods = ["FORMAL_REPAIR", "LINK_REPAIR", "REFRESH_REPAIR"];
const methodNames = ["创建正式维修", "关联已有正式维修", "核对正式维修实际进度"];
const impacts = ["LOW", "MEDIUM", "HIGH"];
const id = (value: unknown): value is string => typeof value === "string" && /^[1-9][0-9]{0,18}$/.test(value);
const blank = (): Data => ({ taskId: "", observationId: "", detail: null, history: [], page: 1, busy: false, retry: false, error: "", notice: "",
    methodIndex: 0, methodNames, reason: "", evidence: "", fault: "", impactIndex: 2, impactNames: ["低", "中", "高"],
    discoveryDate: "", discoveryTime: "", discoverySecond: "00.000", physicalChecked: false, timeChecked: false, repairId: "", linked: null });

export function createInventoryLateDamagePage(getServices: () => MiniServices) {
    let services: MiniServices | null = null, identity = "", live = false, generation = 0;
    let task = "", observation = "", pending: Pending | null = null;
    const owner = () => JSON.stringify(services?.sessions.identity ?? null);
    const current = (epoch: number) => live && generation === epoch && identity === owner();
    const path = () => `/api/v1/mini/inventory-late/tasks/${task}/observations/${observation}/damage-repair`;
    const reset = (page: Context) => page.setData({ ...blank(), taskId: task, observationId: observation });
    const changed = (page: Context, epoch: number) => {
        if (live && epoch === generation && identity !== owner()) {
            generation++; pending = null; task = ""; observation = ""; identity = owner(); reset(page);
            page.setData({ error: "身份或绑定已变化，请从当前补充证据重新进入。" });
        }
    };
    const failure = (page: Context, error: unknown, epoch: number, write = false) => {
        if (!current(epoch)) { changed(page, epoch); return; }
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) {
            pending = null; reset(page); page.setData({ error: "当前身份、权限或原证据已失效，已隐藏原资料。" });
        } else {
            if (write && error instanceof MiniRequestError && error.status >= 400 && error.status < 500) pending = null;
            page.setData({ retry: !!pending, error: error instanceof MiniRequestError && error.fields.processedBy
                ? `已由 ${error.fields.processedBy} 处理，请刷新核对独立损坏版本。`
                : pending ? "结果尚未确认，请保留原键载荷并重试。" : "读取或核实未完成，请刷新独立损坏及正式维修。" });
        }
    };
    const finish = (page: Context, epoch: number) => { if (current(epoch)) page.setData({ busy: false }); else changed(page, epoch); };
    const read = async (page: Context, target = page.data.page) => {
        if (!services || !id(task) || !id(observation) || page.data.busy) return;
        const epoch = ++generation; page.setData({ busy: true, error: "" });
        try {
            const detail = await services.sessions.request("GET", path() + `?page=${target}&pageSize=20`) as Detail;
            if (current(epoch)) page.setData({ detail, page: target, retry: !!pending,
                ...(!pending ? { methodIndex: detail.damage.damage_status === "PROCESSING" ? 2 : 0, physicalChecked: false, timeChecked: false } : {}),
                history: detail.history.items.map(row => ({ id: String(row.after_version), text: `第 ${row.after_version} 版 · ${methodNames[methods.indexOf(row.method)] || row.method} · ${row.damage_status} · ${row.actor_username}\n${row.reason} · ${row.evidence}\n维修 ${row.repair_id}` })) });
        } catch (error) { failure(page, error, epoch); } finally { finish(page, epoch); }
    };
    const send = async (page: Context, operation: Pending, epoch: number) => {
        let saved = false;
        try {
            await services!.sessions.request("POST", operation.path, operation.body, operation);
            if (current(epoch)) { pending = null; saved = true; page.setData({ retry: false, reason: "", evidence: "", notice: "损坏处理已追加；原补充复核仍须分别完成。" }); }
        } catch (error) { failure(page, error, epoch, true); } finally { finish(page, epoch); }
        if (saved && current(epoch)) await read(page);
    };
    return {
        data: blank(),
        onLoad(this: Context, options: { taskId?: string; observationId?: string }) {
            task = id(options.taskId) ? options.taskId : ""; observation = id(options.observationId) ? options.observationId : ""; reset(this);
            if (!task || !observation) this.setData({ error: "请从当前补充证据重新进入。" });
        },
        async onShow(this: Context) {
            live = true; generation++;
            try {
                services = getServices(); const next = owner();
                if (identity && identity !== next) { pending = null; task = ""; observation = ""; reset(this); this.setData({ error: "身份已变化，请从当前补充证据重新进入。" }); }
                identity = next; await read(this);
            } catch { pending = null; reset(this); this.setData({ error: "服务未就绪，请重试。" }); }
        },
        onHide(this: Context) { live = false; generation++; reset(this); },
        onUnload(this: Context) { live = false; generation++; pending = null; task = ""; observation = ""; reset(this); },
        async onRefresh(this: Context) { if (current(generation)) await read(this); },
        onField(this: Context, event: { currentTarget: { dataset: { field?: string } }; detail: { value: string } }) {
            const field = event.currentTarget.dataset.field;
            if (!this.data.busy && !pending && current(generation) && ["reason", "evidence", "fault", "discoveryDate", "discoveryTime", "discoverySecond", "repairId"].includes(field ?? ""))
                this.setData({ [field!]: event.detail.value, ...(field === "repairId" ? { linked: null } : {}) });
        },
        onMethod(this: Context, event: { detail: { value: string } }) {
            const index = Number(event.detail.value);
            if (!this.data.busy && !pending && current(generation) && Number.isInteger(index) && index >= 0 && index < methods.length) this.setData({ methodIndex: index });
        },
        onImpact(this: Context, event: { detail: { value: string } }) {
            const index = Number(event.detail.value);
            if (!this.data.busy && !pending && current(generation) && Number.isInteger(index) && index >= 0 && index < impacts.length) this.setData({ impactIndex: index });
        },
        onChecked(this: Context, event: { detail: { value: string[] } }) {
            if (!this.data.busy && !pending && current(generation)) this.setData({ physicalChecked: event.detail.value.includes("physical"), timeChecked: event.detail.value.includes("time") });
        },
        async onInspect(this: Context) {
            if (!services || this.data.busy || pending || !current(generation) || !this.data.detail || !id(this.data.repairId.trim())) return;
            const epoch = ++generation, requested = this.data.repairId.trim(); this.setData({ busy: true, error: "", linked: null });
            try {
                const result = await services.sessions.request("GET", `/api/v1/mini/repairs/${requested}`) as Linked;
                if (current(epoch) && requested === this.data.repairId.trim()) {
                    if (result.id === requested && result.assetId === this.data.detail.assetId && ["PENDING_ADMIN_DECISION", "WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING"].includes(result.status)) this.setData({ linked: result });
                    else this.setData({ error: "请选择同一资产的有效正式维修单。" });
                }
            } catch (error) { failure(this, error, epoch); } finally { finish(this, epoch); }
        },
        async onDecide(this: Context) {
            const detail = this.data.detail;
            if (!services || this.data.busy || pending || !current(generation) || !detail || detail.damage.damage_status === "CLOSED" || this.data.reason.trim().length < 3 || this.data.evidence.trim().length < 10) return;
            const method = methods[this.data.methodIndex]; if (!method) return;
            let repair: unknown;
            if (method !== "REFRESH_REPAIR") {
                const date = this.data.discoveryDate, time = this.data.discoveryTime, second = this.data.discoverySecond;
                const instant = new Date(`${date}T${time}:${second}`);
                if (detail.damage.damage_status !== "OPEN" || !detail.currentAsset || !this.data.physicalChecked || !this.data.timeChecked
                    || !/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time) || !/^[0-5]\d\.\d{3}$/.test(second)
                    || !Number.isFinite(instant.getTime()) || instant.getTime() > Date.now()
                    || instant.getFullYear() !== Number(date.slice(0, 4)) || instant.getMonth() + 1 !== Number(date.slice(5, 7)) || instant.getDate() !== Number(date.slice(8, 10))) {
                    this.setData({ error: "请独立核实实际损坏及有效发现时间，已有维修须先核对进度。" }); return;
                }
                if (method === "FORMAL_REPAIR" && this.data.fault.trim().length < 3) { this.setData({ error: "请填写实际故障。" }); return; }
                const linked = this.data.linked;
                if (method === "LINK_REPAIR" && (!linked || linked.id !== this.data.repairId.trim() || linked.assetId !== detail.assetId)) { this.setData({ error: "请先核对同资产正式维修单。" }); return; }
                repair = { verifiedDiscoveryAt: instant.toISOString(), physicalDamageChecked: true, discoveryTimeVerified: true,
                    ...(method === "FORMAL_REPAIR" ? { fault: this.data.fault.trim(), impact: impacts[this.data.impactIndex] } : { repairId: linked!.id, repairVersion: linked!.version }) };
            } else if (!detail.damage.repair_id) { this.setData({ error: "尚未关联正式维修。" }); return; }
            const body = { method, assetVersion: detail.currentAsset?.version ?? 0, reason: this.data.reason.trim(), evidence: this.data.evidence.trim(), ...(repair ? { repair } : {}) };
            const operation: Pending = { path: path(), key: services.createKey(), version: detail.damage.version, body: JSON.parse(JSON.stringify(body)) as unknown };
            const epoch = generation; this.setData({ busy: true, error: "" });
            try {
                if (!await services.confirm("核对独立损坏处理", `${methodNames[this.data.methodIndex]}。仅追加独立损坏决定，原补充复核和封存报告不改。`)) return;
                if (!current(epoch)) return;
                pending = operation; this.setData({ retry: true }); await send(this, operation, epoch);
            } catch (error) { failure(this, error, epoch); } finally { finish(this, epoch); }
        },
        async onRetry(this: Context) {
            if (!services || this.data.busy || !pending || !current(generation)) return;
            const epoch = generation, operation = pending; this.setData({ busy: true, error: "" }); await send(this, operation, epoch);
        },
        async onHistoryPage(this: Context, event: { currentTarget: { dataset: { delta?: string } } }) {
            const delta = Number(event.currentTarget.dataset.delta), next = this.data.page + delta;
            if (this.data.busy || pending || !current(generation) || !this.data.detail || ![1, -1].includes(delta) || next < 1 || (next - 1) * 20 >= this.data.detail.history.total) return;
            await read(this, next);
        },
        onRepair(this: Context) {
            const repair = this.data.detail?.damage.repair_id;
            if (!this.data.busy && !pending && current(generation) && id(repair)) services!.navigate(`/pages/repairs/index?id=${repair}`);
        },
        onBack(this: Context) { if (!this.data.busy && !pending && current(generation)) services!.navigate(`/pages/inventory-late/index?taskId=${task}`); }
    };
}
