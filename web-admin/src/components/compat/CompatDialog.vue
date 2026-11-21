<script setup lang="ts">
import { nextTick, watch } from 'vue';
import { length } from './vnode';

const open = defineModel<boolean>({ default: false });
const props = withDefaults(defineProps<{
  title?: string; width?: string | number; beforeClose?: (done: () => void) => void;
  closeOnClickModal?: boolean; closeOnPressEscape?: boolean; showClose?: boolean;
  destroyOnClose?: boolean; appendToBody?: boolean; alignCenter?: boolean; center?: boolean; customStyle?: import('vue').CSSProperties;
}>(), { closeOnClickModal: true, closeOnPressEscape: true, showClose: true, appendToBody: true });
const emit = defineEmits<{ open: []; opened: []; close: []; closed: [] }>();
watch(open, (value, previous) => {
  if (value) emit('open');
  else if (previous) emit('close');
}, { immediate: true });

function requestClose() {
  const done = () => { open.value = false; };
  if (props.beforeClose) props.beforeClose(done);
  else done();
}
function outside(event: Event) {
  event.preventDefault();
  if (props.closeOnClickModal) requestClose();
}
function escape(event: Event) {
  event.preventDefault();
  if (props.closeOnPressEscape) requestClose();
}
async function afterEnter() {
  await nextTick();
  if (open.value) emit('opened');
}
</script>

<template>
  <UModal :open="open" :title="title || '对话框'" :portal="appendToBody" :unmount-on-hide="destroyOnClose" :close="false"
    :content="{ onEscapeKeyDown: escape, onPointerDownOutside: outside, style: { width: length(width), maxWidth: width ? '95vw' : undefined, ...customStyle } }"
    @update:open="(value: boolean) => { if (!value && open) requestClose() }" @after:enter="afterEnter" @after:leave="emit('closed')">
    <template #content>
      <section class="flex max-h-[85vh] flex-col">
        <header v-if="title || $slots.header || showClose" class="flex items-center justify-between gap-3 border-b border-default px-4 py-3">
          <slot name="header" :close="requestClose" title-id="" title-class="font-semibold">
            <h2 v-if="title" class="text-base font-semibold" :class="center && 'text-center flex-1'">{{ title }}</h2>
          </slot>
          <UButton v-if="showClose" aria-label="关闭" color="neutral" variant="ghost" icon="i-lucide-x" @click="requestClose" />
        </header>
        <div class="overflow-auto p-4"><slot /></div>
        <footer v-if="$slots.footer" class="flex gap-2 border-t border-default px-4 py-3" :class="center ? 'justify-center' : 'justify-end'">
          <slot name="footer" />
        </footer>
      </section>
    </template>
  </UModal>
</template>
