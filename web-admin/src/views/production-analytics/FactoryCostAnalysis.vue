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
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
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
  COST_CATEGORY_COLORS,
  costCoverageText,
  type CostCategory,
  fmtMoney,
  fmtPercent,
  fillCalendarDays,
  fmtQty,
  NARROW_MAX_WIDTH,
  PIE_LABEL_MIN_PERCENT,
  productChartLabels,
  productsByUnitCost,
  quantityParts,
  summaryYield,
  summaryYieldMissing,
  unitCostLabel,
  unresolvedProductLabel,
  yieldBasisText,
} from './costAnalysisFormat';
import {
  costPieInsightChart,
  costTrendInsightChart,
  previousRange,
  productInsightChart,
  yieldTrendInsightChart,
} from './costInsightCharts';
import ChartInsightProvider from '@/views/smart-bi/components/ChartInsightProvider.vue';
import type { UserPermissions } from '@/views/smart-bi/components/chartInsight';
import ChartSortControl from '@/components/common/ChartSortControl.vue';
import { sortChartRows, tableSortBy, TABLE_SORT_ORDERS, type ChartSortMode } from '@/utils/chartSort';
import type { ProductCostItem } from '@/api/smartbi/factoryGold';
import { useExternalCost } from '@/composables/useProductionGold';
import BlacklakeCostAnalysis from './BlacklakeCostAnalysis.vue';

// ── Stores ────────────────────────────────────────────────────────────────────

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const external = useExternalCost(factoryId);
const externalDateRange = ref('');
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

/** 手机宽度：图例放图下、横条图少刻度、明细表改卡片。 */
const isNarrow = ref(typeof window !== 'undefined' && window.innerWidth <= NARROW_MAX_WIDTH);

const costData   = ref<CostStructureData | null>(null);
const yieldData  = ref<YieldTrendData   | null>(null);
const productData = ref<ProductCostCompareData | null>(null);
const productTypes = ref<Record<string, { name: string; unit?: string | null }>>({});
/** 上期（紧挨着的前一段等长区间）同口径数据，只给一图一分析算环比；取不到为 null，不写环比。 */
const prevCostData = ref<CostStructureData | null>(null);
const prevYieldData = ref<YieldTrendData | null>(null);
const prevProductData = ref<ProductCostCompareData | null>(null);
/** 产品对比条形图的排列方式（问题 50）：只改排列，不改是哪 10 个产品。 */
const productSortMode = ref<ChartSortMode>('valueDesc');

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
  const entries: Array<{ category: CostCategory; value: number }> = [
    { category: '材料', value: s.totalMaterialCost  ?? 0 },
    { category: '人工', value: s.totalLaborCost     ?? 0 },
    { category: '设备', value: s.totalEquipmentCost ?? 0 },
    { category: '其他', value: s.totalOtherCost     ?? 0 },
  ];
  // 颜色与每日堆叠柱共用 COST_CATEGORY_COLORS：同一类成本两张图一个颜色。
  return entries
    .filter(e => e.value > 0)
    .map(e => ({ name: `${e.category}成本`, value: e.value, itemStyle: { color: COST_CATEGORY_COLORS[e.category] } }));
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
  const narrow = isNarrow.value;
  // 手机宽度：图例横排放到图下方、环居中——竖排图例放右侧会压在环上（11-17 评估 m_donut.png）。
  const center = narrow ? ['50%', '40%'] : ['38%', '50%'];
  costPieChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: ({ name, value, percent }: { name: string; value: number; percent: number }) =>
        `${name}: ¥${fmtMoney(value)} (${percent.toFixed(1)}%)`,
    },
    legend: {
      ...(narrow
        ? { orient: 'horizontal', left: 'center', bottom: 0, itemGap: 12 }
        : { orient: 'vertical', right: '4%', top: 'center' }),
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
      radius: narrow ? ['32%', '56%'] : ['35%', '62%'],
      center,
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
    // 环心总额用 title 定位到环中心：graphic 的 left 是包围盒左边，窄屏下文字会被裁成「¥79,29」。
    title: {
      text: `¥${fmtMoney(total)}`,
      left: center[0],
      top: center[1],
      textAlign: 'center',
      textVerticalAlign: 'middle',
      textStyle: { fontSize: 13, fontWeight: 'bold', color: '#1a2332' },
    },
  });
}

function renderCostTrend() {
  if (!costTrendRef.value || !hasAnyCostRows.value || !canViewPrice.value) return;
  if (!costTrendChart || costTrendChart.isDisposed?.()) {
    costTrendChart = echarts.init(costTrendRef.value, 'cretas');
  }
  // 与出成率趋势同一种横轴：连续日历日，没生产的日子留空（两张时间图并排看不会误读）。
  const days = fillCalendarDays(costData.value!.rows);
  const dates = days.map(d => d.date.slice(5)); // MM-DD
  const series = (key: 'totalMaterialCost' | 'totalLaborCost' | 'totalEquipmentCost' | 'totalOtherCost') =>
    days.map(d => d.point?.[key] ?? null);
  costTrendChart.setOption({
    tooltip: {
      trigger: 'axis',
      confine: true,
      valueFormatter: (v: unknown) => (typeof v === 'number' ? `¥${fmtMoney(v)}` : '未填'),
    },
    legend: { data: ['材料', '人工', '设备', '其他'], bottom: 0, type: 'scroll' },
    grid: { top: 30, right: 20, bottom: 40, left: 60 },
    xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 11, hideOverlap: true } },
    yAxis: { type: 'value', name: '成本(¥)', axisLabel: { formatter: (v: number) => fmtMoney(v) } },
    series: [
      { name: '材料', type: 'bar', stack: 'cost', data: series('totalMaterialCost'),  itemStyle: { color: COST_CATEGORY_COLORS.材料 } },
      { name: '人工', type: 'bar', stack: 'cost', data: series('totalLaborCost'),     itemStyle: { color: COST_CATEGORY_COLORS.人工 } },
      { name: '设备', type: 'bar', stack: 'cost', data: series('totalEquipmentCost'), itemStyle: { color: COST_CATEGORY_COLORS.设备 } },
      { name: '其他', type: 'bar', stack: 'cost', data: series('totalOtherCost'),     itemStyle: { color: COST_CATEGORY_COLORS.其他 } },
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
      min: known.length ? Math.max(0, Math.floor((Math.min(...known) - 5) / 5) * 5) : 0,
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
      // 不画「基准线」：工厂没有配置成品批次的出成率目标（工序目标是逐道工序的），
      // 写死一个 80% 会被当成工厂目标来读。
    }],
  });
}

function renderProductBar() {
  if (!productBarRef.value || !hasProducts.value) return;
  if (!productBarChart || productBarChart.isDisposed?.()) {
    productBarChart = echarts.init(productBarRef.value, 'cretas');
  }
  const canShow = canViewPrice.value;
  const prods = productBarRows.value;
  const narrow = isNarrow.value;
  const labels = productBarLabels.value;
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
          ...(canShow ? [`单位成本: ${unitCostLabel(p.costPerUnit, p.unit)}`, `总成本: ¥${fmtMoney(p.totalCost)}`] : []),
          `批次: ${p.batchCount}`,
          `实际产出: ${fmtQty(p.totalActualQty)} ${productUnit(p)}`,
          `出成率: ${fmtPercent(p.avgYieldRate)}`,
        ];
        return lines.join('<br/>');
      },
    },
    grid: { top: 16, right: narrow ? 84 : 96, bottom: narrow ? 8 : 28, left: 8, containLabel: true },
    // 手机宽度不画数值轴刻度（会挤成「5100000…」一串）：每根条右侧已经有金额标签。
    xAxis: canShow
      ? { type: 'value', name: narrow ? '' : '单位成本(¥)', splitNumber: 3,
          axisLabel: { show: !narrow, formatter: (v: number) => fmtMoney(v, v < 100 ? 1 : 0) } }
      : { type: 'value', name: narrow ? '' : '出成率(%)', min: 0, max: 100, axisLabel: { show: !narrow } },
    yAxis: { type: 'category', inverse: true, data: labels,
      axisLabel: { fontSize: 12, width: narrow ? 110 : 220, overflow: 'break' } },
    series: [{
      type: 'bar',
      data: prods.map(p => (canShow ? p.costPerUnit : p.avgYieldRate)),
      barMaxWidth: 26,
      itemStyle: { color: canShow ? '#1b65a8' : '#5470c6', borderRadius: [0, 4, 4, 0] },
      label: {
        show: true,
        position: 'right',
        fontSize: 11,
        formatter: ({ value, dataIndex }: { value: number | null; dataIndex: number }) =>
          value == null ? '未填' : canShow ? unitCostLabel(value, prods[dataIndex]?.unit) : `${value}%`,
      },
    }],
  });
}

/** 用当前标签规则给一组产品起图上名字（同一产品多单位时带单位）。 */
function labelsFor(list: ProductCostItem[]): string[] {
  return productChartLabels(list, id => productLabel(id, list.find(p => p.productTypeId === id)?.productName));
}

/**
 * 条形图上的 10 个产品：有价格权限时按单位成本取 Top10（谁贵），不比总成本（总成本主要反映产量大小）；
 * 再按「排列方式」重排。数值排序在同单位组内进行——盒和 kg 的单价不比大小。
 */
const productBarRows = computed<ProductCostItem[]>(() => {
  const all = productData.value?.products ?? [];
  const canShow = canViewPrice.value;
  const top = (canShow ? productsByUnitCost(all) : all).slice(0, 10);
  const names = labelsFor(top);
  const nameOf = new Map(top.map((p, i) => [p, names[i]]));
  return sortChartRows(
    top,
    productSortMode.value,
    p => (canShow ? p.costPerUnit : p.avgYieldRate),
    p => nameOf.get(p) ?? p.productTypeId,
    canShow ? p => p.unit ?? '' : undefined,
  );
});
const productBarLabels = computed(() => labelsFor(productBarRows.value));

// ── 一图一分析（问题 40）：四张图各一句现算结论 + 一句建议，复用 smart-bi 的 ChartInsight ──
// 不走 Tier2（autoTier2=false）：后端模板话正是 11-17 评估扣分的来源；结论只来自当前数据。
const insightPerms = computed<UserPermissions>(() => ({ canViewFinance: canViewPrice.value }));
const costPieInsight = computed(() =>
  canViewPrice.value ? costPieInsightChart(costData.value, prevCostData.value) : null);
const costTrendInsight = computed(() =>
  canViewPrice.value ? costTrendInsightChart(costData.value, prevCostData.value) : null);
const yieldInsight = computed(() =>
  hasYieldTrend.value ? yieldTrendInsightChart(yieldData.value, prevYieldData.value) : null);
const productInsight = computed(() => {
  if (!hasProducts.value) return null;
  const prev = prevProductData.value?.products ?? null;
  const prevLabels = prev ? labelsFor(prev) : [];
  const prevLabelOf = new Map((prev ?? []).map((p, i) => [p, prevLabels[i]]));
  return productInsightChart(
    productBarRows.value,
    productBarLabels.value,
    canViewPrice.value,
    prev,
    p => prevLabelOf.get(p) ?? p.productTypeId,
  );
});

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

let loadRevision = 0;

async function loadData() {
  const fid = factoryId.value;
  const revision = ++loadRevision;
  const current = () => revision === loadRevision && fid === factoryId.value;
  if (renderTimer) clearTimeout(renderTimer);
  costData.value = null;
  yieldData.value = null;
  productData.value = null;
  prevCostData.value = null;
  prevYieldData.value = null;
  prevProductData.value = null;
  productTypes.value = {};
  if (!fid) {
    loading.value = false;
    await external.load({});
    loadError.value = '无法获取工厂 ID，请重新登录';
    return;
  }
  loading.value = true;
  loadError.value = '';
  const { startDate, endDate } = getQueryDates();

  try {
    const source = await external.load({ startDate, endDate });
    if (!current() || source === 'obsolete') return;
    if (source === 'external') {
      externalDateRange.value = `${startDate}/${endDate}`;
      costPieChart?.dispose();
      costTrendChart?.dispose();
      yieldTrendChart?.dispose();
      productBarChart?.dispose();
      costPieChart = costTrendChart = yieldTrendChart = productBarChart = null;
      return;
    }
    const prev = previousRange(startDate, endDate);
    // 上期只用于一图一分析的环比：失败就当没有上期（不写环比），不影响本期展示。
    const prevQuery = { factoryId: fid, ...prev };
    const [costRes, yieldRes, productRes, typeRes, prevCostRes, prevYieldRes, prevProductRes] = await Promise.all([
      getFactoryCostStructure({ factoryId: fid, startDate, endDate }),
      getFactoryYieldTrend({ factoryId: fid, startDate, endDate }),
      getFactoryProductCostCompare({ factoryId: fid, startDate, endDate }),
      request.get<unknown>(`/${fid}/product-types/active`).catch((): null => null),
      getFactoryCostStructure(prevQuery).catch((): null => null),
      getFactoryYieldTrend(prevQuery).catch((): null => null),
      getFactoryProductCostCompare(prevQuery).catch((): null => null),
    ]);
    if (!current()) return;
    prevCostData.value = prevCostRes?.success && prevCostRes.data?.rows?.length ? prevCostRes.data : null;
    prevYieldData.value = prevYieldRes?.success && prevYieldRes.data?.trend?.length ? prevYieldRes.data : null;
    prevProductData.value = prevProductRes?.success && prevProductRes.data?.products?.length ? prevProductRes.data : null;

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
    if (!current()) return;
    const msg = e instanceof Error ? e.message : '加载成本数据失败';
    loadError.value = msg;
    ElMessage.error(msg);
  } finally {
    if (current()) loading.value = false;
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
      : yieldBasisText(s.yieldBatchCount, s.batchCount),
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

// 一图一分析的句子在 chartInsight 里按当前数据现算（11-17 评估第 9 条：模板话「占比超过一半」、
// 首尾比较「整体下降 99.7%」、「末位的 7646 倍」都已换掉：占比写实数、趋势比前后半段、排名比平均）。

// ── Lifecycle ─────────────────────────────────────────────────────────────────

let resizeRaf = 0;
let resizeObserver: ResizeObserver | null = null;

function handleResize() {
  const narrow = window.innerWidth <= NARROW_MAX_WIDTH;
  if (narrow !== isNarrow.value) {
    isNarrow.value = narrow;
    renderAll(); // 图例位置 / 刻度随宽度切换，要重新 setOption
  }
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    costPieChart?.resize();
    costTrendChart?.resize();
    yieldTrendChart?.resize();
    productBarChart?.resize();
    resizeRaf = 0;
  });
}

watch(productSortMode, () => renderProductBar());
watch(factoryId, loadData, { flush: 'sync' });

onMounted(() => {
  loadData();
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    document.querySelectorAll('.fca-chart-body').forEach(el => resizeObserver!.observe(el));
  }
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  loadRevision++;
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
          :clearable="false"
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
    <el-alert v-if="external.error.value" :title="external.error.value" type="warning" :closable="false" class="fca-error" />
    <BlacklakeCostAnalysis v-if="external.data.value" v-loading="loading" :data="external.data.value" :factory-id="factoryId"
      :can-view-price="canViewPrice" :date-range="externalDateRange" />

    <!-- ── 全局空状态: 无任何 gold 数据 ────────────────────── -->
    <template v-else-if="!loading && !loadError && !hasAnyData">
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
            <ChartInsightProvider
              :chart="costPieInsight"
              :perms="insightPerms"
              :factory-id="factoryId"
              :auto-tier2="false"
              depth="detailed"
              class="fca-insight"
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
            <ChartInsightProvider
              :chart="costTrendInsight"
              :perms="insightPerms"
              :factory-id="factoryId"
              :auto-tier2="false"
              depth="detailed"
              class="fca-insight"
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
          <div class="fca-chart-title">
            出成率趋势（日度）
            <span class="fca-chart-note">出成率 = 良品 ÷ 实际产出</span>
          </div>
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
          <ChartInsightProvider
            :chart="yieldInsight"
            :perms="insightPerms"
            :factory-id="factoryId"
            :auto-tier2="false"
            depth="detailed"
            class="fca-insight"
          />
        </div>
      </div>

      <!-- ── 产品成本对比 ───────────────────────────────────── -->
      <div class="fca-section">
        <div class="fca-section-title">
          {{ canViewPrice ? '产品成本对比' : '产品出成率对比' }}
        </div>
        <div class="fca-chart-card fca-chart-full">
          <div class="fca-chart-title fca-chart-title--with-tools">
            <span>
              {{ canViewPrice ? '各产品单位成本 (Top 10)' : '各产品出成率 (Top 10)' }}
              <span v-if="canViewPrice" class="fca-chart-note">同单位的产品排在一起，盒和袋的单价不直接比大小</span>
            </span>
            <ChartSortControl v-if="hasProducts" v-model="productSortMode" />
          </div>
          <div v-show="hasProducts" ref="productBarRef" class="fca-chart-body fca-chart-body--bar" />
          <el-empty
            v-if="!hasProducts && !loading"
            description="暂无产品成本对比数据"
            :image-size="80"
            class="fca-chart-empty"
          />
          <ChartInsightProvider
            :chart="productInsight"
            :perms="insightPerms"
            :factory-id="factoryId"
            :auto-tier2="false"
            depth="detailed"
            class="fca-insight"
          />
        </div>
      </div>

      <!-- ── 产品明细表格 (canViewPrice 或 仅出成率) ─────────── -->
      <div class="fca-section fca-detail-section" v-if="hasProducts">
        <div class="fca-section-title">产品明细</div>
        <!-- 手机宽度：卡片（产品 + 单位成本 + 总成本为主），老板最该看的列不藏在横向滚动里 -->
        <div v-if="isNarrow" class="fca-product-cards">
          <div v-for="row in productData!.products" :key="`${row.productTypeId}-${row.unit}`" class="fca-product-card">
            <div class="fca-product-card-name">{{ productLabel(row.productTypeId, row.productName) }}</div>
            <div v-if="canViewPrice" class="fca-product-card-main">
              <div>
                <small>单位成本</small>
                <b>{{ unitCostLabel(row.costPerUnit, productUnit(row)) }}</b>
                <small v-if="costCoverageText(row.costedBatchCount, row.batchCount)" class="fca-coverage">
                  {{ costCoverageText(row.costedBatchCount, row.batchCount) }}
                </small>
              </div>
              <div>
                <small>总成本</small>
                <b>{{ row.totalCost == null ? '—' : `¥${fmtMoney(row.totalCost)}` }}</b>
              </div>
            </div>
            <div class="fca-product-card-meta">
              <span>{{ row.batchCount }} 批</span>
              <span>产出 {{ fmtQty(row.totalActualQty) }} {{ productUnit(row) }}</span>
              <span>出成率 {{ fmtPercent(row.avgYieldRate) }}</span>
            </div>
            <div v-if="canViewPrice" class="fca-product-card-meta">
              <span>材料 ¥{{ fmtMoney(row.totalMaterialCost) }}</span>
              <span>人工 ¥{{ fmtMoney(row.totalLaborCost) }}</span>
              <span>设备 ¥{{ fmtMoney(row.totalEquipmentCost) }}</span>
              <span>其他 ¥{{ fmtMoney(row.totalOtherCost) }}</span>
            </div>
          </div>
        </div>
        <el-table v-else :data="productData!.products" stripe border size="small" empty-text="暂无数据">
          <el-table-column label="产品" min-width="192" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => productLabel(r.productTypeId, r.productName))">
            <template #default="{ row }">
              <span :title="row.productTypeId">{{ productLabel(row.productTypeId, row.productName) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="batchCount" label="批次数" width="92" align="right" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.batchCount)" />
          <el-table-column label="单位" width="102" align="center" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.unit)">
            <template #default="{ row }">{{ productUnit(row) }}</template>
          </el-table-column>
          <el-table-column label="实际产出" width="122" align="right" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.totalActualQty)">
            <template #default="{ row }">
              {{ fmtQty(row.totalActualQty) }}
            </template>
          </el-table-column>
          <el-table-column label="出成率" width="102" align="center" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.avgYieldRate)">
            <template #default="{ row }">
              {{ fmtPercent(row.avgYieldRate) }}
            </template>
          </el-table-column>
          <!-- 成本列仅 canViewPrice 可见 -->
          <template v-if="canViewPrice">
            <el-table-column label="材料成本(¥)" width="122" align="right" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.totalMaterialCost)">
              <template #default="{ row }">
                {{ fmtMoney(row.totalMaterialCost) }}
              </template>
            </el-table-column>
            <el-table-column label="人工成本(¥)" width="122" align="right" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.totalLaborCost)">
              <template #default="{ row }">
                {{ fmtMoney(row.totalLaborCost) }}
              </template>
            </el-table-column>
            <!-- 设备、其他两列：材料 + 人工 + 设备 + 其他 = 总成本，各列能加出来 -->
            <el-table-column label="设备成本(¥)" width="122" align="right" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.totalEquipmentCost)">
              <template #default="{ row }">
                {{ fmtMoney(row.totalEquipmentCost) }}
              </template>
            </el-table-column>
            <el-table-column label="其他成本(¥)" width="122" align="right" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.totalOtherCost)">
              <template #default="{ row }">
                {{ fmtMoney(row.totalOtherCost) }}
              </template>
            </el-table-column>
            <el-table-column label="总成本(¥)" width="112" align="right" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.totalCost)">
              <template #default="{ row }">
                {{ fmtMoney(row.totalCost) }}
              </template>
            </el-table-column>
            <el-table-column label="单位成本" width="162" align="right" sortable :sort-orders="TABLE_SORT_ORDERS" :sort-method="tableSortBy((r: ProductCostItem) => r.costPerUnit)">
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
.fca-insight {
  margin-top: 8px;
}

.fca-chart-title--with-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

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

.fca-chart-note {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: #86909c;
}

/* ── Product cards (narrow) ──────────────────── */
.fca-product-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.fca-product-card {
  border: 1px solid var(--cs-border);
  border-radius: 8px;
  padding: 12px;
}

.fca-product-card-name {
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 8px;
}

.fca-product-card-main {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 6px;
  font-variant-numeric: tabular-nums;
}

.fca-product-card-main small {
  display: block;
  color: #86909c;
  font-size: 12px;
}

.fca-product-card-main b {
  font-size: 16px;
  color: #1d2129;
}

.fca-product-card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 12px;
  color: #4e5969;
  font-variant-numeric: tabular-nums;
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

@media (max-width: 640px) {
  .fca-root {
    padding: 12px;
  }
  /* KPI 两列排，不再四张竖排占满一屏 */
  .fca-kpi-row {
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .fca-kpi-card {
    padding: 10px 12px;
  }
  .fca-kpi-value {
    font-size: 1.25rem;
  }
  .fca-detail-section {
    padding: 12px;
  }
  .fca-chart-body--bar {
    height: 260px;
  }
}
</style>
