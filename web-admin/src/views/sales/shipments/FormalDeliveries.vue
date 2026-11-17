<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import type { CsTableColumn } from '@/types/csTable';

interface DeliveryItem { id: number; productName?: string; deliveredQuantity?: number | string | null; unit?: string }
interface Delivery {
  id: string; deliveryNumber: string; salesOrderId?: string; orderNumber?: string;
  customerName?: string; customerId?: string; recordRole?: string; parentDeliveryId?: string;
  shipmentSequence?: number; deliveryDate?: string; status: string; items?: DeliveryItem[];
}
interface DeliveryPage { content: Delivery[]; totalElements: number }
const auth = useAuthStore();
const router = useRouter();
const factoryId = computed(() => auth.factoryId);
const rows = ref<Delivery[]>([]);
const page = ref(1);
const size = 10;
const total = ref(0);
const loading = ref(false);
const error = ref('');
let requestVersion = 0;

const columns: CsTableColumn<Delivery>[] = [
  { accessorKey: 'deliveryNumber', header: '发货单号' },
  { id: 'role', header: '记录类型' },
  { id: 'order', header: '销售订单' },
  { id: 'customer', header: '客户' },
  { id: 'items', header: '产品 / 单据数量' },
  { id: 'status', header: '状态' },
  { accessorKey: 'deliveryDate', header: '发货日期' },
  { id: 'actions', header: '操作' },
];
function roleLabel(row: Delivery): string {
  if (row.parentDeliveryId || row.recordRole === 'SHIPMENT') return '子发运单';
  if (row.recordRole === 'MASTER') return '母发货单';
  return '单次发货单';
}
const statuses: Record<string, string> = {
  DRAFT: '草稿', PENDING_WAREHOUSE_CONFIRM: '待仓库确认', PICKED: '已拣货',
  PENDING_SPLIT: '待分批', PARTIALLY_SCHEDULED: '部分已安排', FULLY_SCHEDULED: '已全部安排',
  PARTIALLY_SHIPPED: '部分已发货', SHIPPED: '已发货', DELIVERED: '已签收', CANCELLED: '已取消', RETURNED: '已退回',
};
function viewOrder(row: Delivery): void {
  if (row.salesOrderId) void router.push(`/sales/orders/${encodeURIComponent(row.salesOrderId)}`);
}
async function load(): Promise<void> {
  const version = ++requestVersion;
  const factory = factoryId.value;
  rows.value = []; error.value = ''; loading.value = false;
  if (!factory) { total.value = 0; error.value = '请先选择工厂'; return; }
  loading.value = true;
  try {
    const response = await get<DeliveryPage>(`/${factory}/sales/deliveries`, { params: { page: page.value, size } });
    if (version !== requestVersion || factory !== factoryId.value) return;
    if (!response.success || !response.data || !Array.isArray(response.data.content) || !Number.isFinite(response.data.totalElements)) {
      throw new Error(response.message || '正式发货记录响应不完整，请重试');
    }
    rows.value = response.data.content;
    total.value = response.data.totalElements;
  } catch (reason) {
    if (version !== requestVersion || factory !== factoryId.value) return;
    error.value = reason instanceof Error ? reason.message : '正式发货记录加载失败，请重试';
  } finally {
    if (version === requestVersion && factory === factoryId.value) loading.value = false;
  }
}
function changePage(value: number): void { page.value = value; void load(); }
watch(factoryId, () => { page.value = 1; total.value = 0; void load(); }, { immediate: true });
onBeforeUnmount(() => { ++requestVersion; });
</script>
<template>
  <section class="cs-card">
    <header class="cs-card-head">
      <h2>正式发货记录</h2>
      <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="loading" @click="load">刷新</UButton>
    </header>
    <div class="body">
      <UAlert class="explanation" color="info" variant="subtle" title="母发货单记录计划，子发运单记录各次发运；单次发货单沿用原有流程。各行数量按产品和单位展示，不跨单合计。" />
      <div v-if="error" role="alert" class="load-error"><UAlert :title="error" color="error" variant="subtle" /><UButton color="neutral" variant="outline" @click="load">重试</UButton></div>
      <UTable v-else :data="rows" :columns="columns" :loading="loading" class="cs-table" data-testid="formal-deliveries-table">
        <template #empty>暂无正式发货记录</template>
        <template #role-cell="{ row }">
          <div>{{ roleLabel(row.original) }}</div><div v-if="row.original.shipmentSequence != null">第 {{ row.original.shipmentSequence }} 次发运</div>
        </template>
        <template #order-cell="{ row }">{{ row.original.orderNumber || row.original.salesOrderId || '未关联销售订单' }}</template>
        <template #customer-cell="{ row }">{{ row.original.customerName || row.original.customerId || '未提供' }}</template>
        <template #items-cell="{ row }">
          <div v-for="item in row.original.items || []" :key="item.id" class="item-line">{{ item.productName || '未提供产品名称' }}：{{ item.deliveredQuantity ?? '未提供数量' }} {{ item.unit || '未提供单位' }}</div><span v-if="!row.original.items?.length">未提供明细</span>
        </template>
        <template #status-cell="{ row }">{{ statuses[row.original.status] || row.original.status || '未提供' }}</template>
        <template #actions-cell="{ row }"><UButton color="primary" variant="link" size="xs" :disabled="!row.original.salesOrderId" @click="viewOrder(row.original)">查看销售订单</UButton></template>
      </UTable>
      <div v-if="!error" class="pager">
        <span class="count">共 {{ total }} 条</span>
        <UPagination :page="page" :items-per-page="size" :total="total" :disabled="loading" show-edges :sibling-count="1" @update:page="changePage" />
      </div>
    </div>
  </section>
</template>
<style scoped>
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: center; justify-content: space-between; padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.body { padding: var(--cs-space-4) var(--cs-space-5); }
.explanation { margin-bottom: var(--cs-space-4); }
.load-error { display: grid; gap: var(--cs-space-3); justify-items: start; }
.pager { display: flex; align-items: center; justify-content: flex-end; gap: var(--cs-space-3); margin-top: var(--cs-space-4); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.item-line + .item-line { margin-top: 6px; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 6px; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); vertical-align: middle; }
</style>
