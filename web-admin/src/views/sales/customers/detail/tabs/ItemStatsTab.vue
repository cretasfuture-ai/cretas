<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 13 商品统计 (aggregation, 前端聚合).
  Group SalesOrder items by productTypeId, sum quantity + amount, sort by amount desc.
  RBAC: canViewPrice mask on salesAmount / avgPrice.
-->
<template>
  <div class="item-stats-tab">
    <div class="toolbar">
      <span class="title">商品统计 (基于近 {{ orderCount }} 个销售单)</span>
      <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="state === 'loading'" @click="fetchList">刷新</UButton>
    </div>

    <UAlert
      v-if="hitCap"
      color="warning"
      variant="subtle"
      icon="i-lucide-triangle-alert"
      :title="`聚合基于近 ${SCAN_CAP} 单 — 全量请用导出/报表系统`"
      class="cap-alert"
    />

    <div v-if="state === 'loading'" class="skeleton"><USkeleton v-for="i in 5" :key="i" class="h-9 w-full" /></div>
    <UEmpty v-else-if="state === 'empty'" icon="i-lucide-chart-column" description="暂无商品统计数据" />
    <UAlert v-else-if="state === 'error'" color="error" variant="subtle" :title="errorMsg" :actions="[{ label: '重试', color: 'error', variant: 'outline', onClick: fetchList }]" />

    <template v-else>
      <UTable v-model:sorting="sorting" :data="rows" :columns="columns" class="cs-table" data-testid="customer-item-stats-table">
        <template v-for="key in SORTABLE_IDS" :key="key" #[`${key}-header`]="{ column }">
          <button type="button" class="sort-th" @click="column.toggleSorting(column.getIsSorted() === 'asc')">
            {{ SORTABLE_LABELS[key] }}
            <UIcon :name="column.getIsSorted() === 'asc' ? 'i-lucide-arrow-up' : column.getIsSorted() === 'desc' ? 'i-lucide-arrow-down' : 'i-lucide-arrow-up-down'" class="sort-icon" :class="{ active: !!column.getIsSorted() }" />
          </button>
        </template>
        <template #totalQuantity-cell="{ row }"><span class="num">{{ formatNum(row.original.totalQuantity) }} {{ row.original.unit || '' }}</span></template>
        <template #salesAmount-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.salesAmount) }}</span>
          <span v-else class="masked">****</span>
        </template>
        <template #avgPrice-cell="{ row }">
          <span v-if="canViewPrice" class="num">{{ formatMoney(row.original.avgPrice) }}</span>
          <span v-else class="masked">****</span>
        </template>
      </UTable>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

const SCAN_CAP = 500;

interface OrderItem {
  productTypeId?: string;
  productName?: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
}
interface Order {
  id: string;
  orderDate?: string;
  items?: OrderItem[];
}
interface PageResp {
  content: Order[];
  totalElements: number;
}
interface StatsRow {
  productTypeId: string;
  productName: string;
  unit: string;
  totalQuantity: number;
  salesAmount: number;
  avgPrice: number;
  lastDate: string;
}

const props = defineProps<{ customerId: string }>();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);
const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const rows = ref<StatsRow[]>([]);
const orderCount = ref(0);
const hitCap = ref(false);
const R = { class: { th: 'text-right', td: 'text-right' } };
const SORTABLE_LABELS: Record<string, string> = { totalQuantity: '数量', salesAmount: '销售额', avgPrice: '均价', lastDate: '最近订单日期' };
const SORTABLE_IDS = Object.keys(SORTABLE_LABELS);
const sorting = ref<{ id: string; desc: boolean }[]>([{ id: 'salesAmount', desc: true }]);
const columns: TableColumn<StatsRow>[] = [
  { accessorKey: 'productName', header: '商品', enableSorting: false },
  { accessorKey: 'totalQuantity', id: 'totalQuantity', header: '数量', meta: R },
  { accessorKey: 'salesAmount', id: 'salesAmount', header: '销售额', meta: R },
  { accessorKey: 'avgPrice', id: 'avgPrice', header: '均价', meta: R },
  { accessorKey: 'lastDate', id: 'lastDate', header: '最近订单日期' },
];

function formatNum(v?: number): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}
function formatMoney(v?: number): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2 });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/sales/orders/by-customer`, {
      params: { customerId: props.customerId, page: 1, size: SCAN_CAP },
    });
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    const orders = res.data.content;
    orderCount.value = orders.length;
    hitCap.value = res.data.totalElements > SCAN_CAP;

    const groups = new Map<string, StatsRow>();
    for (const o of orders) {
      const items = o.items || [];
      for (const it of items) {
        const key = it.productTypeId || it.productName || '__unknown__';
        const qty = Number(it.quantity || 0);
        const price = Number(it.unitPrice || 0);
        const amount = qty * price;
        if (!groups.has(key)) {
          groups.set(key, {
            productTypeId: it.productTypeId || '',
            productName: it.productName || '—',
            unit: it.unit || '',
            totalQuantity: 0,
            salesAmount: 0,
            avgPrice: 0,
            lastDate: o.orderDate || '',
          });
        }
        const row = groups.get(key)!;
        row.totalQuantity += qty;
        row.salesAmount += amount;
        if (o.orderDate && (!row.lastDate || o.orderDate > row.lastDate)) {
          row.lastDate = o.orderDate;
        }
      }
    }
    // Compute avgPrice
    for (const r of groups.values()) {
      r.avgPrice = r.totalQuantity > 0 ? r.salesAmount / r.totalQuantity : 0;
    }
    rows.value = Array.from(groups.values()).sort((a, b) => b.salesAmount - a.salesAmount);
    state.value = rows.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const backendMsg = e?.response?.data?.message || e?.message;
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    console.error('[CustomerDetail/ItemStatsTab] aggregation failed:', e);
  }
}

onMounted(fetchList);
</script>

<style scoped>
.item-stats-tab { padding: 12px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
.masked { color: var(--cs-muted); font-family: monospace; letter-spacing: 2px; user-select: none; }
.num { font-variant-numeric: tabular-nums; }
.skeleton { display: grid; gap: 8px; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
.cap-alert { margin-bottom: 12px; }
.sort-th { border: 0; background: transparent; padding: 0; display: inline-flex; align-items: center; gap: 4px; font: inherit; color: inherit; cursor: pointer; }
.sort-icon { width: 12px; height: 12px; color: var(--cs-muted); opacity: 0.6; }
.sort-icon.active { color: var(--cs-primary); opacity: 1; }
</style>
