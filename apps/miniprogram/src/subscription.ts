import { MiniRequestError } from "./session.ts";
import type { MiniServices } from "./pages.ts";

export type SubscriptionScene = "PROGRESS" | "RETURN" | "INVENTORY_TASK" | "RECEIPT";
export type SubscriptionOutcome = "SUBSCRIBED" | "REFUSED" | "UNCONFIGURED";

export async function subscriptionTemplate(services: MiniServices): Promise<string> {
    try {
        const result = await services.sessions.request("GET", "/api/v1/mini/subscription-visibility") as { wechatTemplate?: unknown };
        return typeof result.wechatTemplate === "string" ? result.wechatTemplate : "";
    } catch { return ""; }
}

/** Only an explicit tap reaches this helper; refusal or a missing wx capability never posts a grant. */
export async function subscribeReminder(services: MiniServices,
    target: { scene: SubscriptionScene; businessType: string; businessId: string }): Promise<SubscriptionOutcome> {
    const template = await subscriptionTemplate(services);
    if (!template) return "UNCONFIGURED";
    let reply: "accept" | "reject" | "ban" = "reject";
    if (services.requestSubscribe) {
        try { reply = await services.requestSubscribe(template); } catch { return "REFUSED"; }
    }
    if (reply !== "accept") return "REFUSED";
    try {
        await services.sessions.request("POST", "/api/v1/mini/subscription-grants",
            { templateId: template, scene: target.scene, businessType: target.businessType, businessId: target.businessId },
            { key: services.createKey(), version: 0 });
        return "SUBSCRIBED";
    } catch (error) {
        if (error instanceof MiniRequestError && error.status === 409
            && error.code === "WECHAT_SUBSCRIPTION_TEMPLATE_UNCONFIGURED") return "UNCONFIGURED";
        throw error;
    }
}

export function subscriptionUpdate(outcome: SubscriptionOutcome): { subscriptionNotice: string; subscriptionTemplate?: string } {
    if (outcome === "SUBSCRIBED") return { subscriptionNotice: "已订阅，微信将发送一次服务通知。" };
    return { subscriptionNotice: "未开启微信通知，可继续在站内查看。",
        ...(outcome === "UNCONFIGURED" ? { subscriptionTemplate: "" } : {}) };
}