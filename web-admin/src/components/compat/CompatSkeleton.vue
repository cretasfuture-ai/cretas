<script setup lang="ts">
withDefaults(defineProps<{ loading?: boolean; rows?: number; animated?: boolean; count?: number }>(),
  { loading: true, rows: 3, count: 1 });
</script>

<template>
  <div v-if="loading" aria-busy="true" class="space-y-3">
    <div v-for="group in count" :key="group" class="space-y-2">
      <slot name="template">
        <USkeleton class="h-4 w-1/3 rounded" :class="!animated && 'animate-none'" />
        <USkeleton v-for="row in rows" :key="row" class="h-3 rounded"
          :class="[row === rows ? 'w-3/5' : 'w-full', !animated && 'animate-none']" />
      </slot>
    </div>
  </div>
  <slot v-else />
</template>
