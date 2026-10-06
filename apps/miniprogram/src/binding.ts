import { MiniRequestError, MiniSessionClient, type MiniRuntime } from "./session.ts";

export interface WechatLogin { loginCode(): Promise<string> }
export interface BindingForm { employeeNo: string; phone: string; action: "BIND" | "REBIND" }
interface Challenge { challengeToken: string; expiresAt: string; resendAfterSeconds: number }

/** Challenge, OTP and employee inputs remain in memory; each exchange requests a fresh wx.login code. */
export class MiniBindingClient {
    private challenge: Challenge | null = null;
    private key: string | null = null;
    private generation = 0;
    private readonly origin: string;
    private readonly runtime: MiniRuntime;
    private readonly sessions: MiniSessionClient;
    private readonly wechat: WechatLogin;
    private readonly deviceId: string;
    private readonly createKey: () => string;
    constructor(origin: string, runtime: MiniRuntime, sessions: MiniSessionClient,
        wechat: WechatLogin, deviceId: string, createKey: () => string) {
        if (!/^https:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?\/?$/.test(origin)) throw new Error("HTTPS API origin required");
        if (!/^[0-9a-f-]{36}$/i.test(deviceId)) throw new Error("Device identity required");
        this.origin = origin.replace(/\/$/, "");
        this.runtime = runtime;
        this.sessions = sessions;
        this.wechat = wechat;
        this.deviceId = deviceId;
        this.createKey = createKey;
    }
    reset(): void { this.generation++; this.challenge = null; this.key = null; }
    async support(): Promise<string> {
        const reply = await this.runtime.request({ url: this.origin + "/api/v1/mini/auth/support", method: "GET", header: {} });
        const data = this.accept(reply) as { supportText?: unknown };
        if (typeof data?.supportText !== "string" || data.supportText.length > 500) throw new Error("Invalid support response");
        return data.supportText;
    }
    async send(form: BindingForm): Promise<Challenge> {
        this.reset();
        const generation = this.generation;
        const code = await this.wechat.loginCode();
        if (generation !== this.generation) throw new Error("Binding input changed");
        const reply = await this.runtime.request({ url: this.origin + "/api/v1/mini/auth/binding-challenges",
            method: "POST", header: {}, data: { ...form, code, deviceId: this.deviceId } });
        if (generation !== this.generation) throw new Error("Binding input changed");
        const data = this.accept(reply) as Challenge;
        if (!/^[A-Za-z0-9_-]{43}$/.test(data?.challengeToken ?? "")
            || !Number.isFinite(Date.parse(data.expiresAt)) || data.resendAfterSeconds !== 60) {
            throw new Error("Invalid challenge response");
        }
        this.challenge = { ...data };
        this.key = this.createKey();
        return { ...data };
    }
    async verify(otp: string): Promise<void> {
        if (!this.challenge || !this.key || !/^[0-9]{6}$/.test(otp)) throw new Error("Six digit verification code required");
        const generation = this.generation;
        const code = await this.wechat.loginCode();
        if (generation !== this.generation) throw new Error("Binding input changed");
        const reply = await this.runtime.request({ url: this.origin + "/api/v1/mini/auth/binding", method: "POST",
            header: { "Idempotency-Key": this.key, "If-Match-Version": "0" },
            data: { code, challengeToken: this.challenge.challengeToken, deviceId: this.deviceId, otp } });
        if (generation !== this.generation) throw new Error("Binding input changed");
        const receipt = this.accept(reply) as { accountId?: unknown; bindingId?: unknown; bindingVersion?: unknown };
        if (typeof receipt?.accountId !== "string" || !/^[1-9][0-9]*$/.test(receipt.accountId)
            || typeof receipt.bindingId !== "string" || !/^[1-9][0-9]*$/.test(receipt.bindingId)
            || !Number.isSafeInteger(receipt.bindingVersion) || Number(receipt.bindingVersion) < 0) {
            throw new Error("Invalid binding response");
        }
        // Binding replacement ends the old identity locally even if new login fails.
        this.sessions.clear();
        const login = await this.wechat.loginCode();
        if (generation !== this.generation) throw new Error("Binding input changed");
        if (await this.sessions.login(login) !== "BOUND") throw new Error("Binding session unavailable");
        this.reset();
    }
    async unbind(version: number, reason: string, key: string): Promise<void> {
        await this.sessions.request("POST", "/api/v1/mini/auth/unbind",
            { reason, verificationMethod: "SELF_SESSION" }, { key, version });
        this.sessions.clear();
        this.reset();
    }
    private accept(reply: { statusCode: number; data: unknown }): unknown {
        if (reply.statusCode >= 200 && reply.statusCode < 300) return reply.data;
        const data = reply.data as { code?: unknown } | null;
        const code = typeof data?.code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(data.code)
            ? data.code : "MINI_REQUEST_FAILED";
        throw new MiniRequestError(reply.statusCode, code);
    }
}
