<script setup lang="ts">
// 相对日期筛选 (原型 BL23): 今天 / 本周 / 本月 / 近 7 天 / 近 30 天。
// 只存 key, 具体 from/to 每次由 relativeRange 现算 (按北京时间日历日, 本周从周一算)。
import { RELATIVE_DATE_OPTIONS, relativeRange, type RelativeDateKey, type RelativeRange } from '@/utils/listFilters';

const props = defineProps<{ modelValue: RelativeDateKey | ''; placeholder?: string }>();
const emit = defineEmits<{
  'update:modelValue': [key: RelativeDateKey | ''];
  change: [range: RelativeRange | null];
}>();

function onChange(value: RelativeDateKey | '' | undefined): void {
  const key = value || '';
  emit('update:modelValue', key);
  emit('change', key ? relativeRange(key) : null);
}
</script>

<template>
  <el-select
    :model-value="props.modelValue"
    :placeholder="props.placeholder || '检验日期'"
    clearable
    style="width: 140px"
    data-testid="relative-date"
    @update:model-value="onChange"
  >
    <el-option v-for="o in RELATIVE_DATE_OPTIONS" :key="o.key" :label="o.label" :value="o.key" />
  </el-select>
</template>
