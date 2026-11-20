<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { post } from '@/api/request';
import echarts from '@/utils/echarts';
import type { ECharts } from 'echarts/core';
import ShipmentSkuMargin from './ShipmentSkuMargin.vue';
import type { CsTableColumn } from '@/types/csTable';
import type { CsBadgeColor } from '@/utils/csBadge';
import { useRoute } from 'vue-router';
import { readMarginPeriod } from './shipmentMarginNavigation';
import ChartSortControl from '@/components/common/ChartSortControl.vue';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { sortChartRows, type ChartSortMode } from '@/utils/chartSort';
import { buildSkuMarginInsight, skuTopRows, r1 } from '@/utils/chartPageInsights';
import {
  readSkuMarginResponse,
  fmtPct,
  fmtMoney,
  NA,
  type SkuMarginRow,
  type SkuMarginPeriod,
} from './skuMarginData';

// ---------- auth ----------
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ---------- state ----------
const loading = ref(false);
const tableData = ref<SkuMarginRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const dateRange = ref<[string, string] | null>(readMarginPeriod(route.query));
const shipmentRefresh = ref(0);
watch(() => [route.query.periodStart, route.query.periodEnd], () => {
  dateRange.value = readMarginPeriod(route.query);
  handleSearch();
});
type SortKey = 'quantity' | 'realizedRevenue' | 'realizedCost' | 'realizedMargin'
  | 'catalogPrice' | 'catalogUnitCost' | 'catalogMargin';
// 排序: 表头点击写 sorting (UTable v-model:sorting), 实际排序仍由 paginatedData 自己做 (null 永远排最后)
const sorting = ref<{ id: string; desc: boolean }[]>([{ id: 'realizedMargin', desc: true }]);
const sortField = computed<SortKey>(() => {
  const id = sorting.value[0]?.id ?? '';
  return (id in SORT_VALUE ? id : 'realizedMargin') as SortKey;
});
const sortOrder = computed<'ascending' | 'descending'>(() => (sorting.value.length === 0 || sorting.value[0].desc ? 'descending' : 'ascending'));
// 日期区间: 两个原生 date 输入, 两端都填好才触发查询
const startStr = ref(dateRange.value?.[0] ?? '');
const endStr = ref(dateRange.value?.[1] ?? '');
watch(dateRange, (v) => { startStr.value = v?.[0] ?? ''; endStr.value = v?.[1] ?? ''; });
function onDateInput() {
  if (startStr.value && endStr.value) {
    dateRange.value = [startStr.value, endStr.value];
    handleSearch();
  } else if (!startStr.value && !endStr.value && dateRange.value) {
    dateRange.value = null;
    handleSearch();
  }
}
function setRangeDays(days: number) {
  const e = new Date();
  const s = new Date();
  s.setDate(s.getDate() - days);
  const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  dateRange.value = [fmt(s), fmt(e)];
  handleSearch();
}
const referenceOpen = ref(false);
function toggleReference() {
  referenceOpen.value = !referenceOpen.value;
  if (referenceOpen.value) void nextTick(initChart);
}
const PAGE_SIZE_ITEMS = [10, 20, 50, 100].map((n) => ({ label: `${n} 条/页`, value: n }));
const R = { class: { th: 'text-right', td: 'text-right' } };
const SORT_LABELS: Record<string, string> = {
  quantity: '订单数量', realizedRevenue: '订单收入', realizedCost: '订单成本快照', realizedMargin: '订单毛利率',
  catalogPrice: '目录·售价', catalogUnitCost: '目录·单位成本', catalogMargin: '目录价理论毛利率',
};
const SORTABLE_IDS = Object.keys(SORT_LABELS);
const columns: CsTableColumn<SkuMarginRow>[] = [
  { id: 'productName', accessorKey: 'productName', header: '产品名称', enableSorting: false },
  { id: 'quantity', header: '订单数量', meta: R },
  { id: 'realizedRevenue', header: '订单收入', meta: R },
  { id: 'realizedCost', header: '订单成本快照', meta: R },
  { id: 'realizedMargin', header: '订单毛利率', meta: { class: { th: 'text-center', td: 'text-center' } } },
  { id: 'catalogPrice', header: '目录·售价', meta: R },
  { id: 'catalogUnitCost', header: '目录·单位成本', meta: R },
  { id: 'catalogMargin', header: '目录价理论毛利率', meta: { class: { th: 'text-center', td: 'text-center' } } },
];
// 没有明细时的真实原因 (后端给的话 / 请求失败的话), 不再声称「API 尚未接入」
const emptyNotice = ref<{ show: boolean; type: 'info' | 'warning' | 'error'; text: string }>(
  { show: false, type: 'info', text: '' },
);
// 后端实际回答的「实际销售毛利」统计期间 (后端回显, 不是页面自己猜的)
const answeredPeriod = ref<SkuMarginPeriod | null>(null);
const truncatedNotice = ref('');

// Chart
const chartRef = ref<HTMLDivElement | null>(null);
let chartInstance: ECharts | null = null;

// ---------- 取值器 (null 一路保持 null) ----------
const SORT_VALUE: Record<SortKey, (r: SkuMarginRow) => number | null> = {
  quantity: (r) => r.quantity,
  realizedRevenue: (r) => r.realized.revenue,
  realizedCost: (r) => r.realized.cost,
  realizedMargin: (r) => r.realized.marginRate,
  catalogPrice: (r) => r.catalog.price,
  catalogUnitCost: (r) => r.catalog.unitCost,
  catalogMargin: (r) => r.catalog.marginRate,
};

// 图表排列方式（问题 50）：先取毛利率前 10，再按所选方式排列显示
const chartSort = ref<ChartSortMode>('valueDesc');
// 一图一分析（问题 40）：和图同一份行数据现算
const skuInsight = computed(() => buildSkuMarginInsight(
  tableData.value.map((r) => ({ name: r.productName, rate: r.realized.marginRate })),
));

/** 有「实际销售毛利率」的行 (null 不参与任何统计 / 排名, ⛔ 不当 0) */
const ratedRows = computed(() => tableData.value.filter((r) => r.realized.marginRate !== null));

// ---------- KPI ----------
const kpi = computed(() => {
  const rated = ratedRows.value;
  const total = tableData.value.length;
  if (rated.length === 0) {
    return {
      avgMargin: null as number | null,
      bestSku: { name: NA, rate: null as number | null },
      worstSku: { name: NA, rate: null as number | null },
      skuCount: total,
      ratedCount: 0,
    };
  }
  const rates = rated.map((r) => r.realized.marginRate as number);
  const avg = rates.reduce((a, b) => a + b, 0) / rates.length;
  const sorted = [...rated].sort((a, b) => (b.realized.marginRate as number) - (a.realized.marginRate as number));
  const best = sorted[0];
  const worst = sorted[sorted.length - 1];
  return {
    avgMargin: avg,
    bestSku: { name: best.productName, rate: best.realized.marginRate },
    worstSku: { name: worst.productName, rate: worst.realized.marginRate },
    skuCount: total,
    ratedCount: rated.length,
  };
});

function getMarginColor(rate: number | null): string {
  if (rate === null) return '#909399';
  if (rate >= 30) return '#67C23A';
  if (rate >= 15) return '#E6A23C';
  return '#F56C6C';
}

// ---------- chart ----------
function initChart() {
  if (!chartRef.value || chartRef.value.clientWidth === 0 || chartRef.value.clientHeight === 0) return;

  if (chartInstance && !chartInstance.isDisposed?.()) {
    chartInstance.dispose();
  }

  chartInstance = echarts.init(chartRef.value, 'cretas');

  const picked = skuTopRows(ratedRows.value.map((r) => ({ row: r, rate: r.realized.marginRate })))
    .map((x) => x.row);
  // 横向柱图 y 轴自下而上，reverse 后第一名在最上面
  const top10 = sortChartRows(
    picked, chartSort.value, (r) => r.realized.marginRate, (r) => r.productName,
  ).reverse();

  if (top10.length === 0) {
    chartInstance.setOption({
      title: { text: '暂无可排名的订单毛利率', left: 'center', top: 'center', textStyle: { color: '#909399', fontSize: 14 } },
    });
    return;
  }

  const names = top10.map((r) => r.productName);
  const rates = top10.map((r) => r1(r.realized.marginRate as number));

  chartInstance.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: { name: string; value: number }[]) => {
        if (!Array.isArray(params) || params.length === 0) return '';
        const p = params[0];
        return `${p.name}<br/>订单毛利率: ${p.value}%`;
      },
    },
    grid: { left: 140, right: 40, top: 10, bottom: 30 },
    xAxis: {
      type: 'value',
      axisLabel: { formatter: '{value}%' },
    },
    yAxis: {
      type: 'category',
      data: names,
      axisLabel: {
        width: 120,
        overflow: 'truncate',
        fontSize: 12,
      },
    },
    series: [
      {
        type: 'bar',
        data: rates.map((r) => ({
          value: r,
          itemStyle: {
            color: getMarginColor(r),
            borderRadius: [0, 4, 4, 0],
          },
        })),
        barMaxWidth: 28,
        label: {
          show: true,
          position: 'right',
          formatter: '{c}%',
          fontSize: 11,
        },
      },
    ],
  });
}

function handleResize() {
  if (chartRef.value?.clientWidth && chartRef.value.clientHeight) chartInstance?.resize();
}

onMounted(() => {
  loadData();
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
  if (chartInstance && !chartInstance.isDisposed?.()) {
    chartInstance.dispose();
  }
  chartInstance = null;
});

// Re-draw chart when data changes
watch([tableData, chartSort], async () => {
  await nextTick();
  initChart();
});

// ---------- data loading ----------
function clearTable() {
  tableData.value = [];
  pagination.value.total = 0;
  answeredPeriod.value = null;
  truncatedNotice.value = '';
}

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  emptyNotice.value = { show: false, type: 'info', text: '' };
  try {
    // 日期筛选走 request.context: ToolDispatchService 把 context 并进工具参数 (TrustedExecutionContext.merge),
    // 工具读 startDate/endDate。后端会回显它实际回答的期间 (realizedPeriod), 页面直接展示那个,
    // 所以哪怕某条路径没把日期带过去, 用户看到的也是真实统计期间而不是自己选的那个。
    const context: Record<string, unknown> = {};
    if (dateRange.value && dateRange.value[0]) {
      context.startDate = dateRange.value[0];
      context.endDate = dateRange.value[1];
    }
    const res = await post<Record<string, unknown>>(`/${factoryId.value}/ai-intents/execute`, {
      intentCode: 'SKU_GROSS_MARGIN',
      userInput: '查询SKU毛利率排名',
      ...(Object.keys(context).length > 0 ? { context } : {}),
    });

    const data = res.data;
    const status = typeof data?.status === 'string' ? data.status : undefined;
    if (!res.success || (status !== undefined && status !== 'SUCCESS')) {
      // NEED_CLARIFICATION / NEED_MORE_INFO / FAILED: 如实说, 不换成别的数据源顶替
      clearTable();
      const message = typeof data?.message === 'string' && data.message ? data.message : (res.message || '本次没有获得 SKU 毛利率');
      emptyNotice.value = { show: true, type: 'warning', text: message };
      return;
    }

    const read = readSkuMarginResponse(data);
    if (read.rows.length === 0) {
      clearTable();
      emptyNotice.value = { show: true, type: 'info', text: read.emptyMessage ?? '没有可计算毛利率的产品' };
      return;
    }
    tableData.value = read.rows;
    pagination.value.total = read.rows.length;
    answeredPeriod.value = read.period;
    truncatedNotice.value = read.truncated && read.totalProducts !== null
      ? `共 ${read.totalProducts} 个产品, 这里只显示前 ${read.rows.length} 个`
      : '';
  } catch (error) {
    // 失败原因已由 request 拦截器弹出 toast; 页面上也留一份, 不换成别的数据源
    console.error('Failed to load SKU margin data:', error);
    clearTable();
    const message = error instanceof Error && error.message ? error.message : '加载失败';
    emptyNotice.value = { show: true, type: 'error', text: message };
  } finally {
    loading.value = false;
  }
}

// ---------- helpers ----------
function getMarginTagType(rate: number): CsBadgeColor {
  if (rate >= 30) return 'success';
  if (rate >= 15) return 'warning';
  return 'error';
}

// ---------- pagination & sorting ----------
const paginatedData = computed(() => {
  const sorted = [...tableData.value];

  // Apply sort —— null 永远排最后 (升降序都一样), ⛔ 不当 0 参与比较
  const pick = SORT_VALUE[sortField.value];
  if (pick) {
    sorted.sort((a, b) => {
      const av = pick(a);
      const bv = pick(b);
      if (av === null && bv === null) return 0;
      if (av === null) return 1;
      if (bv === null) return -1;
      const cmp = av - bv;
      return sortOrder.value === 'descending' ? -cmp : cmp;
    });
  }

  // Apply pagination
  const start = (pagination.value.page - 1) * pagination.value.size;
  return sorted.slice(start, start + pagination.value.size);
});

function handlePageChange(page: number) {
  pagination.value.page = page;
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
}

function handleSearch() {
  shipmentRefresh.value++;
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  shipmentRefresh.value++;
  pagination.value.page = 1;
  loadData();
}

// 毛利率是不可加总的比率，不送入会按合计计算“头部占比”的通用排名解读。
// 保留逐SKU排名图、简单平均和完整数据，避免把比率之和当作业务总量。
</script>

<template>
  <div class="page" data-testid="finance-sku-margin-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>SKU毛利率分析</h2>
        <span class="count">共 {{ pagination.total }} 个SKU</span>
        <div class="head-actions">
          <UInput v-model="startStr" type="date" class="w-40" data-testid="margin-start" @change="onDateInput" />
          <span class="muted">至</span>
          <UInput v-model="endStr" type="date" class="w-40" data-testid="margin-end" @change="onDateInput" />
          <UButton color="neutral" variant="outline" size="sm" @click="setRangeDays(7)">最近一周</UButton>
          <UButton color="neutral" variant="outline" size="sm" @click="setRangeDays(30)">最近一月</UButton>
          <UButton color="neutral" variant="outline" size="sm" @click="setRangeDays(90)">最近三月</UButton>
          <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" data-testid="margin-refresh" @click="handleRefresh">刷新</UButton>
        </div>
      </header>

      <div class="body">
        <p v-if="loading" class="muted" data-testid="margin-loading">加载中…</p>
        <template v-if="canViewPrice">
          <ShipmentSkuMargin :factory-id="factoryId ?? ''" :period="dateRange" :refresh-key="shipmentRefresh" />

          <UButton color="neutral" variant="subtle" :icon="referenceOpen ? 'i-lucide-chevron-down' : 'i-lucide-chevron-right'"
            class="ref-toggle" data-testid="margin-reference-toggle" @click="toggleReference">订单与目录参考（保留原始成本）</UButton>
          <div v-if="referenceOpen" class="reference" data-testid="margin-reference">
            <!-- 没有明细时的真实原因 (后端文案 / 请求失败原因) -->
            <UAlert
              v-if="emptyNotice.show"
              class="empty-notice mb-4"
              :color="emptyNotice.type"
              variant="soft"
              :icon="emptyNotice.type === 'info' ? 'i-lucide-info' : emptyNotice.type === 'error' ? 'i-lucide-circle-alert' : 'i-lucide-triangle-alert'"
              :data-type="emptyNotice.type"
              :title="emptyNotice.text"
            />

            <template v-if="!emptyNotice.show">
              <!-- 两种口径的定义: 分开给, 不互相顶替 -->
              <div class="definitions">
                <div class="definition realized-def">
                  <b>订单毛利（成本快照）</b>: 销售订单明细折后未税收入减去订单保存的成本，只统计有售价且已回填成本的明细；
                  <template v-if="answeredPeriod">
                    统计期间 {{ answeredPeriod.startDate }} 至 {{ answeredPeriod.endDate }}{{ answeredPeriod.defaultPeriod ? ' (默认最近 30 天)' : '' }}。
                  </template>
                </div>
                <div class="definition financial-basis-note">
                  本页使用订单成本快照。出货后的成本更正按实际批次在财务结转中体现；核对已过账收入、销售成本和利润，请
                  <a href="/finance/three-statements">查看已过账财务报表</a>。
                </div>
                <div class="definition catalog-def">
                  <b>目录价理论毛利</b>: 产品目录价 vs 最近一条已算出单位成本的批次 (三价对比看板同口径)。
                </div>
                <div v-if="truncatedNotice" class="definition">{{ truncatedNotice }}</div>
              </div>

              <!-- KPI Row: 只统计有实际销售毛利率的 SKU (算不出来的不当 0 拉低平均) -->
              <div class="kpi-row">
                <div class="kpi-card">
                  <div class="kpi-value kpi-avg" :style="{ color: getMarginColor(kpi.avgMargin) }">{{ fmtPct(kpi.avgMargin) }}</div>
                  <div class="kpi-label">产品毛利率简单平均</div>
                  <div class="kpi-sub">{{ kpi.ratedCount }} / {{ kpi.skuCount }} 个SKU有订单毛利率</div>
                </div>
                <div class="kpi-card best">
                  <div class="kpi-value kpi-best" :style="{ color: kpi.bestSku.rate === null ? '#909399' : '#67C23A' }">{{ fmtPct(kpi.bestSku.rate) }}</div>
                  <div class="kpi-label">最高毛利SKU</div>
                  <div class="kpi-sub" :title="kpi.bestSku.name">{{ kpi.bestSku.name }}</div>
                </div>
                <div class="kpi-card worst">
                  <div class="kpi-value kpi-worst" :style="{ color: kpi.worstSku.rate === null ? '#909399' : '#F56C6C' }">{{ fmtPct(kpi.worstSku.rate) }}</div>
                  <div class="kpi-label">最低毛利SKU</div>
                  <div class="kpi-sub" :title="kpi.worstSku.name">{{ kpi.worstSku.name }}</div>
                </div>
                <div class="kpi-card">
                  <div class="kpi-value kpi-count">{{ kpi.skuCount }}</div>
                  <div class="kpi-label">SKU数量</div>
                </div>
              </div>

              <!-- Bar Chart: Top 10 SKUs by 实际销售毛利率 -->
              <div class="chart-section">
                <h3 class="section-title">前10名产品订单毛利率</h3>
                <ChartSortControl v-model="chartSort" class="chart-sort" data-testid="margin-chart-sort" />
                <div ref="chartRef" class="chart-container"></div>
                <ChartInsight :insight="skuInsight" depth="detailed" class="chart-insight-slot" data-testid="margin-chart-insight" />
              </div>

              <!-- Main Table -->
              <div class="table-section">
                <h3 class="section-title">SKU 毛利率明细</h3>
                <UTable v-model:sorting="sorting" :sorting-options="{ manualSorting: true }" :data="paginatedData" :columns="columns" class="cs-table" data-testid="margin-table">
                  <template #empty>暂无SKU毛利率数据</template>
                  <template v-for="key in SORTABLE_IDS" :key="key" #[`${key}-header`]="{ column }">
                    <button type="button" class="sort-th" @click="column.toggleSorting(column.getIsSorted() === 'asc')">
                      {{ SORT_LABELS[key] }}
                      <UIcon :name="column.getIsSorted() === 'asc' ? 'i-lucide-arrow-up' : column.getIsSorted() === 'desc' ? 'i-lucide-arrow-down' : 'i-lucide-arrow-up-down'"
                        class="sort-icon" :class="{ active: !!column.getIsSorted() }" />
                    </button>
                  </template>
                  <template #productName-cell="{ row }"><span :title="row.original.productName">{{ row.original.productName }}</span></template>
                  <template #quantity-cell="{ row }"><span class="cell-quantity">{{ row.original.quantity === null ? NA : row.original.quantity.toLocaleString() }}</span></template>
                  <template #realizedRevenue-cell="{ row }"><span class="cell-realized-revenue">{{ fmtMoney(row.original.realized.revenue) }}</span></template>
                  <template #realizedCost-cell="{ row }"><span class="cell-realized-cost">{{ fmtMoney(row.original.realized.cost) }}</span></template>
                  <template #realizedMargin-cell="{ row }">
                    <div class="cell-realized-margin">
                      <UBadge v-if="row.original.realized.marginRate !== null" :color="getMarginTagType(row.original.realized.marginRate)" variant="soft">
                        {{ fmtPct(row.original.realized.marginRate) }}
                      </UBadge>
                      <template v-else>
                        <span class="na">{{ NA }}</span>
                        <div v-if="row.original.realized.reason" class="na-reason">{{ row.original.realized.reason }}</div>
                      </template>
                      <div v-if="row.original.realized.note" class="cell-note">{{ row.original.realized.note }}</div>
                    </div>
                  </template>
                  <template #catalogPrice-cell="{ row }"><span class="cell-catalog-price">{{ fmtMoney(row.original.catalog.price) }}</span></template>
                  <template #catalogUnitCost-cell="{ row }"><span class="cell-catalog-cost">{{ fmtMoney(row.original.catalog.unitCost) }}</span></template>
                  <template #catalogMargin-cell="{ row }">
                    <div class="cell-catalog-margin">
                      <UBadge v-if="row.original.catalog.marginRate !== null" :color="getMarginTagType(row.original.catalog.marginRate)" variant="soft">
                        {{ fmtPct(row.original.catalog.marginRate) }}
                      </UBadge>
                      <template v-else>
                        <span class="na">{{ NA }}</span>
                        <div v-if="row.original.catalog.reason" class="na-reason">{{ row.original.catalog.reason }}</div>
                      </template>
                    </div>
                  </template>
                </UTable>
              </div>

              <!-- Pagination -->
              <footer class="pager">
                <USelect :model-value="pagination.size" :items="PAGE_SIZE_ITEMS" class="w-28" data-testid="margin-page-size" @update:model-value="(v: number) => handleSizeChange(Number(v))" />
                <UPagination :page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
              </footer>
            </template>
          </div>
        </template>
        <p v-else class="empty" data-testid="margin-no-permission">您没有查看价格/财务数据的权限</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: center; flex-wrap: wrap; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count { font-size: 13px; color: var(--cs-muted); }
.head-actions { margin-left: auto; display: flex; align-items: center; flex-wrap: wrap; gap: var(--cs-space-2); }
.body { padding: var(--cs-space-5); }
.muted { color: var(--cs-muted); font-size: var(--cs-font-small); }
.ref-toggle { margin-bottom: var(--cs-space-3); }
.empty { padding: var(--cs-space-6) 0; text-align: center; color: var(--cs-muted); font-size: 13px; background: var(--cs-surface-subtle); border-radius: var(--cs-radius-sm); }
.definitions { margin-bottom: var(--cs-space-4); padding: 10px 14px; background: var(--cs-surface-subtle); border-radius: var(--cs-radius-sm); font-size: 13px; line-height: 1.7; color: var(--cs-text); }
.definitions a { color: var(--cs-primary); }
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--cs-space-4); margin-bottom: var(--cs-space-6); }
.kpi-card { display: flex; flex-direction: column; align-items: center; text-align: center; padding: var(--cs-space-4); background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); }
.kpi-value { font-size: 28px; font-weight: 700; line-height: 1.2; margin-bottom: 4px; font-variant-numeric: tabular-nums; }
.kpi-count { color: var(--cs-primary); }
.kpi-label { font-size: 13px; color: var(--cs-muted); }
.kpi-sub { font-size: 12px; color: var(--cs-muted); margin-top: 4px; max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chart-section { margin-bottom: var(--cs-space-6); }
.section-title { font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); margin: 0 0 var(--cs-space-3); padding-left: 10px; border-left: 3px solid var(--cs-primary); }
.chart-container { width: 100%; height: 360px; }
.chart-sort { margin-bottom: var(--cs-space-2); }
.chart-insight-slot { margin-top: var(--cs-space-2); }
.pager { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding-top: var(--cs-space-4); border-top: 1px solid var(--cs-border); margin-top: var(--cs-space-4); }
.na { color: var(--cs-muted); }
.na-reason { margin-top: 2px; font-size: 12px; line-height: 1.4; color: var(--cs-muted); white-space: normal; }
.cell-note { margin-top: 2px; font-size: 12px; line-height: 1.4; color: var(--cs-warning); white-space: normal; }
.sort-th { border: 0; background: transparent; padding: 0; display: inline-flex; align-items: center; gap: 4px; font: inherit; color: inherit; cursor: pointer; }
.sort-icon { width: 12px; height: 12px; color: var(--cs-muted); opacity: 0.6; }
.sort-icon.active { color: var(--cs-primary); opacity: 1; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 4px; min-height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); font-variant-numeric: tabular-nums; }
@media (max-width: 1200px) { .kpi-row { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .kpi-row { grid-template-columns: 1fr; } .chart-container { height: 280px; } }
</style>
