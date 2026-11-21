import { onBeforeUnmount, onMounted } from "vue";
import type { Ref } from "vue";

const TILT_SELECTOR = ".fams-panel, .fams-card, .fams-metric";
const MAX_TILT_DEG = 3.2;

type Pending = { el: HTMLElement; nx: number; ny: number; px: number; py: number };

/** 鼠标随光倾斜：在容器上委托 pointermove，光标所在玻璃卡片写 --glow-* 柔光坐标
    并按光标偏移做 perspective 微 3D 倾斜；移出平滑复原。触屏指针与降级动效不倾斜。 */
export function useGlowTilt(container: Ref<HTMLElement | null>): void {
  let current: HTMLElement | null = null;
  let pending: Pending | null = null;
  let frame = 0;

  const reducedMotion = (): boolean =>
    typeof window.matchMedia === "function"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function reset(el: HTMLElement): void {
    el.removeAttribute("data-tilt");
    el.style.transform = "";
    pending = null;
  }

  function flush(): void {
    frame = 0;
    const p = pending;
    if (!p) {
      return;
    }
    p.el.style.setProperty("--glow-x", `${Math.round(p.px)}px`);
    p.el.style.setProperty("--glow-y", `${Math.round(p.py)}px`);
    p.el.style.transform = reducedMotion()
      ? ""
      : `perspective(900px) rotateX(${((0.5 - p.ny) * MAX_TILT_DEG).toFixed(2)}deg) rotateY(${((p.nx - 0.5) * MAX_TILT_DEG).toFixed(2)}deg)`;
  }

  function onMove(event: PointerEvent): void {
    if (event.pointerType !== "mouse") {
      return;
    }
    const root = container.value;
    if (!root) {
      return;
    }
    const target = event.target as Element | null;
    const card = target?.closest<HTMLElement>(TILT_SELECTOR) ?? null;
    if (!card || !root.contains(card)) {
      if (current) {
        reset(current);
      }
      return;
    }
    if (current && current !== card) {
      reset(current);
    }
    current = card;
    card.dataset.tilt = "on";
    const r = card.getBoundingClientRect();
    pending = { el: card, nx: (event.clientX - r.left) / r.width, ny: (event.clientY - r.top) / r.height, px: event.clientX - r.left, py: event.clientY - r.top };
    if (!frame) {
      frame = requestAnimationFrame(flush);
    }
  }

  function onLeave(): void {
    if (current) {
      reset(current);
      current = null;
    }
    if (frame) {
      cancelAnimationFrame(frame);
      frame = 0;
    }
  }

  onMounted(() => {
    const root = container.value;
    if (!root) {
      return;
    }
    root.addEventListener("pointermove", onMove);
    root.addEventListener("pointerleave", onLeave);
  });

  onBeforeUnmount(() => {
    const root = container.value;
    root?.removeEventListener("pointermove", onMove);
    root?.removeEventListener("pointerleave", onLeave);
    if (frame) {
      cancelAnimationFrame(frame);
    }
  });
}
