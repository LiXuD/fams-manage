import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
interface Entry { evidence: { observation_id: string; comparison_kind: string; snapshot_json: { rawObservedAt: string; calibratedObservedAt: string | null; receivedAt: string; acceptedObservationId: string | null } };
    review: { decision_version: number; result: string }; todoOpen: boolean }
interface Decision { decision_version: number; method: string; reason: string; evidence: string; linked_task_id: string | null; actor_username: string; decided_at: string }
interface Board { task: { version: number; name: string; range_json: unknown }; items: Entry[]; total: number }
interface Detail { case: Entry; history: { items: Decision[]; page: number; pageSize: number; total: number } }
interface Pending { path: string; key: string; version: number; body?: unknown }
interface Data { taskId: string; board: Board | null; detail: Detail | null; entries: { id: string; label: string }[]; history: { id: string; text: string }[];
    page: number; busy: boolean; retry: boolean; error: string; reason: string; evidence: string; methodIndex: number; methodNames: string[]; draftName: string; startsAt: string; dueAt: string }
interface Context { data: Data; setData(value: Partial<Data>): void }
const methods = ["NOTE", "LINK_RECHECK", "REPLACE_RECHECK", "CONFIRM_RECHECK"];
const methodNames = ["记录补充结论", "创建关联复盘草稿", "已结束复盘后另建一轮", "核对实际完成的复盘"];
const kinds: Record<string, string> = { FROZEN_CONFLICT: "与原采信事实冲突", NO_ACCEPTED: "原项没有有效采信", TIME_UNVERIFIED: "原时间待核实", FINDING: "任务外实物线索", FACTS_MATCH: "事实一致的补充线索" };
const states: Record<string, string> = { OPEN: "待核实", INFORMATION: "补充线索", FOLLOWUP_OPEN: "复盘尚待实际完成", REVIEWED: "已记录补充结论" };
const blank = (): Data => ({ taskId: "", board: null, detail: null, entries: [], history: [], page: 1, busy: false, retry: false, error: "", reason: "", evidence: "", methodIndex: 0, methodNames, draftName: "", startsAt: "", dueAt: "" });
export function createInventoryLatePage(getServices: () => MiniServices) {
    let services: MiniServices, identity = "", live = false, generation = 0, pending: Pending | null = null;
    const current = (epoch: number) => live && epoch === generation && identity === JSON.stringify(services.sessions.identity);
    const failure = (page: Context, cause: unknown) => {
        if (cause instanceof MiniRequestError && [401, 403, 404].includes(cause.status)) {
            pending = null; page.setData({ ...blank(), taskId: page.data.taskId, error: "当前身份、权限或记录失效，已隐藏原资料与操作。" });
        } else if (cause instanceof MiniRequestError && [400, 409].includes(cause.status)) {
            pending = null; page.setData({ retry: false, error: cause.fields.processedBy ? `已由 ${cause.fields.processedBy} 处理，请刷新核对。` : "条件或补充决定已变化；复盘须实际完成对应资产采信，请刷新核对。" });
        } else page.setData({ retry: !!pending, error: pending ? "结果尚未确认，请保留原操作重试。" : "读取未完成，请重试。" });
    };
    const finish = (page: Context, epoch: number) => {
        if (current(epoch)) page.setData({ busy: false });
        else if (live && epoch === generation) { pending = null; page.setData({ ...blank(), taskId: page.data.taskId, error: "身份已变化，已隐藏原资料。" }); }
    };
    const read = async (page: Context) => {
        const epoch = ++generation; page.setData({ busy: true, error: "", board: null, detail: null, entries: [], history: [] });
        try { const board = await services.sessions.request("GET", `/api/v1/mini/inventory-late/tasks/${page.data.taskId}?page=${page.data.page}&pageSize=20`) as Board;
            if (current(epoch)) page.setData({ board, retry: !!pending, entries: board.items.map(row => ({ id: row.evidence.observation_id,
                label: `证据 ${row.evidence.observation_id} · ${kinds[row.evidence.comparison_kind] || row.evidence.comparison_kind} · ${states[row.review.result] || row.review.result} · ${row.todoOpen ? '管理员待办开放' : '没有开放待办'}` })) });
        } catch (cause) { if (current(epoch)) failure(page, cause); } finally { finish(page, epoch); }
    };
    const send = async (page: Context, operation: Pending, epoch: number) => {
        try { await services.sessions.request("POST", operation.path, operation.body, operation);
            if (current(epoch)) { pending = null; page.setData({ retry: false }); await read(page); }
        } catch (cause) { if (current(epoch)) failure(page, cause); }
    };
    const confirm = async (page: Context, operation: Pending, message: string) => {
        const epoch = generation; page.setData({ busy: true, error: "" });
        try { if (!await services.confirm("核对终态补充处理", message)) return;
            if (!current(epoch)) return; pending = operation; page.setData({ retry: true }); await send(page, operation, epoch);
        } catch (cause) { if (current(epoch)) failure(page, cause); } finally { finish(page, epoch); }
    };
    return {
        data: blank(),
        onLoad(this: Context, options: { taskId?: string }) { if (/^[1-9][0-9]*$/.test(options.taskId ?? "")) this.setData({ taskId: options.taskId! }); },
        async onShow(this: Context) {
            live = true;
            try { services = getServices(); const next = JSON.stringify(services.sessions.identity);
                if (next !== identity) { pending = null; this.setData({ ...blank(), taskId: this.data.taskId }); } identity = next;
                if (this.data.taskId) await read(this);
            } catch { this.setData({ ...blank(), taskId: this.data.taskId, error: "服务未就绪，请重试。" }); }
        },
        onHide(this: Context) { live = false; generation++; this.setData({ ...blank(), taskId: this.data.taskId }); },
        onUnload(this: Context) { live = false; generation++; pending = null; this.setData(blank()); },
        async onRefresh(this: Context) { if (!this.data.busy) await read(this); },
        async onPrepare(this: Context) {
            if (this.data.busy || pending || !this.data.board || !current(generation)) return;
            await confirm(this, { path: `/api/v1/mini/inventory-late/tasks/${this.data.taskId}/prepare`, key: services.createKey(), version: this.data.board.task.version },
                "把既有补充或待核时证据纳入清单，每批最多100条；保留原始分类和回执，不改原报告、计数或日期。");
        },
        async onOpen(this: Context, event: { currentTarget: { dataset: { id?: string } } }) {
            const id = event.currentTarget.dataset.id;
            if (this.data.busy || pending || !current(generation) || !this.data.entries.some(row => row.id === id)) return;
            const epoch = ++generation; this.setData({ busy: true, detail: null, history: [], error: "" });
            try { const detail = await services.sessions.request("GET", `/api/v1/mini/inventory-late/tasks/${this.data.taskId}/observations/${id}`) as Detail;
                if (current(epoch)) this.setData({ detail, reason: "", evidence: "", methodIndex: 0, draftName: `${this.data.board?.task.name || '盘点'} 关联复盘`.slice(0, 128),
                    startsAt: new Date(Date.now() + 3600000).toISOString(), dueAt: new Date(Date.now() + 7 * 86400000).toISOString(),
                    history: detail.history.items.map(row => ({ id: String(row.decision_version), text: `${row.actor_username} · ${row.decided_at} · ${methodNames[methods.indexOf(row.method)] || row.method}\n${row.reason} · ${row.evidence}\n关联复盘 ${row.linked_task_id || '无'}` })) });
            } catch (cause) { if (current(epoch)) failure(this, cause); } finally { finish(this, epoch); }
        },
        async onHistoryPage(this: Context, event: { currentTarget: { dataset: { delta?: string } } }) {
            if (this.data.busy || pending || !current(generation) || !this.data.detail) return;
            const delta = Number(event.currentTarget.dataset.delta), history = this.data.detail.history, next = history.page + delta;
            if (![1,-1].includes(delta) || next < 1 || (next-1)*20 >= history.total) return;
            const epoch = ++generation, id = this.data.detail.case.evidence.observation_id; this.setData({ busy: true, error: "" });
            try { const detail = await services.sessions.request("GET", `/api/v1/mini/inventory-late/tasks/${this.data.taskId}/observations/${id}?page=${next}&pageSize=20`) as Detail;
                if(current(epoch)) this.setData({ detail, history: detail.history.items.map(row => ({ id: String(row.decision_version), text: `${row.actor_username} · ${row.decided_at} · ${methodNames[methods.indexOf(row.method)] || row.method}\n${row.reason} · ${row.evidence}\n关联复盘 ${row.linked_task_id || '无'}` })) });
            } catch(cause) { if(current(epoch)) failure(this,cause); } finally { finish(this,epoch); }
        },
        onField(this: Context, event: { currentTarget: { dataset: { field?: string } }; detail: { value: string } }) {
            const name = event.currentTarget.dataset.field;
            if (!this.data.busy && !pending && ["reason", "evidence", "draftName", "startsAt", "dueAt"].includes(name || "")) this.setData({ [name!]: event.detail.value });
        },
        onMethod(this: Context, event: { detail: { value: string } }) {
            const index = Number(event.detail.value); if (!this.data.busy && !pending && Number.isInteger(index) && index >= 0 && index < methods.length) this.setData({ methodIndex: index });
        },
        async onDecide(this: Context) {
            if (this.data.busy || pending || !current(generation) || !this.data.board || !this.data.detail || this.data.reason.trim().length < 3 || this.data.evidence.trim().length < 3) return;
            const method = methods[this.data.methodIndex], body: Record<string, unknown> = { method, reason: this.data.reason.trim(), evidence: this.data.evidence.trim() };
            if (["LINK_RECHECK", "REPLACE_RECHECK"].includes(method!)) {
                const start = new Date(this.data.startsAt), due = new Date(this.data.dueAt);
                if (!this.data.draftName.trim() || !Number.isFinite(start.getTime()) || !Number.isFinite(due.getTime()) || due <= start) { this.setData({ error: "请核对复盘名称及起止时间。" }); return; }
                body.recheck = { name: this.data.draftName.trim(), range: JSON.parse(JSON.stringify(this.data.board.task.range_json)), startsAt: start.toISOString(), dueAt: due.toISOString() };
            }
            await confirm(this, { path: `/api/v1/mini/inventory-late/tasks/${this.data.taskId}/observations/${this.data.detail.case.evidence.observation_id}/decisions`, key: services.createKey(), version: this.data.detail.case.review.decision_version, body },
                `${methodNames[this.data.methodIndex]}：${body.reason}。依据：${body.evidence}。仅追加补充决定，原报告、完成数和最近盘点日期不变。`);
        },
        async onRetry(this: Context) { if (this.data.busy || !pending || !current(generation) || !this.data.board) return;
            const epoch = generation, operation = pending; this.setData({ busy: true, error: "" }); try { await send(this, operation, epoch); } finally { finish(this, epoch); } },
        async onPage(this: Context, event: { currentTarget: { dataset: { delta?: string } } }) {
            const delta = Number(event.currentTarget.dataset.delta), next = this.data.page + delta;
            if (this.data.busy || pending || !current(generation) || !this.data.board || ![1, -1].includes(delta) || next < 1 || (next - 1) * 20 >= this.data.board.total) return;
            this.setData({ page: next }); await read(this);
        }
    };
}
