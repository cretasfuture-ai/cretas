<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';

/**
 * C3: 半成品库存视图 (只读, 仅重量字段)
 *
 * 数据来源: GET /api/mobile/{factoryId}/semi-finished/inventory
 * 展示 semi_finished_inventories 全状态快照 (AVAILABLE / DEPLETED / RETURNED),
 * 按产品类型名称分组, 仅显示重量字段, 不含成本。
 *
 * 客户要求: "只做重量库存" — 仓管员只需知道有多少可用半成品, 不需要看成本。
 *
 * 2026-09-01 LIUSHANMEN IA 收拢 (owner 拍板): 标题从「半成品重量库存」改为
 * 「半成品库存」，与 menuConfig 曾经的说法收敛 (形态 D，见
 * router/index.ts 与 __tests__/gates/breadcrumbMatchesMenuLabel.source.spec.ts
 * 的说明)，本页现在是 /warehouse/inventory-query hub 里「半成品库存」tab 的
 * 内嵌页面，标题字面量不再有第二个名字。
 */

interface WipRow {
  intermediateBatchNo: string | null;
  sourceWorkProcessTaskId: number | null;
  processOrder: number | null;
  productTypeId: string | null;
  producedQuantity: number;
  consumedQuantity: number;
  availableQuantity: number;
  unit: string | null;
  status: string;
  // C3 扩展字段
  productTypeName: string | null;
  batchId: number | null;
}

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const tableData = ref<WipRow[]>([]);

// 筛选: 产品类型 / 状态 (实时按当前数据里实际存在的值生成选项, 非固定枚举)
const filterProductType = ref('');
const filterStatus = ref('');

const productTypeOptions = computed(() => {
  const set = new Set(tableData.value.map(r => r.productTypeName || r.productTypeId).filter((v): v is string => !!v));
  return Array.from(set).sort();
});
const statusOptions = computed(() => {
  const set = new Set(tableData.value.map(r => r.status).filter((v): v is string => !!v));
  return Array.from(set).map(v => ({ value: v, label: statusLabel(v) }));
});

const filteredData = computed(() => tableData.value.filter(row => {
  if (filterProductType.value && (row.productTypeName || row.productTypeId) !== filterProductType.value) return false;
  if (filterStatus.value && row.status !== filterStatus.value) return false;
  return true;
}));

function handleFilterReset() {
  filterProductType.value = '';
  filterStatus.value = '';
}

// USelect 的选项值不能是空串: 页面内用 ALL 表示不筛
const ALL = 'ALL';
const productTypeItems = computed(() => [{ label: '全部产品类型', value: ALL }, ...productTypeOptions.value.map(pt => ({ label: pt, value: pt }))]);
const statusItems = computed(() => [{ label: '全部状态', value: ALL }, ...statusOptions.value.map(o => ({ label: o.label, value: o.value }))]);
const productTypeSelect = computed({
  get: () => filterProductType.value || ALL,
  set: (v: string) => { filterProductType.value = v === ALL ? '' : v; },
});
const statusSelect = computed({
  get: () => filterStatus.value || ALL,
  set: (v: string) => { filterStatus.value = v === ALL ? '' : v; },
});

const R = { class: { th: 'text-right', td: 'text-right' } };
const C = { class: { th: 'text-center', td: 'text-center' } };
const columns: TableColumn<WipRow>[] = [
  { id: 'productType', header: '产品类型' },
  { id: 'batchId', header: '批次', meta: C },
  { id: 'batchNo', header: '中间批次号' },
  { id: 'processOrder', header: '工序', meta: C },
  { id: 'produced', header: '产出量', meta: R },
  { id: 'consumed', header: '已消耗', meta: R },
  { id: 'available', header: '可用量', meta: R },
  { id: 'status', header: '状态', meta: C },
];

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/semi-finished/inventory`);
    if (response.success && response.data) {
      tableData.value = Array.isArray(response.data) ? response.data : [];
    } else if (response.success === false) {
      ElMessage({ message: response.message || '加载半成品库存失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error) {
    console.error('加载半成品库存失败:', error);
    ElMessage({ message: '加载半成品库存失败，请稍后重试', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'AVAILABLE': return '可用';
    case 'DEPLETED':  return '已耗尽';
    case 'RETURNED':  return '已退回';
    default:          return status;
  }
}

function statusType(status: string): 'success' | 'info' | 'warning' | 'danger' | '' {
  switch (status) {
    case 'AVAILABLE': return 'success';
    case 'DEPLETED':  return 'info';
    case 'RETURNED':  return 'warning';
    default:          return '';
  }
}

function formatKg(val: number | null | undefined): string {
  if (val == null) return '-';
  return `${val} kg`;
}
</script>

<template>
  <div class="semi-finished-inventory-page">
    <div class="cs-page-head">
      <h2>半成品库存</h2>
      <div class="page-actions">
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="loadData">刷新</UButton>
      </div>
    </div>

    <section class="cs-card">
      <!-- 筛选: 按当前数据里实际存在的 产品类型/状态 生成选项, 非固定枚举 -->
      <div class="filter-bar">
        <USelect v-model="productTypeSelect" :items="productTypeItems" class="w-48" data-testid="semi-filter-product-type" />
        <USelect v-model="statusSelect" :items="statusItems" class="w-36" data-testid="semi-filter-status" />
        <UButton icon="i-lucide-rotate-ccw" color="neutral" variant="outline" @click="handleFilterReset">重置</UButton>
      </div>

      <UTable :data="filteredData" :columns="columns" :loading="loading" class="cs-table" data-testid="semi-finished-table">
        <template #empty>
          {{ tableData.length === 0 ? '暂无半成品库存记录' : '没有符合筛选条件的记录' }}
        </template>
        <template #productType-cell="{ row }">{{ row.original.productTypeName || row.original.productTypeId || '-' }}</template>
        <template #batchId-cell="{ row }">{{ row.original.batchId ?? '-' }}</template>
        <template #batchNo-cell="{ row }"><span class="mono">{{ row.original.intermediateBatchNo || '-' }}</span></template>
        <template #processOrder-cell="{ row }">{{ row.original.processOrder ?? '-' }}</template>
        <template #produced-cell="{ row }"><span class="qty-cell num">{{ formatKg(row.original.producedQuantity) }}</span></template>
        <template #consumed-cell="{ row }"><span class="qty-cell consumed num">{{ formatKg(row.original.consumedQuantity) }}</span></template>
        <template #available-cell="{ row }">
          <span class="qty-cell available num" :class="{ zero: row.original.availableQuantity === 0 }">{{ formatKg(row.original.availableQuantity) }}</span>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(statusType(row.original.status) || undefined)" variant="soft">{{ statusLabel(row.original.status) }}</UBadge>
        </template>
      </UTable>
    </section>
  </div>
</template>

<style scoped>
.semi-finished-inventory-page { padding: var(--cs-space-4); }
.filter-bar { display: flex; gap: var(--cs-space-2); flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.mono { font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: 12px; }
.qty-cell { font-weight: 500; }
.qty-cell.available { color: var(--cs-primary); font-weight: 600; }
.qty-cell.available.zero { color: var(--cs-muted); font-weight: 400; }
.qty-cell.consumed { color: var(--cs-muted); }
</style>
