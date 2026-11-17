<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 21 文件附件 by customer.
  Backend: GET /api/mobile/{factoryId}/attachments?entityType=CUSTOMER&entityId= (existing).
  支持下载, 上传暂留 Phase 后续接.
-->
<template>
  <div class="attachments-tab">
    <div class="toolbar">
      <span class="title">文件附件</span>
      <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="state === 'loading'" @click="fetchList">刷新</UButton>
    </div>

    <div v-if="state === 'loading'" class="skeleton"><USkeleton v-for="i in 5" :key="i" class="h-9 w-full" /></div>
    <UEmpty v-else-if="state === 'empty'" icon="i-lucide-paperclip" description="该客户暂无附件">
      <template #actions><p class="hint">附件上传请在客户编辑页或相关业务单据上传后自动归档.</p></template>
    </UEmpty>
    <UAlert v-else-if="state === 'error'" color="error" variant="subtle" :title="errorMsg" :actions="[{ label: '重试', color: 'error', variant: 'outline', onClick: fetchList }]" />

    <template v-else>
      <UTable :data="attachments" :columns="columns" class="cs-table" data-testid="customer-attachments-table">
        <template #fileSize-cell="{ row }">{{ formatSize(row.original.fileSize) }}</template>
        <template #createdAt-cell="{ row }">{{ formatDate(row.original.createdAt) }}</template>
        <template #actions-cell="{ row }">
          <UButton variant="link" size="xs" @click="download(row.original)">下载</UButton>
        </template>
      </UTable>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';

interface Attachment {
  id: string;
  fileName?: string;
  fileSize?: number;
  contentType?: string;
  createdAt?: string;
}

const props = defineProps<{ customerId: string }>();

const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const attachments = ref<Attachment[]>([]);
const columns: TableColumn<Attachment>[] = [
  { accessorKey: 'fileName', header: '文件名' },
  { id: 'fileSize', header: '大小' },
  { accessorKey: 'contentType', header: '类型' },
  { id: 'createdAt', header: '上传时间' },
  { id: 'actions', header: '操作' },
];

function formatSize(b?: number): string {
  if (b == null) return '—';
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(s?: string): string {
  if (!s) return '—';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<Attachment[] | { content: Attachment[] }>(
      `/${factoryId.value}/attachments`,
      { params: { entityType: 'CUSTOMER', entityId: props.customerId } },
    );
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    // backend returns List directly (see AttachmentController @GetMapping line 88)
    attachments.value = Array.isArray(res.data) ? res.data : (res.data as any).content || [];
    state.value = attachments.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const status = e?.response?.status || e?.code;
    const backendMsg = e?.response?.data?.message || e?.message;
    if (status === 403) {
      state.value = 'error';
      errorMsg.value = '无权查看此客户的附件';
    } else {
      state.value = 'error';
      errorMsg.value = backendMsg || '加载失败';
      ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    }
    console.error('[CustomerDetail/AttachmentsTab] fetch failed:', e);
  }
}

function download(row: Attachment) {
  if (!factoryId.value) return;
  const url = `/api/mobile/${factoryId.value}/attachments/${row.id}/download`;
  window.open(url, '_blank');
}

onMounted(fetchList);
</script>

<style scoped>
.attachments-tab { padding: 12px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
.masked { color: var(--cs-muted); font-family: monospace; letter-spacing: 2px; user-select: none; }
.num { font-variant-numeric: tabular-nums; }
.skeleton { display: grid; gap: 8px; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
.hint { color: var(--cs-muted); font-size: var(--cs-font-small); margin: 0; }
</style>
