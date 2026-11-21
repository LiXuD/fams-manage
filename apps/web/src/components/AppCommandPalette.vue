<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter, type RouteRecordNormalized } from "vue-router";

type CommandItem = { title: string; group: string; path: string };
type Seg = { text: string; hit: boolean };
type Row = { item: CommandItem; titleSegs: Seg[]; groupSegs: Seg[]; pathSegs: Seg[]; score: number };

const router = useRouter();
const open = ref(false);
const query = ref("");
const activeIndex = ref(0);
const inputRef = ref<HTMLInputElement | null>(null);
const listRef = ref<HTMLElement | null>(null);
let lastFocus: Element | null = null;

/** 命令源与页头同源：带 uiGroup+uiTitle 的授权路由；带路径参数的详情路由无法直达，不进面板。 */
const index = computed<CommandItem[]>(() => {
  const items: CommandItem[] = [];
  for (const route of router.getRoutes() as RouteRecordNormalized[]) {
    const { requiresAuth, uiGroup, uiTitle } = route.meta;
    if (requiresAuth && uiGroup && uiTitle && !route.path.includes(":")) {
      items.push({ title: uiTitle, group: uiGroup, path: route.path });
    }
  }
  return items;
});

function segments(text: string, q: string): Seg[] {
  const at = q ? text.toLowerCase().indexOf(q) : -1;
  if (at < 0) {
    return [{ text, hit: false }];
  }
  const segs: Seg[] = [];
  if (at > 0) segs.push({ text: text.slice(0, at), hit: false });
  segs.push({ text: text.slice(at, at + q.length), hit: true });
  if (at + q.length < text.length) segs.push({ text: text.slice(at + q.length), hit: false });
  return segs;
}

const results = computed<Row[]>(() => {
  const q = query.value.trim().toLowerCase();
  const rows: Row[] = [];
  for (const item of index.value) {
    const title = item.title.toLowerCase();
    const group = item.group.toLowerCase();
    const path = item.path.toLowerCase();
    let score: number;
    if (!q) {
      score = 0;
    } else if (title.startsWith(q)) {
      score = 0;
    } else if (title.includes(q)) {
      score = 1;
    } else if (group.includes(q)) {
      score = 2;
    } else if (path.includes(q)) {
      score = 3;
    } else {
      continue;
    }
    rows.push({
      item,
      score,
      titleSegs: segments(item.title, q),
      groupSegs: segments(item.group, q),
      pathSegs: segments(item.path, q),
    });
  }
  if (q) {
    rows.sort((a, b) => a.score - b.score);
  }
  return rows.slice(0, 12);
});

watch(query, () => {
  activeIndex.value = 0;
});

watch(results, (rows) => {
  if (rows.length > 0 && rows.length <= activeIndex.value) {
    activeIndex.value = rows.length - 1;
  }
});

function scrollToActive(): void {
  const option = listRef.value?.children[activeIndex.value] as HTMLElement | undefined;
  option?.scrollIntoView?.({ block: "nearest" });
}

watch(activeIndex, () => nextTick(scrollToActive));

function openPanel(): void {
  if (open.value) {
    return;
  }
  lastFocus = document.activeElement;
  open.value = true;
  query.value = "";
  activeIndex.value = 0;
  void nextTick(() => inputRef.value?.focus());
}

function closePanel(): void {
  if (!open.value) {
    return;
  }
  open.value = false;
  query.value = "";
  if (lastFocus instanceof HTMLElement) {
    lastFocus.focus();
  }
  lastFocus = null;
}

function go(row: Row | undefined): void {
  if (!row) {
    return;
  }
  closePanel();
  void router.push(row.item.path);
}

function onInputKeydown(event: KeyboardEvent): void {
  if (event.key === "ArrowDown") {
    event.preventDefault();
    activeIndex.value = Math.min(results.value.length - 1, activeIndex.value + 1);
  } else if (event.key === "ArrowUp") {
    event.preventDefault();
    activeIndex.value = Math.max(0, activeIndex.value - 1);
  } else if (event.key === "Enter") {
    event.preventDefault();
    go(results.value[activeIndex.value]);
  } else if (event.key === "Escape") {
    event.preventDefault();
    closePanel();
  }
}

function onGlobalKeydown(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    if (open.value) {
      closePanel();
    } else {
      openPanel();
    }
  }
}

onMounted(() => window.addEventListener("keydown", onGlobalKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onGlobalKeydown));

defineExpose({ openPanel, closePanel });
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="fams-cmd-overlay" @click.self="closePanel">
      <div class="fams-cmd-panel" role="dialog" aria-modal="true" aria-label="命令面板">
        <input ref="inputRef" v-model="query" class="fams-cmd-input" type="search" autocomplete="off"
          placeholder="搜索页面名称、分组或路径…" aria-label="搜索页面" @keydown="onInputKeydown" />
        <ul ref="listRef" class="fams-cmd-list" role="listbox" aria-label="可选页面">
          <li v-for="(row, i) in results" :key="row.item.path" role="option" :aria-selected="i === activeIndex"
            @click="go(row)" @mousemove="activeIndex = i">
            <span class="fams-cmd-title"><template v-for="(seg, s) in row.titleSegs" :key="s"><mark
                  v-if="seg.hit">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></span>
            <span class="fams-cmd-group"><template v-for="(seg, s) in row.groupSegs" :key="s"><mark
                  v-if="seg.hit">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></span>
            <code class="fams-cmd-path"><template v-for="(seg, s) in row.pathSegs" :key="s"><mark
                  v-if="seg.hit">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></code>
          </li>
          <li v-if="!results.length" class="fams-cmd-empty">无匹配页面</li>
        </ul>
        <div class="fams-cmd-foot"><span><kbd>↑↓</kbd> 选择</span><span><kbd>↵</kbd> 跳转</span><span><kbd>esc</kbd> 关闭</span></div>
      </div>
    </div>
  </Teleport>
</template>
