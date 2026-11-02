import { type ComputedRef, type InjectionKey, type WritableComputedRef, computed, inject, onScopeDispose, provide, shallowRef } from "vue";

/** 同一报修办理视图内互相排斥的面板身份；host 是宿主视图自身的读取、待确认与在途办理。 */
export type BusyId = "collaboration" | "factory" | "host" | "physical" | "reassignment" | "return" | "returnSource" | "targetChange";

/** 一个占用者拿到的互斥位：只能写自己这一位，只能读「别人是否在忙」。 */
export interface BusyClaim {
  /** 本面板的占用位，写入面板自身的互斥合成值（busy、prepared、pending），等价于原 busyChange 载荷。 */
  readonly busy: WritableComputedRef<boolean>;
  /** 只读禁用判据：任一其它占用者在忙即为真，不含自己。 */
  readonly blocked: ComputedRef<boolean>;
}

export interface BusyRegistry {
  register(id: BusyId): BusyClaim;
  /** 读取单个占用位；宿主视图保留只针对某一个面板的编排判据时使用。 */
  occupied(id: BusyId): ComputedRef<boolean>;
}

export const busyRegistryKey: InjectionKey<BusyRegistry> = Symbol("fams-repair-busy-registry");

/** Provider 私有簿记，每个宿主视图实例一份；不进入 props，也不跨实例共享。 */
export function createBusyRegistry(): BusyRegistry {
  const claims = shallowRef<ReadonlyMap<BusyId, boolean>>(new Map());
  const read = (id: BusyId): boolean => claims.value.get(id) === true;
  const write = (id: BusyId, value: boolean): void => {
    if (read(id) === value) return;
    const next = new Map(claims.value);
    if (value) next.set(id, true);
    else next.delete(id);
    claims.value = next;
  };
  return {
    register: (id: BusyId): BusyClaim => ({
      busy: computed<boolean>({ get: () => read(id), set: (value) => write(id, value) }),
      blocked: computed(() => [...claims.value.keys()].some((other) => other !== id && read(other))),
    }),
    occupied: (id: BusyId): ComputedRef<boolean> => computed(() => read(id)),
  };
}

/** 在最近共同祖先（宿主视图）的 setup 中调用一次；返回同一实例，供宿主自己占用与定点读取。 */
export function provideBusyRegistry(): BusyRegistry {
  const registry = createBusyRegistry();
  provide(busyRegistryKey, registry);
  return registry;
}

/** 面板在 setup 中调用；缺少 provider 直接报错，不造本地副本。作用域销毁时释放自己的位。 */
export function useBusy(id: BusyId): BusyClaim {
  const registry = inject(busyRegistryKey);
  if (!registry) throw new Error(`useBusy("${id}") requires provideBusyRegistry() in an ancestor view setup`);
  const claim = registry.register(id);
  onScopeDispose(() => { claim.busy.value = false; });
  return claim;
}
