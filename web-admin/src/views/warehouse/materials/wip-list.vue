<script setup lang="ts">
import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { formatDateTime } from '@/utils/dateFormat';
import type { TableRow } from '@/types/api';

/**
 * 在制品 (WIP) 批次列表 — Sprint 4 Wave 2 M-WIP-1.
 *
 * 显示 status = PRODUCING_RESERVED 的原材料批次, 即已被
 * 进行中的生产批次 (ProductionBatch.status IN_PROGRESS) 占用的物料。
 *
 * 状态转换由后端 ProcessingServiceImpl 维护:
 *  - startProduction → RESERVED/AVAILABLE → PRODUCING_RESERVED
 *  - completeProduction / cancelProduction → PRODUCING_RESERVED → AVAILABLE/RESERVED/DEPLETED
 *
 * 组件全部是 Nuxt UI (UTable / UAlert / UButton) + --cs-*。
 */
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const tableData = ref<TableRow[]>([]);

const columns = computed<TableColumn<TableRow>[]>(() => [
  { accessorKey: 'batchNumber', header: '批次号' },
  { accessorKey: 'factoryId', header: '工厂' },
  { id: 'materialType', header: '物料类型' },
  { id: 'remaining', header: '剩余数量' },
  { accessorKey: 'reservedQuantity', header: '预留数量' },
  { id: 'supplier', header: '供应商' },
  ...(canViewPrice.value ? [{ accessorKey: 'unitPrice', header: '单价' } as TableColumn<TableRow>] : []),
  { id: 'inboundDate', header: '入库日期' },
  { id: 'expiryDate', header: '过期日期' },
  { id: 'lastUsedAt', header: '最后使用时间' },
]);

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/material-batches/wip`);
    if (response.success && response.data) {
      tableData.value = Array.isArray(response.data) ? response.data : [];
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载在制品列表失败');
    }
  } catch (error) {
    console.error('加载在制品失败:', error);
  } finally {
    loading.value = false;
  }
}

function formatQuantity(row: TableRow): string {
  const qty = row.currentQuantity ?? row.remainingQuantity ?? 0;
  return `${qty} ${row.quantityUnit || ''}`.trim();
}
</script>

<template>
  <div class="wip-batches-page">
    <div class="cs-page-head">
      <h2>在制品 (WIP)</h2>
      <div class="page-actions">
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="loadData">刷新</UButton>
      </div>
    </div>

    <UAlert color="info" variant="subtle" icon="i-lucide-info" title="在制品说明" class="info-banner">
      <template #description>
        本页显示当前已被「进行中」生产批次占用的原材料批次 (状态 = PRODUCING_RESERVED)。
        生产批次完成或取消后, 物料批次状态会自动恢复为 AVAILABLE / RESERVED / DEPLETED。
      </template>
    </UAlert>

    <section class="cs-card">
      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="wip-table">
        <template #empty><ListEmptyExplain empty-title="还没有在制品批次" empty-hint="生产领料投入后、产成品入库前的批次会出现在这里；还没有生产领料或已全部入库时为空。" /></template>
        <template #materialType-cell="{ row }">{{ row.original.materialType?.name || row.original.materialTypeId || '-' }}</template>
        <template #remaining-cell="{ row }"><span class="num">{{ formatQuantity(row.original) }}</span></template>
        <template #supplier-cell="{ row }">{{ row.original.supplier?.name || row.original.supplierId || '-' }}</template>
        <template #inboundDate-cell="{ row }">{{ row.original.inboundDate || row.original.receiptDate || '-' }}</template>
        <template #expiryDate-cell="{ row }">{{ row.original.expiryDate || row.original.expireDate || '-' }}</template>
        <template #lastUsedAt-cell="{ row }">{{ formatDateTime(row.original.lastUsedAt) }}</template>
      </UTable>
    </section>
  </div>
</template>

<style scoped>
.wip-batches-page { padding: var(--cs-space-4); }
.info-banner { margin-bottom: var(--cs-space-3); }
</style>
