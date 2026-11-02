import { mount } from "@vue/test-utils";
import { defineComponent, h, nextTick, ref } from "vue";
import { expect, it } from "vitest";
import { createBusyRegistry, provideBusyRegistry, useBusy, type BusyClaim, type BusyId } from "./busyRegistry";

type Claims = Partial<Record<BusyId, BusyClaim>>;

/** 用一个最小组件占住一位，便于断言「谁在忙→谁禁用」与卸载释放。 */
function panel(id: BusyId, claims: Claims) {
  return defineComponent({ name: `BusyPanel${id}`, setup() { claims[id] = useBusy(id); return () => h("span", id); } });
}

it("两个消费者互相占用，且判据不含自己的位", () => {
  const claims: Claims = {};
  const physical = panel("physical", claims), returned = panel("return", claims);
  const host = defineComponent({ setup() { provideBusyRegistry(); return () => h("div", [h(physical), h(returned)]); } });
  mount(host);
  expect(claims.physical!.blocked.value).toBe(false);
  expect(claims.return!.blocked.value).toBe(false);
  claims.physical!.busy.value = true;
  expect(claims.return!.blocked.value).toBe(true);
  expect(claims.physical!.blocked.value).toBe(false);
  claims.physical!.busy.value = false;
  expect(claims.return!.blocked.value).toBe(false);
});

it("消费者卸载即释放自己的占用位", async () => {
  const claims: Claims = {};
  const shown = ref(true);
  const physical = panel("physical", claims), source = panel("returnSource", claims);
  const host = defineComponent({ setup() { provideBusyRegistry(); return () => h("div", [h(physical), shown.value ? h(source) : null]); } });
  mount(host);
  claims.returnSource!.busy.value = true;
  expect(claims.physical!.blocked.value).toBe(true);
  shown.value = false;
  await nextTick();
  expect(claims.physical!.blocked.value).toBe(false);
});

it("缺少 provider 时明确报错而不造本地副本", () => {
  const orphan = defineComponent({ setup() { useBusy("factory"); return () => h("span"); } });
  expect(() => mount(orphan)).toThrow(/provideBusyRegistry/);
});

it("occupied 只读单个占用位，宿主据此保留定点编排", () => {
  const registry = createBusyRegistry();
  const factory = registry.register("factory"), physical = registry.register("physical");
  expect(registry.occupied("factory").value).toBe(false);
  factory.busy.value = true;
  expect(registry.occupied("factory").value).toBe(true);
  expect(registry.occupied("physical").value).toBe(false);
  expect(physical.blocked.value).toBe(true);
  factory.busy.value = false;
  expect(physical.blocked.value).toBe(false);
});

it("每个 provider 实例独立簿记", () => {
  const claims: Claims = {};
  const first = panel("collaboration", claims);
  const host = defineComponent({ setup() { provideBusyRegistry(); return () => h(first); } });
  const wrapper = mount(host);
  const other = createBusyRegistry();
  claims.collaboration!.busy.value = true;
  expect(other.occupied("collaboration").value).toBe(false);
  wrapper.unmount();
  expect(claims.collaboration!.blocked.value).toBe(false);
});
