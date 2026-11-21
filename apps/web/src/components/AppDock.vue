<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";

type DockGroup = { label: string; items: { label: string; path: string }[] };

const props = defineProps<{ groups: DockGroup[] }>();
const emit = defineEmits<{ command: [] }>();
const router = useRouter();

const expanded = ref(false);

/** 分组图标直达该组当前可见的第一条目；权限过滤沿用外壳 navGroups。 */
const shortcuts = computed(() => props.groups
  .filter((group) => group.items.length > 0)
  .map((group) => ({ label: group.label, path: group.items[0].path })));

function go(path: string): void {
  expanded.value = false;
  void router.push(path);
}

function toggleExpand(): void {
  expanded.value = !expanded.value;
}

function closeLaunch(): void {
  expanded.value = false;
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape" && expanded.value) {
    closeLaunch();
  }
}

onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<template>
  <div class="fams-dock-zone">
    <Transition name="fams-launch">
      <div v-if="expanded" class="fams-launch" @click.self="closeLaunch">
        <div class="fams-launch-grid">
          <section v-for="group in groups" :key="group.label" class="fams-launch-group">
            <h3 class="fams-launch-title">{{ group.label }}</h3>
            <div class="fams-launch-items">
              <button v-for="item in group.items" :key="item.path" class="fams-launch-tile" type="button"
                @click="go(item.path)">{{ item.label }}</button>
            </div>
          </section>
        </div>
      </div>
    </Transition>
    <nav class="fams-dock" aria-label="快捷停靠栏">
      <button class="fams-dock-item fams-dock-expand" type="button" data-label="全部功能" aria-label="展开全部功能"
        :aria-expanded="expanded" @click="toggleExpand">
        <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7"
          stroke-linecap="round">
          <path d="M6 6h.01M12 6h.01M18 6h.01M6 12h.01M12 12h.01M18 12h.01M6 18h.01M12 18h.01M18 18h.01" stroke-width="2.4" />
        </svg>
      </button>
      <button v-for="shortcut in shortcuts" :key="shortcut.path" class="fams-dock-item" type="button"
        :data-label="shortcut.label" :aria-label="shortcut.label" @click="go(shortcut.path)">
        <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6"
          stroke-linecap="round" stroke-linejoin="round">
          <path v-if="shortcut.label === '总览'" d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z" />
          <path v-else-if="shortcut.label === '系统与主数据'"
            d="M4 7h10M18 7h2M4 12h4M12 12h8M4 17h13M21 17h-1M16 5v4M10 10v4M19 15v4" />
          <path v-else-if="shortcut.label === '资产'" d="M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3zM4 7.5l8 4.5 8-4.5M12 12v9" />
          <path v-else-if="shortcut.label === '业务流转'" d="M4 9h13l-3.5-3.5M20 15H7l3.5 3.5" />
          <path v-else d="M12 3l7 3v5c0 5-3.4 8.2-7 10-3.6-1.8-7-5-7-10V6l7-3zM9 12l2 2 4-4.5" />
        </svg>
      </button>
      <button class="fams-dock-item fams-dock-search" type="button" data-label="搜索 ⌘K" aria-label="打开命令面板"
        @click="emit('command')">
        <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7"
          stroke-linecap="round">
          <circle cx="11" cy="11" r="6" />
          <path d="M20 20l-4.6-4.6" />
        </svg>
      </button>
    </nav>
  </div>
</template>
