import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import { defineComponent, ref } from "vue";
import { useModuleFocus, type FocusModule } from "./useModuleFocus";

const DEFS = ref<FocusModule[]>([
  { id: "a", title: "模块 A" },
  { id: "b", title: "模块 B" },
  { id: "c", title: "模块 C" },
]);

function setup() {
  let api!: ReturnType<typeof useModuleFocus>;
  const wrapper = mount(defineComponent({
    setup() {
      api = useModuleFocus(DEFS);
      return () => null;
    },
  }));
  return { wrapper, api };
}

describe("useModuleFocus", () => {
  it("toggles focus, swaps via thumbnails and closes without leaving the stage", async () => {
    const { wrapper, api } = setup();
    expect(api.focusId.value).toBeNull();
    expect(api.isVisible("b")).toBe(true);

    api.toggleModule("b");
    expect(api.focusId.value).toBe("b");
    expect(api.isVisible("b")).toBe(true);
    expect(api.isVisible("a")).toBe(false);
    expect(api.thumbnailModules.value.map((m) => m.id)).toEqual(["a", "c"]);

    api.focusModule("c");
    expect(api.focusId.value).toBe("c");
    expect(api.thumbnailModules.value.map((m) => m.id)).toEqual(["a", "b"]);

    api.toggleModule("c");
    expect(api.focusId.value).toBeNull();
    wrapper.unmount();
  });

  it("Escape closes the stage only while focused", async () => {
    const { wrapper, api } = setup();
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    expect(api.focusId.value).toBeNull();

    api.focusModule("a");
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await wrapper.vm.$nextTick();
    expect(api.focusId.value).toBeNull();
    wrapper.unmount();
  });
});
