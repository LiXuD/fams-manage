import { computed, onBeforeUnmount, onMounted, ref, type Ref } from "vue";

export type FocusModule = { id: string; title: string };

/** 模块原位放大：focusId 空为常规流；聚焦后其余模块隐去并落进底部缩略条，
    点缩略原位切换、收回原路退出，全程不改路由。Esc 与「沿原路收回」等价。 */
export function useModuleFocus(modules: Ref<FocusModule[]>) {
  const focusId = ref<string | null>(null);

  const thumbnailModules = computed(() => modules.value.filter((module) => module.id !== focusId.value));

  function isFocused(id: string): boolean {
    return focusId.value === id;
  }

  function isVisible(id: string): boolean {
    return focusId.value === null || focusId.value === id;
  }

  function focusModule(id: string): void {
    focusId.value = id;
  }

  function toggleModule(id: string): void {
    focusId.value = focusId.value === id ? null : id;
  }

  function closeModules(): void {
    focusId.value = null;
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === "Escape" && focusId.value !== null) {
      closeModules();
    }
  }

  onMounted(() => window.addEventListener("keydown", onKeydown));
  onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));

  return { focusId, thumbnailModules, isFocused, isVisible, focusModule, toggleModule, closeModules };
}
