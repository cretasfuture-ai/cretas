<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { displayUnit } from '@/utils/unitPricing';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import {
  listTransitLedgers,
  type ProductionTransitLedgerItem,
} from '@/api/productionPlan';

// 组件全部是 Nuxt UI (UTable / USelect / UInput / UBadge / UButton) + --cs-*；提示仍用 ElMessage（命令式服务）。

// ────────────────────────────────────────────────────────────────────────────
// Auth
// ────────────────────────────────────────────────────────────────────────────
const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ────────────────────────────────────────────────────────────────────────────
// Label maps
// ────────────────────────────────────────────────────────────────────────────
const statusMap: Record<string, { label: string; type: 'success' | 'warning' | 'info' | 'danger' }> = {
  OPEN: { label: '待处理', type: 'warning' },
  RESOLVED: { label: '已清账', type: 'success' },
  VOIDED: { label: '已作废', type: 'info' },
};

const responsibilitySideMap: Record<string, string> = {
  PENDING: '待判责',
  PRODUCTION: '生产侧',
  WAREHOUSE: '仓库侧',
  WEIGHING_ERROR: '称重误差',
};

const varianceReasonMap: Record<string, string> = {
  WITHIN_TOLERANCE: '容差范围内',
  PRODUCTION_SHORTAGE: '生产少产',
  WAREHOUSE_SHORTAGE: '仓库少掉锅',
  TRANSIT_LOSS: '在途损耗',
  WEIGHING_DIFF: '称重差异',
  MANUAL_ADJUSTMENT: '手动调整',
  OTHER: '其他',
};

const ledgerTypeMap: Record<string, string> = {
  FINISHED_GOODS_RECEIPT: '成品入库',
  RAW_MATERIAL_ISSUE: '原料发料',
  SEMI_FINISHED_ISSUE: '半成品发料',
};

function statusLabel(val: string) { return statusMap[val]?.label ?? val; }
function statusType(val: string) { return statusMap[val]?.type ?? 'info'; }
function responsibilityLabel(val: string) { return responsibilitySideMap[val] ?? val; }
function varianceReasonLabel(val: string) { return varianceReasonMap[val] ?? val; }
function ledgerTypeLabel(val: string) { return ledgerTypeMap[val] ?? val; }
function responsibilityType(side: string): 'warning' | 'danger' | 'info' {
  return side === 'PENDING' ? 'warning' : side === 'PRODUCTION' ? 'danger' : 'info';
}

// ────────────────────────────────────────────────────────────────────────────
// Filters
// ────────────────────────────────────────────────────────────────────────────
const statusFilter = ref('');
const planNumberSearch = ref('');

// USelect 的选项值不能是空串: 页面内用 ALL 表示全部状态, 请求时还原成空串
const ALL = 'ALL';
const statusOptions = [
  { value: ALL, label: '全部状态' },
  { value: 'OPEN', label: '待处理' },
  { value: 'RESOLVED', label: '已清账' },
  { value: 'VOIDED', label: '已作废' },
];
const statusSelect = computed({
  get: () => statusFilter.value || ALL,
  set: (v: string) => { statusFilter.value = v === ALL ? '' : v; },
});

const R = { class: { th: 'text-right', td: 'text-right' } };
const columns: TableColumn<ProductionTransitLedgerItem>[] = [
  { id: 'planNumber', header: '计划单号' },
  { id: 'ledgerType', header: '挂账类型' },
  { id: 'status', header: '状态' },
  { id: 'reported', header: '报工数量', meta: R },
  { id: 'confirmed', header: '仓库确认量', meta: R },
  { id: 'variance', header: '差异量', meta: R },
  { id: 'tolerance', header: '容差量', meta: R },
  { id: 'varianceReason', header: '差异原因' },
  { id: 'responsibility', header: '责任侧' },
  { id: 'note', header: '备注' },
  { id: 'createdAt', header: '确认时间' },
  { id: 'updatedAt', header: '更新时间' },
  { id: 'actions', header: '操作' },
];

// ────────────────────────────────────────────────────────────────────────────
// Data
// ────────────────────────────────────────────────────────────────────────────
const loading = ref(false);
const allLedgers = ref<ProductionTransitLedgerItem[]>([]);

const tableData = computed(() => {
  let rows = allLedgers.value;
  if (planNumberSearch.value.trim()) {
    const kw = planNumberSearch.value.trim().toLowerCase();
    rows = rows.filter((r) => r.planNumber.toLowerCase().includes(kw));
  }
  return rows;
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listTransitLedgers(factoryId.value, statusFilter.value || undefined);
    if (res.success && Array.isArray(res.data)) {
      allLedgers.value = res.data;
    } else {
      allLedgers.value = [];
    }
  } catch (e) {
    ElMessage({
      message: String((e as Error).message || '加载中转挂账失败'),
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

function handleFilterChange() {
  planNumberSearch.value = '';
  loadData();
}

function handleRefresh() {
  loadData();
}

// ────────────────────────────────────────────────────────────────────────────
// Summary counts
// ────────────────────────────────────────────────────────────────────────────
const openCount = computed(() => allLedgers.value.filter((r) => r.status === 'OPEN').length);
const resolvedCount = computed(() => allLedgers.value.filter((r) => r.status === 'RESOLVED').length);

// ────────────────────────────────────────────────────────────────────────────
// Format helpers
// ────────────────────────────────────────────────────────────────────────────
function formatQty(qty: number | null | undefined, unit: string | null) {
  if (qty == null) return '—';
  return unit ? `${qty} ${displayUnit(unit)}` : String(qty);
}

function formatDateTime(dt: string | null | undefined) {
  if (!dt) return '—';
  return dt.replace('T', ' ').substring(0, 16);
}

// ────────────────────────────────────────────────────────────────────────────
// Init
// ────────────────────────────────────────────────────────────────────────────
onMounted(() => {
  loadData();
});

watch(factoryId, (v) => {
  if (v) loadData();
});
</script>

<template>
  <div class="transit-ledger-list">
    <!-- 页面标题 -->
    <div class="cs-page-head">
      <div>
        <h2 class="page-title">中转挂账对账</h2>
        <p class="page-subtitle">查询生产→仓库成品入库确认后产生的差异挂账记录，追踪仓库/生产责任判定与清账状态。</p>
      </div>
    </div>

    <!-- 汇总卡片 -->
    <div class="summary-row">
      <section class="cs-card summary-card">
        <span class="summary-label">待处理挂账</span>
        <span class="summary-value warning">{{ openCount }}</span>
      </section>
      <section class="cs-card summary-card">
        <span class="summary-label">已清账</span>
        <span class="summary-value success">{{ resolvedCount }}</span>
      </section>
      <section class="cs-card summary-card">
        <span class="summary-label">合计记录</span>
        <span class="summary-value">{{ allLedgers.length }}</span>
      </section>
    </div>

    <section class="cs-card">
      <!-- 筛选栏 -->
      <div class="filters">
        <USelect v-model="statusSelect" :items="statusOptions" class="w-44" data-testid="transit-status" @update:model-value="handleFilterChange" />
        <UInput v-model="planNumberSearch" icon="i-lucide-search" placeholder="按计划单号搜索" class="w-60" data-testid="transit-plan-search">
          <template v-if="planNumberSearch" #trailing>
            <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="planNumberSearch = ''" />
          </template>
        </UInput>
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="handleRefresh">刷新</UButton>
      </div>

      <!-- 数据表格 -->
      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="transit-ledger-table">
        <template #empty>
          <div class="empty-hint">
            <p>{{ planNumberSearch ? '没有符合条件的挂账记录' : '暂无中转挂账记录' }}</p>
            <template v-if="!planNumberSearch">
              <p>当生产报工与仓库实收数量出现超容差差异时，系统将在此生成挂账记录。</p>
              <p class="small">容差范围（默认 10kg）内的差异不产生挂账。</p>
            </template>
          </div>
        </template>
        <template #planNumber-cell="{ row }"><span class="plan-number">{{ row.original.planNumber }}</span></template>
        <template #ledgerType-cell="{ row }">
          <UBadge color="neutral" variant="soft">{{ ledgerTypeLabel(row.original.ledgerType) }}</UBadge>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(statusType(row.original.status))" variant="soft">{{ statusLabel(row.original.status) }}</UBadge>
        </template>
        <template #reported-cell="{ row }"><span class="num">{{ formatQty(row.original.reportedQuantity, row.original.quantityUnit) }}</span></template>
        <template #confirmed-cell="{ row }"><span class="num">{{ formatQty(row.original.confirmedQuantity, row.original.quantityUnit) }}</span></template>
        <template #variance-cell="{ row }">
          <span class="num" :class="row.original.varianceQuantity > row.original.toleranceQuantity ? 'text-danger' : ''">
            {{ formatQty(row.original.varianceQuantity, row.original.quantityUnit) }}
          </span>
        </template>
        <template #tolerance-cell="{ row }"><span class="num">{{ formatQty(row.original.toleranceQuantity, row.original.quantityUnit) }}</span></template>
        <template #varianceReason-cell="{ row }">{{ varianceReasonLabel(row.original.varianceReason) }}</template>
        <template #responsibility-cell="{ row }">
          <UBadge :color="badgeColor(responsibilityType(row.original.responsibilitySide))" variant="soft">{{ responsibilityLabel(row.original.responsibilitySide) }}</UBadge>
        </template>
        <template #note-cell="{ row }"><span class="note" :title="row.original.note || ''">{{ row.original.note || '—' }}</span></template>
        <template #createdAt-cell="{ row }">{{ formatDateTime(row.original.createdAt) }}</template>
        <template #updatedAt-cell="{ row }">{{ formatDateTime(row.original.updatedAt) }}</template>
        <template #actions-cell="{ row }">
          <UButton size="xs" variant="link" @click="router.push(`/production/plans?planId=${row.original.productionPlanId}`)">查看计划</UButton>
        </template>
      </UTable>
    </section>
  </div>
</template>

<style scoped>
.transit-ledger-list { padding: var(--cs-space-4); }
.page-title { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.page-subtitle { margin: 4px 0 0; font-size: 13px; color: var(--cs-muted); }
.summary-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--cs-space-3); margin-bottom: var(--cs-space-3); }
.summary-row .cs-card + .cs-card { margin-top: 0; }
.summary-card { display: flex; justify-content: space-between; align-items: center; padding: var(--cs-space-4) var(--cs-space-5); }
.summary-label { font-size: 13px; color: var(--cs-muted); }
.summary-value { font-size: 24px; font-weight: 700; color: var(--cs-text); }
.summary-value.warning { color: var(--cs-warning); }
.summary-value.success { color: var(--cs-success); }
.filters { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.plan-number { font-family: monospace; font-size: 13px; }
.text-danger { color: var(--cs-danger); font-weight: 600; }
.note { display: inline-block; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
.empty-hint { padding: var(--cs-space-4) 0; color: var(--cs-muted); }
.empty-hint p { margin: 0 0 4px; }
.empty-hint .small { font-size: 12px; }
</style>
