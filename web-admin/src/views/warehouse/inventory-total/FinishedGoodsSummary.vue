<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, onActivated, onDeactivated } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { fmtQty } from '@/utils/tableFormatters';
import { displayUnit } from '@/utils/unitPricing';
import { loadAllFinishedStock, summarizeFinishedStock, type FinishedStockBatch, type StockPage } from './finishedGoodsSummary';

const auth = useAuthStore();
const rows = ref<FinishedStockBatch[]>([]);
const keyword = ref('');
const loading = ref(false);
const error = ref('');
type SummaryRow = ReturnType<typeof summarizeFinishedStock>[number];
const R = { class: { th: 'text-right', td: 'text-right' } };
const columns: TableColumn<SummaryRow>[] = [
  { accessorKey: 'name', header: '成品名称' },
  { accessorKey: 'itemId', header: '产品ID' },
  { id: 'warehouse', header: '仓库' },
  { accessorKey: 'batchCount', header: '批次数', meta: R },
  { id: 'quantity', header: '账面合计', meta: R },
  { id: 'available', header: '扣预留后余量', meta: R },
  { id: 'unit', header: '单位' },
  { accessorKey: 'status', header: '库存状态' },
  { id: 'owner', header: '归属' },
];
let requestId = 0;
let controller: AbortController | undefined;
let deactivated = false;
function cancelLoad() {
  ++requestId;
  controller?.abort();
  loading.value = false;
}
onBeforeUnmount(cancelLoad);
onDeactivated(() => { deactivated = true; cancelLoad(); });
onActivated(() => { if (deactivated) { deactivated = false; void load(); } });
const summary = computed(() => summarizeFinishedStock(rows.value.filter(row =>
  !keyword.value.trim() || (row.productName || '').toLowerCase().includes(keyword.value.trim().toLowerCase())
  || (row.productTypeId || '').toLowerCase().includes(keyword.value.trim().toLowerCase()),
)));
async function load() {
  cancelLoad();
  const current = ++requestId;
  const factoryId = auth.factoryId;
  rows.value = [];
  error.value = '';
  if (!factoryId || deactivated) return;
  controller = new AbortController();
  const signal = controller.signal;
  loading.value = true;
  try {
    const result = await loadAllFinishedStock(factoryId, async page => {
      const response = await get<StockPage>(`/${factoryId}/sales/finished-goods`, { params: { page, size: 100 }, signal });
      if (!response.success || !response.data) throw new Error(response.message || '加载成品库存失败');
      return response.data;
    }, signal);
    if (current === requestId) rows.value = result;
  } catch (cause) {
    if (current === requestId) error.value = cause instanceof Error ? cause.message : '加载成品库存失败';
  } finally {
    if (current === requestId) loading.value = false;
  }
}
watch(() => auth.factoryId, load, { immediate: true });
</script>

<template>
  <section class="cs-card">
    <div class="toolbar">
      <UInput v-model="keyword" icon="i-lucide-search" placeholder="成品名称 / 产品ID" class="w-65">
        <template v-if="keyword" #trailing>
          <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="keyword = ''" />
        </template>
      </UInput>
      <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="loading" @click="load">刷新成品库存</UButton>
    </div>
    <p class="note">全工厂成品批次汇总（含未分仓批次），按物品、单位、仓库、归属与状态分别统计。账面为生产量减已发货，余量再减预留；余量不代表质量放行。查询期间库存可能变化，可刷新重查。</p>
    <UAlert v-if="error" color="error" variant="subtle" :title="error" class="m-4" />
    <UTable v-else :data="summary" :columns="columns" :loading="loading" class="cs-table" data-testid="finished-stock-summary">
      <template #empty>暂无符合条件的成品库存</template>
      <template #warehouse-cell="{ row }">{{ row.original.warehouseId || '未分仓' }}</template>
      <template #quantity-cell="{ row }"><span class="num">{{ row.original.quantity == null ? '—' : fmtQty(row.original.quantity) }}</span></template>
      <template #available-cell="{ row }"><span class="num">{{ row.original.available == null ? '—' : fmtQty(row.original.available) }}</span></template>
      <template #unit-cell="{ row }">{{ displayUnit(row.original.unit) || '—' }}</template>
      <template #owner-cell="{ row }">{{ row.original.ownerCustomerId ? `客户 ${row.original.ownerCustomerId}` : row.original.ownership || '未提供' }}</template>
    </UTable>
  </section>
</template>

<style scoped>
.toolbar { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5) 0; }
.note { margin: var(--cs-space-3) 0; padding: 0 var(--cs-space-5); color: var(--cs-muted); font-size: 13px; }
</style>
