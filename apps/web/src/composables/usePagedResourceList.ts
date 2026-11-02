import { computed, readonly, shallowRef, type ComputedRef, type Ref } from "vue";
import { useResourceLoad } from "./useResourceLoad";

/** 后端分页响应在本项目里的共同形状。 */
export interface PagedResult<T> { items: T[]; total: number }

export interface PagedResourceList<T> {
  rows: ComputedRef<T[]>;
  total: ComputedRef<number>;
  page: Readonly<Ref<number>>;
  busy: Readonly<Ref<boolean>>;
  error: Readonly<Ref<string>>;
  refresh: () => Promise<void>;
  goTo: (next: number) => void;
  pages: (delta: number) => void;
  /** 筛选条件变了：回到第一页重读；本就在第一页时直接重读，不能因为页码没变就不发请求。 */
  restart: () => Promise<void>;
}

/**
 * 分页列表：页码是唯一事实源，行与总数都从最近一次成功读取派生。
 * 筛选条件留在调用方闭包里，改完条件直接 refresh()。
 */
export function usePagedResourceList<T>(fetchPage: (page: number) => Promise<PagedResult<T>>, options: {
  problem: (cause: unknown) => string;
  immediate?: boolean;
}): PagedResourceList<T> {
  const page = shallowRef(1);
  const load = useResourceLoad<PagedResult<T>>({
    load: () => fetchPage(page.value),
    problem: options.problem,
    immediate: options.immediate,
  });

  function goTo(next: number): void {
    if (next < 1 || next === page.value) return;
    page.value = next;
    void load.refresh();
  }

  function restart(): Promise<void> {
    page.value = 1;
    return load.refresh();
  }

  return {
    rows: computed(() => load.data.value?.items ?? []),
    total: computed(() => load.data.value?.total ?? 0),
    page: readonly(page) as Readonly<Ref<number>>,
    busy: load.busy,
    error: load.error,
    refresh: load.refresh,
    restart,
    goTo,
    pages: (delta) => goTo(page.value + delta),
  };
}
