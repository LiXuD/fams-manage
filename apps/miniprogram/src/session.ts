/** Runtime ports map to wx.request and synchronous wx storage when pages are connected. */
export interface MiniRuntime {
    request(input: { url: string; method: string; header: Record<string, string>; data?: unknown }):
        Promise<{ statusCode: number; data: unknown }>;
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
    constructor(status: number, code: string) {
        super(code);
        this.status = status;
        this.code = code;
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
        if (!path.startsWith("/api/v1/mini/") || /[\\%#\s]/.test(path)
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

    async logout(): Promise<void> {
        const generation = this.generation;
        try {
            await this.request("POST", "/api/v1/mini/auth/logout");
        } finally {
            if (generation === this.generation) this.clear();
        }
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
        const data = response.data as { code?: unknown } | null;
        const code = typeof data?.code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(data.code)
            ? data.code : "MINI_REQUEST_FAILED";
        throw new MiniRequestError(response.statusCode, code);
    }
}
