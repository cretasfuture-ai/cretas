<script setup lang="ts" generic="T extends object">
import { ref } from 'vue';
import { TableAdapter } from './table';

const props = defineProps<{
  data?: T[]; rowKey?: string | ((row: T) => string | number); border?: boolean; stripe?: boolean;
  size?: string; height?: string | number; maxHeight?: string | number; emptyText?: string; highlightCurrentRow?: boolean;
}>();
const emit = defineEmits<{
  'selection-change': [rows: T[]]; 'row-click': [row: T, column: undefined, event: Event];
  'current-change': [current: T | undefined, previous: T | undefined];
}>();
const table = ref<{
  clearSelection: () => void; getSelectionRows: () => T[];
  toggleRowSelection: (row: T, selected?: boolean) => void; setCurrentRow: (row?: T) => void;
}>();
const adapterProps = () => ({ ...props });
defineExpose({
  clearSelection: () => table.value?.clearSelection(),
  getSelectionRows: () => table.value?.getSelectionRows() ?? [],
  toggleRowSelection: (row: T, selected?: boolean) => table.value?.toggleRowSelection(row, selected),
  setCurrentRow: (row?: T) => table.value?.setCurrentRow(row),
});
</script>

<template>
  <TableAdapter ref="table" v-bind="adapterProps()"
    @selection-change="rows => emit('selection-change', rows as T[])"
    @row-click="(row, column, event) => emit('row-click', row as T, column, event)"
    @current-change="(current, previous) => emit('current-change', current as T | undefined, previous as T | undefined)">
    <slot />
    <template v-if="$slots.empty" #empty><slot name="empty" /></template>
  </TableAdapter>
</template>
