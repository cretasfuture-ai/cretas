<script setup lang="ts">
// 相对日期筛选: 对齐原型 cs-rel-date (圆角胶囊按钮组, aria-pressed 表示选中)。
// Nuxt UI UButton (soft = 选中 / outline neutral = 未选) + --cs-*。
// 只存 key, 具体 from/to 每次由 relativeRange 现算 (按北京时间日历日, 本周从周一算)。
import { RELATIVE_DATE_OPTIONS, relativeRange, type RelativeDateKey, type RelativeRange } from '@/utils/listFilters';

const props = defineProps<{ modelValue: RelativeDateKey | '' }>();
const emit = defineEmits<{
  'update:modelValue': [key: RelativeDateKey | ''];
  change: [range: RelativeRange | null];
}>();

function pick(key: RelativeDateKey | ''): void {
  // 再点已选中的 = 取消, 回到「全部」
  const next = key && key === props.modelValue ? '' : key;
  emit('update:modelValue', next);
  emit('change', next ? relativeRange(next) : null);
}
</script>

<template>
  <span class="cs-rel-date" role="group" aria-label="检验日期" data-testid="relative-date">
    <UButton
      size="xs"
      class="rounded-full"
      :color="props.modelValue === '' ? 'primary' : 'neutral'"
      :variant="props.modelValue === '' ? 'soft' : 'outline'"
      :aria-pressed="props.modelValue === ''"
      data-testid="rel-date-all"
      @click="pick('')"
    >全部</UButton>
    <UButton
      v-for="o in RELATIVE_DATE_OPTIONS"
      :key="o.key"
      size="xs"
      class="rounded-full"
      :color="props.modelValue === o.key ? 'primary' : 'neutral'"
      :variant="props.modelValue === o.key ? 'soft' : 'outline'"
      :aria-pressed="props.modelValue === o.key"
      :data-testid="`rel-date-${o.key}`"
      @click="pick(o.key)"
    >{{ o.label }}</UButton>
  </span>
</template>

<style scoped>
.cs-rel-date { display: inline-flex; flex-wrap: wrap; gap: 4px; }
.cs-rel-date :deep(button[aria-pressed='true']) { font-weight: 600; }
</style>
