import { API_ORIGIN } from "./config.ts";
import { MiniBindingClient } from "./binding.ts";
import { MiniSessionClient } from "./session.ts";
import { createUuid, createWxRuntime, deviceId } from "./wx-runtime.ts";
import type { MiniServices } from "./pages.ts";

let services: MiniServices | null = null;
App({
    getServices() {
        if (!services) {
            const runtime = createWxRuntime(wx);
            const sessions = new MiniSessionClient(API_ORIGIN, runtime);
            services = { sessions, wechat: runtime,
                binding: new MiniBindingClient(API_ORIGIN, runtime, sessions, runtime, deviceId(wx), createUuid),
                createKey: createUuid,
                scan: () => new Promise((resolve, reject) => {
                    if (!wx.scanCode) { reject(new Error("Scanner unavailable")); return; }
                    wx.scanCode({ onlyFromCamera: true, success: result => resolve(result.result), fail: reject });
                }),
                navigate: url => wx.redirectTo({ url }),
                confirm: (title, content) => new Promise(resolve => wx.showModal({ title, content,
                    success: result => resolve(result.confirm) })),
            };
        }
        return services;
    },
});
