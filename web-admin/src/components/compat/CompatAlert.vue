<script setup lang="ts">
import { ref } from 'vue';

defineProps<{
  title?: string; description?: string; color?: 'primary' | 'success' | 'warning' | 'error' | 'info';
  variant?: 'subtle' | 'solid'; close?: boolean;
}>();
const emit = defineEmits<{ close: [event: MouseEvent] }>();
const visible = ref(true);
function closeAlert(event: MouseEvent) {
  visible.value = false;
  emit('close', event);
}
</script>

<template>
  <UAlert v-if="visible" v-bind="$props" :close="close ?? true" @close="closeAlert" />
</template>
