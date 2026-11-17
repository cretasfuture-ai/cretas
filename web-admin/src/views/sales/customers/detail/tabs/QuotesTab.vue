<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 9 报价单 by customer.
  Backend: GET /api/mobile/{factoryId}/quotes/by-customer?customerId= (Phase D3 added).
  RBAC: canViewPrice masks unitPrice; quotes have unit prices, not order totals.
-->
<template>
  <div class="quotes-tab">
    <div class="toolbar">
      <span class="title">报价单</span>
      <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="state === 'loading'" @click="fetchList">刷新</UButton>
    </div>

    <div v-if="state === 'loading'" class="skeleton"><USkeleton v-for="i in 5" :key="i" class="h-9 w-full" /></div>
    <UEmpty v-else-if="state === 'empty'" icon="i-lucide-file-text" description="该客户暂无报价单">
      <template #actions><UButton @click="goCreate">创建报价单</UButton></template>
    </UEmpty>
    <UAlert v-else-if="state === 'error'" color="error" variant="subtle" :title="errorMsg" :actions="[{ label: '重试', color: 'error', variant: 'outline', onClick: fetchList }]" />

    <template v-else>
      <UTable :data="quotes" :columns="columns" class="cs-table" data-testid="customer-quotes-table">
        <template #createdAt-cell="{ row }">{{ formatDate(row.original.createdAt) }}</template>
        <template #status-cell="{ row }"><UBadge :color="statusColor(row.original.status)" variant="soft">{{ row.original.status }}</UBadge></template>
        <template #unitPrice-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.unitPrice) }}<template v-if="row.original.unit">/{{ row.original.unit }}</template></span>
          <span v-else class="masked">****</span>
        </template>
      </UTable>

      <div class="pager">
        <span class="total">共 {{ totalElements }} 条</span>
        <UPagination :page="currentPage" :items-per-page="pageSize" :total="totalElements" show-edges :sibling-count="1" @update:page="onPageChange" />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

interface QuoteRow {
  id: string;
  quoteNo?: string;
  customerId: string;
  productTypeId?: string;
  status?: string;
  unitPrice?: number;
  unit?: string;
  validUntil?: string;
  createdAt?: string;
}

interface PageResp {
  content: QuoteRow[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

const props = defineProps<{ customerId: string }>();

const router = useRouter();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);
const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const quotes = ref<QuoteRow[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;
const R = { class: { th: 'text-right', td: 'text-right' } };
const columns: TableColumn<QuoteRow>[] = [
  { accessorKey: 'quoteNo', header: '报价单号' },
  { id: 'createdAt', header: '创建日期' },
  { id: 'status', header: '状态' },
  { accessorKey: 'productTypeId', header: '产品 ID' },
  { id: 'unitPrice', header: '报价', meta: R },
  { accessorKey: 'validUntil', header: '有效期' },
];

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2 });
}

function formatDate(s?: string): string {
  if (!s) return '—';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

function statusColor(status?: string): 'success' | 'warning' | 'neutral' | 'error' {
  if (!status) return 'neutral';
  if (status === 'APPROVED') return 'success';
  if (status === 'PENDING') return 'warning';
  if (status === 'REJECTED' || status === 'CANCELLED') return 'error';
  return 'neutral';
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/quotes/by-customer`, {
      params: { customerId: props.customerId, page: currentPage.value, size: pageSize },
      _silent: true,
    });
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    quotes.value = res.data.content;
    totalElements.value = res.data.totalElements;
    state.value = quotes.value.length === 0 ? 'empty' : 'ready';
  } catch (e: unknown) {
    handleError(e);
  }
}

function handleError(e: unknown) {
  const error = e as { status?: number; code?: string | number; message?: string; response?: { status?: number; data?: { message?: string } } };
  const status = error?.status || error?.response?.status || error?.code;
  const backendMsg = error?.response?.data?.message || error?.message;
  if (status === 403) {
    state.value = 'error';
    errorMsg.value = '当前数据范围或权限不支持此客户快捷页，请使用已授权的主列表';
    return;
  } else {
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
  }
  console.error('[CustomerDetail/QuotesTab] fetch failed:', e);
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

function goCreate() {
  router.push({ path: '/sales/quotes', query: { create: '1', customerId: props.customerId } });
}

onMounted(fetchList);
</script>

<style scoped>
.quotes-tab { padding: 12px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
.pager { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 16px; }
.total { color: var(--cs-muted); font-size: var(--cs-font-small); }
.masked { color: var(--cs-muted); font-family: monospace; letter-spacing: 2px; user-select: none; }
.num { font-variant-numeric: tabular-nums; }
.skeleton { display: grid; gap: 8px; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
