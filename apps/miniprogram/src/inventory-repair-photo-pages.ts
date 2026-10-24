import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import { photoReferences, type ReviewPhoto, type ReviewPhotoReference } from "./inventory-review-photos.ts";
import { readRepairInventoryPhoto, repairPhotoScope } from "./inventory-repair-photos.ts";
interface Origin { taskId: string; observationId: string; photoIds: string[] }
interface Data { repairId: string; observationId: string; taskId: string; photos: ReviewPhotoReference[];
    photoPath: string; photoName: string; busy: boolean; cleanupPending: boolean; error: string }
interface Context { data: Data; setData(value: Partial<Data>): void }
const id = (value: unknown): value is string => typeof value === "string" && /^[1-9][0-9]{0,18}$/.test(value);
const blank = (): Data => ({ repairId: "", observationId: "", taskId: "", photos: [], photoPath: "", photoName: "", busy: false, cleanupPending: false, error: "" });

export function createInventoryRepairPhotoPage(getServices: () => MiniServices) {
    let services: MiniServices | null = null, repair = "", observation = "", identity = "", live = false, generation = 0;
    const downloaded = new Set<string>();
    const owner = () => JSON.stringify(services?.sessions.identity ?? null);
    const current = (epoch: number) => live && generation === epoch && identity === owner();
    const cleanup = (page: Context) => {
        page.setData({ photoPath: "", photoName: "" });
        for (const path of [...downloaded]) {
            try { services!.sessions.discardInventoryPhoto(path); downloaded.delete(path); } catch { /* Keep the path for an explicit retry. */ }
        }
        page.setData({ cleanupPending: downloaded.size > 0 });
        return downloaded.size === 0;
    };
    const changed = (page: Context, epoch: number) => {
        if (!live || epoch !== generation || identity === owner()) return;
        generation++; repair = ""; observation = ""; identity = owner();
        page.setData({ ...blank(), error: "身份或绑定已变化，请从当前维修单重新进入。" }); cleanup(page);
    };
    const failure = (page: Context, error: unknown, epoch: number) => {
        if (!current(epoch)) { changed(page, epoch); return; }
        if (error instanceof MiniRequestError && [401, 403, 404].includes(error.status)) {
            page.setData({ ...blank(), error: "当前维修处理权或证据权限已失效，原资料已隐藏。" }); cleanup(page);
        } else page.setData({ error: "原始照片暂不可读取，请刷新核对；证据引用保留。" });
    };
    const load = async (page: Context) => {
        if (!services || page.data.busy || !id(repair) || !id(observation) || !cleanup(page)) return;
        const epoch = ++generation; page.setData({ ...blank(), repairId: repair, observationId: observation, busy: true });
        try {
            const detail = await services.sessions.request("GET", `/api/v1/mini/repairs/${repair}`) as { inventoryEvidence?: Origin[] };
            if (!current(epoch)) { changed(page, epoch); return; }
            const origin = detail.inventoryEvidence?.find(row => row.observationId === observation);
            if (!origin) throw new MiniRequestError(403, "INVENTORY_REPAIR_EVIDENCE_FORBIDDEN");
            const metadata = await services.sessions.request("GET", repairPhotoScope(repair, observation)) as { items: ReviewPhoto[] };
            if (current(epoch)) page.setData({ taskId: origin.taskId, photos: photoReferences(origin.photoIds, metadata.items) });
            else changed(page, epoch);
        } catch (error) { failure(page, error, epoch); }
        finally { if (current(epoch)) page.setData({ busy: false }); }
    };
    return {
        data: blank(),
        onLoad(this: Context, options: { repairId?: string; observationId?: string }) {
            repair = id(options.repairId) ? options.repairId : ""; observation = id(options.observationId) ? options.observationId : "";
            if (!repair || !observation) this.setData({ error: "来源编号无效，请从维修单重新进入。" });
        },
        async onShow(this: Context) {
            live = true; generation++; services = getServices();
            const next = owner();
            if (identity && identity !== next) { repair = ""; observation = ""; this.setData({ ...blank(), error: "身份已变化，请从当前维修单重新进入。" }); cleanup(this); }
            identity = next; await load(this);
        },
        onHide(this: Context) { live = false; generation++; this.setData(blank()); cleanup(this); },
        onUnload(this: Context) { live = false; generation++; repair = ""; observation = ""; this.setData(blank()); cleanup(this); },
        async onRefresh(this: Context) { await load(this); },
        onClose(this: Context) { if (!this.data.busy) cleanup(this); },
        onBack(this: Context) { if (!this.data.busy && cleanup(this)) services?.navigate(`/pages/repairs/index${repair ? "?id=" + repair : ""}`); },
        async onRead(this: Context, event: { currentTarget?: { dataset?: { id?: string } } }) {
            if (!services || this.data.busy || !cleanup(this)) return;
            const photo = this.data.photos.find(row => row.objectId === event.currentTarget?.dataset?.id && row.readable)?.photo;
            if (!photo) return;
            const epoch = ++generation; this.setData({ busy: true, error: "" });
            try {
                const path = await readRepairInventoryPhoto(services.sessions, repair, observation, photo, () => services!.createKey(), () => current(epoch));
                downloaded.add(path);
                if (!current(epoch)) {
                    try { services.sessions.discardInventoryPhoto(path); downloaded.delete(path); }
                    catch { this.setData({ cleanupPending: true }); }
                    changed(this, epoch); return;
                }
                this.setData({ photoPath: path, photoName: photo.filename });
            } catch (error) { failure(this, error, epoch); }
            finally { if (current(epoch)) this.setData({ busy: false }); }
        }
    };
}
