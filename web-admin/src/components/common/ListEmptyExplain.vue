<script setup lang="ts">
import { computed } from 'vue';
import { resolveListEmpty } from '@/utils/listEmptyState';

/** 说明式空状态：放进 el-table / UTable 的 #empty 插槽。判定见 utils/listEmptyState.ts。 */
const props = defineProps<{
  loadError?: string | null;
  filtered?: boolean;
  emptyTitle: string;
  emptyHint?: string;
}>();
const emit = defineEmits<{ (e: 'retry'): void; (e: 'clear-filter'): void }>();

const view = computed(() => resolveListEmpty(props));
</script>

<template>
  <div class="list-empty-explain" :data-kind="view.kind" data-testid="list-empty-explain">
    <div class="list-empty-title">{{ view.title }}</div>
    <div v-if="view.hint" class="list-empty-hint">{{ view.hint }}</div>
    <button v-if="view.kind === 'error'" type="button" class="list-empty-action" data-testid="list-empty-retry" @click="emit('retry')">重试</button>
    <button v-else-if="view.kind === 'filtered'" type="button" class="list-empty-action" data-testid="list-empty-clear" @click="emit('clear-filter')">清空筛选</button>
  </div>
</template>

<style scoped>
.list-empty-explain { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 24px 12px; text-align: center; line-height: 1.6; }
.list-empty-title { font-size: 14px; color: var(--el-text-color-primary, #303133); }
.list-empty-hint { font-size: 13px; color: var(--el-text-color-secondary, #909399); max-width: 520px; }
.list-empty-explain[data-kind='error'] .list-empty-title { color: var(--el-color-danger, #f56c6c); }
.list-empty-action { margin-top: 4px; padding: 4px 14px; border: 1px solid var(--el-border-color, #dcdfe6); border-radius: 4px; background: transparent; cursor: pointer; font-size: 13px; }
</style>
