/** Runtime ports map to wx.request and synchronous wx storage when pages are connected. */
export interface MiniRuntime {
    request(input: { url: string; method: string; header: Record<string, string>; data?: unknown }):
        Promise<{ statusCode: number; data: unknown }>;
    upload?(input: { url: string; filePath: string; header: Record<string, string> }):
        Promise<{ statusCode: number; data: unknown }>;
    download?(input: { url: string; header: Record<string, string> }):
        Promise<{ statusCode: number; tempFilePath: string }>;
    downloadInventoryPhoto?(input: { url: string; header: Record<string, string>;
        identity: { accountId: string; bindingId: string }; contentType: string }): Promise<{ statusCode: number; filePath: string }>;
    discardInventoryPhoto?(path: string): void;
    readSession(): unknown;
    writeSession(value: SavedSession): void;
    removeSession(): void;
    clearIdentityData(identity: { accountId: string; bindingId: string }): void;
}

export interface SavedSession {
    sessionToken: string;
    accountId: string;
    bindingId: string;
}

interface LoginGrant {
    bindingStatus: "BOUND" | "UNBOUND";
    sessionToken: string | null;
    accountId: string | null;
    bindingId: string | null;
    idleExpiresAt: string | null;
    absoluteExpiresAt: string | null;
}

export class MiniRequestError extends Error {
    readonly status: number;
    readonly code: string;
    readonly fields: Readonly<Record<string, string>>;
    constructor(status: number, code: string, metadata: unknown = null) {
        super(code);
        this.status = status;
        this.code = code;
        const fields: Record<string, string> = {};
        if (status === 409 && metadata && typeof metadata === "object" && !Array.isArray(metadata)) {
            const source = metadata as Record<string, unknown>;
            for (const name of ["processedBy", "processedAt", "method", "result", "taskVersion", "subjectVersion", "reviewVersion", "readOnlyUrl"]) {
                const value = source[name];
                if (typeof value === "string" && value.length <= 1000) fields[name] = value;
            }
        }
        this.fields = Object.freeze(fields);
    }
}

function isSavedSession(value: unknown): value is SavedSession {
    if (typeof value !== "object" || value === null) return false;
    const row = value as Record<string, unknown>;
    return typeof row.sessionToken === "string" && /^[A-Za-z0-9_-]{43}$/.test(row.sessionToken)
        && typeof row.accountId === "string" && /^[1-9][0-9]*$/.test(row.accountId)
        && typeof row.bindingId === "string" && /^[1-9][0-9]*$/.test(row.bindingId);
}

/** Tokens remain opaque; server expiry and current binding are authoritative. */
export class MiniSessionClient {
    private readonly runtime: MiniRuntime;
    private readonly origin: string;
    private current: SavedSession | null = null;
    private generation = 0;

    constructor(origin: string, runtime: MiniRuntime) {
        if (!/^https:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?\/?$/.test(origin)) {
            throw new Error("HTTPS API origin required");
        }
        this.origin = origin.replace(/\/$/, "");
        this.runtime = runtime;
        const saved = runtime.readSession();
        if (isSavedSession(saved)) this.current = { ...saved };
        else if (saved != null) runtime.removeSession();
    }

    get identity(): { accountId: string; bindingId: string } | null {
        return this.current ? { accountId: this.current.accountId, bindingId: this.current.bindingId } : null;
    }

    async login(code: string): Promise<"BOUND" | "UNBOUND"> {
        if (!code.trim() || code.length > 256) throw new Error("Login code required");
        const generation = ++this.generation;
        const response = await this.runtime.request({
            url: `${this.origin}/api/v1/mini/auth/login`, method: "POST", header: {}, data: { code },
        });
        this.assertGeneration(generation);
        this.checkResponse(response);
        const grant = response.data as LoginGrant;
        if (grant?.bindingStatus === "UNBOUND" && grant.sessionToken === null && grant.accountId === null
            && grant.bindingId === null && grant.idleExpiresAt === null && grant.absoluteExpiresAt === null) {
            this.clear();
            return "UNBOUND";
        }
        if (grant?.bindingStatus !== "BOUND" || !isSavedSession(grant)
            || typeof grant.idleExpiresAt !== "string" || typeof grant.absoluteExpiresAt !== "string"
            || !Number.isFinite(Date.parse(grant.idleExpiresAt))
            || !Number.isFinite(Date.parse(grant.absoluteExpiresAt))
            || Date.parse(grant.idleExpiresAt) > Date.parse(grant.absoluteExpiresAt)) {
            throw new Error("Invalid session response");
        }
        const saved: SavedSession = {
            sessionToken: grant.sessionToken, accountId: grant.accountId, bindingId: grant.bindingId,
        };
        if (this.current && (this.current.accountId !== saved.accountId || this.current.bindingId !== saved.bindingId)) {
            this.clear();
        }
        // Storage must succeed before the caller can observe authentication.
        this.runtime.writeSession(saved);
        this.current = saved;
        return "BOUND";
    }

    async request(method: string, path: string, data?: unknown,
        mutation?: { key: string; version: number }): Promise<unknown> {
        const pathname = path.split("?")[0];
        // Imported business codes may contain Unicode or slashes. Only this exact read-only lookup
        // may encode values; encoded path segments, extra parameters and other routes stay denied.
        const findingLookup = method === "GET" && !mutation && path.length <= 4096
            && /^\/api\/v1\/mini\/inventory-reviews\/tasks\/[1-9][0-9]{0,18}\/finding-targets\?assetCode=(?:[A-Za-z0-9_.!~*'()-]|%[0-9A-Fa-f]{2})+(?:&receiptNumber=(?:[A-Za-z0-9_.!~*'()-]|%[0-9A-Fa-f]{2})+)?$/.test(path);
        if (!path.startsWith("/api/v1/mini/") || /[\\#\s]/.test(path) || path.includes("%") && !findingLookup
            || /(^|\/)\.\.?(\/|$)/.test(pathname) || pathname.includes("//")
            || pathname === "/api/v1/mini/auth/login") {
            throw new Error("Mini API path required");
        }
        const session = this.current;
        if (!session) throw new MiniRequestError(401, "AUTH_SESSION_INVALID");
        const generation = this.generation;
        const header: Record<string, string> = { "X-FAMS-Mini-Session": session.sessionToken };
        if (mutation) {
            if (mutation.key.length < 16 || mutation.key.length > 128
                || !Number.isSafeInteger(mutation.version) || mutation.version < 0) {
                throw new Error("Invalid mutation identity");
            }
            header["Idempotency-Key"] = mutation.key;
            header["If-Match-Version"] = String(mutation.version);
        }
        const response = await this.runtime.request({
            url: this.origin + path, method, header, data,
        });
        this.assertGeneration(generation);
        if (response.statusCode === 401) this.clear();
        this.checkResponse(response);
        return response.data;
    }

    async uploadEvidence(path: string, filePath: string, mutation: { key: string; version: number; deviceId?: string }): Promise<unknown> {
        if (!/^\/api\/v1\/mini\/(repairs\/[1-9][0-9]*\/photos|proxy-confirmations\/[1-9][0-9]*\/attachments|inventory-observations\/tasks\/[1-9][0-9]*\/events\/[0-9a-f-]{36}\/photos)$/.test(path)
            || !filePath || mutation.key.length < 16 || mutation.key.length > 128
            || !Number.isSafeInteger(mutation.version) || mutation.version < 0) {
            throw new Error("Invalid evidence upload identity");
        }
        const session = this.current;
        if (!session) throw new MiniRequestError(401, "AUTH_SESSION_INVALID");
        if (!this.runtime.upload) throw new Error("Evidence upload unavailable");
        if (path.includes("/inventory-observations/") && !/^[0-9a-f-]{36}$/i.test(mutation.deviceId ?? "")) {
            throw new Error("Inventory device identity required");
        }
        const generation = this.generation;
        const response = await this.runtime.upload({ url: this.origin + path, filePath,
            header: { "X-FAMS-Mini-Session": session.sessionToken, "Idempotency-Key": mutation.key,
                "If-Match-Version": String(mutation.version), ...(mutation.deviceId ? { "X-FAMS-Device-Id": mutation.deviceId } : {}) } });
        this.assertGeneration(generation);
        if (response.statusCode === 401) this.clear();
        this.checkResponse(response);
        return response.data;
    }

    async downloadEvidence(path: string): Promise<string> {
        if (!/^\/api\/v1\/mini\/(attachments\/[0-9a-f-]{36}|inventory-observations\/tasks\/[1-9][0-9]*\/events\/[0-9a-f-]{36}\/photos\/[0-9a-f-]{36})\/content\?grant=[A-Za-z0-9_.-]+$/i.test(path)) {
            throw new Error("Invalid evidence download path");
        }
        const session = this.current;
        if (!session) throw new MiniRequestError(401, "AUTH_SESSION_INVALID");
        if (!this.runtime.download) throw new Error("Evidence download unavailable");
        const generation = this.generation;
        const response = await this.runtime.download({ url: this.origin + path,
            header: { "X-FAMS-Mini-Session": session.sessionToken } });
        this.assertGeneration(generation);
        if (response.statusCode === 401) this.clear();
        this.checkResponse({ statusCode: response.statusCode, data: null });
        if (!response.tempFilePath) throw new Error("Downloaded evidence unavailable");
        return response.tempFilePath;
    }

    async logout(): Promise<void> {
        const generation = this.generation;
        try {
            await this.request("POST", "/api/v1/mini/auth/logout");
        } finally {
            if (generation === this.generation) this.clear();
        }
    }

    async downloadInventoryPhoto(path: string, contentType: string): Promise<string> {
        const uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        if (!new RegExp(`^/api/v1/mini/(inventory-observations/tasks/[1-9][0-9]{0,18}/events/${uuid}|inventory-repair-evidence/[1-9][0-9]{0,18}/observations/[1-9][0-9]{0,18})/photos/${uuid}/content\\?grant=[A-Za-z0-9_.-]{1,256}$`, "i").test(path)
            || !["image/jpeg", "image/png", "image/webp"].includes(contentType)) throw new Error("Invalid inventory photo download");
        const session = this.current;
        if (!session) throw new MiniRequestError(401, "AUTH_SESSION_INVALID");
        if (!this.runtime.downloadInventoryPhoto || !this.runtime.discardInventoryPhoto) throw new Error("当前环境无法安全读取现场照片。");
        const generation = this.generation;
        const response = await this.runtime.downloadInventoryPhoto({ url: this.origin + path,
            header: { "X-FAMS-Mini-Session": session.sessionToken }, contentType,
            identity: { accountId: session.accountId, bindingId: session.bindingId } });
        try {
            this.assertGeneration(generation);
            if (response.statusCode === 401) this.clear();
            this.checkResponse({ statusCode: response.statusCode, data: null });
            if (!response.filePath) throw new Error("照片文件暂不可读取。");
            return response.filePath;
        } catch (error) {
            if (response.filePath) this.runtime.discardInventoryPhoto(response.filePath);
            throw error;
        }
    }

    async downloadRepairPhoto(path: string, contentType: string): Promise<string> {
        if (!/^\/api\/v1\/mini\/repairs\/[1-9][0-9]{0,18}\/photos\/[0-9a-f-]{36}\/content\?grant=[A-Za-z0-9_.-]{1,256}$/i.test(path)
            || !["image/jpeg", "image/png", "image/webp"].includes(contentType)) throw new Error("Invalid repair photo download");
        const session = this.current;
        if (!session) throw new MiniRequestError(401, "AUTH_SESSION_INVALID");
        if (!this.runtime.downloadInventoryPhoto || !this.runtime.discardInventoryPhoto) throw new Error("当前环境无法安全读取故障照片。");
        const generation = this.generation;
        const response = await this.runtime.downloadInventoryPhoto({ url: this.origin + path,
            header: { "X-FAMS-Mini-Session": session.sessionToken }, contentType,
            identity: { accountId: session.accountId, bindingId: session.bindingId } });
        try {
            this.assertGeneration(generation);
            if (response.statusCode === 401) this.clear();
            this.checkResponse({ statusCode: response.statusCode, data: null });
            if (!response.filePath) throw new Error("故障照片暂不可读取。");
            return response.filePath;
        } catch (error) {
            if (response.filePath) this.runtime.discardInventoryPhoto(response.filePath);
            throw error;
        }
    }

    discardInventoryPhoto(path: string): void {
        if (!this.runtime.discardInventoryPhoto) throw new Error("照片清理服务暂不可用。");
        this.runtime.discardInventoryPhoto(path);
    }

    clear(): void {
        this.generation++;
        const previous = this.current;
        this.current = null;
        this.runtime.removeSession();
        if (previous) this.runtime.clearIdentityData({ accountId: previous.accountId, bindingId: previous.bindingId });
    }

    private assertGeneration(generation: number): void {
        if (generation !== this.generation) throw new Error("Session changed during request");
    }

    private checkResponse(response: { statusCode: number; data: unknown }): void {
        if (response.statusCode >= 200 && response.statusCode < 300) return;
        const data = response.data as { code?: unknown; fieldErrors?: unknown } | null;
        const code = typeof data?.code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(data.code)
            ? data.code : "MINI_REQUEST_FAILED";
        throw new MiniRequestError(response.statusCode, code, data?.fieldErrors);
    }
}
