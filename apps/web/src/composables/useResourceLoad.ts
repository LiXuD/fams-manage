import { onBeforeUnmount, onMounted, readonly, ref, shallowRef, type Ref } from "vue";

/**
 * 一次性「读取—失败—再读取」的加载生命周期。
 *
 * 只拥有 busy/error/data 与迟到响应抑制：请求返回时若已有更新的一次读取或组件已卸载，结果一律丢弃，
 * 既不给旧数据覆盖新数据，也不在卸载后写状态。会话身份变化后的重置属域内政策，留在视图自己判断。
 */

export interface ResourceLoadOptions<T> {
  /** 真正的读取动作；由调用方闭包持有筛选条件等输入 */
  load: () => Promise<T>;
  /** 把捕获的错误转成该域的中文提示 */
  problem: (cause: unknown) => string;
  /** 默认在 onMounted 立即读取一次 */
  immediate?: boolean;
}

export interface ResourceLoad<T> {
  data: Readonly<Ref<T | null>>;
  busy: Readonly<Ref<boolean>>;
  error: Readonly<Ref<string>>;
  /** 允许并发触发；只有最后一次调用有权写回数据与状态 */
  refresh: () => Promise<void>;
}

export function useResourceLoad<T>(options: ResourceLoadOptions<T>): ResourceLoad<T> {
  const data = shallowRef<T | null>(null);
  const busy = ref(false);
  const error = ref("");
  let epoch = 0;
  let live = true;

  function current(token: number): boolean {
    return live && epoch === token;
  }

  async function refresh(): Promise<void> {
    if (!live) return;
    const token = ++epoch;
    busy.value = true;
    error.value = "";
    try {
      const value = await options.load();
      if (current(token)) data.value = value;
    } catch (cause) {
      if (current(token)) error.value = options.problem(cause);
    } finally {
      if (current(token)) busy.value = false;
    }
  }

  onBeforeUnmount(() => { live = false; epoch++; busy.value = false; });
  if (options.immediate !== false) onMounted(refresh);

  return {
    data: readonly(data) as Readonly<Ref<T | null>>,
    busy: readonly(busy),
    error: readonly(error),
    refresh,
  };
}
