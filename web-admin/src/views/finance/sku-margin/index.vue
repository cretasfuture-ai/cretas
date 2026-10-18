<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { post } from '@/api/request';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import type { ECharts } from 'echarts/core';
import ShipmentSkuMargin from './ShipmentSkuMargin.vue';
import { useRoute } from 'vue-router';
import { readMarginPeriod } from './shipmentMarginNavigation';
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
const sortField = ref<SortKey>('realizedMargin');
const sortOrder = ref<'ascending' | 'descending'>('descending');
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

  const top10 = [...ratedRows.value]
    .sort((a, b) => (b.realized.marginRate as number) - (a.realized.marginRate as number))
    .slice(0, 10)
    .reverse(); // reverse so highest is at top of horizontal bar

  if (top10.length === 0) {
    chartInstance.setOption({
      title: { text: '暂无可排名的订单毛利率', left: 'center', top: 'center', textStyle: { color: '#909399', fontSize: 14 } },
    });
    return;
  }

  const names = top10.map((r) => r.productName);
  const rates = top10.map((r) => Math.round((r.realized.marginRate as number) * 10) / 10);

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
watch(tableData, async () => {
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
function getMarginTagType(rate: number): string {
  if (rate >= 30) return 'success';
  if (rate >= 15) return 'warning';
  return 'danger';
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

function handleSortChange({ prop, order }: { prop: string; order: string | null }) {
  sortField.value = (prop in SORT_VALUE ? prop : 'realizedMargin') as SortKey;
  sortOrder.value = (order as 'ascending' | 'descending') || 'descending';
}

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
  <div class="page-wrapper" v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">SKU毛利率分析</span>
            <span class="data-count">共 {{ pagination.total }} 个SKU</span>
          </div>
          <div class="header-right">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
              style="width: 280px"
              :shortcuts="[
                { text: '最近一周', value: () => { const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 7); return [s, e]; } },
                { text: '最近一月', value: () => { const e = new Date(); const s = new Date(); s.setMonth(s.getMonth() - 1); return [s, e]; } },
                { text: '最近三月', value: () => { const e = new Date(); const s = new Date(); s.setMonth(s.getMonth() - 3); return [s, e]; } },
              ]"
              @change="handleSearch"
            />
            <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
          </div>
        </div>
      </template>

      <template v-if="canViewPrice">
      <ShipmentSkuMargin :factory-id="factoryId ?? ''" :period="dateRange" :refresh-key="shipmentRefresh" />
      <el-collapse @change="() => nextTick(initChart)">
      <el-collapse-item title="订单与目录参考（保留原始成本）" name="reference">
      <!-- 没有明细时的真实原因 (后端文案 / 请求失败原因) -->
      <el-alert
        v-if="emptyNotice.show"
        class="empty-notice"
        :type="emptyNotice.type"
        :closable="false"
        show-icon
        :title="emptyNotice.text"
        style="margin-bottom: 16px;"
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
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value kpi-avg" :style="{ color: getMarginColor(kpi.avgMargin) }">
            {{ fmtPct(kpi.avgMargin) }}
          </div>
          <div class="kpi-label">产品毛利率简单平均</div>
          <div class="kpi-sub">{{ kpi.ratedCount }} / {{ kpi.skuCount }} 个SKU有订单毛利率</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card best">
          <div class="kpi-value kpi-best" :style="{ color: kpi.bestSku.rate === null ? '#909399' : '#67C23A' }">
            {{ fmtPct(kpi.bestSku.rate) }}
          </div>
          <div class="kpi-label">最高毛利SKU</div>
          <div class="kpi-sub">{{ kpi.bestSku.name }}</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card worst">
          <div class="kpi-value kpi-worst" :style="{ color: kpi.worstSku.rate === null ? '#909399' : '#F56C6C' }">
            {{ fmtPct(kpi.worstSku.rate) }}
          </div>
          <div class="kpi-label">最低毛利SKU</div>
          <div class="kpi-sub">{{ kpi.worstSku.name }}</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" style="color: #409EFF">
            {{ kpi.skuCount }}
          </div>
          <div class="kpi-label">SKU数量</div>
        </el-card>
      </div>

      <!-- Bar Chart: Top 10 SKUs by 实际销售毛利率 -->
      <div class="chart-section">
        <h3 class="section-title">前10名产品订单毛利率</h3>
        <div ref="chartRef" class="chart-container"></div>
      </div>

      <!-- Main Table -->
      <div class="table-section">
        <h3 class="section-title">SKU 毛利率明细</h3>
        <el-table
          :data="paginatedData"
          stripe
          border
          style="width: 100%"
          :default-sort="{ prop: 'realizedMargin', order: 'descending' }"
          @sort-change="handleSortChange"
          empty-text="暂无SKU毛利率数据"
        >
          <el-table-column prop="productName" label="产品名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="quantity" label="订单数量" width="100" align="right" sortable="custom">
            <template #default="{ row }">
              <span class="cell-quantity">{{ row.quantity === null ? NA : row.quantity.toLocaleString() }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="realizedRevenue" label="订单收入" width="130" align="right" sortable="custom">
            <template #default="{ row }">
              <span class="cell-realized-revenue">{{ fmtMoney(row.realized.revenue) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="realizedCost" label="订单成本快照" width="130" align="right" sortable="custom">
            <template #default="{ row }">
              <span class="cell-realized-cost">{{ fmtMoney(row.realized.cost) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="realizedMargin" label="订单毛利率" min-width="190" align="center" sortable="custom">
            <template #default="{ row }">
              <div class="cell-realized-margin">
                <el-tag
                  v-if="row.realized.marginRate !== null"
                  :type="getMarginTagType(row.realized.marginRate)"
                  size="small"
                  effect="light"
                >
                  {{ fmtPct(row.realized.marginRate) }}
                </el-tag>
                <template v-else>
                  <span class="na">{{ NA }}</span>
                  <div v-if="row.realized.reason" class="na-reason">{{ row.realized.reason }}</div>
                </template>
                <div v-if="row.realized.note" class="cell-note">{{ row.realized.note }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="catalogPrice" label="目录·售价" width="120" align="right" sortable="custom">
            <template #default="{ row }">
              <span class="cell-catalog-price">{{ fmtMoney(row.catalog.price) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="catalogUnitCost" label="目录·单位成本" width="130" align="right" sortable="custom">
            <template #default="{ row }">
              <span class="cell-catalog-cost">{{ fmtMoney(row.catalog.unitCost) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="catalogMargin" label="目录价理论毛利率" min-width="190" align="center" sortable="custom">
            <template #default="{ row }">
              <div class="cell-catalog-margin">
                <el-tag
                  v-if="row.catalog.marginRate !== null"
                  :type="getMarginTagType(row.catalog.marginRate)"
                  size="small"
                  effect="light"
                >
                  {{ fmtPct(row.catalog.marginRate) }}
                </el-tag>
                <template v-else>
                  <span class="na">{{ NA }}</span>
                  <div v-if="row.catalog.reason" class="na-reason">{{ row.catalog.reason }}</div>
                </template>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- Pagination -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
      </template>
      </el-collapse-item>
      </el-collapse>
      </template>
      <el-empty v-else description="您没有查看价格/财务数据的权限" />
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
    overflow: auto;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }

  .header-right {
    display: flex;
    gap: 12px;
    align-items: center;
  }
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.kpi-card {
  text-align: center;

  :deep(.el-card__body) {
    padding: 16px;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .kpi-value {
    font-size: 28px;
    font-weight: 700;
    line-height: 1.2;
    margin-bottom: 4px;
  }

  .kpi-label {
    font-size: 13px;
    color: var(--text-color-secondary, #909399);
  }

  .kpi-sub {
    font-size: 12px;
    color: var(--text-color-secondary, #909399);
    margin-top: 4px;
    max-width: 160px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.chart-section {
  margin-bottom: 24px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
  padding-left: 10px;
  border-left: 3px solid #409eff;
}

.chart-container {
  width: 100%;
  height: 360px;
}

.table-section {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}

@media (max-width: 1200px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: 1fr;
  }

  .chart-container {
    height: 280px;
  }
}
.definitions {
  margin-bottom: 16px;
  padding: 10px 14px;
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
}

.na {
  color: #909399;
}

.na-reason {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.4;
  color: #909399;
  white-space: normal;
}

.cell-note {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.4;
  color: #E6A23C;
  white-space: normal;
}

</style>
