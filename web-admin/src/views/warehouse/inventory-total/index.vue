<script setup lang="ts">
/**
 * 总库存查询 (工厂级原料总库存, 按物料聚合, 跨所有仓库)
 *
 * F006 六膳门真实客户需求。区别于「分仓库存查询」(/inventory/by-warehouse, 批次级 + 按单仓):
 * 本页是 工厂级 + 按物料聚合 的只读视图 —— 每个原料类型一行, 把该物料在所有仓库的
 * 所有在库批次的当前剩余量 / 价值 汇总。
 *
 * 后端: GET /{factoryId}/material-batches/stock-summary
 *   - 仅统计在库批次 (剩余量 > 0 且 status 非 耗尽/用完/过期/报废/不良品)
 *   - totalQuantity = SUM(receiptQuantity - usedQuantity - reservedQuantity)
 *   - totalValue / avgUnitPrice 受 RBAC 价格可见性约束 (canViewPrice)
 *
 * 只读页面 (无新建/编辑/删除)。组件全部是 Nuxt UI (UTable / USelect / UInput / UButton) + --cs-*。
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { formatAmount, fmtQty } from '@/utils/tableFormatters';
import { sumStockValue } from './stockValueSum';
import FinishedGoodsSummary from './FinishedGoodsSummary.vue';
const inventoryKind = ref('materials');
const KIND_ITEMS = [
  { label: '原料库存', value: 'materials' },
  { label: '成品库存', value: 'products' },
];

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
// 价格可见性 RBAC: 无 price-view 角色 (仓管/质检/操作员) 不显示价值/均价列。
const canViewPrice = computed(() => permissionStore.canViewPrice);

interface MaterialStockSummaryRow {
  materialTypeId: string;
  materialName?: string;
  materialCode?: string;
  materialCategory?: string;
  unit?: string;
  totalQuantity?: number;
  totalValue?: number;
  batchCount?: number;
  warehouseCount?: number;
  avgUnitPrice?: number | null;
}

const loading = ref(false);
const loadError = ref('');
const tableData = ref<MaterialStockSummaryRow[]>([]);
const searchKeyword = ref('');
const filterCategory = ref('');

const R = { class: { th: 'text-right', td: 'text-right' } };
const C = { class: { th: 'text-center', td: 'text-center' } };
const columns = computed<TableColumn<MaterialStockSummaryRow>[]>(() => [
  { accessorKey: 'materialCode', header: '原料编码' },
  { accessorKey: 'materialName', header: '原料名称' },
  { id: 'materialCategory', header: '类别' },
  { id: 'totalQuantity', header: '当前总库存', meta: R },
  { id: 'unit', header: '单位', meta: C },
  ...(canViewPrice.value ? [
    { id: 'avgUnitPrice', header: '移动均价', meta: R },
    { id: 'totalValue', header: '库存总价值', meta: R },
  ] : []),
  { id: 'batchCount', header: '批次数', meta: R },
  { id: 'warehouseCount', header: '仓库数', meta: R },
]);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const resp = await get<MaterialStockSummaryRow[]>(
      `/${factoryId.value}/material-batches/stock-summary`
    );
    if (resp.success && Array.isArray(resp.data)) {
      tableData.value = resp.data;
    } else if (resp.success === false) {
      // 防呆 4 位一体: 原样显示后端 message, 不吞错。
      loadError.value = resp.message || '加载总库存失败';
      ElMessage.error(loadError.value);
      tableData.value = [];
    }
  } catch (e: unknown) {
    const msg =
      typeof e === 'object' && e !== null && 'message' in e
        ? String((e as { message?: unknown }).message ?? '')
        : '';
    loadError.value = msg || '加载总库存失败, 请稍后重试';
    ElMessage.error(loadError.value);
    tableData.value = [];
    console.error('加载总库存失败:', e);
  } finally {
    loading.value = false;
  }
}

// ==================== 前端过滤 ====================

// 类别下拉选项 (从已加载数据动态去重)
const categoryOptions = computed<string[]>(() => {
  const set = new Set<string>();
  for (const r of tableData.value) {
    if (r.materialCategory) set.add(r.materialCategory);
  }
  return Array.from(set).sort();
});

// USelect 的选项值不能是空串: 页面内用 ALL 表示全部类别
const ALL = 'ALL';
const categoryItems = computed(() => [
  { label: '全部类别', value: ALL },
  ...categoryOptions.value.map((c) => ({ label: c, value: c })),
]);
const categorySelect = computed({
  get: () => filterCategory.value || ALL,
  set: (v: string) => { filterCategory.value = v === ALL ? '' : v; },
});

const filteredData = computed<MaterialStockSummaryRow[]>(() => {
  const kw = searchKeyword.value.trim().toLowerCase();
  const cat = filterCategory.value;
  // 原 el-table 的 default-sort: 原料名称升序
  const sorted = [...tableData.value].sort((a, b) =>
    (a.materialName || '').localeCompare(b.materialName || '', 'zh-CN'));
  return sorted.filter((r) => {
    if (cat && r.materialCategory !== cat) return false;
    if (!kw) return true;
    const name = (r.materialName || '').toLowerCase();
    const code = (r.materialCode || '').toLowerCase();
    return name.includes(kw) || code.includes(kw);
  });
});

// ==================== 顶部汇总 ====================

const totalMaterialKinds = computed(() => filteredData.value.length);
const totalValueSum = computed(() => {
  if (!canViewPrice.value) return null;
  return sumStockValue(filteredData.value);
});
const totalValueIncomplete = computed(() => canViewPrice.value && totalValueSum.value == null && filteredData.value.length > 0);

function handleRefresh() {
  searchKeyword.value = '';
  filterCategory.value = '';
  loadData();
}

onMounted(() => {
  loadData();
});
</script>

<template>
  <div class="page-wrapper">
    <div class="kind-switch" role="group" aria-label="库存类型">
      <UButton
        v-for="k in KIND_ITEMS"
        :key="k.value"
        size="sm"
        :color="inventoryKind === k.value ? 'primary' : 'neutral'"
        :variant="inventoryKind === k.value ? 'solid' : 'outline'"
        :data-testid="`inventory-kind-${k.value}`"
        @click="inventoryKind = k.value"
      >{{ k.label }}</UButton>
    </div>
    <FinishedGoodsSummary v-if="inventoryKind === 'products'" />
    <template v-if="inventoryKind === 'materials'">
      <section class="cs-card">
        <header class="cs-card-head">
          <h2 class="page-title">总库存查询</h2>
          <span class="hint">工厂级原料总库存 — 按物料聚合, 跨所有仓库 (仅在库批次)</span>
        </header>
        <div class="filter-row">
          <UInput v-model="searchKeyword" icon="i-lucide-search" placeholder="原料名称 / 编码" class="w-60" data-testid="inventory-total-keyword">
            <template v-if="searchKeyword" #trailing>
              <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="searchKeyword = ''" />
            </template>
          </UInput>
          <USelect v-model="categorySelect" :items="categoryItems" class="w-44" data-testid="inventory-total-category" />
          <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" @click="handleRefresh">刷新</UButton>
        </div>
      </section>

      <section class="cs-card">
        <div class="summary-row">
          <div class="summary-item">
            <div class="summary-label">原料种类</div>
            <div class="summary-value strong-blue">{{ totalMaterialKinds }}</div>
          </div>
          <div v-if="canViewPrice" class="summary-item">
            <div class="summary-label">库存总价值</div>
            <div class="summary-value strong-green">{{ totalValueSum == null ? '-' : formatAmount(totalValueSum) }}</div>
            <div v-if="totalValueIncomplete" class="summary-label">含缺单价物料，无法合计</div>
          </div>
        </div>
      </section>

      <section class="cs-card">
        <UTable :data="filteredData" :columns="columns" :loading="loading" class="cs-table" data-testid="inventory-total-table">
          <template #empty>暂无库存数据</template>
          <template #materialCategory-cell="{ row }">{{ row.original.materialCategory || '-' }}</template>
          <template #totalQuantity-cell="{ row }"><strong class="num">{{ fmtQty(row.original.totalQuantity) }}</strong></template>
          <template #unit-cell="{ row }">{{ row.original.unit || '-' }}</template>
          <template #avgUnitPrice-cell="{ row }"><span class="num">{{ row.original.avgUnitPrice == null ? '-' : formatAmount(row.original.avgUnitPrice) }}</span></template>
          <template #totalValue-cell="{ row }"><span class="num">{{ formatAmount(row.original.totalValue) }}</span></template>
          <template #batchCount-cell="{ row }"><span class="num">{{ row.original.batchCount ?? 0 }}</span></template>
          <template #warehouseCount-cell="{ row }"><span class="num">{{ row.original.warehouseCount ?? 0 }}</span></template>
        </UTable>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page-wrapper { display: flex; flex-direction: column; gap: var(--cs-space-3); padding: var(--cs-space-4); }
.page-wrapper > .cs-card + .cs-card { margin-top: 0; }
.kind-switch { display: flex; gap: var(--cs-space-2); }
.cs-card-head { align-items: baseline; }
.page-title { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.hint { font-size: 13px; color: var(--cs-muted); font-weight: 400; }
.filter-row { display: flex; gap: var(--cs-space-3); flex-wrap: wrap; align-items: center; padding: var(--cs-space-4) var(--cs-space-5); }
.summary-row { display: flex; gap: 48px; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.summary-item { display: flex; flex-direction: column; gap: 4px; }
.summary-label { font-size: 13px; color: var(--cs-muted); }
.summary-value { font-size: 22px; font-weight: 600; color: var(--cs-text); }
.summary-value.strong-blue { color: var(--cs-primary); }
.summary-value.strong-green { color: var(--cs-success); }
</style>
