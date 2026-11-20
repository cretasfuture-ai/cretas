<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 15 开票 by customer.
  Backend: GET /api/mobile/{factoryId}/finance/invoices/by-customer?customerId= (Phase C C1).
  RBAC: canViewPrice mask on amount / taxAmount / totalAmount per spec §7.2.
-->
<template>
  <div class="invoices-tab">
    <div class="toolbar">
      <span class="title">开票记录</span>
      <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="state === 'loading'" @click="fetchList">刷新</UButton>
    </div>

    <div v-if="state === 'loading'" class="skeleton"><USkeleton v-for="i in 5" :key="i" class="h-9 w-full" /></div>
    <UEmpty v-else-if="state === 'empty'" icon="i-lucide-receipt" description="该客户暂无开票记录" />
    <UAlert v-else-if="state === 'error'" color="error" variant="subtle" :title="errorMsg" :actions="[{ label: '重试', color: 'error', variant: 'outline', onClick: fetchList }]" />

    <template v-else>
      <UTable :data="invoices" :columns="columns" class="cs-table" data-testid="customer-invoices-table">
        <template #status-cell="{ row }"><UBadge :color="statusColor(row.original.status)" variant="soft">{{ invoiceStatusText(row.original.status) }}</UBadge></template>
        <template #invoiceType-cell="{ row }">{{ invoiceTypeText(row.original.invoiceType) }}</template>
        <template #amount-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.amount) }}</span>
          <span v-else class="masked">****</span>
        </template>
        <template #taxAmount-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.taxAmount) }}</span>
          <span v-else class="masked">****</span>
        </template>
        <template #totalAmount-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.totalAmount) }}</span>
          <span v-else class="masked">****</span>
        </template>
        <template #issuedAt-cell="{ row }">{{ row.original.issuedAt ? new Date(row.original.issuedAt).toLocaleDateString('zh-CN') : '—' }}</template>
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
import { invoiceStatusText, invoiceTypeText, statusColor } from './customerTabLabels';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

interface InvoiceRow {
  id: string;
  invoiceNumber?: string;
  invoiceType?: string;
  status?: string;
  amount?: number;
  taxAmount?: number;
  totalAmount?: number;
  issuedAt?: string;
}

interface PageResp {
  content: InvoiceRow[];
  totalElements: number;
  totalPages: number;
}

const props = defineProps<{ customerId: string }>();

const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);
const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const invoices = ref<InvoiceRow[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;
const R = { class: { th: 'text-right', td: 'text-right' } };
const columns: TableColumn<InvoiceRow>[] = [
  { accessorKey: 'invoiceNumber', header: '发票号' },
  { id: 'invoiceType', header: '类型' },
  { id: 'status', header: '状态' },
  { id: 'amount', header: '不含税', meta: R },
  { id: 'taxAmount', header: '税额', meta: R },
  { id: 'totalAmount', header: '价税合计', meta: R },
  { id: 'issuedAt', header: '开票日期' },
];

function formatMoney(v?: number | null): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2 });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/finance/invoices/by-customer`, {
      params: { customerId: props.customerId, page: currentPage.value, size: pageSize },
      _silent: true,
    });
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    invoices.value = res.data.content;
    totalElements.value = res.data.totalElements;
    state.value = invoices.value.length === 0 ? 'empty' : 'ready';
  } catch (e: unknown) {
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
    console.error('[CustomerDetail/InvoicesTab] fetch failed:', e);
  }
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

onMounted(fetchList);
</script>

<style scoped>
.invoices-tab { padding: 12px 0; }
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
