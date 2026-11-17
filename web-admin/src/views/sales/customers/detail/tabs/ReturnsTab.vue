<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 17 退货 by customer.
  Backend: GET /api/mobile/{factoryId}/return-orders/by-customer?customerId= (Phase C C1).

  Spec drift #7: ReturnOrder 没有直接 customerId 字段, backend 用 counterpartyId +
  returnType=SALES_RETURN 组合过滤. 前端调用 endpoint 时传 customerId, backend 内部映射.

  RBAC: canViewPrice mask on totalAmount.
-->
<template>
  <div class="returns-tab">
    <div class="toolbar">
      <span class="title">销售退货</span>
      <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="state === 'loading'" @click="fetchList">刷新</UButton>
    </div>

    <div v-if="state === 'loading'" class="skeleton"><USkeleton v-for="i in 5" :key="i" class="h-9 w-full" /></div>
    <UEmpty v-else-if="state === 'empty'" icon="i-lucide-undo-2" description="该客户暂无销售退货记录" />
    <UAlert v-else-if="state === 'error'" color="error" variant="subtle" :title="errorMsg" :actions="[{ label: '重试', color: 'error', variant: 'outline', onClick: fetchList }]" />

    <template v-else>
      <UTable :data="returns" :columns="columns" class="cs-table" data-testid="customer-returns-table">
        <template #status-cell="{ row }"><UBadge :color="statusColor(row.original.status)" variant="soft">{{ returnStatusText(row.original.status) }}</UBadge></template>
        <template #remark-cell="{ row }">{{ cleanRemark(row.original.remark) }}</template>
        <template #totalAmount-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.totalAmount) }}</span>
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
import { cleanRemark, returnStatusText, statusColor } from './customerTabLabels';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

interface ReturnRow {
  id: string;
  returnNumber?: string;
  returnDate?: string;
  status?: string;
  totalAmount?: number;
  reason?: string;
  remark?: string;
}

interface PageResp {
  content: ReturnRow[];
  totalElements: number;
}

const props = defineProps<{ customerId: string }>();

const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);
const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const returns = ref<ReturnRow[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;
const R = { class: { th: 'text-right', td: 'text-right' } };
const columns: TableColumn<ReturnRow>[] = [
  { accessorKey: 'returnNumber', header: '退货单号' },
  { accessorKey: 'returnDate', header: '退货日期' },
  { id: 'status', header: '状态' },
  { id: 'totalAmount', header: '金额', meta: R },
  { accessorKey: 'reason', header: '原因' },
  { id: 'remark', header: '备注' },
];

function formatMoney(v?: number | null): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2 });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/return-orders/by-customer`, {
      params: { customerId: props.customerId, page: currentPage.value, size: pageSize },
    });
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    returns.value = res.data.content;
    totalElements.value = res.data.totalElements;
    state.value = returns.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const status = e?.response?.status || e?.code;
    const backendMsg = e?.response?.data?.message || e?.message;
    if (status === 403) {
      state.value = 'error';
      errorMsg.value = '无权查看此客户的退货记录';
    } else {
      state.value = 'error';
      errorMsg.value = backendMsg || '加载失败';
      ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    }
    console.error('[CustomerDetail/ReturnsTab] fetch failed:', e);
  }
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

onMounted(fetchList);
</script>

<style scoped>
.returns-tab { padding: 12px 0; }
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
