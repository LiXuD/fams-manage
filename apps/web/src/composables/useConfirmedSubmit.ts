import { computed, onBeforeUnmount, shallowRef, watch, type ComputedRef } from "vue";

/**
 * 一次「确认式幂等提交」的意图：key 在首次起草时生成，此后重试只复用同一 key，
 * 直到流程成功（settle）、确定性失败（abandon/fail）或作废（cancel/reset）为止。
 * payload 就是去掉幂等键的突变对象本体；请求 URL、版本核对、文件与文案全部留在面板。
 */
export interface Submission<T> {
  readonly key: string;
  readonly payload: T;
}

export interface ConfirmedSubmit<T> {
  /** 页内二次确认快照；写入只经 prepare/cancel，面板与模板只做读取和 watch。 */
  readonly prepared: ComputedRef<Submission<T> | null>;
  /** 已发出的在途意图；重试必须复用其 key。 */
  readonly pending: ComputedRef<Submission<T> | null>;
  /** 起草（或在既有草稿上重新取内容）：首次生成幂等键，重复调用不换键。 */
  prepare(payload: () => T): Submission<T>;
  /** 确认发送：把草稿原子移交为在途意图；无草稿返回 null 且不改状态。 */
  confirm(): Submission<T> | null;
  /** 成功收尾：清空两个槽。 */
  settle(): void;
  /** 确定性失败：清掉在途意图允许重新起草（草稿槽不受影响）。 */
  abandon(): void;
  /** 用 options.failure 判定该失败是否确定性；是则等价 abandon，返回判定结果。 */
  fail(cause: unknown): boolean;
  /** 「返回修改」：只撤销草稿，不动在途意图。 */
  cancel(): void;
  /** 身份变化或卸载级别的作废：清空两个槽。 */
  reset(): void;
}

/**
 * 只拥有幂等键的持有与清键规则这一项职责：草稿/在途两个槽的簿记、confirm 的原子移交、
 * 确定性失败后的清键、identity 变化与卸载时的作废。busy/locked 合成、错误文案、
 * 请求构造与成功后的 reload/emit 时序都留在调用方面板。
 *
 * 对外暴露 ComputedRef 而非可写 Ref：模板与 watch 只读取，任何状态迁移都必须走命名方法，
 * 「重试不换键」的不变量因此无法被消费方绕开。
 */
export function useConfirmedSubmit<T>(options: {
  /** 返回值变化即作废两个槽（同步生效）；不传则由调用方在既有清理路径里显式 reset。 */
  identity?: () => unknown;
  /** 该失败是否确定性（清键允许重新起草）；不传则 fail 一律判非确定性、保留在途意图。 */
  failure?: (cause: unknown) => boolean;
} = {}): ConfirmedSubmit<T> {
  const draft = shallowRef<Submission<T> | null>(null);
  const inFlight = shallowRef<Submission<T> | null>(null);
  function reset(): void {
    draft.value = null;
    inFlight.value = null;
  }
  function prepare(payload: () => T): Submission<T> {
    const submission: Submission<T> = { key: draft.value?.key ?? crypto.randomUUID(), payload: payload() };
    draft.value = submission;
    return submission;
  }
  function confirm(): Submission<T> | null {
    const submission = draft.value;
    if (!submission) return null;
    draft.value = null;
    inFlight.value = submission;
    return submission;
  }
  function abandon(): void {
    inFlight.value = null;
  }
  function fail(cause: unknown): boolean {
    if (options.failure?.(cause) !== true) return false;
    abandon();
    return true;
  }
  function cancel(): void {
    draft.value = null;
  }
  if (options.identity) {
    const identity = options.identity;
    watch(() => identity(), reset, { flush: "sync" });
  }
  onBeforeUnmount(reset);
  return { prepared: computed(() => draft.value), pending: computed(() => inFlight.value), prepare, confirm, settle: reset, abandon, fail, cancel, reset };
}
