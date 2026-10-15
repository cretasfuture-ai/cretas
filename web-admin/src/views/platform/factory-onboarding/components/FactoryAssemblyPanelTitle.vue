<template>
  <span class="panel-title">
    <b>{{ index }}</b>
    <span>{{ title }}</span>
    <em v-if="label">{{ label }}</em>
    <em v-else>{{ count ?? 0 }} 项</em>
  </span>
</template>

<script setup lang="ts">
defineProps<{ index: string; title: string; count?: number; label?: string }>()
</script>

<style scoped>
.panel-title { display: flex; align-items: center; gap: 10px; width: 100%; color: #1a2332; }
.panel-title b { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 8px; background: #e8f2fb; color: #1b65a8; font-size: 12px; }
.panel-title > span { font-weight: 650; }
.panel-title em { margin-left: auto; padding-right: 12px; color: #7a8599; font-size: 12px; font-style: normal; font-weight: 400; }
</style>
