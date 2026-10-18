<script setup lang="ts">
import { ref } from 'vue';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';

const props = defineProps<{ factoryId: string; invoiceId: string; fileName?: string }>();
const loading = ref(false);
async function download() {
  if (loading.value) return;
  const target = window.open('about:blank', '_blank');
  if (!target) { ElMessage.error('请允许打开新窗口后重试下载'); return; }
  target.opener = null;
  loading.value = true;
  const factory = props.factoryId;
  const invoice = props.invoiceId;
  try {
    const result = await get<{ url: string }>(
      `/${encodeURIComponent(factory)}/finance/invoices/${encodeURIComponent(invoice)}/download-url`,
    );
    if (!result.success || !result.data?.url || factory !== props.factoryId || invoice !== props.invoiceId) {
      throw new Error('Invoice download unavailable');
    }
    target.location.href = result.data.url;
  } catch {
    target.close();
    ElMessage.error('发票下载失败，请重试；仍失败时请联系管理员核对权限和附件');
  } finally { loading.value = false; }
}
</script>

<template>
  <el-button type="primary" link :loading="loading" @click="download">
    下载{{ fileName ? ` (${fileName})` : '发票PDF' }}
  </el-button>
</template>
