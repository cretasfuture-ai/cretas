<template>
  <el-select v-if="options.length > 1" :model-value="modelValue" placeholder="请选择包装规格" aria-label="包装规格"
    clearable @update:model-value="selectPackaging">
    <el-option v-for="spec in options" :key="String(spec.id)" :value="spec.id"
      :label="`${spec.name || '包装规格'} · 1${spec.packageUnit}=${spec.conversionFactor}${spec.baseUnit}`" />
  </el-select>
  <span v-else-if="options.length === 1">1{{ options[0].packageUnit }}={{ options[0].conversionFactor }}{{ options[0].baseUnit }}</span>
  <span v-else>按基本单位</span>
</template>

<script setup lang="ts">
import type { SalesOrderPackagingSpec } from '../salesOrderNewLineContract';
defineProps<{ options: SalesOrderPackagingSpec[]; modelValue?: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: string | undefined] }>();
function selectPackaging(value: string | undefined): void {
  emit('update:modelValue', value || undefined);
}
</script>
