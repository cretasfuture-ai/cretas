<script setup lang="ts">
import { defineComponent, onBeforeUnmount, ref } from 'vue';
import CompatDialog from './CompatDialog.vue';
import type { BoxResult, BoxOptions, BoxMode, Text } from './messageContracts';

const props = defineProps<{ mode: BoxMode; title: string; message: Text; options: BoxOptions }>();
const emit = defineEmits<{ close: [result: BoxResult] }>();
const open = ref(true);
const value = ref(props.options.inputValue ?? '');
const error = ref('');
let completed = false;
const Message = defineComponent({ setup: () => () =>
  typeof props.message === 'function' ? props.message() : props.message });
function answer(action: BoxResult['action']) {
  if (completed) return;
  if (action === 'confirm' && props.mode === 'prompt') {
    const validator = props.options.inputValidator?.(value.value);
    const pattern = props.options.inputPattern;
    if (pattern) pattern.lastIndex = 0;
    if (validator === false || typeof validator === 'string' || (pattern && !pattern.test(value.value))) {
      error.value = typeof validator === 'string' ? validator : props.options.inputErrorMessage ?? '输入不符合要求';
      return;
    }
  }
  completed = true;
  emit('close', { action, value: value.value });
}
onBeforeUnmount(() => { if (!completed) answer('close'); });
</script>

<template>
  <CompatDialog v-model="open" :title="title" :custom-style="options.customStyle"
    :close-on-click-modal="options.closeOnClickModal ?? mode !== 'alert'"
    :close-on-press-escape="options.closeOnPressEscape ?? mode !== 'alert'" :show-close="options.showClose ?? true"
    :before-close="() => answer('close')" destroy-on-close>
    <p v-if="options.dangerouslyUseHTMLString && typeof message === 'string'" class="whitespace-pre-line" v-html="message" />
    <p v-else class="whitespace-pre-line"><Message /></p>
    <UFormField v-if="mode === 'prompt'" :error="error" class="mt-3">
      <UInput v-model="value" :type="options.inputType ?? 'text'" :placeholder="options.inputPlaceholder"
        aria-label="输入内容" autofocus @keydown.enter.prevent="answer('confirm')" />
    </UFormField>
    <template #footer>
      <UButton v-if="options.showCancelButton ?? mode !== 'alert'" color="neutral" variant="outline"
        :class="options.cancelButtonClass" @click="answer('cancel')">{{ options.cancelButtonText ?? '取消' }}</UButton>
      <UButton v-if="options.showConfirmButton ?? true" :color="options.type || 'primary'" :class="options.confirmButtonClass"
        @click="answer('confirm')">{{ options.confirmButtonText ?? '确定' }}</UButton>
    </template>
  </CompatDialog>
</template>
