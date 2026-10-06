import type { WxApi } from "./wx-runtime.ts";
import type { MiniServices } from "./pages.ts";
declare global {
    const wx: WxApi;
    function App(options: { getServices(): MiniServices }): void;
    function Page(options: unknown): void;
    function getApp(): { getServices(): MiniServices };
}
