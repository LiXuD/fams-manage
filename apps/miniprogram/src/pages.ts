import { MiniBindingClient } from "./binding.ts";
import { MiniRequestError, MiniSessionClient } from "./session.ts";
import type { WechatLogin } from "./binding.ts";
import type { InventoryQueue } from "./inventory-queue.ts";
import type { createInventoryFiles } from "./inventory-files.ts";

export interface MiniServices {
    inventory?(): { queue: InventoryQueue; files: ReturnType<typeof createInventoryFiles>; deviceId: string };
    sessions: MiniSessionClient;
    binding: MiniBindingClient;
    wechat: WechatLogin;
    createKey(): string;
    navigate(url: string): void;
    confirm(title: string, content: string): Promise<boolean>;
    scan?(): Promise<string>;
    chooseEvidence?(): Promise<{ path: string; name: string; size: number }>;
    chooseInventoryPhoto?(source: "camera" | "album"): Promise<{ path: string; name: string; size: number }>;
    openEvidence?(path: string, filename?: string): Promise<void>;
}

function message(error: unknown): string {
    if (error instanceof MiniRequestError) {
        if (error.status === 429) return "请求过于频繁，请稍后重试。连续验证码错误会暂停绑定 30 分钟。";
        if (error.status === 503) return "验证服务暂不可用，请稍后重试或联系企业管理员。";
        if (error.status === 401) return "登录已失效，请重新登录。";
        if (error.status === 409) return "绑定状态已变更，请重新登录后确认。";
        if (error.status === 400) return "请核对工号、预登记手机号和验证码。";
    }
    return "操作未完成，请检查网络后重试。";
}

interface LoginData {
    employeeNo: string; phone: string; otp: string; action: "BIND" | "REBIND";
    busy: boolean; ready: boolean; sent: boolean; error: string; support: string; retrySeconds: number;
}
interface LoginContext { data: LoginData; setData(data: Partial<LoginData>): void }
interface InputEvent { detail: { value: string } }

/** Native Page handlers are separately exported so tests drive the same runtime transitions. */
export function createLoginPage(getServices: () => MiniServices) {
    let live = false;
    let generation = 0;
    let services: MiniServices | null = null;
    let timer: ReturnType<typeof setInterval> | null = null;
    let resendAt = 0;
    const stopTimer = () => { if (timer) clearInterval(timer); timer = null; };
    const changed = (page: LoginContext) => {
        generation++;
        services?.binding.reset();
        page.setData({ otp: "", sent: false, error: "" });
    };
    return {
        data: { employeeNo: "", phone: "", otp: "", action: "BIND", busy: false, ready: false,
            sent: false, error: "", support: "", retrySeconds: 0 } as LoginData,
        async onLoad(this: LoginContext, options: { action?: string }) {
            live = true;
            this.setData({ action: options.action === "REBIND" ? "REBIND" : "BIND" });
            try {
                services = getServices();
                this.setData({ ready: true });
                const support = await services.binding.support();
                if (live) this.setData({ support });
            } catch {
                if (live) this.setData({ error: "服务未就绪，请联系企业管理员。" });
            }
        },
        onShow(this: LoginContext) {
            live = true;
            stopTimer();
            const update = () => this.setData({ retrySeconds: Math.max(0, Math.ceil((resendAt - Date.now()) / 1000)) });
            update();
            timer = setInterval(update, 1000);
        },
        onHide(this: LoginContext) {
            live = false;
            changed(this);
            this.setData({ phone: "", busy: false });
            stopTimer();
        },
        onUnload(this: LoginContext) { live = false; changed(this); this.setData({ phone: "", busy: false }); stopTimer(); },
        onEmployee(this: LoginContext, event: InputEvent) {
            changed(this); this.setData({ employeeNo: event.detail.value });
        },
        onPhone(this: LoginContext, event: InputEvent) {
            changed(this); this.setData({ phone: event.detail.value });
        },
        onOtp(this: LoginContext, event: InputEvent) { this.setData({ otp: event.detail.value }); },
        onChooseRebind(this: LoginContext) {
            if (this.data.busy) return;
            changed(this);
            this.setData({ action: this.data.action === "BIND" ? "REBIND" : "BIND" });
        },
        async onLogin(this: LoginContext) {
            if (!services || this.data.busy) return;
            this.setData({ busy: true, error: "" });
            const current = generation;
            try {
                const status = await services.sessions.login(await services.wechat.loginCode());
                if (!live || current !== generation) return;
                if (status === "BOUND") services.navigate("/pages/mine/index");
                else this.setData({ error: "请填写企业工号及预登记手机号完成绑定。" });
            } catch (error) { if (live && current === generation) this.setData({ error: message(error) }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onSend(this: LoginContext) {
            if (!services || this.data.busy || Date.now() < resendAt) return;
            if (!this.data.employeeNo.trim() || !/^1[3-9][0-9]{9}$/.test(this.data.phone)) {
                this.setData({ error: "请填写企业工号和 11 位预登记手机号。" }); return;
            }
            this.setData({ busy: true, error: "", otp: "", sent: false });
            const current = generation;
            try {
                await services.binding.send({ employeeNo: this.data.employeeNo.trim(), phone: this.data.phone, action: this.data.action });
                if (!live || current !== generation) return;
                resendAt = Date.now() + 60000;
                this.setData({ sent: true, retrySeconds: 60 });
            } catch (error) { if (live && current === generation) this.setData({ error: message(error) }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
        async onVerify(this: LoginContext) {
            if (!services || this.data.busy || !this.data.sent) return;
            if (!/^[0-9]{6}$/.test(this.data.otp)) { this.setData({ error: "请输入 6 位短信验证码。" }); return; }
            this.setData({ busy: true, error: "" });
            const current = generation;
            try {
                await services.binding.verify(this.data.otp);
                if (live && current === generation) {
                    this.setData({ otp: "", phone: "" }); services.navigate("/pages/mine/index");
                }
            } catch (error) { if (live && current === generation) this.setData({ error: message(error) }); }
            finally { if (live && current === generation) this.setData({ busy: false }); }
        },
    };
}

interface MineData { accountId: string; busy: boolean; error: string; bindingVersion: number; pendingCount: number; queueError: boolean }
interface MineContext { data: MineData; setData(data: Partial<MineData>): void }
export function createMinePage(getServices: () => MiniServices) {
    let live = false, generation = 0, identity = "";
    let services: MiniServices | null = null;
    let unbindKey: string | null = null;
    const blank = (): MineData => ({ accountId: "", busy: false, error: "", bindingVersion: 0, pendingCount: 0, queueError: false });
    const current = (epoch: number) => live && epoch === generation && identity === JSON.stringify(services?.sessions.identity);
    const count = (page: MineContext) => {
        try {
            const queue = services?.inventory?.().queue;
            const pendingCount = queue ? queue.summaries().reduce((sum, task) => sum + task.pending, 0) : 0;
            page.setData({ pendingCount, queueError: false });
        } catch { page.setData({ pendingCount: 0, queueError: true }); }
    };
    const warning = (page: MineContext) => page.data.queueError
        ? "无法读取本机待上传数量。继续操作将清除本身份的本机记录和照片，未上传事实无法恢复。"
        : `本机有 ${page.data.pendingCount} 条尚未取得服务器回执的盘点记录。继续操作会清除本身份的记录和照片；可取消并先同步。`;
    const changed = (page: MineContext, epoch: number) => {
        if (live && epoch === generation) page.setData({ ...blank(), error: "身份已变化，请重新进入账号页。" });
    };
    return {
        data: blank(),
        async onShow(this: MineContext) {
            live = true; const epoch = ++generation;
            services = getServices(); const nextIdentity = JSON.stringify(services.sessions.identity);
            if (nextIdentity !== identity) unbindKey = null; identity = nextIdentity;
            this.setData({ ...blank(), busy: true }); count(this);
            try {
                const result = await services.sessions.request("GET", "/api/v1/mini/auth/session") as
                    { accountId: string; bindingVersion: number };
                if (current(epoch)) this.setData({ accountId: result.accountId, bindingVersion: result.bindingVersion });
            } catch (error) {
                if (current(epoch)) this.setData({ error: message(error) });
                if (live && epoch === generation && !services.sessions.identity) services.navigate("/pages/login/index");
            } finally { if (current(epoch)) this.setData({ busy: false }); else changed(this, epoch); }
        },
        onHide(this: MineContext) { live = false; generation++; this.setData(blank()); },
        onUnload(this: MineContext) { live = false; generation++; this.setData(blank()); },
        async onLogout(this: MineContext) {
            if (!services || this.data.busy || !current(generation)) return;
            const epoch = generation; this.setData({ busy: true, error: "" }); count(this);
            try {
                if ((this.data.pendingCount || this.data.queueError)
                    && !await services.confirm("退出并清除本机资料", warning(this))) return;
                if (!current(epoch)) { changed(this, epoch); return; }
                await services.sessions.logout();
                if (live && epoch === generation && !services.sessions.identity) services.navigate("/pages/login/index");
            } catch {
                if (live && epoch === generation && !services.sessions.identity) {
                    this.setData({ ...blank(), error: "本机身份已退出；网络或资料清理未完成，请重启后继续清理或重新登录。" });
                }
            } finally { if (current(epoch)) this.setData({ busy: false }); else if (services?.sessions.identity) changed(this, epoch); }
        },
        async onUnbind(this: MineContext) {
            if (!services || this.data.busy || !current(generation)) return;
            const epoch = generation; this.setData({ busy: true, error: "" }); count(this);
            try {
                if (!await services.confirm("解除微信绑定", `${warning(this)} 解除绑定后再次使用需要短信验证。`)) return;
                if (!current(epoch)) { changed(this, epoch); return; }
                unbindKey ??= services.createKey();
                await services.binding.unbind(this.data.bindingVersion, "用户在我的页面确认解绑", unbindKey);
                if (live && epoch === generation && !services.sessions.identity) services.navigate("/pages/login/index");
            } catch (error) {
                if (current(epoch)) this.setData({ error: message(error) });
                else if (live && epoch === generation && !services.sessions.identity) this.setData({ ...blank(), error: "绑定操作或本机清理未完成，请重新登录核对。" });
            } finally { if (current(epoch)) this.setData({ busy: false }); else if (services?.sessions.identity) changed(this, epoch); }
        },
    };
}
