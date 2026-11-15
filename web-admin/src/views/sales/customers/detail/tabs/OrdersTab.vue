<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 7 销售单 by customer.
  Backend: GET /api/mobile/{factoryId}/sales/orders/by-customer?customerId= (Phase C C1).
  RBAC: canViewPrice mask on totalAmount / paidAmount columns per spec §7.2.
  组件: Nuxt UI 迁移第5批 (UTable / UBadge / UButton / UEmpty / UAlert / USkeleton / UPagination) + --cs-*。
  金额列无 canViewPrice 时显示 **** (与迁移前同一判断)。
-->
<template>
  <div class="orders-tab">
    <div class="toolbar">
      <span class="title">销售单</span>
      <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="state === 'loading'" @click="fetchList">刷新</UButton>
    </div>

    <div v-if="state === 'loading'" class="skeleton" data-testid="customer-orders-loading">
      <USkeleton v-for="i in 5" :key="i" class="h-9 w-full" />
    </div>

    <UEmpty v-else-if="state === 'empty'" icon="i-lucide-file-text" description="该客户暂无销售单" class="empty">
      <template #actions>
        <UButton @click="goCreate">创建销售单</UButton>
      </template>
    </UEmpty>

    <div v-else-if="state === 'error'" class="error-panel">
      <UAlert color="error" variant="subtle" :title="errorMsg" :actions="[{ label: '重试', color: 'error', variant: 'outline', onClick: fetchList }]" />
    </div>

    <template v-else>
      <UTable :data="orders" :columns="columns" class="cs-table" data-testid="customer-orders-table">
        <template #status-cell="{ row }">
          <UBadge color="neutral" variant="soft">{{ row.original.status }}</UBadge>
        </template>
        <template #totalAmount-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.totalAmount) }}</span>
          <span v-else class="masked">****</span>
        </template>
        <template #paidAmount-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.paidAmount) }}</span>
          <span v-else class="masked">****</span>
        </template>
        <template #actions-cell="{ row }">
          <UButton variant="link" size="xs" @click="goDetail(row.original.id)">查看</UButton>
        </template>
      </UTable>

      <div class="pagination">
        <span class="total">共 {{ totalElements }} 条</span>
        <UPagination :page="currentPage" :items-per-page="pageSize" :total="totalElements" show-edges :sibling-count="1" @update:page="onPageChange" />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

interface SalesOrderRow {
  id: string;
  orderNumber: string;
  orderDate: string;
  status: string;
  salesperson?: string;
  totalAmount?: number;
  paidAmount?: number;
  customerId: string;
}

interface PageResp {
  content: SalesOrderRow[];
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
const orders = ref<SalesOrderRow[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;
const R = { class: { th: 'text-right', td: 'text-right' } };
const columns = computed<TableColumn<SalesOrderRow>[]>(() => [
  { accessorKey: 'orderNumber', header: '订单号' },
  { accessorKey: 'orderDate', header: '下单日期' },
  { id: 'status', header: '状态' },
  { accessorKey: 'salesperson', header: '业务员' },
  { id: 'totalAmount', header: '总额', meta: R },
  { id: 'paidAmount', header: '已收款', meta: R },
  { id: 'actions', header: '操作' },
]);

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
  });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/sales/orders/by-customer`, {
      params: { customerId: props.customerId, page: currentPage.value, size: pageSize },
    });
    if (!res.success || !res.data) {
      throw new Error(res.message || '加载失败');
    }
    orders.value = res.data.content;
    totalElements.value = res.data.totalElements;
    state.value = orders.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    handleError(e);
  }
}

function handleError(e: any) {
  const status = e?.response?.status || e?.code;
  const backendMsg = e?.response?.data?.message || e?.message;
  if (status === 403) {
    state.value = 'error';
    errorMsg.value = '无权查看此客户的销售单';
  } else {
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
  }
  console.error('[CustomerDetail/OrdersTab] fetch failed:', e);
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

function goDetail(id: string) {
  router.push(`/sales/orders/${id}`);
}

function goCreate() {
  router.push({ path: '/sales/orders', query: { create: '1', customerId: props.customerId } });
}

onMounted(fetchList);
</script>

<style scoped>
.orders-tab { padding: 8px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
.skeleton { display: grid; gap: 8px; }
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); }
.total { color: var(--cs-muted); font-size: var(--cs-font); }
.masked { color: var(--cs-muted); font-family: monospace; letter-spacing: 2px; user-select: none; }
.error-panel { padding: 24px 0; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
