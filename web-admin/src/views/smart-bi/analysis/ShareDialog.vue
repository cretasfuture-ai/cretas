<template>
  <DesktopModal
    v-model="visible"
    action="分享分析报告"
    :context-label="reportContextLabel"
    width="500px"
  >
    <!-- 后端没有 /smart-bi/share (2026-11-18 核实): 不再调用, 也不造假链接。
         shareLink.ts 的 /share-links 是采购/销售单转发, 不是分析报告分享。 -->
    <div style="text-align: center; padding: 20px;">
      <el-result icon="info" title="暂不支持分享" sub-title="分析报告的公开分享链接功能尚未开通，请先导出报告后发送给对方" />
    </div>
  </DesktopModal>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { DesktopModal } from '@/components/dialog';

interface BatchInfo {
  uploadId?: number | string;
  id?: number | string;
  fileName?: string;
  batchName?: string;
}

// 父组件仍传 factoryId / activeTab, 保留 props 声明以免改动调用方。
defineProps<{
  factoryId: string;
  /** Sheet index as string (parent uses ref<string> for el-tabs binding). */
  activeTab: string;
}>();

const visible = ref(false);
const currentBatchRef = ref<BatchInfo | null>(null);

// 防呆 R2: title shows entity identity (file name + batch id).
const reportContextLabel = computed(() => {
  const b = currentBatchRef.value;
  if (!b) return '';
  const name = b.fileName || b.batchName || '';
  const id = b.uploadId || b.id || '';
  return name && id ? `${name} (#${id})` : name || (id ? `#${id}` : '');
});

const open = (batch: BatchInfo | null | undefined) => {
  currentBatchRef.value = batch ?? null;
  visible.value = true;
};

defineExpose({ open });
</script>
