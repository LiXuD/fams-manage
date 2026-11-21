import { mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, ref, type Ref } from "vue";
import { useCountUp } from "./useCountUp";

function harness(source: Ref<number>) {
  return mount(defineComponent({
    setup() {
      const shown = useCountUp(source);
      return { shown };
    },
    template: "<span>{{ shown }}</span>",
  }));
}

describe("useCountUp", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("snaps the first value, then rolls subsequent changes to the target", async () => {
    const source = ref(0);
    const wrapper = harness(source);

    source.value = 25;
    await vi.advanceTimersByTimeAsync(0);
    expect(wrapper.text()).toBe("25"); // 首次直出，不等动画

    source.value = 7;
    await vi.advanceTimersByTimeAsync(250);
    expect(Number(wrapper.text())).toBeLessThanOrEqual(25);
    expect(Number(wrapper.text())).toBeGreaterThan(7); // 滚动中段

    await vi.advanceTimersByTimeAsync(600);
    expect(wrapper.text()).toBe("7");
    wrapper.unmount();
  });

  it("cancels the in-flight roll when a newer value arrives mid-animation", async () => {
    const source = ref(0);
    const wrapper = harness(source);
    source.value = 100;
    await vi.advanceTimersByTimeAsync(0);
    source.value = 40;
    await vi.advanceTimersByTimeAsync(200);
    source.value = 60;
    await vi.advanceTimersByTimeAsync(800);
    expect(wrapper.text()).toBe("60");
    wrapper.unmount();
  });
});
