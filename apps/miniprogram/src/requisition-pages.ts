import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";
import type { Requisition, RequisitionCatalog, RequisitionChoice, RequisitionDemandInput, RequisitionPage, RequisitionSummary }
    from "../../../packages/api-types/src/requisition";

interface FormRow extends RequisitionDemandInput {
    employeeChoices: RequisitionChoice[]; categoryIndex: number; organizationIndex: number;
    employeeIndex: number; locationIndex: number;
}
interface Data {
    accountId: string; busy: boolean; error: string; notice: string; rows: RequisitionSummary[]; selected: Requisition | null;
    page: number; total: number; showForm: boolean; retryable: boolean; mode: "new" | "revise" | "change";
    categories: RequisitionChoice[]; organizations: RequisitionChoice[]; employees: RequisitionChoice[];
    locations: RequisitionChoice[]; canApplyShared: boolean; form: FormRow[]; reason: string;
}
interface Context { data: Data; setData(value: Partial<Data>): void }
interface Event { detail: { value: string }; currentTarget: { dataset: { index?: number; field?: string; id?: string } } }
function message(error: unknown) {
    if (error instanceof MiniRequestError) {
        if (error.status === 401) return "登录已失效，请重新登录。";
        if (error.status === 403 || error.status === 404) return "没有当前申请或操作的权限。";
        if (error.status === 409) return "申请、人员或处理状态已变更，请刷新核对后重试。";
        if (error.status === 400) return "请核对必填项、数量、日期、目标和原因。";
    }
    return "尚未确认操作结果，请重试上次操作或刷新查询。";
}

/** M03/M04/M07 share these actual native handlers and the opaque mini session client. */
export function createRequisitionPage(getServices: () => MiniServices, initiallyCreate = false) {
    let live = false; let generation = 0; let services: MiniServices | null = null;
    let identity = ""; let selfEmployee = ""; let selfOrg = "";
    let pending: { path: string; method: string; key: string; version: number; body?: unknown } | null = null;
    const path = "/api/v1/mini/requisitions";
    const choices = (page: Context, field: string) => field === "category" ? page.data.categories
        : field === "organization" ? page.data.organizations : field === "location" ? page.data.locations : page.data.employees;
    const defaultRow = (page: Context): FormRow => {
        const reference = (field: string, preferred?: string) => {
            const rows = choices(page, field); const row = rows.find(row => row.id === preferred) ?? rows[0];
            return { id: row?.id ?? "", version: row?.version ?? 0 };
        };
        const org = reference("organization", selfOrg);
        const employees = page.data.employees.filter(e => e.organizationId === org.id);
        const employee = employees.find(e => e.id === selfEmployee) ?? employees[0];
        return { category: reference("category"), organization: org, employee: { id: employee?.id ?? "", version: employee?.version ?? 0 },
            location: reference("location"), specification: "", purpose: "", expectedDate: new Date().toISOString().slice(0, 10),
            custodyType: "PERSONAL", quantity: 1, preferredBrand: "", preferredModel: "", employeeChoices: employees,
            categoryIndex: 0, organizationIndex: Math.max(0, page.data.organizations.findIndex(o => o.id === org.id)),
            employeeIndex: Math.max(0, employees.findIndex(e => e.id === employee?.id)), locationIndex: 0 };
    };
    const load = async (page: Context) => {
        const current = generation;
        const result = await services!.sessions.request("GET", `${path}?page=${page.data.page}&pageSize=20`) as RequisitionPage;
        if (live && generation === current) page.setData({ rows: result.items, total: result.total });
    };
    const mutate = async (page: Context, suffix: string, body?: unknown, method = "POST") => {
        if (!services || page.data.busy) return;
        if (pending && pending.path !== path + suffix) { page.setData({ error: "上次结果未确认，请先重试或刷新。" }); return; }
        pending ??= { path: path + suffix, method, key: services.createKey(), version: page.data.selected?.version ?? 0,
            body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
        page.setData({ busy: true, error: "", notice: "" }); const current = generation;
        try {
            const result = await services.sessions.request(pending.method, pending.path, pending.body, pending) as Requisition;
            if (!live || generation !== current) return;
            pending = null; page.setData({ selected: result, showForm: false, retryable: false, notice: "操作已保存。" });
            await load(page);
        } catch (error) {
            if (live && generation === current) page.setData({ error: message(error), retryable: true });
            if (live && !services.sessions.identity) services.navigate("/pages/login/index");
        } finally { if (live && generation === current) page.setData({ busy: false }); }
    };
    const edit = (page: Context, mode: "new" | "revise" | "change") => {
        if (page.data.busy || pending) return;
        const form: FormRow[] = mode === "new" ? [defaultRow(page)] : page.data.selected!.demands.map(d => {
            const employeeChoices = page.data.employees.filter(e => e.organizationId === d.targetOrganizationId);
            const employeeId = d.targetEmployeeId ?? d.targetDepartmentEmployeeId!;
            return { category: { id: d.categoryId, version: d.categoryVersionSnapshot }, specification: d.specification,
                preferredBrand: d.preferredBrand ?? "", preferredModel: d.preferredModel ?? "", purpose: d.purpose,
                expectedDate: d.expectedDate, custodyType: d.custodyType,
                organization: { id: d.targetOrganizationId, version: d.organizationVersionSnapshot },
                employee: { id: employeeId, version: d.employeeVersionSnapshot }, location: { id: d.targetLocationId, version: d.locationVersionSnapshot },
                quantity: d.requestedQuantity, employeeChoices,
                categoryIndex: Math.max(0, page.data.categories.findIndex(c => c.id === d.categoryId)),
                organizationIndex: Math.max(0, page.data.organizations.findIndex(c => c.id === d.targetOrganizationId)),
                employeeIndex: Math.max(0, employeeChoices.findIndex(c => c.id === employeeId)),
                locationIndex: Math.max(0, page.data.locations.findIndex(c => c.id === d.targetLocationId)) };
        });
        page.setData({ mode, form, showForm: true, error: "" });
    };
    return {
        data: { accountId: "", busy: false, error: "", notice: "", rows: [], selected: null, page: 1, total: 0,
            showForm: false, retryable: false, mode: "new", categories: [], organizations: [], employees: [], locations: [],
            canApplyShared: false, form: [], reason: "" } as Data,
        async onShow(this: Context) {
            live = true; const current = ++generation;
            try {
                services = getServices(); const nextIdentity = JSON.stringify(services.sessions.identity);
                if (nextIdentity !== identity) {
                    identity = nextIdentity; pending = null;
                    this.setData({ selected: null, rows: [], form: [], showForm: false, retryable: false, reason: "" });
                }
                this.setData({ busy: true, error: "", accountId: services.sessions.identity?.accountId ?? "" });
                const result: Record<string, RequisitionChoice[]> = {};
                for (const kind of ["category", "organization", "employee", "location"]) {
                    const rows: RequisitionChoice[] = []; let page = 1; let reply: RequisitionCatalog;
                    do {
                        reply = await services.sessions.request("GET", `${path}/catalog/${kind}?page=${page++}&pageSize=200`) as RequisitionCatalog;
                        if (!live || generation !== current) return;
                        rows.push(...reply.items);
                    } while (reply.items.length === 200);
                    result[kind] = rows; selfEmployee = reply.employeeId; selfOrg = reply.organizationId;
                    if (live && generation === current) this.setData({ canApplyShared: reply.canApplyShared });
                }
                if (!live || generation !== current) return;
                this.setData({ categories: result.category, organizations: result.organization, employees: result.employee,
                    locations: result.location });
                await load(this);
                if (live && generation === current) {
                    this.setData({ busy: false });
                    if (initiallyCreate && !this.data.selected && !this.data.showForm) edit(this, "new");
                }
            } catch (error) {
                if (live && generation === current) this.setData({ error: message(error) });
                if (live && services && !services.sessions.identity) services.navigate("/pages/login/index");
            } finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        onHide() { live = false; generation++; },
        onUnload() { live = false; generation++; pending = null; },
        onNew(this: Context) { edit(this, "new"); },
        onRevise(this: Context) { edit(this, "revise"); },
        onChange(this: Context) { edit(this, "change"); },
        onAdd(this: Context) { if (!this.data.busy && !pending && this.data.form.length < 50) this.setData({ form: [...this.data.form, defaultRow(this)] }); },
        onRemove(this: Context, event: Event) {
            if (this.data.busy || pending || this.data.form.length === 1 || this.data.mode === "change") return;
            this.setData({ form: this.data.form.filter((_, i) => i !== Number(event.currentTarget.dataset.index)) });
        },
        onField(this: Context, event: Event) {
            if (this.data.busy || pending) return;
            const index = Number(event.currentTarget.dataset.index); const field = event.currentTarget.dataset.field;
            if (!Number.isInteger(index) || !this.data.form[index] || !field
                || !["specification", "purpose", "quantity", "expectedDate", "preferredBrand", "preferredModel"].includes(field)) return;
            const form = this.data.form.map((row, i) => i === index ? { ...row,
                [field]: field === "quantity" ? Number(event.detail.value) : event.detail.value } : row);
            this.setData({ form });
        },
        onPick(this: Context, event: Event) {
            if (this.data.busy || pending) return;
            const index = Number(event.currentTarget.dataset.index); const field = event.currentTarget.dataset.field;
            const selectedIndex = Number(event.detail.value); const row = this.data.form[index];
            if (!row || !field || !["category", "organization", "employee", "location"].includes(field)) return;
            const candidates = field === "employee" ? row.employeeChoices : choices(this, field);
            const picked = candidates[selectedIndex]; if (!picked) return;
            const changed = { ...row, [field]: { id: picked.id, version: picked.version }, [field + "Index"]: selectedIndex };
            if (field === "organization") {
                changed.employeeChoices = this.data.employees.filter(e => e.organizationId === picked.id);
                const first = changed.employeeChoices[0]; changed.employee = { id: first?.id ?? "", version: first?.version ?? 0 };
                changed.employeeIndex = 0;
            }
            this.setData({ form: this.data.form.map((row, i) => i === index ? changed : row) });
        },
        onCustody(this: Context, event: Event) {
            if (this.data.busy || pending || !this.data.canApplyShared) return;
            const index = Number(event.currentTarget.dataset.index);
            this.setData({ form: this.data.form.map((row, i) => i === index
                ? { ...row, custodyType: row.custodyType === "PERSONAL" ? "SHARED" : "PERSONAL" } : row) });
        },
        onReason(this: Context, event: Event) { if (!this.data.busy && !pending) this.setData({ reason: event.detail.value }); },
        async onSave(this: Context) {
            const demands: RequisitionDemandInput[] = this.data.form.map(row => ({
                category: row.category, specification: row.specification, preferredBrand: row.preferredBrand,
                preferredModel: row.preferredModel, purpose: row.purpose, expectedDate: row.expectedDate,
                custodyType: row.custodyType, organization: row.organization, employee: row.employee,
                location: row.location, quantity: row.quantity,
            }));
            if (demands.some(d => !d.specification.trim() || !d.purpose.trim() || !Number.isInteger(d.quantity)
                || d.quantity < 1 || d.quantity > 10000 || !d.employee.id || !d.expectedDate)) {
                this.setData({ error: "请填写需求规格、用途、数量、日期和接收目标。" }); return;
            }
            const suffix = this.data.mode === "new" ? "" : "/" + this.data.selected!.id + (this.data.mode === "change" ? "/changes" : "");
            await mutate(this, suffix, { demands }, this.data.mode === "revise" ? "PATCH" : "POST");
        },
        async onOpen(this: Context, event: Event) {
            if (!services || this.data.busy || pending) return; const current = generation; this.setData({ busy: true, error: "" });
            try {
                const selected = await services.sessions.request("GET", path + "/" + event.currentTarget.dataset.id) as Requisition;
                if (live && generation === current) this.setData({ selected, showForm: false });
            } catch (error) { if (live && generation === current) this.setData({ error: message(error) }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        async onSubmit(this: Context) { if (this.data.selected) await mutate(this, "/" + this.data.selected.id + "/submit"); },
        async onCancel(this: Context) {
            if (this.data.selected && this.data.reason.trim()) await mutate(this, "/" + this.data.selected.id + "/cancel", { reason: this.data.reason });
        },
        async onDispute(this: Context) {
            if (this.data.selected && this.data.reason.trim()) await mutate(this, "/" + this.data.selected.id + "/dispute", { reason: this.data.reason });
        },
        async onFollowup(this: Context, event: Event) {
            if (this.data.selected) await mutate(this, `/${this.data.selected.id}/demands/${event.currentTarget.dataset.id}/followup`);
        },
        async onRetry(this: Context) { if (pending) await mutate(this, pending.path.slice(path.length)); },
        async onRefresh(this: Context) {
            if (!services || this.data.busy) return; const current = generation; this.setData({ busy: true, error: "" });
            try {
                await load(this);
                if (this.data.selected) {
                    const selected = await services.sessions.request("GET", path + "/" + this.data.selected.id) as Requisition;
                    if (live && generation === current) this.setData({ selected });
                }
                if (live && generation === current) { pending = null; this.setData({ retryable: false }); }
            } catch (error) { if (live && generation === current) this.setData({ error: message(error) }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        async onPrevious(this: Context) {
            if (!services || this.data.busy || pending || this.data.page <= 1) return;
            const current = generation;
            this.setData({ page: this.data.page - 1, busy: true });
            try { await load(this); } catch (error) { if (live && generation === current) this.setData({ error: message(error) }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
        async onNext(this: Context) {
            if (!services || this.data.busy || this.data.page * 20 >= this.data.total) return;
            const current = generation;
            this.setData({ page: this.data.page + 1, busy: true });
            try { await load(this); } catch (error) { if (live && generation === current) this.setData({ error: message(error) }); }
            finally { if (live && generation === current) this.setData({ busy: false }); }
        },
    };
}
