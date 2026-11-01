import { describe, expect, it, vi } from "vitest";
import { defineComponent } from "vue";
import { flushPromises, mount } from "@vue/test-utils";
import { useResourceLoad } from "./useResourceLoad";
import { usePagedResourceList } from "./usePagedResourceList";

function withSetup<T>(composable: () => T) {
  let captured!: T;
  const host = defineComponent({ setup() { captured = composable(); return () => null; } });
  const wrapper = mount(host);
  return { result: captured, wrapper };
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

describe("useResourceLoad", () => {
  it("挂载即读取一次并写回数据", async () => {
    const load = vi.fn(async () => ["a"]);
    const { result } = withSetup(() => useResourceLoad({ load, problem: () => "" }));
    await flushPromises();
    expect(load).toHaveBeenCalledTimes(1);
    expect(result.data.value).toEqual(["a"]);
    expect(result.error.value).toBe("");
    expect(result.busy.value).toBe(false);
  });

  it("失败只写提示，保留上一次成功数据", async () => {
    let fail = false;
    const { result } = withSetup(() => useResourceLoad<string[]>({
      load: async () => { if (fail) throw new Error("boom"); return ["a"]; },
      problem: (cause) => cause instanceof Error ? cause.message : "未知错误",
    }));
    await flushPromises();
    fail = true;
    await result.refresh();
    expect(result.data.value).toEqual(["a"]);
    expect(result.error.value).toBe("boom");
    expect(result.busy.value).toBe(false);
  });

  it("并发时只有最后一次调用有权写回，旧响应被丢弃", async () => {
    const order = ["slow", "fast"];
    const { result } = withSetup(() => useResourceLoad<string[]>({
      load: async () => { const tag = order.shift()!; await wait(tag.startsWith("slow") ? 30 : 2); return [tag]; },
      problem: () => "",
    }));
    await flushPromises();
    order.length = 0; order.push("slow-2", "fast-2");
    const first = result.refresh();
    const second = result.refresh();
    await Promise.all([first, second]);
    expect(result.data.value).toEqual(["fast-2"]);
    await wait(40);
    expect(result.data.value).toEqual(["fast-2"]);
    expect(result.busy.value).toBe(false);
  });

  it("卸载后在途结果与状态一律不写回", async () => {
    const { result, wrapper } = withSetup(() => useResourceLoad<string[]>({
      load: async () => { await wait(20); return ["a"]; },
      problem: () => "",
    }));
    wrapper.unmount();
    await result.refresh();
    await flushPromises();
    await wait(30);
    expect(result.data.value).toBeNull();
    expect(result.busy.value).toBe(false);
  });

  it("immediate 为 false 时挂载不读取", async () => {
    const load = vi.fn(async () => ["a"]);
    withSetup(() => useResourceLoad({ load, problem: () => "", immediate: false }));
    await flushPromises();
    expect(load).not.toHaveBeenCalled();
  });
});

describe("usePagedResourceList", () => {
  it("页码是唯一事实源，行与总数从最近成功读取派生", async () => {
    const fetchPage = vi.fn(async (page: number) => ({ items: [`p${page}`], total: 41 }));
    const { result } = withSetup(() => usePagedResourceList<string>(fetchPage, { problem: () => "" }));
    await flushPromises();
    expect(fetchPage).toHaveBeenCalledWith(1);
    expect(result.rows.value).toEqual(["p1"]);
    expect(result.total.value).toBe(41);
    expect(result.page.value).toBe(1);
  });

  it("goTo 换页后立即重读，pages 以增量表达同一动作", async () => {
    const fetchPage = vi.fn(async (page: number) => ({ items: [`p${page}`], total: 41 }));
    const { result } = withSetup(() => usePagedResourceList<string>(fetchPage, { problem: () => "" }));
    await flushPromises();
    result.pages(1);
    await flushPromises();
    expect(result.page.value).toBe(2);
    expect(result.rows.value).toEqual(["p2"]);
    result.pages(-1);
    await flushPromises();
    expect(result.page.value).toBe(1);
    expect(fetchPage).toHaveBeenCalledTimes(3);
  });

  it("越界与同页的翻页请求不产生新读取", async () => {
    const fetchPage = vi.fn(async (page: number) => ({ items: [`p${page}`], total: 5 }));
    const { result } = withSetup(() => usePagedResourceList<string>(fetchPage, { problem: () => "" }));
    await flushPromises();
    result.goTo(1);
    result.goTo(0);
    result.pages(-1);
    await flushPromises();
    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(result.page.value).toBe(1);
  });

  it("读取失败保留原行与总数，仅写提示", async () => {
    const fetchPage = vi.fn(async (page: number) => {
      if (page === 2) throw new Error("超时");
      return { items: [`p${page}`], total: 9 };
    });
    const { result } = withSetup(() => usePagedResourceList<string>(fetchPage, { problem: () => "加载失败" }));
    await flushPromises();
    result.goTo(2);
    await flushPromises();
    expect(result.rows.value).toEqual(["p1"]);
    expect(result.total.value).toBe(9);
    expect(result.error.value).toBe("加载失败");
  });
});
