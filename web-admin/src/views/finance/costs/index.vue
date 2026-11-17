<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { TableRow } from '@/types/api';
import type { CsTableColumn } from '@/types/csTable';
import type { CsBadgeColor } from '@/utils/csBadge';
import { enumLabel, FINANCE_TRANSACTION_TYPE_LABELS } from '@/utils/enumDisplay';
import { cleanFinanceRemark } from '../financeDisplay';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const overview = ref<TableRow | null>(null);
const overviewFailed = ref(false);
const transactions = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const ALL = 'ALL';
const searchType = ref(ALL);
const TYPE_ITEMS = [
  { label: '全部类型', value: ALL },
  { label: '客户 (应收)', value: 'CUSTOMER' },
  { label: '供应商 (应付)', value: 'SUPPLIER' },
];

onMounted(() => {
  loadOverview();
  loadTransactions();
});

async function loadOverview() {
  if (!factoryId.value) return;

  loading.value = true;
  overviewFailed.value = false;
  try {
    const overviewRes = await get(`/${factoryId.value}/finance/overview`);
    if (overviewRes.success) {
      overview.value = overviewRes.data;
    } else {
      overviewFailed.value = true;
    }
  } catch (error) {
    console.error('加载概览失败:', error);
    overviewFailed.value = true;
    ElMessage.error('加载财务概览失败');
  } finally {
    loading.value = false;
  }
}

async function loadTransactions() {
  if (!factoryId.value) return;

  try {
    const params: TableRow = {
      page: pagination.value.page,
      size: pagination.value.size
    };
    if (searchType.value !== ALL) {
      params.counterpartyType = searchType.value;
    }

    const response = await get(`/${factoryId.value}/finance/transactions`, { params });
    if (response.success && response.data) {
      transactions.value = response.data.content || response.data.records || [];
      pagination.value.total = response.data.totalElements || response.data.total || 0;
    }
  } catch (error) {
    console.error('加载交易失败:', error);
  }
}

function handleRefresh() {
  searchType.value = ALL;
  pagination.value.page = 1;
  loadOverview();
  loadTransactions();
}

function handleSearch() {
  pagination.value.page = 1;
  loadTransactions();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadTransactions();
}

/** 金额: 没有值(null/undefined/非数字)显示「—」, 不当 0 显示。 */
function formatMoney(val: unknown) {
  if (val === null || val === undefined || val === '') return '—';
  const n = Number(val);
  return isNaN(n) ? '—' : '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDate(val: string | null) {
  if (!val) return '—';
  return val.substring(0, 10);
}

function typeText(type: unknown) {
  return enumLabel(type, FINANCE_TRANSACTION_TYPE_LABELS);
}

function typeColor(type: string): CsBadgeColor {
  const map: Record<string, CsBadgeColor> = {
    RECEIVABLE: 'warning',
    AR_INVOICE: 'warning',
    AR_PAYMENT: 'success',
    PAYABLE: 'error',
    AP_INVOICE: 'error',
    AP_PAYMENT: 'primary',
  };
  return map[type] || 'neutral';
}

const columns = computed<CsTableColumn<TableRow>[]>(() => {
  const cols: CsTableColumn<TableRow>[] = [
    { accessorKey: 'transactionNumber', header: '交易编号' },
    { id: 'transactionType', header: '类型' },
    { accessorKey: 'counterpartyName', header: '客户/供应商' },
  ];
  if (canViewPrice.value) cols.push({ id: 'amount', header: '金额', meta: { class: { th: 'text-right', td: 'text-right' } } });
  if (canViewPrice.value && overview.value && !overview.value.scopeApplied) {
    cols.push({ id: 'balanceAfter', header: '余额', meta: { class: { th: 'text-right', td: 'text-right' } } });
  }
  cols.push(
    { id: 'transactionDate', header: '交易日期' },
    { id: 'dueDate', header: '到期日' },
    { id: 'remark', header: '备注' },
  );
  return cols;
});
</script>

<template>
  <div class="page" data-testid="finance-costs-page">
    <UAlert v-if="canViewPrice && overview?.scopeApplied" color="info" variant="soft" icon="i-lucide-info"
      title="仅显示当前授权范围内的账目与金额，不代表全工厂余额。" />
    <UAlert v-if="overviewFailed" color="error" variant="soft" icon="i-lucide-circle-alert" data-testid="costs-overview-error"
      title="财务概览暂时没取到，下方金额显示为「—」，请点右上角「刷新」重试。" />

    <!-- 财务概览 KPI: 取不到时显示「—」, 不显示 0 -->
    <div class="kpi-row">
      <div v-if="canViewPrice" class="kpi-card receivable" data-testid="kpi-receivable">
        <div class="kpi-label">应收总额</div>
        <div class="kpi-value">{{ formatMoney(overview?.totalReceivable) }}</div>
        <div class="kpi-sub">逾期: {{ formatMoney(overview?.overdueReceivable) }}</div>
      </div>
      <div v-if="canViewPrice" class="kpi-card payable" data-testid="kpi-payable">
        <div class="kpi-label">应付总额</div>
        <div class="kpi-value">{{ formatMoney(overview?.totalPayable) }}</div>
        <div class="kpi-sub">逾期: {{ formatMoney(overview?.overduePayable) }}</div>
      </div>
      <div v-if="canViewPrice" class="kpi-card net" data-testid="kpi-net">
        <div class="kpi-label">净额 (应收-应付)</div>
        <div class="kpi-value" :class="{
          'positive': overview && ((overview.totalReceivable || 0) - (overview.totalPayable || 0)) >= 0,
          'negative': overview && ((overview.totalReceivable || 0) - (overview.totalPayable || 0)) < 0
        }">
          {{ overview ? formatMoney((overview.totalReceivable || 0) - (overview.totalPayable || 0)) : '—' }}
        </div>
      </div>
      <div class="kpi-card count" data-testid="kpi-count">
        <div class="kpi-label">本月交易笔数</div>
        <!-- ⛔ 不要 `|| 0`: 「后端没下发这个字段」和「本月一笔交易都没有」是两件事,
             而 `undefined || 0` 把前者翻译成了后者 (2026-08-28 实拍)。缺数据显示「—」。 -->
        <div class="kpi-value">{{ overview?.monthlyTransactionCount ?? '—' }}</div>
        <div class="kpi-sub">客户: {{ overview?.customerCount ?? '—' }} / 供应商: {{ overview?.supplierCount ?? '—' }}</div>
      </div>
    </div>

    <section class="cs-card">
      <header class="cs-card-head">
        <h2>交易记录</h2>
        <span class="count">共 {{ pagination.total }} 条记录</span>
        <div class="head-actions">
          <USelect v-model="searchType" :items="TYPE_ITEMS" class="w-40" data-testid="costs-type-filter" />
          <UButton icon="i-lucide-search" data-testid="costs-search" @click="handleSearch">搜索</UButton>
          <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="loading" data-testid="costs-refresh" @click="handleRefresh">刷新</UButton>
        </div>
      </header>

      <UTable :data="transactions" :columns="columns" :loading="loading" class="cs-table" data-testid="costs-table">
        <template #empty>暂无交易记录</template>
        <template #transactionType-cell="{ row }">
          <UBadge :color="typeColor(row.original.transactionType as string)" variant="soft">{{ typeText(row.original.transactionType) }}</UBadge>
        </template>
        <template #amount-cell="{ row }">
          <span class="num" :class="{ 'text-income': row.original.transactionType === 'AR_PAYMENT', 'text-expense': row.original.transactionType === 'PAYABLE' }">{{ formatMoney(row.original.amount) }}</span>
        </template>
        <template #balanceAfter-cell="{ row }"><span class="num">{{ formatMoney(row.original.balanceAfter) }}</span></template>
        <template #transactionDate-cell="{ row }">{{ formatDate((row.original.transactionDate || row.original.createdAt) as string | null) }}</template>
        <template #dueDate-cell="{ row }">{{ formatDate(row.original.dueDate as string | null) }}</template>
        <template #remark-cell="{ row }"><span class="remark">{{ cleanFinanceRemark(row.original.remark) }}</span></template>
      </UTable>

      <footer v-if="pagination.total > pagination.size" class="pager">
        <UPagination :page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </footer>
    </section>
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); display: flex; flex-direction: column; gap: var(--cs-space-4); }
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--cs-space-4); }
.kpi-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); padding: var(--cs-space-5); text-align: center; border-top-width: 3px; }
.kpi-label { font-size: 13px; color: var(--cs-muted); margin-bottom: var(--cs-space-2); }
.kpi-value { font-size: 22px; font-weight: 700; color: var(--cs-text); line-height: 1.3; font-variant-numeric: tabular-nums; }
.kpi-sub { font-size: 12px; color: var(--cs-muted); margin-top: 6px; }
.kpi-card.receivable { border-top-color: var(--cs-warning); }
.kpi-card.receivable .kpi-value { color: var(--cs-warning); }
.kpi-card.payable { border-top-color: var(--cs-danger); }
.kpi-card.payable .kpi-value { color: var(--cs-danger); }
.kpi-card.net { border-top-color: var(--cs-primary); }
.kpi-card.net .positive { color: var(--cs-success); }
.kpi-card.net .negative { color: var(--cs-danger); }
.kpi-card.count { border-top-color: var(--cs-success); }
.kpi-card.count .kpi-value { color: var(--cs-success); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-2); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
.num { font-variant-numeric: tabular-nums; }
.text-income { color: var(--cs-success); font-weight: 600; }
.text-expense { color: var(--cs-danger); font-weight: 600; }
.remark { color: var(--cs-muted); }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
@media (max-width: 1200px) { .kpi-row { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .kpi-row { grid-template-columns: 1fr; } }
</style>
