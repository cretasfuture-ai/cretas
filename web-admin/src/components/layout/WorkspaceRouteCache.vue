<script setup lang="ts">
import type { VNode } from 'vue';

defineProps<{
  component: VNode | undefined;
  activeKey: string;
  cacheKeys: readonly string[];
}>();
</script>

<template>
  <!-- One cache per live tab: closing or invalidating its key unmounts the
       entire cache, while opening another tab cannot evict an unsaved draft. -->
  <keep-alive v-for="key in cacheKeys" :key="key" :max="1">
    <component v-if="key === activeKey" :is="component" :key="key" />
  </keep-alive>
</template>
