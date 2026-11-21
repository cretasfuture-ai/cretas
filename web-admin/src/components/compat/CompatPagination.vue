<script setup lang="ts">
import { computed } from 'vue';

const page = defineModel<number>('currentPage', { default: 1 });
const pageSize = defineModel<number>('pageSize', { default: 10 });
const props = withDefaults(defineProps<{
  total?: number; pageCount?: number; pageSizes?: number[]; layout?: string;
  pagerCount?: number; background?: boolean; small?: boolean; disabled?: boolean; hideOnSinglePage?: boolean;
}>(), { pageSizes: () => [10, 20, 30, 40, 50, 100], layout: 'prev, pager, next, jumper', pagerCount: 7 });
const emit = defineEmits<{ 'current-change': [page: number]; 'size-change': [size: number]; change: [page: number, size: number];
  'prev-click': [page: number]; 'next-click': [page: number] }>();
const total = computed(() => props.pageCount == null ? props.total ?? 0 : props.pageCount * pageSize.value);
const last = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
const pieces = computed(() => props.layout.split(',').map(part => part.trim()));
const paging = new Set(['prev', 'pager', 'next']);
const main = computed(() => pieces.value.find(part => paging.has(part)));
function changePage(input: number) {
  if (!Number.isFinite(input)) return;
  const next = Math.min(last.value, Math.max(1, input));
  if (page.value === next) return;
  page.value = next;
  emit('current-change', next);
  emit('change', next, pageSize.value);
}
function changeSize(next: number) {
  if (next === pageSize.value) return;
  pageSize.value = next;
  emit('size-change', next);
  if (page.value > last.value) changePage(last.value);
  else emit('change', page.value, next);
}
</script>

<template>
  <div v-if="!hideOnSinglePage || last > 1" class="flex flex-wrap items-center gap-3">
    <template v-for="part in pieces" :key="part">
      <span v-if="part === 'total'" class="text-sm text-muted">共 {{ total }} 条</span>
      <USelect v-else-if="part === 'sizes'" :model-value="pageSize" :items="pageSizes.map(size => ({ value: size, label: `${size} 条/页` }))"
        aria-label="每页条数" :disabled="disabled" @update:model-value="changeSize" />
      <UPagination v-else-if="part === main" :page="page" :items-per-page="pageSize" :total="total" :sibling-count="Math.max(0, (pagerCount - 3) / 2)"
        :show-edges="false" :size="small ? 'sm' : 'md'" :disabled="disabled"
        :variant="background ? 'soft' : 'ghost'" @update:page="changePage">
        <template v-if="!pieces.includes('prev')" #prev />
        <template v-else #prev="{ prevPage }"><UButton icon="i-lucide-chevron-left" aria-label="上一页" :disabled="disabled || page <= 1"
          variant="ghost" @click="() => { changePage(prevPage); emit('prev-click', prevPage) }" /></template>
        <template v-if="!pieces.includes('next')" #next />
        <template v-else #next="{ nextPage }"><UButton icon="i-lucide-chevron-right" aria-label="下一页" :disabled="disabled || page >= last"
          variant="ghost" @click="() => { changePage(nextPage); emit('next-click', nextPage) }" /></template>
        <template v-if="!pieces.includes('pager')" #item />
      </UPagination>
      <label v-else-if="part === 'jumper'" class="flex items-center gap-2 text-sm text-muted">
        前往 <UInput class="w-20" type="number" :min="1" :max="last" :model-value="page" :disabled="disabled"
          @change="(event: Event) => changePage(Number((event.target as HTMLInputElement).value))" /> 页
      </label>
      <slot v-else-if="part === 'slot'" />
    </template>
  </div>
</template>
