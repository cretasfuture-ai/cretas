<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  value?: number | string; max?: number; isDot?: boolean; hidden?: boolean; showZero?: boolean;
  type?: 'primary' | 'success' | 'warning' | 'danger' | 'info';
}>(), { max: 99, showZero: true });
const color = computed(() => props.type === 'danger' || !props.type ? 'error' : props.type);
const text = computed(() => {
  if (props.isDot) return undefined;
  return typeof props.value === 'number' && props.value > props.max ? `${props.max}+` : props.value;
});
</script>

<template>
  <UChip :show="!hidden && (isDot || (value !== undefined && (showZero || value !== 0)))" :text="text" :color="color">
    <slot />
  </UChip>
</template>
