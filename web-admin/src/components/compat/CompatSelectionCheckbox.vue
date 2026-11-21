<script setup lang="ts">
const value = defineModel<boolean | 'indeterminate'>();
</script>

<template>
  <UCheckbox v-model="value" />
</template>
