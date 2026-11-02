import { describe, expect, it, afterEach, vi } from "vitest";
import { defineComponent } from "vue";
import { mount } from "@vue/test-utils";
import { useObjectUrlPreview } from "./useObjectUrlPreview";

function withSetup<T>(composable: () => T) {
  let captured!: T;
  const host = defineComponent({ setup() { captured = composable(); return () => null; } });
  const wrapper = mount(host);
  return { result: captured, wrapper };
}

afterEach(() => { vi.restoreAllMocks(); });

describe("useObjectUrlPreview", () => {
  it("show 发布三值并在替换时回收上一个 object URL", () => {
    const create = vi.spyOn(URL, "createObjectURL").mockReturnValueOnce("blob:first").mockReturnValueOnce("blob:second");
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const { result } = withSetup(() => useObjectUrlPreview());
    result.show(new Blob(["p"], { type: "image/png" }), "a.png");
    expect(create).toHaveBeenCalledTimes(1);
    expect(result.url).toBe("blob:first");
    expect(result.name).toBe("a.png");
    expect(result.mime).toBe("image/png");
    result.show(new Blob(["d"], { type: "application/pdf" }), "b.pdf");
    expect(revoke).toHaveBeenCalledWith("blob:first");
    expect(revoke).toHaveBeenCalledTimes(1);
    expect(result.url).toBe("blob:second");
    expect(result.mime).toBe("application/pdf");
  });

  it("close 取消在途 signal、回收当前 URL 并清空三值", () => {
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:first");
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const { result } = withSetup(() => useObjectUrlPreview());
    const signal = result.beginDownload();
    result.show(new Blob(["p"], { type: "image/png" }), "a.png");
    result.close();
    expect(signal.aborted).toBe(true);
    expect(revoke).toHaveBeenCalledWith("blob:first");
    expect(result.url).toBe("");
    expect(result.name).toBe("");
    expect(result.mime).toBe("");
  });

  it("beginDownload 重复调用取消前一次而不影响最新一次", () => {
    const { result } = withSetup(() => useObjectUrlPreview());
    const first = result.beginDownload();
    const second = result.beginDownload();
    expect(first.aborted).toBe(true);
    expect(second.aborted).toBe(false);
    result.close();
    expect(second.aborted).toBe(true);
  });

  it("空预览上的 close 不回收也不取消", () => {
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const { result } = withSetup(() => useObjectUrlPreview());
    result.close();
    expect(revoke).not.toHaveBeenCalled();
    expect(result.url).toBe("");
  });

  it("组件卸载后自动 close", () => {
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:first");
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const { result, wrapper } = withSetup(() => useObjectUrlPreview());
    const signal = result.beginDownload();
    result.show(new Blob(["p"], { type: "image/png" }), "a.png");
    wrapper.unmount();
    expect(revoke).toHaveBeenCalledWith("blob:first");
    expect(signal.aborted).toBe(true);
    expect(result.url).toBe("");
    expect(result.name).toBe("");
    expect(result.mime).toBe("");
  });
});
