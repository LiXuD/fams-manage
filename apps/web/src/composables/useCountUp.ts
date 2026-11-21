import { onScopeDispose, ref, watch } from "vue";
import type { Ref } from "vue";

const ROLL_MS = 520;

/** 数字滚动：源值首次到达直出，之后的变化用 rAF 三次缓出滚动；降级动效时直出。 */
export function useCountUp(source: Ref<number>): Ref<number> {
  const display = ref(Math.round(source.value));
  let frame = 0;
  let primed = false;

  const reducedMotion = (): boolean =>
    typeof window.matchMedia === "function"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  watch(source, (raw) => {
    const to = Math.round(raw);
    if (frame) {
      cancelAnimationFrame(frame);
      frame = 0;
    }
    if (!primed || reducedMotion() || display.value === to) {
      primed = true;
      display.value = to;
      return;
    }
    primed = true;
    const from = display.value;
    let begin = 0;
    const step = (now: number): void => {
      if (!begin) {
        begin = now;
      }
      const t = Math.min(1, (now - begin) / ROLL_MS);
      const eased = 1 - Math.pow(1 - t, 3);
      display.value = Math.round(from + (to - from) * eased);
      if (t < 1) {
        frame = requestAnimationFrame(step);
      } else {
        frame = 0;
        display.value = to;
      }
    };
    frame = requestAnimationFrame(step);
  });

  onScopeDispose(() => {
    if (frame) {
      cancelAnimationFrame(frame);
    }
  });

  return display;
}
