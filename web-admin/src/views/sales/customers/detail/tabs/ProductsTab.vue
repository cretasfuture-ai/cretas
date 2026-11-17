<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 10 产品 (aggregation, 前端聚合).
  Fetch orders + group items by productTypeId, count distinct, list specs.
  Capped at 500 orders to avoid memory bloat.
-->
<template>
  <div class="products-tab">
    <div class="toolbar">
      <span class="title">产品 (基于近 {{ orderCount }} 个销售单聚合)</span>
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
    <UEmpty v-else-if="state === 'empty'" icon="i-lucide-package" description="该客户暂无购买产品记录" />
    <UAlert v-else-if="state === 'error'" color="error" variant="subtle" :title="errorMsg" :actions="[{ label: '重试', color: 'error', variant: 'outline', onClick: fetchList }]" />

    <template v-else>
      <UTable v-model:sorting="sorting" :data="rows" :columns="columns" class="cs-table" data-testid="customer-products-table">
        <template v-for="key in SORTABLE_IDS" :key="key" #[`${key}-header`]="{ column }">
          <button type="button" class="sort-th" @click="column.toggleSorting(column.getIsSorted() === 'asc')">
            {{ SORTABLE_LABELS[key] }}
            <UIcon :name="column.getIsSorted() === 'asc' ? 'i-lucide-arrow-up' : column.getIsSorted() === 'desc' ? 'i-lucide-arrow-down' : 'i-lucide-arrow-up-down'" class="sort-icon" :class="{ active: !!column.getIsSorted() }" />
          </button>
        </template>
        <template #totalQuantity-cell="{ row }"><span class="num">{{ formatNum(row.original.totalQuantity) }} {{ row.original.unit || '' }}</span></template>
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
import { get } from '@/api/request';

const SCAN_CAP = 500;

interface OrderItem {
  productTypeId?: string;
  productName?: string;
  quantity?: number;
  unit?: string;
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
interface ProductRow {
  productTypeId: string;
  productName: string;
  unit: string;
  orderCount: number;
  totalQuantity: number;
  firstDate: string;
  lastDate: string;
}

const props = defineProps<{ customerId: string }>();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const rows = ref<ProductRow[]>([]);
const orderCount = ref(0);
const hitCap = ref(false);
const R = { class: { th: 'text-right', td: 'text-right' } };
const SORTABLE_LABELS: Record<string, string> = { orderCount: '购买订单数', totalQuantity: '累计数量', firstDate: '首次购买', lastDate: '最近购买' };
const SORTABLE_IDS = Object.keys(SORTABLE_LABELS);
const sorting = ref<{ id: string; desc: boolean }[]>([{ id: 'orderCount', desc: true }]);
const columns: TableColumn<ProductRow>[] = [
  { accessorKey: 'productName', header: '产品名', enableSorting: false },
  { accessorKey: 'orderCount', id: 'orderCount', header: '购买订单数', meta: R },
  { accessorKey: 'totalQuantity', id: 'totalQuantity', header: '累计数量', meta: R },
  { accessorKey: 'firstDate', id: 'firstDate', header: '首次购买' },
  { accessorKey: 'lastDate', id: 'lastDate', header: '最近购买' },
];

function formatNum(v?: number): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
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

    // Group items by productTypeId (or productName if id missing)
    const groups = new Map<string, ProductRow>();
    for (const o of orders) {
      const items = o.items || [];
      const seenInThisOrder = new Set<string>();
      for (const it of items) {
        const key = it.productTypeId || it.productName || '__unknown__';
        if (!groups.has(key)) {
          groups.set(key, {
            productTypeId: it.productTypeId || '',
            productName: it.productName || '—',
            unit: it.unit || '',
            orderCount: 0,
            totalQuantity: 0,
            firstDate: o.orderDate || '',
            lastDate: o.orderDate || '',
          });
        }
        const row = groups.get(key)!;
        row.totalQuantity += Number(it.quantity || 0);
        if (!seenInThisOrder.has(key)) {
          row.orderCount += 1;
          seenInThisOrder.add(key);
        }
        if (o.orderDate) {
          if (!row.firstDate || o.orderDate < row.firstDate) row.firstDate = o.orderDate;
          if (!row.lastDate || o.orderDate > row.lastDate) row.lastDate = o.orderDate;
        }
      }
    }
    rows.value = Array.from(groups.values()).sort((a, b) => b.orderCount - a.orderCount);
    state.value = rows.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const backendMsg = e?.response?.data?.message || e?.message;
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    console.error('[CustomerDetail/ProductsTab] aggregation failed:', e);
  }
}

onMounted(fetchList);
</script>

<style scoped>
.products-tab { padding: 12px 0; }
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
