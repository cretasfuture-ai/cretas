<script setup lang="ts">
import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { PACKAGING_MISMATCH_CODE, confirmPackagingConversion, isPackagingMismatchError, mismatchConfirmLines } from '@/views/sales/packagingConversion';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
/* 组件全部是 Nuxt UI (UTable / UTabs / UModal / USlideover / UInput / USelectMenu / UBadge / UPagination) + --cs-*；弹层里不放 Element 控件。提示仍用 ElMessage；规格换算确认仍是 ElMessageBox（见 confirmWithPackagingCheck 的收起/还原）。 */
import { formatDateTime } from '@/utils/dateFormat';
import { handleCatchError } from '@/utils/errorToast';
import { emptyCell } from '@/utils/tableFormatters';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';
import { useNativeDemoAdapters } from '@/demo/f006NativeBridge';
import { commerceAdapter, entityFromResponse } from '@/demo/nativeCommerceSteps';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// Issue #740 (六扇门 May10): Tab 切换 — 默认进入新的"待销售发货单确认"queue.
//   legacy:  老 /shipments endpoint，只读展示存量记录
//   sales:   新 /warehouse/deliveries/pending (销售推过来的发货单待 confirm)
const activeTab = ref<'pending-sales' | 'legacy'>('pending-sales');

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  keyword: '',
  status: ''
});

// Issue #740: 销售推过来的待确认发货单 state.
const pendingLoading = ref(false);
const pendingData = ref<TableRow[]>([]);
const pendingPagination = ref({ page: 1, size: 10, total: 0 });

// confirm 弹窗 state
const confirmDialogVisible = ref(false);
const confirmDialogLoading = ref(false);
const confirmingDelivery = ref<TableRow | null>(null);
const confirmItems = ref<Array<{ id: string; productName: string; plannedQty: number; actualQty: number; unit: string; productTypeId: string; sourceWarehouseCode: string; batches: string[] }>>([]);

const R = { class: { th: 'text-right', td: 'text-right' } };
const C = { class: { th: 'text-center', td: 'text-center' } };
// 列很多（12 列）：操作列固定在右侧，不被横向滚动挤出视口（原 el-table fixed="right"）
const STICKY = { class: { th: 'sticky right-0 bg-default text-center', td: 'sticky right-0 bg-default text-center' } };
const dash = (v: unknown) => emptyCell(null, null, v);
const DELIVERY_METHOD_TEXT: Record<string, string> = { LOGISTICS: '物流配送', SELF_DELIVERY: '自送', SELF_PICKUP: '客户自提' };
const deliveryMethodText = (m: unknown) => DELIVERY_METHOD_TEXT[String(m)] || '历史未记录';
const pendingColumns = computed<TableColumn<TableRow>[]>(() => [
  { accessorKey: 'deliveryNumber', header: '发货单号' },
  { id: 'customer', header: '客户' },
  { accessorKey: 'deliveryDate', header: '计划发货日期' },
  { id: 'nextStep', header: '下一步' },
  { id: 'creator', header: '销售创建人' },
  { id: 'method', header: '配送方式' },
  { accessorKey: 'logisticsCompany', header: '物流公司' },
  { accessorKey: 'trackingNumber', header: '运单号' },
  { id: 'order', header: '销售订单' },
  ...(canViewPrice.value ? [{ id: 'amount', header: '金额', meta: R }] : []),
  { id: 'status', header: '状态', meta: C },
  { id: 'createdAt', header: '销售创建时间' },
  { id: 'actions', header: '操作', meta: STICKY },
]);
const legacyColumns = computed<TableColumn<TableRow>[]>(() => [
  { accessorKey: 'shipmentNumber', header: '出货单号' },
  { id: 'customerName', header: '客户名称' },
  { id: 'product', header: '产品' },
  { id: 'quantity', header: '数量', meta: R },
  { id: 'logistics', header: '物流/车牌' },
  ...(canViewPrice.value ? [{ id: 'amount', header: '单价/金额', meta: R }] : []),
  { id: 'status', header: '状态', meta: C },
  { id: 'createdAt', header: '创建时间' },
  { id: 'actions', header: '操作', meta: C },
]);
const confirmColumns: TableColumn<(typeof confirmItems.value)[number]>[] = [
  { accessorKey: 'productName', header: '产品' },
  { id: 'plannedQty', header: '计划数量', meta: R },
  { id: 'actualQty', header: '实际数量', meta: C },
  { id: 'batches', header: '分配批次' },
];
const pendingCount = computed(() => pendingPagination.value.total);
const tabItems = computed<TabsItem[]>(() => [
  { label: pendingCount.value > 0 ? `销售发货单待确认 ${pendingCount.value > 99 ? '99+' : pendingCount.value}` : '销售发货单待确认', value: 'pending-sales' },
  { label: '历史出货记录 (只读)', value: 'legacy' },
]);
const sizeItems = [10, 20, 50, 100].map((v) => ({ label: `${v} 条/页`, value: v }));
// Nuxt UI 选项值不能是空串：ALL 表示「全部状态」，回写 searchForm.status 仍是 ''
const legacyStatusItems = [
  { label: '全部状态', value: 'ALL' },
  { label: '待发货', value: 'pending' },
  { label: '运输中', value: 'shipped' },
  { label: '已送达', value: 'delivered' },
  { label: '已退货', value: 'returned' },
  { label: '已取消', value: 'cancelled' },
];
const legacyStatusModel = computed({
  get: () => searchForm.value.status || 'ALL',
  set: (v: string) => { searchForm.value.status = v === 'ALL' ? '' : v; },
});
const pendingPageModel = computed({ get: () => pendingPagination.value.page, set: (v: number) => handlePendingPageChange(v) });
const pendingSizeModel = computed({ get: () => pendingPagination.value.size, set: (v: number) => handlePendingSizeChange(v) });
const pageModel = computed({ get: () => pagination.value.page, set: (v: number) => handlePageChange(v) });
const sizeModel = computed({ get: () => pagination.value.size, set: (v: number) => handleSizeChange(v) });
// el-input-number 原本会把实发数量夹在 [0, 计划数量]；原生数字框不夹，失焦时补上
function clampActualQty(it: { actualQty: number; plannedQty: number }) {
  const v = Number(it.actualQty);
  if (!Number.isFinite(v)) { it.actualQty = it.plannedQty; return; }
  it.actualQty = Math.min(Math.max(v, 0), it.plannedQty);
}

onMounted(() => {
  // Issue #740: 默认加载销售待确认 queue
  loadPendingSalesDeliveries();
  loadData();
});

// ==================== Issue #740: 销售发货单待 confirm queue ====================

async function loadPendingSalesDeliveries() {
  if (!factoryId.value) return;
  pendingLoading.value = true;
  try {
    const response = await get(`/${factoryId.value}/warehouse/deliveries/pending`, {
      params: {
        page: pendingPagination.value.page,
        size: pendingPagination.value.size,
      }
    });
    if (response.success && response.data) {
      pendingData.value = response.data.content || [];
      pendingPagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载待确认发货单失败');
    }
  } catch (error) {
    console.error('加载待确认发货单失败:', error);
  } finally {
    pendingLoading.value = false;
  }
}

function handlePendingPageChange(page: number) {
  pendingPagination.value.page = page;
  loadPendingSalesDeliveries();
}

function handlePendingSizeChange(size: number) {
  pendingPagination.value.size = size;
  pendingPagination.value.page = 1;
  loadPendingSalesDeliveries();
}

async function openConfirmDialog(row: TableRow) {
  confirmingDelivery.value = row;
  // 拉取明细
  try {
    const res = await get(`/${factoryId.value}/sales/deliveries/${row.id}`);
    if (!res.success || !res.data) {
      ElMessage.error('加载发货单明细失败');
      return;
    }
    const items = Array.isArray(res.data.items) ? res.data.items : [];
    confirmItems.value = await Promise.all(items.map(async (it: TableRow) => {
      const id = String(it.id || '');
      let batches: string[] = [];
      if (id) {
        try {
          const allocationRes = await get<TableRow[]>(`/${factoryId.value}/sales-deliveries/items/${id}/batch-allocations`);
          if (allocationRes.success && Array.isArray(allocationRes.data)) {
            batches = allocationRes.data.map((row) =>
              `${String(row.batchNumber || row.finishedGoodsBatchId || '批次未标注')} · ${Number(row.allocatedQty || 0)} ${String(row.unit || it.unit || '')}`,
            );
          }
        } catch { /* automatic FIFO may allocate only during confirmation */ }
      }
      return {
        id,
        productName: String(it.productName || `产品-${it.productTypeId || ''}`),
        plannedQty: Number(it.deliveredQuantity || 0),
        actualQty: Number(it.deliveredQuantity || 0),
        unit: String(it.unit || 'kg'),
        productTypeId: String(it.productTypeId || ''),
        sourceWarehouseCode: String(it.sourceWarehouseCode || ''),
        batches,
      };
    }));
    confirmDialogVisible.value = true;
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    console.error('加载发货单明细失败:', e);
    handleCatchError(e, '加载发货单明细失败');
  }
}

async function submitConfirmDelivery() {
  if (!confirmingDelivery.value) return;
  // 校验 actualQty
  for (const it of confirmItems.value) {
    if (it.actualQty == null || it.actualQty < 0) {
      ElMessage.warning(`${it.productName} 实际发货数量不能为负`);
      return;
    }
  }
  const actualQuantities: Record<string, number> = {};
  for (const it of confirmItems.value) {
    if (it.id) actualQuantities[it.id] = it.actualQty;
  }
  confirmDialogLoading.value = true;
  try {
    const res = await confirmWithPackagingCheck(actualQuantities);
    if (res === null) return;
    if (res.success) {
      ElMessage.success('出库完成，成品库存已扣减');
      confirmDialogVisible.value = false;
      confirmingDelivery.value = null;
      loadPendingSalesDeliveries();
    } else {
      ElMessage.error(res.message || '发货确认失败');
    }
  } catch (e) {
    // interceptor 已显示具体错误 (e.g. 批次未分配 409)
    console.error('发货确认失败:', e);
  } finally {
    confirmDialogLoading.value = false;
    // 成功会把 confirmingDelivery 置空；没成功（取消换算确认 / 分配或确认失败）就把弹窗还给用户
    if (confirmingDelivery.value) confirmDialogVisible.value = true;
  }
}

useNativeDemoAdapters('warehouse-shipments-list', [
  commerceAdapter('sales-warehouse-confirm', () => canWrite.value, async ({ state, ui }) => {
    const response = await ui.capture({ method: 'POST', path: /\/warehouse\/deliveries\/[^/]+\/confirm/ }, () => ui.click('[data-testid="warehouse-confirm-delivery"]', '原生确认出库'));
    await ui.hold('仓库确认出库后');
    const confirmed = entityFromResponse(response);
    const entity = entityFromResponse(await ui.read(`/sales/deliveries/${confirmed?.id || state.entities.salesShipment?.id || confirmingDelivery.value?.id || ''}`));
    if (entity) ui.remember('salesShipment', entity);
    return entity;
  }, async ({ state, context }) => {
    const shipmentId = state.entities.salesShipment?.id;
    const row = pendingData.value.find(candidate => String(candidate.id) === String(shipmentId))
      || tableData.value.find(candidate => String(candidate.id) === String(shipmentId));
    if (!row) throw new Error('未找到本次销售发运单');
    await openConfirmDialog(row);
    if (!confirmingDelivery.value) throw new Error('原生出库确认弹窗未打开');
    for (const item of confirmItems.value) item.actualQty = Number(context.finishedQuantity);
  }),
])

function postDeliveryConfirm(actualQuantities: Record<string, number>) {
  if (!confirmingDelivery.value) return Promise.resolve(null);
  return post(
    `/${factoryId.value}/warehouse/deliveries/${confirmingDelivery.value.id}/confirm`,
    { actualQuantities },
    { _handledErrorCodes: [PACKAGING_MISMATCH_CODE] },
  );
}

/**
 * O-309-1: 确认发货时系统不会自动换算规格不一致的货。后端拦下来后, 在这里把每行要用的批次和两边规格
 * 列给仓管确认, 确认后写分配 (带确认标记) 再发一次。取消 = 返回 null, 什么都没发。
 */
async function confirmWithPackagingCheck(actualQuantities: Record<string, number>) {
  try {
    return await postDeliveryConfirm(actualQuantities);
  } catch (e) {
    if (!isPackagingMismatchError(e)) throw e;
  }
  const plans: Array<{ item: (typeof confirmItems.value)[number]; rows: TableRow[] }> = [];
  for (const item of confirmItems.value) {
    if (!item.id || !item.productTypeId || item.actualQty <= 0) continue;
    const res = await get<TableRow[]>(`/${factoryId.value}/sales-deliveries/items/${item.id}/batch-allocations/recommend-fifo`, {
      params: {
        productTypeId: item.productTypeId,
        requiredQty: item.actualQty,
        unit: item.unit,
        sourceWarehouseCode: item.sourceWarehouseCode || undefined,
      },
    });
    const rows = res.success && Array.isArray(res.data) ? res.data : [];
    if (rows.some((row) => row.packagingMismatch === true)) plans.push({ item, rows });
  }
  const lines = plans.flatMap(({ item, rows }) => mismatchConfirmLines(item.productName, rows.map((row) => ({
    batchNumber: String(row.batchNumber || ''),
    allocatedQty: Number(row.recommendedQuantity || 0),
    unit: String(row.unit || item.unit),
    packagingMismatch: row.packagingMismatch === true,
    batchPackagingLabel: String(row.batchPackagingLabel || ''),
    linePackagingLabel: String(row.linePackagingLabel || ''),
  }))));
  if (lines.length === 0) {
    ElMessage.error('库存已变动，请关闭后重新打开这一单');
    return null;
  }
  // 模态 UModal 打开时 body 不可点，ElMessageBox 会点不了：先收起确认弹窗，问完（或取消）后由 submitConfirmDelivery 的 finally 按需还原
  confirmDialogVisible.value = false;
  if (!(await confirmPackagingConversion(lines))) return null;
  for (const { item, rows } of plans) {
    await post(`/${factoryId.value}/sales-deliveries/items/${item.id}/batch-allocations`, {
      allocations: rows.map((row) => ({
        finishedGoodsBatchId: String(row.batchId),
        allocatedQty: Number(row.recommendedQuantity || 0),
        ...(row.packagingMismatch === true ? { packagingConversionConfirmed: true } : {}),
      })),
    });
  }
  return postDeliveryConfirm(actualQuantities);
}

function getDeliveryStatusType(status: string) {
  const map: Record<string, string> = {
    DRAFT: 'info',
    PENDING_WAREHOUSE_CONFIRM: 'warning',
    PICKED: '',
    SHIPPED: 'warning',
    DELIVERED: 'success',
    RETURNED: 'danger',
  };
  return map[status?.toUpperCase()] || 'info';
}

function getDeliveryStatusText(status: string) {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    PENDING_WAREHOUSE_CONFIRM: '待仓库确认',
    PICKED: '已拣货',
    SHIPPED: '已发货',
    DELIVERED: '已签收',
    RETURNED: '已退回',
  };
  return map[status?.toUpperCase()] || status || '-';
}

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/shipments`, {
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        keyword: searchForm.value.keyword || undefined,
        status: searchForm.value.status || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchForm.value = { keyword: '', status: '' };
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

// 详情抽屉
const detailVisible = ref(false);
const detailRow = ref<TableRow | null>(null);

function showDetail(row: TableRow) {
  detailRow.value = row;
  detailVisible.value = true;
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    PENDING: 'info',
    SHIPPED: 'warning',
    DELIVERED: 'success',
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待发货',
    SHIPPED: '运输中',
    DELIVERED: '已送达',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status || '-';
}
</script>

<template>
  <div class="page-wrapper" data-testid="warehouse-shipments">
    <ConceptDisambiguationAlert
      here-name="出货管理"
      here="把成品发给客户的物流操作（车牌号、司机、批次分配）"
      other-name="销售管理 → 销售订单"
      other="客户下的销售订单（金额、收款）。出货是销售订单的物流执行环节"
      other-path="/sales/orders"
    />
    <section class="cs-card">
      <header class="cs-card-head">
        <span class="page-title">出货管理</span>
        <span v-if="activeTab === 'legacy'" class="data-count">共 {{ pagination.total }} 条记录</span>
        <span v-else class="data-count">共 {{ pendingPagination.total }} 条待确认</span>
      </header>

      <!--
        Issue #740 (六扇门 May10): 仓库出货管理重构为两 tab.
          tab 1 (default):  销售推过来的待确认发货单 → 填实发数量 + 扣库存
          tab 2 (legacy):   老 /shipments endpoint 历史数据，只读
      -->
      <UTabs v-model="activeTab" :items="tabItems" :content="false" variant="link" class="px-4" data-testid="shipments-tabs" />

      <div class="tab-body">
        <template v-if="activeTab === 'pending-sales'">
          <UAlert
            color="info"
            variant="subtle"
            icon="i-lucide-info"
            title="销售创建的发货任务列在这里。仓管只需核对实发数量并确认出库；客户已有库存会自动匹配本销售订单预留的精确批次。"
            class="mb-3"
          />

          <UTable :data="pendingData" :columns="pendingColumns" :loading="pendingLoading" class="cs-table" data-testid="shipments-pending-table">
            <template #empty><ListEmptyExplain empty-title="没有待确认的发货单" empty-hint="销售订单发货后生成的发货单会在这里等待确认；全部确认完毕时为空。" /></template>
            <template #deliveryNumber-cell="{ row }">{{ dash(row.original.deliveryNumber) }}</template>
            <template #customer-cell="{ row }">{{ row.original.customer?.name || row.original.customerName || row.original.customerId || '-' }}</template>
            <template #deliveryDate-cell="{ row }">{{ dash(row.original.deliveryDate) }}</template>
            <template #nextStep-cell>仓管核量并出库</template>
            <template #creator-cell="{ row }">用户 #{{ row.original.shippedBy || '-' }}</template>
            <template #method-cell="{ row }">{{ deliveryMethodText(row.original.deliveryMethod) }}</template>
            <template #logisticsCompany-cell="{ row }">{{ dash(row.original.logisticsCompany) }}</template>
            <template #trackingNumber-cell="{ row }">{{ dash(row.original.trackingNumber) }}</template>
            <template #order-cell="{ row }">{{ row.original.orderNumber || row.original.salesOrder?.orderNumber || row.original.salesOrderId || '-' }}</template>
            <template #amount-cell="{ row }"><span class="num">{{ row.original.totalAmount ? `¥${row.original.totalAmount}` : '-' }}</span></template>
            <template #status-cell="{ row }">
              <UBadge :color="badgeColor(getDeliveryStatusType(row.original.status) as ElementTagType)" variant="soft">
                {{ getDeliveryStatusText(row.original.status) }}
              </UBadge>
            </template>
            <template #createdAt-cell="{ row }">{{ formatDateTime(row.original.createdAt) }}</template>
            <template #actions-cell="{ row }">
              <div class="row-actions">
                <UButton v-if="canWrite" size="xs" icon="i-lucide-check" data-testid="shipments-open-confirm" @click="openConfirmDialog(row.original)">确认并出库</UButton>
              </div>
            </template>
          </UTable>

          <div class="pagination-wrapper">
            <span class="data-count">共 {{ pendingPagination.total }} 条</span>
            <USelectMenu v-model="pendingSizeModel" value-key="value" :items="sizeItems" :search-input="false" class="w-28" />
            <UPagination v-model:page="pendingPageModel" :items-per-page="pendingPagination.size" :total="pendingPagination.total" show-edges :sibling-count="1" />
          </div>
        </template>

        <template v-else>
          <UAlert
            color="warning"
            variant="subtle"
            icon="i-lucide-triangle-alert"
            title="旧出货台账已冻结，仅供历史查询；新发货流程：① 在销售订单中创建发货单 → ② 点「新建分批发运」生成子发运单 → ③ 在左侧「销售发货单待确认」页完成仓库确认。"
            class="mb-3"
          />

          <div class="search-bar">
            <UInput v-model="searchForm.keyword" icon="i-lucide-search" placeholder="搜索出货单号/客户名称" class="w-70" data-testid="shipments-keyword" @keyup.enter="handleSearch">
              <template v-if="searchForm.keyword" #trailing>
                <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="searchForm.keyword = ''" />
              </template>
            </UInput>
            <USelectMenu v-model="legacyStatusModel" value-key="value" :items="legacyStatusItems" :search-input="false" class="w-40" data-testid="shipments-status" />
            <UButton icon="i-lucide-search" @click="handleSearch">搜索</UButton>
            <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" @click="handleRefresh">重置</UButton>
          </div>

          <UTable :data="tableData" :columns="legacyColumns" :loading="loading" class="cs-table" data-testid="shipments-legacy-table">
            <template #empty><ListEmptyExplain empty-title="还没有出货记录" empty-hint="在销售订单里点「发货」后，出货记录会出现在这里。" /></template>
            <template #shipmentNumber-cell="{ row }">{{ dash(row.original.shipmentNumber) }}</template>
            <template #customerName-cell="{ row }">{{ row.original.customerName || row.original.customerId || '-' }}</template>
            <template #product-cell="{ row }">{{ row.original.productName || row.original.productBatchNumber || '-' }}</template>
            <template #quantity-cell="{ row }"><span class="num">{{ dash(row.original.quantity) }}</span></template>
            <template #logistics-cell="{ row }">{{ row.original.logisticsCompany || row.original.vehicleNumber || row.original.trackingNumber || '-' }}</template>
            <template #amount-cell="{ row }"><span class="num">{{ row.original.totalAmount ? `¥${row.original.totalAmount}` : row.original.unitPrice ? `¥${row.original.unitPrice}/${row.original.unit || ''}` : '-' }}</span></template>
            <template #status-cell="{ row }">
              <UBadge :color="badgeColor(getStatusType(row.original.status) as ElementTagType)" variant="soft">
                {{ getStatusText(row.original.status) }}
              </UBadge>
            </template>
            <template #createdAt-cell="{ row }">{{ formatDateTime(row.original.createdAt) }}</template>
            <template #actions-cell="{ row }">
              <div class="row-actions">
                <UButton size="xs" variant="link" @click="showDetail(row.original)">查看</UButton>
              </div>
            </template>
          </UTable>

          <div class="pagination-wrapper">
            <span class="data-count">共 {{ pagination.total }} 条</span>
            <USelectMenu v-model="sizeModel" value-key="value" :items="sizeItems" :search-input="false" class="w-28" />
            <UPagination v-model:page="pageModel" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" />
          </div>
        </template>
      </div>
    </section>

    <!--
      Issue #740: 仓库 confirm 弹窗 — 填实发数量, 确认后扣库存 + 转 SHIPPED.
      客户原话: "比如说我可能计划发发一版, 那但是可能车或者是没装下什么的, 各种各样的因素,
                然后他可能就发了八十, 然后他确认以后呢, 他就可以直接点打印"
      规格不一致要弹 ElMessageBox 确认（见 confirmWithPackagingCheck）：模态 UModal 会让 body 不可点，
      所以那一步先收起本弹窗，确认完再按需打开。
    -->
    <UModal v-model:open="confirmDialogVisible" title="确认发货 (填实际数量)" description="核对每行实际发货数量后确认出库" :dismissible="false" :ui="{ content: 'max-w-[680px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <template v-if="confirmingDelivery">
          <dl class="summary">
            <div><dt>发货单号</dt><dd>{{ confirmingDelivery.deliveryNumber }}</dd></div>
            <div><dt>客户</dt><dd>{{ confirmingDelivery.customer?.name || confirmingDelivery.customerName || confirmingDelivery.customerId || '-' }}</dd></div>
            <div><dt>销售订单</dt><dd>{{ confirmingDelivery.orderNumber || confirmingDelivery.salesOrder?.orderNumber || confirmingDelivery.salesOrderId || '-' }}</dd></div>
            <div><dt>计划日期</dt><dd>{{ confirmingDelivery.deliveryDate || '-' }}</dd></div>
            <div><dt>销售创建人</dt><dd>用户 #{{ confirmingDelivery.shippedBy || '-' }}</dd></div>
            <div><dt>配送方式</dt><dd>{{ deliveryMethodText(confirmingDelivery.deliveryMethod) }}</dd></div>
            <div><dt>物流公司</dt><dd>{{ confirmingDelivery.logisticsCompany || '-' }}</dd></div>
            <div><dt>运单号</dt><dd>{{ confirmingDelivery.trackingNumber || '-' }}</dd></div>
          </dl>

          <UAlert
            color="warning"
            variant="subtle"
            icon="i-lucide-triangle-alert"
            title="请填写每行实际发货数量 (默认 = 计划数量, 可少填以记录短发). 确认后系统将按已提前分配的批次扣减成品库存; 未提前分配的, 由系统按 FIFO 自动选择批次并记录, 无需先去手动分配。"
            class="my-3"
          />

          <UTable :data="confirmItems" :columns="confirmColumns" class="cs-table" data-testid="shipments-confirm-items">
            <template #plannedQty-cell="{ row }"><span class="num">{{ row.original.plannedQty }} {{ row.original.unit }}</span></template>
            <template #actualQty-cell="{ row }">
              <div class="qty-cell">
                <UInput
                  v-model.number="row.original.actualQty"
                  type="number"
                  :min="0"
                  :max="row.original.plannedQty"
                  step="0.01"
                  size="sm"
                  class="w-28"
                  data-testid="shipments-actual-qty"
                  @blur="clampActualQty(row.original)"
                />
                <span>{{ row.original.unit }}</span>
              </div>
            </template>
            <template #batches-cell="{ row }">
              <div v-if="row.original.batches.length"><div v-for="batch in row.original.batches" :key="batch">{{ batch }}</div></div>
              <span v-else>确认时按 FIFO 自动记录</span>
            </template>
          </UTable>
        </template>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="confirmDialogVisible = false">取消</UButton>
        <UButton data-testid="warehouse-confirm-delivery" :loading="confirmDialogLoading" @click="submitConfirmDelivery">
          确认并扣库存
        </UButton>
      </template>
    </UModal>

    <!-- 出货详情抽屉 -->
    <USlideover v-model:open="detailVisible" side="right" title="出货详情" description="历史出货记录详情（只读）" :ui="{ content: 'cs-slide', description: 'sr-only' }">
      <template #body>
        <dl v-if="detailRow" class="detail-list">
          <div><dt>出货单号</dt><dd>{{ detailRow.shipmentNumber || '-' }}</dd></div>
          <div><dt>客户名称</dt><dd>{{ detailRow.customerName || '-' }}</dd></div>
          <div><dt>产品批次</dt><dd>{{ detailRow.productBatchNumber || '-' }}</dd></div>
          <div><dt>数量</dt><dd>{{ detailRow.quantity ?? '-' }}</dd></div>
          <div>
            <dt>状态</dt>
            <dd><UBadge :color="badgeColor(getStatusType(detailRow.status) as ElementTagType)" variant="soft">{{ getStatusText(detailRow.status) }}</UBadge></dd>
          </div>
          <div><dt>车牌号</dt><dd>{{ detailRow.vehicleNumber || '-' }}</dd></div>
          <div><dt>司机</dt><dd>{{ detailRow.driverName || '-' }}</dd></div>
          <div><dt>司机电话</dt><dd>{{ detailRow.driverPhone || '-' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatDateTime(detailRow.createdAt) }}</dd></div>
          <div><dt>备注</dt><dd>{{ detailRow.notes || '-' }}</dd></div>
        </dl>
      </template>
    </USlideover>
  </div>
</template>

<style scoped>
.page-wrapper { display: flex; flex-direction: column; gap: var(--cs-space-3); padding: var(--cs-space-4); width: 100%; }
.cs-card-head { padding-block: var(--cs-space-4); align-items: baseline; }
.page-title { font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.data-count { font-size: var(--cs-font-small); color: var(--cs-muted); font-weight: 400; }
.tab-body { padding: var(--cs-space-3) var(--cs-space-5) var(--cs-space-4); }
.search-bar { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; margin-bottom: var(--cs-space-3); }
.row-actions { display: flex; justify-content: center; gap: var(--cs-space-1); }
.pagination-wrapper { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding-top: var(--cs-space-4); }
.qty-cell { display: flex; align-items: center; gap: var(--cs-space-1); justify-content: center; }
.summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-3); margin: 0; padding: var(--cs-space-3) var(--cs-space-4); background: var(--cs-surface-subtle); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.summary dt, .detail-list dt { font-size: var(--cs-font-small); color: var(--cs-muted); }
.summary dd, .detail-list dd { margin: 2px 0 0; color: var(--cs-text); }
.detail-list { display: flex; flex-direction: column; gap: var(--cs-space-3); margin: 0; }
</style>
