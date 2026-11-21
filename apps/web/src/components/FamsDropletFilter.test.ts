import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import FamsDropletFilter from "./FamsDropletFilter.vue";

const OPTIONS = [
  { value: "", label: "全部" },
  { value: "true", label: "已逾期" },
  { value: "false", label: "无逾期" },
];

function setup() {
  return mount(FamsDropletFilter, {
    props: { id: "ledger-overdue", label: "借用逾期", options: OPTIONS, modelValue: "" },
    attachTo: document.body,
  });
}

describe("FamsDropletFilter", () => {
  it("capsule shows current choice, expands to chips and reports selection on change", async () => {
    const wrapper = setup();
    const capsule = wrapper.get(".fams-droplet-capsule");
    expect(capsule.attributes("aria-expanded")).toBe("false");
    expect(capsule.text()).toContain("全部");
    expect(wrapper.find(".fams-droplet-options").exists()).toBe(false);

    await capsule.trigger("click");
    expect(wrapper.findAll(".fams-droplet-chip")).toHaveLength(3);
    expect(capsule.attributes("aria-expanded")).toBe("true");

    const chips = wrapper.findAll(".fams-droplet-chip");
    expect(chips[0].attributes("aria-pressed")).toBe("true"); // 当前值 "" 对应「全部」
    await chips[2].trigger("click");
    expect(wrapper.emitted("update:modelValue")).toEqual([["false"]]);
    expect(wrapper.emitted("change")).toEqual([["false"]]);
    expect(wrapper.attributes("data-open")).toBe("false");
    wrapper.unmount();
  });

  it("collapses on outside click and Escape", async () => {
    const wrapper = setup();
    await wrapper.get(".fams-droplet-capsule").trigger("click");
    expect(wrapper.find(".fams-droplet-options").exists()).toBe(true);

    await wrapper.get(".fams-droplet-options").trigger("keydown", { key: "Escape" });
    expect(wrapper.attributes("data-open")).toBe("false");

    await wrapper.get(".fams-droplet-capsule").trigger("click");
    document.body.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await wrapper.vm.$nextTick();
    expect(wrapper.attributes("data-open")).toBe("false");
    wrapper.unmount();
  });
});
