import { onBeforeUnmount } from "vue";

/**
 * 一次请求的「还算不算数」判据：更新的请求已启动、依赖身份已变化、或本组件已卸载，
 * 那么这次返回的响应就不允许写回视图。
 *
 * 只拥有迟到抑制本身。busy/error/数据槽、失败文案映射与卸载时的其他清理都留在调用方，
 * 因为本项目的读取与写流程共用同一个互斥位，把槽位一起抽走会改变禁用与错误覆盖语义。
 */
export interface LivenessToken {
  readonly sequence: number;
  readonly keys: readonly unknown[];
}

export interface Liveness {
  /** 捕获当前代号与身份；同一时刻只有最后一次 begin() 有权写回。 */
  begin(): LivenessToken;
  /** 写回前询问：仍为最新一代、身份未变且组件仍存活时才为真。 */
  isCurrent(token: LivenessToken): boolean;
  /** 让所有在途结果作废：清理或复位状态时用，不启动新一轮读取。 */
  invalidate(): void;
}

export function useRequestLiveness(identity: () => readonly unknown[] = () => []): Liveness {
  let sequence = 0;
  let live = true;

  function begin(): LivenessToken {
    return { sequence: ++sequence, keys: identity() };
  }

  function isCurrent(token: LivenessToken): boolean {
    if (!live || token.sequence !== sequence) return false;
    const now = identity();
    return now.length === token.keys.length && now.every((value, index) => value === token.keys[index]);
  }

  function invalidate(): void {
    sequence++;
  }

  onBeforeUnmount(() => {
    live = false;
    invalidate();
  });

  return { begin, isCurrent, invalidate };
}
