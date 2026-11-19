<script setup lang="ts">
/** 默认日期窗口回退说明条。判定与文案见 utils/dateWindowFallback.ts。 */
defineProps<{
  /** 说明文字；空串不渲染。 */
  notice: string;
  /** fellBack=true 才显示「切回原窗口」。 */
  fellBack: boolean;
}>();
const emit = defineEmits<{ (e: 'restore'): void }>();
</script>

<template>
  <div v-if="notice" class="window-fallback-notice" :data-kind="fellBack ? 'fallback' : 'none'" data-testid="window-fallback-notice">
    <span class="notice-text">{{ notice }}</span>
    <button v-if="fellBack" type="button" class="notice-action" data-testid="window-fallback-restore" @click="emit('restore')">切回原窗口</button>
  </div>
</template>

<style scoped>
.window-fallback-notice { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; padding: 8px 14px; border-radius: 4px; font-size: 13px; background: var(--el-color-info-light-9, #f4f4f5); color: var(--el-text-color-regular, #606266); }
.window-fallback-notice[data-kind='fallback'] { background: var(--el-color-warning-light-9, #fdf6ec); color: var(--el-color-warning, #e6a23c); }
.notice-action { padding: 2px 12px; border: 1px solid currentColor; border-radius: 4px; background: transparent; color: inherit; cursor: pointer; font-size: 13px; }
</style>
