<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";

type DropletOption = { value: string; label: string };

const props = defineProps<{ id: string; label: string; options: DropletOption[]; modelValue: string }>();
const emit = defineEmits<{ "update:modelValue": [string]; change: [string] }>();

const expanded = ref(false);
const rootRef = ref<HTMLElement | null>(null);

const currentLabel = computed(
  () => props.options.find((option) => option.value === props.modelValue)?.label ?? props.options[0]?.label ?? "");

function toggle(): void {
  expanded.value = !expanded.value;
}

function collapse(): void {
  expanded.value = false;
}

function pick(value: string): void {
  emit("update:modelValue", value);
  emit("change", value);
  collapse();
}

function onDocumentClick(event: MouseEvent): void {
  if (!rootRef.value?.contains(event.target as Node)) {
    collapse();
  }
}

watch(expanded, (open) => {
  if (open) {
    document.addEventListener("click", onDocumentClick);
  } else {
    document.removeEventListener("click", onDocumentClick);
  }
});

onBeforeUnmount(() => document.removeEventListener("click", onDocumentClick));

function onKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") {
    collapse();
  }
}
</script>

<template>
  <div ref="rootRef" class="fams-droplet" :data-open="expanded ? 'true' : 'false'" @keydown="onKeydown">
    <button class="fams-droplet-capsule" type="button" :id="id" :aria-expanded="expanded"
      :aria-controls="`${id}-options`" @click="toggle">
      <span class="fams-droplet-name">{{ label }}</span>
      <span class="fams-droplet-value">{{ currentLabel }}</span>
      <svg class="fams-droplet-drop" viewBox="0 0 16 16" aria-hidden="true">
        <path d="M8 2.5c2.6 3 4 4.8 4 6.6a4 4 0 1 1-8 0c0-1.8 1.4-3.6 4-6.6z" fill="none"
          stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
      </svg>
    </button>
    <TransitionGroup v-if="expanded" :id="`${id}-options`" tag="div" name="fams-droplet" class="fams-droplet-options"
      :aria-label="`${label}筛选`">
      <button v-for="(option, i) in options" :key="option.value" class="fams-droplet-chip" type="button"
        :style="{ '--i': i }" :aria-pressed="option.value === modelValue" @click="pick(option.value)">
        {{ option.label }}
      </button>
    </TransitionGroup>
    <div v-else :id="`${id}-options`" hidden></div>
  </div>
</template>
