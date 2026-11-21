<script setup lang="ts">
defineProps<{ title?: string }>();
</script>

<template>
  <UCard variant="ghost" :ui="{ body: 'p-0 sm:p-0', header: 'px-0 sm:px-0 py-2', footer: 'px-0 sm:px-0 py-2' }">
    <template v-if="title || $slots.title || $slots.extra" #header>
      <div class="flex items-center justify-between gap-2">
        <slot name="title"><h3 v-if="title" class="font-semibold">{{ title }}</h3></slot>
        <slot name="extra" />
      </div>
    </template>
    <slot />
  </UCard>
</template>
