<template>
  <div class="page-container">
    <div class="cs-page-head">
      <div>
        <h2>历史付款申请</h2>
        <p>仅用于查询迁移前的付款申请记录</p>
      </div>
    </div>

    <UAlert
      color="info"
      variant="subtle"
      icon="i-lucide-info"
      class="mb-4"
      title="采购付款申请旧入口已停用"
      description="现行流程为：仓库确认收货生成未清应付，财务在“应收应付”页面选择该未清项并执行付款核销。这里不会再创建、提交、审批或确认付款。"
    />

    <section class="cs-card">
      <div class="cs-card-head">
        <USelect v-model="statusSelect" :items="statusItems" placeholder="状态" class="w-40" @update:model-value="handleFilterChange" />
        <UInput
          v-model="filterKeyword"
          icon="i-lucide-search"
          placeholder="搜索供应商或申请单号"
          class="w-72"
          @keyup.enter="handleFilterChange"
        />
        <UButton icon="i-lucide-search" @click="handleFilterChange">查询</UButton>
        <UButton icon="i-lucide-rotate-ccw" color="neutral" variant="outline" @click="resetFilter">重置</UButton>
      </div>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table">
        <template #empty>暂无历史付款申请</template>
        <template #requestNumber-cell="{ row }">{{ row.original.requestNumber ?? '' }}</template>
        <template #purchaseOrderNumber-cell="{ row }">{{ row.original.purchaseOrderNumber ?? '' }}</template>
        <template #supplierName-cell="{ row }">{{ row.original.supplierName ?? '' }}</template>
        <template #amount-cell="{ row }"><span class="num">{{ row.original.amount == null ? '—' : formatAmount(row.original.amount) }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(statusTag(row.original.status))" variant="soft">{{ statusLabel(row.original.status) }}</UBadge>
        </template>
        <template #createdAt-cell="{ row }"><span class="muted">{{ formatDate(row.original.createdAt) }}</span></template>
        <template #remark-cell="{ row }"><span class="muted" :title="row.original.remark ?? ''">{{ row.original.remark ?? '' }}</span></template>
      </UTable>

      <div class="pagination-wrap">
        <span class="total">共 {{ total }} 条</span>
        <USelect v-model="pageSize" :items="sizeItems" class="w-28" @update:model-value="handleSizeChange" />
        <UPagination v-model:page="page" :items-per-page="pageSize" :total="total" show-edges :sibling-count="1" @update:page="fetchData" />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import { ElMessage } from 'element-plus';
import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

interface HistoricalPaymentRequest {
  id: string;
  requestNumber?: string;
  purchaseOrderNumber?: string;
  supplierName?: string;
  amount?: number | null;
  status?: string;
  createdAt?: string | null;
  remark?: string | null;
}

const authStore = useAuthStore();
const loading = ref(false);
const tableData = ref<HistoricalPaymentRequest[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const filterStatus = ref('');
// USelect 的选项值不能是空串: 页面内用 ALL 表示不筛
const statusItems = [
  { label: '全部', value: 'ALL' },
  { label: '待提交', value: 'PENDING' },
  { label: '财务审核中', value: 'FINANCE_REVIEW' },
  { label: '已批准', value: 'APPROVED' },
  { label: '已付款', value: 'PAID' },
  { label: '已拒绝', value: 'REJECTED' },
];
const statusSelect = computed({
  get: () => filterStatus.value || 'ALL',
  set: (v: string) => { filterStatus.value = v === 'ALL' ? '' : v; },
});
const sizeItems = [10, 20, 50].map((n) => ({ label: `${n} 条/页`, value: n }));
const columns: TableColumn<HistoricalPaymentRequest>[] = [
  { id: 'requestNumber', header: '申请单号' },
  { id: 'purchaseOrderNumber', header: '采购单号' },
  { id: 'supplierName', header: '供应商' },
  { id: 'amount', header: '申请金额', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'status', header: '状态' },
  { id: 'createdAt', header: '创建时间' },
  { id: 'remark', header: '备注' },
];
function handleSizeChange() { page.value = 1; void fetchData(); }
const filterKeyword = ref('');

function statusLabel(status?: string): string {
  const labels: Record<string, string> = {
    PENDING: '待提交', FINANCE_REVIEW: '财务审核中', APPROVED: '已批准',
    PAID: '已付款', REJECTED: '已拒绝',
  };
  return status ? labels[status] ?? status : '—';
}

function statusTag(status?: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'PAID' || status === 'APPROVED') return 'success';
  if (status === 'PENDING' || status === 'FINANCE_REVIEW') return 'warning';
  if (status === 'REJECTED') return 'danger';
  return 'info';
}

function formatAmount(value: number): string {
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatDate(value?: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '—';
}

async function fetchData() {
  if (!authStore.factoryId) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = { page: page.value - 1, size: pageSize.value };
    if (filterStatus.value) params.status = filterStatus.value;
    if (filterKeyword.value.trim()) params.keyword = filterKeyword.value.trim();
    const res = await get(`/${authStore.factoryId}/payment-requests`, { params });
    if (!res.success) {
      ElMessage.error(res.message || '历史付款申请加载失败');
      return;
    }
    const data = res.data as HistoricalPaymentRequest[]
      | { content?: HistoricalPaymentRequest[]; totalElements?: number }
      | null;
    tableData.value = Array.isArray(data) ? data : data?.content ?? [];
    total.value = Array.isArray(data) ? data.length : Number(data?.totalElements ?? tableData.value.length);
  } finally {
    loading.value = false;
  }
}

function handleFilterChange() {
  page.value = 1;
  void fetchData();
}

function resetFilter() {
  filterStatus.value = '';
  filterKeyword.value = '';
  handleFilterChange();
}

onMounted(fetchData);
</script>

<style scoped>
.page-container { padding: var(--cs-space-4); }
.muted { color: var(--cs-muted); }
.total { color: var(--cs-muted); font-size: var(--cs-font); }
.pagination-wrap { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
</style>
