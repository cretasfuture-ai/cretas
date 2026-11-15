<script setup lang="ts">
/**
 * FactoryCostAnalysis.vue — 工厂生产成本分析
 *
 * 消费 Python factory gold 端点:
 *   GET /api/smartbi/factory/cost-structure     → 成本结构饼图 + 趋势
 *   GET /api/smartbi/factory/yield-trend        → 出成率趋势折线
 *   GET /api/smartbi/factory/product-cost-compare → 产品成本对比柱形
 *
 * RBAC: 成本绝对值仅 canViewPrice 角色可见 (同 RestaurantFinanceContent 模式).
 *       后端已脱敏 (null), 前端再 v-if canViewPrice 隐藏整个成本卡区.
 *
 * 空状态: 无 gold 数据 → 白话提示 "该工厂暂无生产成本数据 — 报工填了成本才会显示".
 * 不碰后端 gold/ETL.
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useChartInsight } from '@/composables/useChartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import {
  getFactoryCostStructure,
  getFactoryYieldTrend,
  getFactoryProductCostCompare,
  type CostStructureData,
  type CostStructureSummary,
  type YieldTrendData,
  type ProductCostCompareData,
} from '@/api/smartbi/factoryGold';
import { toDateString } from '@/utils/dateFormat';
import request from '@/api/request';
import {
  costCoverageText,
  fmtMoney,
  fmtPercent,
  fillCalendarDays,
  fmtQty,
  PIE_LABEL_MIN_PERCENT,
  productChartLabels,
  quantityParts,
  summaryYield,
  summaryYieldMissing,
  unresolvedProductLabel,
} from './costAnalysisFormat';

// ── Stores ────────────────────────────────────────────────────────────────────

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ── Date range ────────────────────────────────────────────────────────────────

// value-format="YYYY-MM-DD" 的 el-date-picker 要字符串；传 Date 会显示成空框。
const dateRange = ref<[string, string]>([
  toDateString(new Date(Date.now() - 29 * 86400000)),
  toDateString(new Date()),
]);

const shortcuts = [
  { text: '近7天',  value: () => [new Date(Date.now() - 6 * 86400000),  new Date()] as [Date, Date] },
  { text: '近30天', value: () => [new Date(Date.now() - 29 * 86400000), new Date()] as [Date, Date] },
  { text: '近90天', value: () => [new Date(Date.now() - 89 * 86400000), new Date()] as [Date, Date] },
];

function fmtDate(d: Date | string): string {
  if (typeof d === 'string') return d.split('T')[0];
  return toDateString(d);
}

function getQueryDates() {
  const [start, end] = dateRange.value;
  return { startDate: fmtDate(start), endDate: fmtDate(end) };
}

// ── State ─────────────────────────────────────────────────────────────────────

const loading = ref(false);
const loadError = ref('');

const costData   = ref<CostStructureData | null>(null);
const yieldData  = ref<YieldTrendData   | null>(null);
const productData = ref<ProductCostCompareData | null>(null);
const productTypes = ref<Record<string, { name: string; unit?: string | null }>>({});

function unwrapProductTypes(value: unknown): Array<Record<string, unknown>> {
  const outer = (value as { data?: unknown } | null)?.data ?? value;
  const inner = (outer as { content?: unknown } | null)?.content ?? outer;
  return Array.isArray(inner) ? inner as Array<Record<string, unknown>> : [];
}

function productLabel(productTypeId: string, inlineName?: string | null): string {
  return inlineName || productTypes.value[productTypeId]?.name || unresolvedProductLabel(productTypeId);
}

function productUnit(product: { productTypeId: string; unit?: string | null }): string {
  return product.unit || '单位未提供';
}

// ── Derived empty checks ──────────────────────────────────────────────────────

/** True when backend returned rows (even if costs are null due to RBAC) */
const hasAnyCostRows = computed(() => (costData.value?.rows?.length ?? 0) > 0);
const hasYieldTrend  = computed(() => (yieldData.value?.trend?.length ?? 0) >= 2);
const hasProducts    = computed(() => (productData.value?.products?.length ?? 0) > 0);

/** True if any of the 3 endpoints returned data */
const hasAnyData = computed(() => hasAnyCostRows.value || hasYieldTrend.value || hasProducts.value);

// ── Cost structure helpers ────────────────────────────────────────────────────

/** Build [材料, 人工, 设备, 其他] pie slices from summary */
const costPieSlices = computed(() => {
  const s = costData.value?.summary;
  if (!s || !canViewPrice.value) return [];
  const entries = [
    { name: '材料成本', value: s.totalMaterialCost  ?? 0 },
    { name: '人工成本', value: s.totalLaborCost     ?? 0 },
    { name: '设备成本', value: s.totalEquipmentCost ?? 0 },
    { name: '其他成本', value: s.totalOtherCost     ?? 0 },
  ];
  return entries.filter(e => e.value > 0);
});

const hasCostPie = computed(() => costPieSlices.value.length > 0);

// ── Chart refs ────────────────────────────────────────────────────────────────

const costPieRef    = ref<HTMLDivElement>();
const costTrendRef  = ref<HTMLDivElement>();
const yieldTrendRef = ref<HTMLDivElement>();
const productBarRef = ref<HTMLDivElement>();

let costPieChart:    echarts.ECharts | null = null;
let costTrendChart:  echarts.ECharts | null = null;
let yieldTrendChart: echarts.ECharts | null = null;
let productBarChart: echarts.ECharts | null = null;

let renderTimer: ReturnType<typeof setTimeout> | null = null;

// ── Render functions ──────────────────────────────────────────────────────────

function renderCostPie() {
  if (!costPieRef.value || !hasCostPie.value) return;
  if (!costPieChart || costPieChart.isDisposed?.()) {
    costPieChart = echarts.init(costPieRef.value, 'cretas');
  }
  const total = costPieSlices.value.reduce((s, e) => s + e.value, 0);
  costPieChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: ({ name, value, percent }: { name: string; value: number; percent: number }) =>
        `${name}: ¥${fmtMoney(value)} (${percent.toFixed(1)}%)`,
    },
    legend: {
      orient: 'vertical',
      right: '4%',
      top: 'center',
      textStyle: { fontSize: 12 },
      // 占比放进图例：小扇区不再挤在图上
      formatter: (name: string) => {
        const slice = costPieSlices.value.find(e => e.name === name);
        const pct = slice && total ? ((slice.value / total) * 100).toFixed(1) : '0.0';
        return `${name}  ${pct}%`;
      },
    },
    series: [{
      type: 'pie',
      radius: ['35%', '62%'],
      center: ['38%', '50%'],
      data: costPieSlices.value.map(e => (
        total && (e.value / total) * 100 < PIE_LABEL_MIN_PERCENT
          ? { ...e, label: { show: false }, labelLine: { show: false } }
          : e
      )),
      label: {
        fontSize: 12,
        formatter: ({ percent }: { percent: number }) =>
          percent >= PIE_LABEL_MIN_PERCENT ? `${percent.toFixed(1)}%` : '',
      },
      labelLine: { show: true },
      labelLayout: { hideOverlap: true },
      emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.2)' } },
    }],
    graphic: [{
      type: 'text',
      left: '38%',
      top: 'middle',
      style: {
        text: `¥${fmtMoney(total)}`,
        fontSize: 13,
        fontWeight: 'bold',
        fill: '#1a2332',
        textAlign: 'center',
        textVerticalAlign: 'middle',
      },
      x: 0,
    }],
  });
}

function renderCostTrend() {
  if (!costTrendRef.value || !hasAnyCostRows.value || !canViewPrice.value) return;
  if (!costTrendChart || costTrendChart.isDisposed?.()) {
    costTrendChart = echarts.init(costTrendRef.value, 'cretas');
  }
  const rows = costData.value!.rows;
  const dates = rows.map(r => r.statDate.slice(5)); // MM-DD
  costTrendChart.setOption({
    tooltip: {
      trigger: 'axis',
      confine: true,
      valueFormatter: (v: unknown) => (typeof v === 'number' ? `¥${fmtMoney(v)}` : '未填'),
    },
    legend: { data: ['材料', '人工', '设备', '其他'], bottom: 0, type: 'scroll' },
    grid: { top: 30, right: 20, bottom: 40, left: 60 },
    xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', name: '成本(¥)', axisLabel: { formatter: (v: number) => fmtMoney(v) } },
    series: [
      { name: '材料', type: 'bar', stack: 'cost', data: rows.map(r => r.totalMaterialCost  ?? null), itemStyle: { color: '#5470c6' } },
      { name: '人工', type: 'bar', stack: 'cost', data: rows.map(r => r.totalLaborCost     ?? null), itemStyle: { color: '#91cc75' } },
      { name: '设备', type: 'bar', stack: 'cost', data: rows.map(r => r.totalEquipmentCost ?? null), itemStyle: { color: '#fac858' } },
      { name: '其他', type: 'bar', stack: 'cost', data: rows.map(r => r.totalOtherCost     ?? null), itemStyle: { color: '#ee6666' } },
    ],
  });
}

function renderYieldTrend() {
  if (!yieldTrendRef.value || !hasYieldTrend.value) return;
  if (!yieldTrendChart || yieldTrendChart.isDisposed?.()) {
    yieldTrendChart = echarts.init(yieldTrendRef.value, 'cretas');
  }
  // 补成连续日历日：无报工的日子是断点，不连线、不插值（不画不存在的值）。
  const days = fillCalendarDays(yieldData.value!.trend);
  const dates = days.map(d => d.date.slice(5));
  const rates = days.map(d => d.point?.avgYieldRate != null ? Number(d.point.avgYieldRate.toFixed(1)) : null);
  const known = rates.filter((v): v is number => v !== null);
  yieldTrendChart.setOption({
    tooltip: {
      trigger: 'axis',
      confine: true,
      formatter: (params: Array<{ name: string; value: number | null }>) =>
        `${params[0]?.name}<br/>出成率: ${params[0]?.value != null ? `${params[0].value}%` : '当天无报工'}`,
    },
    grid: { top: 48, right: 24, bottom: 30, left: 60 },
    xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 11, hideOverlap: true } },
    yAxis: {
      type: 'value',
      name: '出成率(%)',
      min: known.length ? Math.max(0, Math.min(...known, 80) - 5) : 0,
      max: 105,
      interval: 5,
      axisLabel: { formatter: (v: number) => (v > 100 ? '' : `${v}`) },
    },
    series: [{
      name: '出成率',
      type: 'line',
      data: rates,
      smooth: false,
      connectNulls: false,
      showSymbol: true,
      symbolSize: 7,
      itemStyle: { color: '#f5576c' },
      lineStyle: { width: 2 },
      markLine: {
        symbol: 'none',
        data: [{ yAxis: 80, name: '基准', lineStyle: { color: '#52c41a', type: 'dashed' },
          label: { formatter: '基准 80%', position: 'insideStartTop' } }],
        silent: true,
      },
    }],
  });
}

function renderProductBar() {
  if (!productBarRef.value || !hasProducts.value) return;
  if (!productBarChart || productBarChart.isDisposed?.()) {
    productBarChart = echarts.init(productBarRef.value, 'cretas');
  }
  const prods = productData.value!.products.slice(0, 10); // top 10
  const labels = productChartLabels(prods, id => productLabel(id, prods.find(p => p.productTypeId === id)?.productName));
  const canShow = canViewPrice.value;
  // 横向条形图：产品全名直接可读，不再被 x 轴截断成「演示·酱卤牛肉…」。
  productBarChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      confine: true,
      formatter: (params: Array<{ dataIndex?: number }>) => {
        const p = prods[params[0]?.dataIndex ?? 0];
        if (!p) return '';
        const lines = [
          productLabel(p.productTypeId, p.productName),
          ...(canShow ? [`总成本: ¥${fmtMoney(p.totalCost)}`] : []),
          `批次: ${p.batchCount}`,
          `实际产出: ${fmtQty(p.totalActualQty)} ${productUnit(p)}`,
          `出成率: ${fmtPercent(p.avgYieldRate)}`,
        ];
        return lines.join('<br/>');
      },
    },
    grid: { top: 16, right: 64, bottom: 28, left: 8, containLabel: true },
    xAxis: canShow
      ? { type: 'value', name: '总成本(¥)', axisLabel: { formatter: (v: number) => fmtMoney(v) } }
      : { type: 'value', name: '出成率(%)', min: 0, max: 100 },
    yAxis: { type: 'category', inverse: true, data: labels, axisLabel: { fontSize: 12, width: 220, overflow: 'break' } },
    series: [{
      type: 'bar',
      data: prods.map(p => (canShow ? p.totalCost : p.avgYieldRate)),
      barMaxWidth: 26,
      itemStyle: { color: canShow ? '#1b65a8' : '#5470c6', borderRadius: [0, 4, 4, 0] },
      label: {
        show: true,
        position: 'right',
        fontSize: 11,
        formatter: ({ value }: { value: number | null }) =>
          value == null ? '未填' : canShow ? `¥${fmtMoney(value)}` : `${value}%`,
      },
    }],
  });
}

function renderAll() {
  if (renderTimer) clearTimeout(renderTimer);
  renderTimer = setTimeout(() => {
    renderCostPie();
    renderCostTrend();
    renderYieldTrend();
    renderProductBar();
  }, 80);
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function loadData() {
  const fid = factoryId.value;
  if (!fid) {
    loadError.value = '无法获取工厂 ID，请重新登录';
    return;
  }
  loading.value = true;
  loadError.value = '';
  const { startDate, endDate } = getQueryDates();

  try {
    const [costRes, yieldRes, productRes, typeRes] = await Promise.all([
      getFactoryCostStructure({ factoryId: fid, startDate, endDate }),
      getFactoryYieldTrend({ factoryId: fid, startDate, endDate }),
      getFactoryProductCostCompare({ factoryId: fid, startDate, endDate }),
      request.get<unknown>(`/${fid}/product-types/active`).catch((): null => null),
    ]);

    productTypes.value = Object.fromEntries(unwrapProductTypes(typeRes).map(row => {
      const id = String(row.id || row.productTypeId || '');
      const name = String(row.productName || row.name || row.code || id);
      const unit = row.unit == null ? null : String(row.unit);
      return [id, { name, unit }];
    }).filter(([id]) => id));

    if (costRes.success) {
      costData.value = costRes.data;
    } else {
      console.warn('[FactoryCostAnalysis] cost-structure error:', costRes.message);
    }

    if (yieldRes.success) {
      yieldData.value = yieldRes.data;
    } else {
      console.warn('[FactoryCostAnalysis] yield-trend error:', yieldRes.message);
    }

    if (productRes.success) {
      productData.value = productRes.data;
    } else {
      console.warn('[FactoryCostAnalysis] product-cost-compare error:', productRes.message);
    }

    if (!costRes.success && !yieldRes.success && !productRes.success) {
      ElMessage.warning(costRes.message || '加载失败');
    }

    renderAll();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载成本数据失败';
    loadError.value = msg;
    ElMessage.error(msg);
  } finally {
    loading.value = false;
  }
}

function onDateChange() {
  loadData();
}

// ── Summary KPI display ───────────────────────────────────────────────────────

interface Kpi {
  key: string;
  label: string;
  value: string;
  unit?: string;
  hint?: string;
  parts?: Array<{ unit: string; text: string }>;
}

const summaryKpis = computed<Kpi[]>(() => {
  const s = (costData.value?.summary ?? {}) as Partial<CostStructureSummary>;
  const kpis: Kpi[] = [
    { key: 'batch', label: '生产批次', value: fmtQty(s.batchCount ?? 0, 0), unit: '批' },
  ];
  // 不同单位的产出不相加：每个单位单列，而不是只显示「—」。
  const parts = quantityParts(s.quantityByUnit).map(p => ({ unit: p.unit, text: fmtQty(p.quantity, 1) }));
  kpis.push({
    key: 'qty',
    label: '实际产出',
    value: parts.length === 1 ? parts[0].text : parts.length ? '' : '—',
    unit: parts.length === 1 ? parts[0].unit : undefined,
    parts: parts.length > 1 ? parts : undefined,
    hint: parts.length > 1 ? '不同单位不合并相加' : undefined,
  });
  const y = summaryYield(s);
  kpis.push({
    key: 'yield',
    label: '平均出成率',
    value: fmtPercent(y),
    hint: summaryYieldMissing(s)
      ? '缺批次数，后端未更新'
      : y == null ? '暂无带出成率的批次' : `按 ${s.yieldBatchCount ?? 0} 批加权`,
  });
  if (canViewPrice.value) {
    kpis.push({
      key: 'cost',
      label: '总成本',
      value: s.totalCost == null ? '—' : `¥${fmtMoney(s.totalCost)}`,
      hint: s.totalCost == null ? '报工未填成本或无权限查看' : undefined,
    });
  }
  return kpis;
});

// ── Chart Insight wiring (useChartInsight per-chart) ──────────────────────────

const costInsightPerms = computed(() => ({ canViewFinance: canViewPrice.value }));

// 1. 成本结构饼图 (PIE PROPORTION, yMetric:cost)
const { insight: costPieInsight, loading: costPieInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    if (!hasCostPie.value) return null;
    return {
      chart: {
        chartType: 'pie',
        title: '成本结构占比',
        meta: { xDim: 'category', yMetric: 'cost', aggregation: 'sum', domain: 'factory' },
        config: {
          series: [{ type: 'pie', data: costPieSlices.value }],
        },
      },
    };
  },
  () => costInsightPerms.value,
  { factoryId: () => factoryId.value, autoTier2: true },
);

// 趋势类图（成本趋势 / 出成率趋势）不挂自动洞察：首尾比较得出的「整体下降 99.7%」会把
// 正常的开工日波动说成异常，客户演示场景下误导大于帮助。

// 产品对比图不挂自动洞察：「是末位产品的 7646 倍」这类首尾倍数对客户没有业务意义，还会放大 QA 测试产品的噪声。

// ── Lifecycle ─────────────────────────────────────────────────────────────────

let resizeRaf = 0;
let resizeObserver: ResizeObserver | null = null;

function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    costPieChart?.resize();
    costTrendChart?.resize();
    yieldTrendChart?.resize();
    productBarChart?.resize();
    resizeRaf = 0;
  });
}

onMounted(() => {
  loadData();
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    document.querySelectorAll('.fca-chart-body').forEach(el => resizeObserver!.observe(el));
  }
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  if (resizeRaf) cancelAnimationFrame(resizeRaf);
  resizeObserver?.disconnect();
  if (renderTimer) clearTimeout(renderTimer);
  window.removeEventListener('resize', handleResize);
  costPieChart?.dispose();
  costTrendChart?.dispose();
  yieldTrendChart?.dispose();
  productBarChart?.dispose();
});
</script>

<template>
  <div class="fca-root" v-loading="loading">
    <!-- ── 顶部工具栏 ─────────────────────────────────────── -->
    <div class="fca-toolbar">
      <h2 class="fca-title">生产成本分析</h2>
      <div class="fca-toolbar-actions">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :shortcuts="shortcuts"
          value-format="YYYY-MM-DD"
          @change="onDateChange"
          style="width: 280px"
        />
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>
    </div>

    <!-- ── 加载错误 ───────────────────────────────────────── -->
    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      class="fca-error"
    />

    <!-- ── 全局空状态: 无任何 gold 数据 ────────────────────── -->
    <template v-if="!loading && !loadError && !hasAnyData">
      <div class="fca-empty-state">
        <el-icon class="fca-empty-icon"><i class="fca-empty-icon-char">📊</i></el-icon>
        <div class="fca-empty-title">该工厂暂无生产成本数据</div>
        <div class="fca-empty-desc">
          报工时填写材料、人工成本后，生产成本分析将在此处展示。
        </div>
      </div>
    </template>

    <!-- ── 主体内容 ───────────────────────────────────────── -->
    <template v-else-if="hasAnyData || loading">
      <!-- KPI 摘要行 -->
      <div class="fca-kpi-row" v-if="hasAnyCostRows">
        <div v-for="kpi in summaryKpis" :key="kpi.key" class="fca-kpi-card">
          <div class="fca-kpi-label">{{ kpi.label }}</div>
          <div v-if="kpi.parts" class="fca-kpi-parts">
            <span v-for="part in kpi.parts" :key="part.unit" class="fca-kpi-part">
              <b>{{ part.text }}</b><small>{{ part.unit }}</small>
            </span>
          </div>
          <div v-else class="fca-kpi-value">
            {{ kpi.value }}
            <span v-if="kpi.unit && kpi.value !== '—'" class="fca-kpi-unit">{{ kpi.unit }}</span>
          </div>
          <div v-if="kpi.hint" class="fca-kpi-hint">{{ kpi.hint }}</div>
        </div>
      </div>

      <!-- ── 成本块 (仅 canViewPrice) ─────────────────────── -->
      <div v-if="canViewPrice" class="fca-section">
        <div class="fca-section-title">成本结构</div>

        <!-- 无成本数据空状态 -->
        <div v-if="!hasAnyCostRows && !loading" class="fca-inline-empty">
          <el-empty
            description="该时间范围暂无成本数据，请在报工时填写材料/人工成本后再来查看"
            :image-size="80"
          />
        </div>

        <div v-else class="fca-charts-row">
          <!-- 成本饼图 -->
          <div class="fca-chart-card fca-chart-half">
            <div class="fca-chart-title">成本结构占比</div>
            <div v-show="hasCostPie" ref="costPieRef" class="fca-chart-body" />
            <el-empty
              v-if="!hasCostPie && !loading"
              description="暂无成本拆分数据（可能报工时未填写各项成本）"
              :image-size="60"
              class="fca-chart-empty"
            />
            <ChartInsight
              :insight="costPieInsight"
              :loading="costPieInsightLoading"
              depth="detailed"
            />
          </div>

          <!-- 每日成本趋势堆叠柱 -->
          <div class="fca-chart-card fca-chart-half">
            <div class="fca-chart-title">每日成本趋势（堆叠）</div>
            <div v-show="hasAnyCostRows" ref="costTrendRef" class="fca-chart-body" />
            <el-empty
              v-if="!hasAnyCostRows && !loading"
              description="暂无成本数据"
              :image-size="60"
              class="fca-chart-empty"
            />
          </div>
        </div>
      </div>

      <!-- RBAC 遮挡：无价格权限 -->
      <div v-else class="fca-rbac-mask">
        <el-icon><i class="el-icon--lock">🔒</i></el-icon>
        <span>成本数据仅工厂管理员/超级管理员可见，如需查看请联系管理员开通权限</span>
      </div>

      <!-- ── 出成率趋势 (无 RBAC, 所有角色可见) ────────────── -->
      <div class="fca-section">
        <div class="fca-section-title">出成率趋势</div>
        <div class="fca-chart-card fca-chart-full">
          <div class="fca-chart-title">出成率趋势（日度）</div>
          <div v-show="hasYieldTrend" ref="yieldTrendRef" class="fca-chart-body" />
          <el-empty
            v-if="!hasYieldTrend && !loading"
            :image-size="80"
            class="fca-chart-empty"
          >
            <template #description>
              <span>该时间范围暂无出成率数据<br/>（需有 ≥ 2 天的报工记录且记录了实际产出量）</span>
            </template>
          </el-empty>
        </div>
      </div>

      <!-- ── 产品成本对比 ───────────────────────────────────── -->
      <div class="fca-section">
        <div class="fca-section-title">
          {{ canViewPrice ? '产品成本对比' : '产品出成率对比' }}
        </div>
        <div class="fca-chart-card fca-chart-full">
          <div class="fca-chart-title">
            {{ canViewPrice ? '各产品总成本 (Top 10)' : '各产品出成率 (Top 10)' }}
          </div>
          <div v-show="hasProducts" ref="productBarRef" class="fca-chart-body" />
          <el-empty
            v-if="!hasProducts && !loading"
            description="暂无产品成本对比数据"
            :image-size="80"
            class="fca-chart-empty"
          />
        </div>
      </div>

      <!-- ── 产品明细表格 (canViewPrice 或 仅出成率) ─────────── -->
      <div class="fca-section fca-detail-section" v-if="hasProducts">
        <div class="fca-section-title">产品明细</div>
        <el-table :data="productData!.products" stripe border size="small" empty-text="暂无数据">
          <el-table-column label="产品" min-width="180">
            <template #default="{ row }">
              <span :title="row.productTypeId">{{ productLabel(row.productTypeId, row.productName) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="batchCount" label="批次数" width="80" align="right" />
          <el-table-column label="单位" width="90" align="center">
            <template #default="{ row }">{{ productUnit(row) }}</template>
          </el-table-column>
          <el-table-column label="实际产出" width="110" align="right">
            <template #default="{ row }">
              {{ fmtQty(row.totalActualQty) }}
            </template>
          </el-table-column>
          <el-table-column label="出成率" width="90" align="center">
            <template #default="{ row }">
              {{ fmtPercent(row.avgYieldRate) }}
            </template>
          </el-table-column>
          <!-- 成本列仅 canViewPrice 可见 -->
          <template v-if="canViewPrice">
            <el-table-column label="材料成本(¥)" width="110" align="right">
              <template #default="{ row }">
                {{ fmtMoney(row.totalMaterialCost) }}
              </template>
            </el-table-column>
            <el-table-column label="人工成本(¥)" width="110" align="right">
              <template #default="{ row }">
                {{ fmtMoney(row.totalLaborCost) }}
              </template>
            </el-table-column>
            <el-table-column label="总成本(¥)" width="100" align="right">
              <template #default="{ row }">
                {{ fmtMoney(row.totalCost) }}
              </template>
            </el-table-column>
            <el-table-column label="单位成本" width="150" align="right">
              <template #default="{ row }">
                <template v-if="row.costPerUnit != null">
                  ¥{{ fmtMoney(row.costPerUnit, 2) }}/{{ productUnit(row) }}
                  <small v-if="costCoverageText(row.costedBatchCount, row.batchCount)" class="fca-coverage">
                    {{ costCoverageText(row.costedBatchCount, row.batchCount) }}
                  </small>
                </template>
                <template v-else>—</template>
              </template>
            </el-table-column>
          </template>
        </el-table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.fca-root {
  padding: 20px;
  min-height: calc(100vh - 64px);
  background: var(--cs-surface-subtle);
}

/* ── Toolbar ─────────────────────────────────── */
.fca-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.fca-title {
  font-size: 20px;
  font-weight: 600;
  color: #1d2129;
  margin: 0;
}

.fca-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.fca-error {
  margin-bottom: 16px;
}

/* ── Empty state ─────────────────────────────── */
.fca-empty-state {
  text-align: center;
  padding: 80px 24px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.fca-empty-icon {
  font-size: 56px;
  margin-bottom: 12px;
}

.fca-empty-icon-char {
  font-style: normal;
  font-size: 56px;
}

.fca-empty-title {
  font-size: 18px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 8px;
}

.fca-empty-desc {
  font-size: 14px;
  color: #86909c;
  line-height: 1.7;
}

/* ── KPI row ─────────────────────────────────── */
.fca-kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--cs-space-4);
  margin-bottom: var(--cs-space-5);
}

.fca-kpi-card {
  background: var(--cs-surface);
  border: 1px solid var(--cs-border);
  border-radius: var(--cs-radius);
  padding: var(--cs-space-4) var(--cs-space-5);
  box-shadow: var(--cs-shadow);
}

.fca-kpi-label {
  font-size: var(--cs-font-small);
  color: var(--cs-muted);
  margin-bottom: var(--cs-space-2);
}

.fca-kpi-value {
  font-size: 1.75rem;
  font-weight: 700;
  line-height: 1.2;
  color: var(--cs-text);
  font-variant-numeric: tabular-nums;
}

.fca-kpi-unit {
  font-size: var(--cs-font);
  font-weight: 400;
  margin-left: var(--cs-space-1);
  color: var(--cs-muted);
}

.fca-kpi-parts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cs-space-1) var(--cs-space-3);
  font-variant-numeric: tabular-nums;
}

.fca-kpi-part b {
  font-size: 1.25rem;
  color: var(--cs-text);
}

.fca-kpi-part small {
  margin-left: 2px;
  color: var(--cs-muted);
}

.fca-kpi-hint {
  margin-top: var(--cs-space-1);
  font-size: var(--cs-font-small);
  color: var(--cs-muted);
}

.fca-coverage {
  display: block;
  color: var(--cs-warning);
  font-size: var(--cs-font-small);
}

/* ── Sections ────────────────────────────────── */
.fca-section {
  margin-bottom: 20px;
}

.fca-section-title {
  font-size: 16px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 12px;
  padding-left: 12px;
  border-left: 3px solid #5470c6;
}

.fca-inline-empty {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

/* ── Charts ──────────────────────────────────── */
.fca-charts-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.fca-chart-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.fca-chart-half {
  /* inside .fca-charts-row grid — inherits 50% */
}

.fca-chart-full {
  width: 100%;
}

.fca-chart-title {
  font-size: 14px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 12px;
}

.fca-chart-body {
  height: 300px;
}

.fca-chart-empty {
  height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ── RBAC mask ───────────────────────────────── */
.fca-rbac-mask {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  display: flex;
  align-items: center;
  gap: 10px;
  color: #86909c;
  font-size: 14px;
  border-left: 4px solid #f59e0b;
}

/* ── Detail table ────────────────────────────── */
.fca-detail-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

/* ── Responsive ──────────────────────────────── */
@media (max-width: 1200px) {
  .fca-charts-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .fca-toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
