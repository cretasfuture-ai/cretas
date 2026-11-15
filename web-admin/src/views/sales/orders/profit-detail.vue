<!--
  产品级利润详情: Nuxt UI 迁移第5批 (UTable / UBadge / USelect / UButton / UAlert) + --cs-*。
  价格敏感列(单价/成本/毛利/毛利率/折让/税额/净利润/历史均价/趋势)整体受 permissionStore.canViewPrice 控制, 无权限时列不渲染并显示提示。
-->
<template>
  <div class="profit-detail-page">
    <div class="cs-page-head">
      <div class="head-left">
        <UButton icon="i-lucide-arrow-left" variant="link" color="neutral" @click="goBack">返回订单详情</UButton>
        <h2>产品级利润详情</h2>
      </div>
      <div class="header-meta" v-if="orderNumber">
        <span class="meta-label">订单号：</span>
        <span class="meta-value">{{ orderNumber }}</span>
      </div>
    </div>

    <section class="cs-card">
      <div class="cs-card-head">
        <span class="filter-label">历史均价回溯</span>
        <USelect v-model="lookbackDays" :items="lookbackItems" class="w-40" data-testid="profit-lookback" @update:model-value="loadProfitDetail" />
        <UButton icon="i-lucide-refresh-cw" :loading="loading" @click="loadProfitDetail">刷新</UButton>
      </div>

      <UTable :data="rows" :columns="columns" :loading="loading" class="cs-table" data-testid="profit-detail-table">
        <template #empty>无数据</template>
        <template #productName-cell="{ row: { original: row } }">
          <span>{{ row.productName || '—' }}</span>
          <div class="product-meta" v-if="row.unit">{{ displayUnit(row.unit) }}</div>
        </template>
        <template #quantity-cell="{ row }"><span class="num">{{ fmtQty(row.original.quantity) }}</span></template>
        <template #unitPrice-cell="{ row }"><span class="num">{{ formatAmount(row.original.unitPrice) }}</span></template>
        <template #costUnitPrice-cell="{ row }"><span class="num">{{ formatAmount(row.original.costUnitPrice) }}</span></template>
        <template #grossProfit-cell="{ row }">
          <span class="num" :class="profitClass(row.original.grossProfit)">{{ formatAmount(row.original.grossProfit) }}</span>
        </template>
        <template #grossMarginPct-cell="{ row }">
          <span class="num" :class="marginClass(row.original.grossMarginPct)">{{ fmtPct(row.original.grossMarginPct) }}</span>
        </template>
        <template #discountAmount-cell="{ row }"><span class="num">{{ formatAmount(row.original.discountAmount) }}</span></template>
        <template #taxAmount-cell="{ row }"><span class="num">{{ formatAmount(row.original.taxAmount) }}</span></template>
        <template #netProfit-cell="{ row }">
          <strong class="num" :class="profitClass(row.original.netProfit)">{{ formatAmount(row.original.netProfit) }}</strong>
        </template>
        <template #historicalAvgPrice-cell="{ row }"><span class="num">{{ formatAmount(row.original.historicalAvgPrice) }}</span></template>
        <template #trend-cell="{ row }">
          <UBadge v-if="row.original.priceTrend === 'UP'" color="success" variant="soft">↑ 上涨</UBadge>
          <UBadge v-else-if="row.original.priceTrend === 'DOWN'" color="error" variant="soft">↓ 下跌</UBadge>
          <UBadge v-else-if="row.original.priceTrend === 'FLAT'" color="neutral" variant="soft">— 持平</UBadge>
          <span v-else>—</span>
        </template>
      </UTable>

      <div v-if="!canViewPrice" class="cs-card-body" data-testid="profit-price-hidden">
        <UAlert
          color="info"
          variant="subtle"
          icon="i-lucide-info"
          title="价格敏感信息已隐藏"
          description="当前角色无权查看单价、成本、利润等敏感字段。如需查看请联系管理员调整权限。"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { formatAmount, fmtQty } from '@/utils/tableFormatters';
import { displayUnit } from '@/utils/unitPricing';
import { handleCatchError } from '@/utils/errorToast';

interface SalesProductProfitRow {
  productTypeId: string;
  productName: string | null;
  unit: string | null;
  quantity: number | null;
  unitPrice: number | null;
  costUnitPrice: number | null;
  grossProfit: number | null;
  grossMarginPct: number | null;
  discountAmount: number | null;
  taxAmount: number | null;
  netProfit: number | null;
  historicalAvgPrice: number | null;
  priceTrend: 'UP' | 'DOWN' | 'FLAT' | null;
}

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
const orderId = computed(() => route.params.id as string);

const R = { class: { th: 'text-right', td: 'text-right' } };
// 价格敏感列与原 el-table-column v-if="canViewPrice" 同一判断: 无权限账号 DOM 里没有这些列
const columns = computed<TableColumn<SalesProductProfitRow>[]>(() => [
  { accessorKey: 'productName', header: '产品' },
  { accessorKey: 'quantity', header: '数量', meta: R },
  ...(canViewPrice.value
    ? [
        { accessorKey: 'unitPrice', header: '单价', meta: R },
        { accessorKey: 'costUnitPrice', header: '成本', meta: R },
        { accessorKey: 'grossProfit', header: '毛利', meta: R },
        { accessorKey: 'grossMarginPct', header: '毛利率', meta: R },
        { accessorKey: 'discountAmount', header: '折让', meta: R },
        { accessorKey: 'taxAmount', header: '税额', meta: R },
        { accessorKey: 'netProfit', header: '净利润', meta: R },
        { accessorKey: 'historicalAvgPrice', header: '历史均价', meta: R },
        { id: 'trend', header: '趋势' },
      ] as TableColumn<SalesProductProfitRow>[]
    : []),
]);


const loading = ref(false);
const lookbackDays = ref(90);
const lookbackItems = [30, 60, 90, 180].map((d) => ({ label: `近 ${d} 天`, value: d }));
const rows = ref<SalesProductProfitRow[]>([]);
const orderNumber = ref<string | null>(null);

async function loadProfitDetail() {
  if (!factoryId.value || !orderId.value) return;
  loading.value = true;
  try {
    // get() 返回 ApiResponse 信封 { success, data }; 迁移前这里把信封当数组判断, 表格永远是「无数据」
    const resp = await get<SalesProductProfitRow[]>(
      `/${factoryId.value}/reports/sales/profit-detail/${orderId.value}?lookbackDays=${lookbackDays.value}`
    );
    rows.value = Array.isArray(resp?.data) ? resp.data : [];
  } catch (err) {
    handleCatchError(err, '加载利润详情失败');
  } finally {
    loading.value = false;
  }
}

async function loadOrderNumber() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const resp = await get<{ orderNumber?: string }>(`/${factoryId.value}/sales/orders/${orderId.value}`);
    orderNumber.value = resp.data?.orderNumber ?? null;
  } catch {
    // 非关键路径 — 主表格已能渲染. 静默失败.
  }
}

function goBack() {
  router.push(`/sales/orders/${orderId.value}`);
}

function fmtPct(val: number | null | undefined): string {
  if (val === null || val === undefined) return '—';
  return `${Number(val).toFixed(2)}%`;
}

function profitClass(val: number | null | undefined): string {
  if (val === null || val === undefined) return '';
  if (val > 0) return 'text-positive';
  if (val < 0) return 'text-negative';
  return '';
}

function marginClass(val: number | null | undefined): string {
  if (val === null || val === undefined) return '';
  if (val >= 30) return 'text-positive';
  if (val < 10) return 'text-negative';
  return 'text-neutral';
}

onMounted(() => {
  loadOrderNumber();
  loadProfitDetail();
});
</script>

<style scoped>
.profit-detail-page { padding: var(--cs-space-4); }
.head-left { display: flex; align-items: center; gap: var(--cs-space-4); }
.head-left h2 { margin: 0; font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
.header-meta { color: var(--cs-muted); font-size: var(--cs-font); }
.meta-label { color: var(--cs-muted); }
.meta-value { font-weight: 600; color: var(--cs-text); }
.filter-label { font-weight: 500; color: var(--cs-muted); }
.product-meta { color: var(--cs-muted); font-size: var(--cs-font-small); margin-top: 2px; }
.text-positive { color: var(--cs-success); font-weight: 600; }
.text-negative { color: var(--cs-danger); font-weight: 600; }
.text-neutral { color: var(--cs-warning); }
</style>
