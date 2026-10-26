<!-- web-admin/src/components/common/AiUploadHint.vue -->
<script setup lang="ts">
/**
 * AiUploadHint — 「图片会发给 AI 识别」一句话提示。
 * 放在每个上传后会把图片 / PDF 交给 AI 识别的入口旁边 (图片识别不做名称脱敏, 见官网数据安全说明)。
 */
withDefaults(defineProps<{ kind?: 'image' | 'pdf' }>(), { kind: 'image' });
</script>

<template>
  <span class="ai-upload-hint" data-testid="ai-upload-hint">
    <span aria-hidden="true">ⓘ</span>
    {{ kind === 'pdf' ? 'PDF 文件会发给 AI 识别' : '图片会发给 AI 识别' }}
  </span>
</template>

<style scoped>
.ai-upload-hint {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 13px; line-height: 1.6;
  /* 对白底 6:1 (#909399 只有 3:1, 不够) */
  color: var(--el-text-color-regular, #606266);
}
</style>
