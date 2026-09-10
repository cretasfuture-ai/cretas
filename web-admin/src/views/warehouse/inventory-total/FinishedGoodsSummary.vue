<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, onActivated, onDeactivated } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { fmtQty } from '@/utils/tableFormatters';
import { displayUnit } from '@/utils/unitPricing';
import { loadAllFinishedStock, summarizeFinishedStock, type FinishedStockBatch, type StockPage } from './finishedGoodsSummary';

const auth = useAuthStore();
const rows = ref<FinishedStockBatch[]>([]);
const keyword = ref('');
const loading = ref(false);
const error = ref('');
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
  <el-card shadow="never" v-loading="loading">
    <el-input v-model="keyword" placeholder="成品名称 / 产品ID" clearable style="width: 260px; margin-right: 12px" />
    <el-button @click="load">刷新成品库存</el-button>
    <p>全工厂成品批次汇总（含未分仓批次），按物品、单位、仓库、归属与状态分别统计。账面为生产量减已发货，余量再减预留；余量不代表质量放行。查询期间库存可能变化，可刷新重查。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table v-else :data="summary" stripe border empty-text="暂无符合条件的成品库存" data-testid="finished-stock-summary">
      <el-table-column prop="name" label="成品名称" min-width="150" />
      <el-table-column prop="itemId" label="产品ID" min-width="130" />
      <el-table-column label="仓库" min-width="130"><template #default="{ row }">{{ row.warehouseId || '未分仓' }}</template></el-table-column>
      <el-table-column prop="batchCount" label="批次数" width="80" />
      <el-table-column label="账面合计" width="110"><template #default="{ row }">{{ row.quantity == null ? '—' : fmtQty(row.quantity) }}</template></el-table-column>
      <el-table-column label="扣预留后余量" width="130"><template #default="{ row }">{{ row.available == null ? '—' : fmtQty(row.available) }}</template></el-table-column>
      <el-table-column label="单位" width="80"><template #default="{ row }">{{ displayUnit(row.unit) || '—' }}</template></el-table-column>
      <el-table-column prop="status" label="库存状态" width="120" />
      <el-table-column label="归属" min-width="150"><template #default="{ row }">{{ row.ownerCustomerId ? `客户 ${row.ownerCustomerId}` : row.ownership || '未提供' }}</template></el-table-column>
    </el-table>
  </el-card>
</template>
