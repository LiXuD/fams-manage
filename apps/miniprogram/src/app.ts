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
                chooseEvidence: () => new Promise((resolve, reject) => {
                    if (!wx.chooseMessageFile) { reject(new Error("File picker unavailable")); return; }
                    wx.chooseMessageFile({ count: 1, type: "file", extension: ["pdf", "png", "jpg", "jpeg", "webp"],
                        success: result => result.tempFiles[0] ? resolve(result.tempFiles[0]) : reject(new Error("No evidence selected")),
                        fail: reject });
                }),
                openEvidence: (filePath, filename) => new Promise((resolve, reject) => {
                    if (filename && /\.(png|jpe?g|webp)$/i.test(filename)) {
                        if (!wx.previewImage) { reject(new Error("Image viewer unavailable")); return; }
                        wx.previewImage({ current: filePath, urls: [filePath], success: resolve, fail: reject });
                        return;
                    }
                    if (!wx.openDocument) { reject(new Error("Document viewer unavailable")); return; }
                    wx.openDocument({ filePath, showMenu: true, success: resolve, fail: reject });
                }),
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
