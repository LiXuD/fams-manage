<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";

const emit = defineEmits<{ dismiss: [] }>();
const dialog = ref<HTMLDialogElement | null>(null);

onMounted(() => {
  const element = dialog.value;
  if (!element) return;
  // jsdom 未实现 showModal/close，测试环境靠 open 属性渲染同一棵子树。
  if (typeof element.showModal === "function") element.showModal();
  else element.setAttribute("open", "");
});

onBeforeUnmount(() => {
  // 父级用 v-if 撤下预览，节点是被移除而不是被 close 的；先走一次原生关闭，
  // 让 UA 的焦点归位与 inert 解除发生。
  if (typeof dialog.value?.close === "function") dialog.value.close();
});

function onSelfClick(event: MouseEvent) {
  if (event.target === dialog.value) emit("dismiss");
}
</script>
<template>
  <dialog ref="dialog" class="fams-dialog fams-media-preview" @click="onSelfClick" @close="emit('dismiss')">
    <slot />
  </dialog>
</template>
