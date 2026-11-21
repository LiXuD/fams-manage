import { nextTick, onBeforeUnmount, onMounted, watch } from "vue";
import type { Ref } from "vue";

type Rect = { x: number; y: number; w: number; h: number };

const SPRING_EASE = "cubic-bezier(0.34, 1.56, 0.64, 1)";

function motionAllowed(el: HTMLElement): boolean {
  if (typeof el.animate !== "function") {
    return false;
  }
  return !(typeof window.matchMedia === "function"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches);
}

/** 液态高亮胶囊：路由或折叠变化后按 FLIP 移到选中条目，行进沿方向拉长、到位弹性回弹；
    无 WAAPI、降级动效或无布局（headless）时直接落位。 */
export function useLiquidNavHighlight(
  container: Ref<HTMLElement | null>,
  deps: () => unknown,
): void {
  let prev: Rect | null = null;
  let observer: ResizeObserver | null = null;

  function place(animate: boolean): void {
    const root = container.value;
    const liquid = root?.querySelector<HTMLElement>(".fams-nav-liquid");
    const active = root?.querySelector<HTMLElement>('[aria-current="page"]');
    if (!root || !liquid || !active) {
      prev = null;
      return;
    }
    const base = root.getBoundingClientRect();
    const item = active.getBoundingClientRect();
    const rect: Rect = { x: item.left - base.left, y: item.top - base.top, w: item.width, h: item.height };
    if (rect.w === 0 || rect.h === 0) {
      return;
    }
    const transform = `translate(${rect.x}px, ${rect.y}px)`;
    liquid.style.width = `${rect.w}px`;
    liquid.style.height = `${rect.h}px`;
    liquid.style.opacity = "1";
    if (!animate || !motionAllowed(liquid)) {
      liquid.style.transform = transform;
      prev = rect;
      return;
    }
    if (prev) {
      const p = prev;
      liquid.style.transform = transform;
      liquid.animate([
        {
          transform: `translate(${p.x}px, ${p.y}px) scale(${p.w / rect.w}, ${p.h / rect.h})`,
          easing: "cubic-bezier(0.45, 0, 0.75, 0.28)",
        },
        {
          transform: `translate(${rect.x}px, ${rect.y}px) scale(0.94, ${Math.max(1.08, (p.h / rect.h) * 0.92)})`,
          offset: 0.55,
          easing: SPRING_EASE,
        },
        { transform },
      ], { duration: Math.min(560, Math.max(340, 300 + Math.abs(p.y - rect.y) * 0.8)) });
    } else {
      liquid.style.transform = transform;
      liquid.animate(
        [{ transform: `${transform} scale(0.92)`, opacity: 0 }, { transform, opacity: 1 }],
        { duration: 220, easing: SPRING_EASE },
      );
    }
    prev = rect;
  }

  watch(deps, () => {
    void nextTick(() => place(true));
  }, { immediate: true });

  onMounted(() => {
    if (typeof ResizeObserver === "undefined" || !container.value) {
      return;
    }
    observer = new ResizeObserver(() => place(false));
    observer.observe(container.value);
  });

  onBeforeUnmount(() => observer?.disconnect());
}
