import { mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AppDock from "./AppDock.vue";

const push = vi.fn();
vi.mock("vue-router", () => ({ useRouter: () => ({ push }) }));

const GROUPS = [
  { label: "总览", items: [{ label: "工作台", path: "/" }] },
  { label: "资产", items: [{ label: "资产台账", path: "/assets" }, { label: "资产查询", path: "/asset-lookup" }] },
];

function setup() {
  return mount(AppDock, { props: { groups: GROUPS }, attachTo: document.body });
}

function settle(): Promise<void> {
  // Transition 的 leave 收尾等一帧 rAF，nextTick 不足以让元素真正移除。
  return new Promise((resolve) => setTimeout(resolve, 80));
}

describe("AppDock", () => {
  beforeEach(() => push.mockReset());

  it("renders one shortcut per group plus expand and search, and jumps to the group's first item", async () => {
    const wrapper = setup();
    const items = wrapper.findAll(".fams-dock-item");
    expect(items).toHaveLength(4); // 展开 + 总览 + 资产 + 搜索
    await items[1]!.trigger("click");
    expect(push).toHaveBeenCalledWith("/");
    expect(wrapper.find(".fams-launch").exists()).toBe(false);
    wrapper.unmount();
  });

  it("opens the full-screen launch panel listing every visible item, and closes on jump", async () => {
    const wrapper = setup();
    await wrapper.get(".fams-dock-expand").trigger("click");
    expect(wrapper.find(".fams-launch").exists()).toBe(true);
    const tiles = wrapper.findAll(".fams-launch-tile");
    expect(tiles.map((tile) => tile.text())).toEqual(["工作台", "资产台账", "资产查询"]);

    await tiles[2]!.trigger("click");
    expect(push).toHaveBeenCalledWith("/asset-lookup");
    await settle();
    expect(wrapper.find(".fams-launch").exists()).toBe(false);
    wrapper.unmount();
  });

  it("emits command for the search slot and Escape closes the panel", async () => {
    const wrapper = setup();
    await wrapper.get(".fams-dock-search").trigger("click");
    expect(wrapper.emitted("command")).toHaveLength(1);

    await wrapper.get(".fams-dock-expand").trigger("click");
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await settle();
    expect(wrapper.find(".fams-launch").exists()).toBe(false);
    wrapper.unmount();
  });
});
