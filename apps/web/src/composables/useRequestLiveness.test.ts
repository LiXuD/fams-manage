import { mount } from "@vue/test-utils";
import { defineComponent, h, ref, shallowRef } from "vue";
import { describe, expect, it } from "vitest";
import { useRequestLiveness } from "./useRequestLiveness";

function withSetup<T>(composable: () => T): { result: T; owner: ReturnType<typeof ref<number>> } {
  const owner = ref(0);
  let captured!: T;
  const Probe = defineComponent({
    setup() {
      captured = composable();
      return () => h("div", String(owner.value));
    },
  });
  mount(Probe);
  return { result: captured, owner };
}

describe("useRequestLiveness", () => {
  it("只允许最新一代请求写回", () => {
    const { result: liveness } = withSetup(() => useRequestLiveness());
    const first = liveness.begin();
    const second = liveness.begin();
    expect(liveness.isCurrent(first)).toBe(false);
    expect(liveness.isCurrent(second)).toBe(true);
  });

  it("身份变化后旧请求不再有权写回", () => {
    const account = shallowRef("1");
    const { result: liveness } = withSetup(() => useRequestLiveness(() => [account.value]));
    const before = liveness.begin();
    expect(liveness.isCurrent(before)).toBe(true);
    account.value = "2";
    expect(liveness.isCurrent(before)).toBe(false);
  });

  it("卸载后一律作废，且不因在途响应继续写状态", async () => {
    let captured!: ReturnType<typeof useRequestLiveness>;
    const Probe = defineComponent({
      setup() {
        captured = useRequestLiveness();
        return () => h("div");
      },
    });
    const wrapper = mount(Probe);
    const token = captured.begin();
    wrapper.unmount();
    expect(captured.isCurrent(token)).toBe(false);
  });

  it("invalidate 让在途结果作废但不启动新读取", () => {
    const { result: liveness } = withSetup(() => useRequestLiveness());
    const token = liveness.begin();
    expect(liveness.isCurrent(token)).toBe(true);
    liveness.invalidate();
    expect(liveness.isCurrent(token)).toBe(false);
    const after = liveness.begin();
    expect(liveness.isCurrent(after)).toBe(true);
  });

  it("多元素身份逐项比较，长度变化也算失效", () => {
    const keys = shallowRef<readonly unknown[]>(["a", "b"]);
    const { result: liveness } = withSetup(() => useRequestLiveness(() => keys.value));
    const token = liveness.begin();
    keys.value = ["a", "b", "c"];
    expect(liveness.isCurrent(token)).toBe(false);
    keys.value = ["a", "c"];
    expect(liveness.isCurrent(token)).toBe(false);
    keys.value = ["a", "b"];
    expect(liveness.isCurrent(token)).toBe(true);
  });
});
