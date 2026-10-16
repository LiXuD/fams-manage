import assert from "node:assert/strict";
import test from "node:test";
import { chooseInventoryPhoto } from "../src/wx-runtime.ts";
test("scene photo adapter explicitly selects camera or album and uses reported image format", async () => {
    for (const source of ["camera", "album"]) {
        const wx = { chooseImage: options => { assert.deepEqual(options.sourceType, [source]); assert.equal(options.count, 1);
            options.success({ tempFiles: [{ path: "temporary/no-extension", size: 42 }] }); },
            getImageInfo: options => { assert.equal(options.src, "temporary/no-extension"); options.success({ type: "png" }); } };
        assert.deepEqual(await chooseInventoryPhoto(wx, source), { path: "temporary/no-extension", name: "现场照片.png", size: 42 });
    }
});
test("unsupported or cancelled scene photo produces no claimed file", async () => {
    await assert.rejects(chooseInventoryPhoto({}, "camera"), /无法打开/);
    await assert.rejects(chooseInventoryPhoto({ chooseImage: options => options.fail({}), getImageInfo() {} }, "album"), /未完成/);
    await assert.rejects(chooseInventoryPhoto({ chooseImage: options => options.success({ tempFiles: [{ path: "temp", size: 1 }] }),
        getImageInfo: options => options.success({ type: "unknown" }) }, "camera"), /JPG/);
});
