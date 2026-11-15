<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';

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
  <el-card>
    <template #header><div class="header"><span>正式发货记录</span><el-button :loading="loading" @click="load">刷新</el-button></div></template>
    <el-alert class="explanation" type="info" :closable="false" title="母发货单记录计划，子发运单记录各次发运；单次发货单沿用原有流程。各行数量按产品和单位展示，不跨单合计。" />
    <div v-if="error" role="alert" class="load-error"><el-alert :title="error" type="error" :closable="false" /><el-button @click="load">重试</el-button></div>
    <el-table v-else :data="rows" v-loading="loading" row-key="id" border stripe empty-text="暂无正式发货记录">
      <el-table-column prop="deliveryNumber" label="发货单号" min-width="180" />
      <el-table-column label="记录类型" min-width="150"><template #default="{ row }">
        <div>{{ roleLabel(row) }}</div><div v-if="row.shipmentSequence != null">第 {{ row.shipmentSequence }} 次发运</div>
      </template></el-table-column>
      <el-table-column label="销售订单" min-width="170"><template #default="{ row }">{{ row.orderNumber || row.salesOrderId || '未关联销售订单' }}</template></el-table-column>
      <el-table-column label="客户" min-width="130"><template #default="{ row }">{{ row.customerName || row.customerId || '未提供' }}</template></el-table-column>
      <el-table-column label="产品 / 单据数量" min-width="220"><template #default="{ row }">
        <div v-for="item in row.items || []" :key="item.id" class="item-line">{{ item.productName || '未提供产品名称' }}：{{ item.deliveredQuantity ?? '未提供数量' }} {{ item.unit || '未提供单位' }}</div><span v-if="!row.items?.length">未提供明细</span>
      </template></el-table-column>
      <el-table-column label="状态" min-width="130"><template #default="{ row }">{{ statuses[row.status] || row.status || '未提供' }}</template></el-table-column>
      <el-table-column prop="deliveryDate" label="发货日期" min-width="120" />
      <el-table-column label="操作" width="130" fixed="right"><template #default="{ row }"><el-button type="primary" link :disabled="!row.salesOrderId" @click="viewOrder(row)">查看销售订单</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="!error" :current-page="page" :page-size="size" :total="total" :disabled="loading" layout="total, prev, pager, next" class="pagination" @current-change="changePage" />
  </el-card>
</template>
<style scoped>
.header { display: flex; align-items: center; justify-content: space-between; }
.explanation { margin-bottom: 16px; }
.load-error { display: grid; gap: 12px; justify-items: start; }
.pagination { margin-top: 20px; justify-content: flex-end; }
.item-line + .item-line { margin-top: 6px; }
</style>
