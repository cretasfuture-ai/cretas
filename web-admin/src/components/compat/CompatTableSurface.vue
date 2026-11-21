<script setup lang="ts" generic="T extends object">
import type { TableColumn, TableProps, TableRow } from '@nuxt/ui/components/Table.vue';

defineProps<{
  data: T[]; columns: TableColumn<T>[]; getRowId: (row: T, index: number) => string;
  empty?: string; sticky?: boolean; ui?: TableProps<T>['ui']; meta?: TableProps<T>['meta'];
  columnPinning: { left: string[]; right: string[] };
}>();
const emit = defineEmits<{ select: [event: Event, row: TableRow<T>] }>();
</script>

<template>
  <UTable v-bind="$props" :column-pinning="columnPinning" @select="(event: Event, row: TableRow<T>) => emit('select', event, row)">
    <template v-if="$slots.empty" #empty><slot name="empty" /></template>
  </UTable>
</template>
