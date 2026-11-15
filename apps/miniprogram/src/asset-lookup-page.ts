import type { MiniServices } from "./pages.ts";
import { MiniRequestError } from "./session.ts";

const lifecycle: Record<string, string> = { IN_STOCK: "在库", IN_USE: "在用", BORROWED: "借出",
    UNDER_REPAIR: "维修中", FACTORY_REPAIR: "返厂维修", PENDING_SCRAP: "待报废", SCRAPPED: "已报废" };
const exceptions: Record<string, string> = { NORMAL: "正常", SEARCHING: "查找中", LOST: "已遗失" };
interface Result {
    asset: { assetCode: string; name: string; lifecycle: string; exception: string };
    page: number;
    more: boolean;
    timeline: { id: string; occurredAt: string; eventType: string; summary?: string }[];
}
interface Data { code: string; busy: boolean; error: string; result: Result | null; status: string }
interface Context { data: Data; setData(patch: Partial<Data>): void }

export function createAssetLookupPage(services: () => MiniServices) {
    let generation = 0;
    async function query(page: Context, number: number) {
        const code = number === 1 ? page.data.code.trim() : page.data.result?.asset.assetCode;
        if (!code || page.data.busy) return;
        const api = services().sessions;
        const identity = JSON.stringify(api.identity);
        const current = ++generation;
        const valid = () => generation === current && JSON.stringify(api.identity) === identity;
        page.setData({ busy: true, result: null, error: "", status: "" });
        try {
            const result = await api.request("GET",
                `/api/v1/mini/asset-lookup?assetCode=${encodeURIComponent(code)}&page=${number}`) as Result;
            if (valid()) page.setData({ result, status: (lifecycle[result.asset.lifecycle] ?? result.asset.lifecycle)
                + " · " + (exceptions[result.asset.exception] ?? result.asset.exception) });
        } catch (cause) {
            if (valid()) page.setData({ error: cause instanceof MiniRequestError && cause.status === 404
                ? "未找到当前账号可查询的资产。请核对资产编码。" : "查询未完成，请核对当前权限后重试。" });
        } finally {
            if (generation === current) page.setData({ busy: false });
        }
    }
    function clear(page: Context) {
        generation++;
        page.setData({ code: "", busy: false, error: "", result: null, status: "" });
    }
    return {
        data: { code: "", busy: false, error: "", result: null, status: "" } as Data,
        onShow(this: Context) {
            if (!services().sessions.identity) services().navigate("/pages/login/index");
        },
        onHide(this: Context) { clear(this); },
        onUnload(this: Context) { clear(this); },
        onCode(this: Context, event: { detail: { value: string } }) {
            this.setData({ code: event.detail.value, result: null, status: "", error: "" });
        },
        onSearch(this: Context) { return query(this, 1); },
        onPrevious(this: Context) { return query(this, (this.data.result?.page ?? 2) - 1); },
        onNext(this: Context) { return query(this, (this.data.result?.page ?? 1) + 1); },
    };
}
