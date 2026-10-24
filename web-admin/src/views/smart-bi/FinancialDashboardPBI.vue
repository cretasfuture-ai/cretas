<script setup lang="ts">
/**
 * FinancialDashboardPBI - 财务分析看板 (Power BI Style)
 * Orchestrates all 7 financial chart types with AI analysis and PPT export
 */
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import FinanceAnalysis from './FinanceAnalysis.vue';
import FinancePbiGoldView from './components/FinancePbiGoldView.vue';
import { resolveFinanceSection, shouldRenderGoldFinanceView } from './financeDashboardSection';
import {
  periodSelectionToRequest,
  chartGapReason,
  chartNotes,
  createDataVersion,
  buildChartNav,
  periodRequestLabel,
  dashboardRenderKey,
  createQueryHistory,
  findingSections,
} from './financialDashboardState';
import type { PeriodRequest } from './financialDashboardState';
// A3: 工厂成本卡 — 仅工厂租户 (FACTORY) 显示此 tab
import FactoryCostAnalysis from '@/views/production-analytics/FactoryCostAnalysis.vue';
import { DataAnalysis, Download, ArrowDown, Delete, Calendar, MoreFilled } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { processEChartsOptions } from '@/utils/echarts-fmt';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { sparklinePath, sparklineSVG } from '@/utils/sparkline';

/** Deep clone that handles ECharts options with functions (structuredClone can't clone functions) */
function safeClone<T>(obj: T): T {
  try {
    return structuredClone(obj);
  } catch {
    // Fallback: JSON round-trip (drops functions, but processEChartsOptions re-adds formatters)
    try {
      return JSON.parse(JSON.stringify(obj));
    } catch {
      return obj;
    }
  }
}

// Components
import PeriodSelector from '@/components/smartbi/PeriodSelector.vue';
import ChartSkeleton from '@/components/smartbi/ChartSkeleton.vue';
import PresentationMode from '@/components/smartbi/PresentationMode.vue';
import type { Slide } from '@/components/smartbi/PresentationMode.vue';
import SankeyChart from '@/components/smartbi/SankeyChart.vue';
import SmallMultiplesChart from '@/components/smartbi/SmallMultiplesChart.vue';
import BookmarkPanel from '@/components/smartbi/BookmarkPanel.vue';
import ConditionalFormatPanel from '@/components/smartbi/ConditionalFormatPanel.vue';
import SmartBIEmptyState from '@/components/smartbi/SmartBIEmptyState.vue';
import type { BookmarkState } from '@/views/smart-bi/composables/useBookmarks';
import type { SmallMultiplesConfig } from '@/components/smartbi/SmallMultiplesChart.vue';
import ChartInsightProvider from './components/ChartInsightProvider.vue';
import type { ChartWithMeta, ChartMeta, UserPermissions } from './components/chartInsight';

// API
import {
  batchGenerate,
  analyzeChart,
  exportPPT,
  exportPDF,
  exportExcel,
  type ChartResult,
  type DashboardResponse,
  type FinancialDashboardRequest,
} from '@/api/smartbi/financial-dashboard';

// Types from PeriodSelector
import type { PeriodSelection } from '@/components/smartbi/PeriodSelector.vue';

// ---- State ----
const isDarkMode = ref(false);
const activeFilter = ref<{ dimension: string; value: string } | null>(null);
const autoRefreshInterval = ref<number>(0); // 0=off, 60000=1m, 300000=5m, 900000=15m

// Fix 72: Global slicer filters (Power BI / Looker style)
const slicerFilters = ref<Record<string, string[]>>({});
// 数据源 / 月份芯片 / 期间都是先改再请求的: 记住页面上正显示的那一次, 请求失败就退回,
// 否则芯片显示「3月」而页面还是全年的数, 或标签写「数据源 #99999999」而页面还是 5250 的数
interface DashboardQuery {
  uploadId: number | null;
  uploadIdInput: string;
  demo: boolean;
  slicers: Record<string, string[]>;
  period: PeriodSelection;
}
const queryHistory = createQueryHistory<DashboardQuery>();
function currentQuery(): DashboardQuery {
  return {
    uploadId: uploadId.value,
    uploadIdInput: uploadIdInput.value,
    demo: isInDemoMode.value,
    slicers: slicerFilters.value,
    period: periodSelection.value,
  };
}
function restoreLastApplied() {
  const applied = queryHistory.lastApplied();
  if (!applied) return;
  uploadId.value = applied.uploadId;
  uploadIdInput.value = applied.uploadIdInput;
  isInDemoMode.value = applied.demo;
  slicerFilters.value = applied.slicers;
  periodSelection.value = applied.period;
}
const availableDimensions = ref<{ name: string; values: string[] }[]>([]);
let autoRefreshTimer: ReturnType<typeof setInterval> | null = null;
const useSvgRenderer = ref(false); // D4: SVG renderer toggle
const isInDemoMode = ref(false); // Track demo mode for slicer re-generation

// P4: 顶层 section tab — 财务 PBI 看板(dashboard) / 财务数据分析(analysis)。
// 注意: 与下方 chart activeTab 完全独立 (后者是图表类型 key)。
// 修 #10: 餐饮租户默认落「财务数据分析」(gold 全历史, 进页即见数据); 工厂租户保持 PBI 看板默认。
// 显式 ?section= 永远优先 (保书签 / 工厂用户主动选看板)。
const route = useRoute();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const sectionTab = ref(resolveFinanceSection(route.query, authStore.factoryType));
watch(() => route.query.section, () => { sectionTab.value = resolveFinanceSection(route.query, authStore.factoryType); });

// follow-up (fu2): 餐饮租户在 PBI 看板(dashboard)子页 → 自动渲染 Gold 财务全量视图
// (KPI 卡 + 月度营收趋势 + 门店排行 + 渠道占比), 免上传 / 免手动生成。
// PBI 看板原本 upload_id 绑定, 餐饮租户没传财务 Excel → 只能看到空态。
// 工厂租户保持原 upload-based PBI 看板 (PeriodSelector + 生成看板) 不变。
// 餐饮租户可点「上传财务文件」临时切到 upload 路径 (高级)。
const showUploadForRestaurant = ref(false);
const goldFinanceMode = computed(() =>
  shouldRenderGoldFinanceView(authStore.factoryType, sectionTab.value) && !showUploadForRestaurant.value);

// Tab navigation state
const viewMode = ref<'tab' | 'grid'>('tab');
const activeTab = ref('budget_achievement');
const conclusionsText = ref('');
const conclusionsLoading = ref(false);

// Follow-up interaction state
interface FollowUpItem { question: string; answer: string }
const followUpsByType = ref<Record<string, FollowUpItem[]>>({});
const followUpInput = ref('');
const followUpLoading = ref(false);

// D2: User annotations (persisted in localStorage)
interface Annotation { text: string; x: number; y: number; chartType: string; color: string; id: number }
const annotations = ref<Record<string, Annotation[]>>(
  JSON.parse(localStorage.getItem('financial-dashboard-annotations') || '{}')
);
const annotationDialogVisible = ref(false);
const annotationForm = ref({ text: '', color: '#e6a23c', chartType: '', x: 0, y: 0 });
let annotationIdCounter = Date.now();

function saveAnnotations() {
  localStorage.setItem('financial-dashboard-annotations', JSON.stringify(annotations.value));
}

function openAnnotationDialog(chartType: string, x: number, y: number) {
  annotationForm.value = { text: '', color: '#e6a23c', chartType, x, y };
  annotationDialogVisible.value = true;
}

function confirmAnnotation() {
  const { text, color, chartType, x, y } = annotationForm.value;
  if (!text.trim()) return;
  if (!annotations.value[chartType]) annotations.value[chartType] = [];
  annotations.value[chartType].push({ text: text.trim(), x, y, chartType, color, id: annotationIdCounter++ });
  saveAnnotations();
  annotationDialogVisible.value = false;
  // Re-render chart to show annotation
  const domEl = chartDomRefs.value.get(chartType);
  if (domEl) renderGenericChart(chartType, domEl);
}

function removeAnnotation(chartType: string, annotationId: number) {
  if (!annotations.value[chartType]) return;
  annotations.value[chartType] = annotations.value[chartType].filter(a => a.id !== annotationId);
  saveAnnotations();
  const domEl = chartDomRefs.value.get(chartType);
  if (domEl) renderGenericChart(chartType, domEl);
}

function clearAllAnnotations(chartType: string) {
  annotations.value[chartType] = [];
  saveAnnotations();
  const domEl = chartDomRefs.value.get(chartType);
  if (domEl) renderGenericChart(chartType, domEl);
}

const uploadId = ref<number | null>(null);
const uploadIdInput = ref<string>('');
// 默认「全部历史」: type='year' + allHistory 标记 → 后端 period_type='all'
// (不按年也不按月过滤, 即该数据源全部数据)。
const periodSelection = ref<PeriodSelection>({
  type: 'year',
  year: new Date().getFullYear(),
  value: String(new Date().getFullYear()),
  compareEnabled: false,
  allHistory: true,
});

const isGenerating = ref(false);
// 期间选择收进一个按钮; 弹层受控 (内部下拉框挂在 body 上, 点它们不能把弹层关掉)
const periodPopoverOpen = ref(false);
// 条件格式 (收进「更多」后用对话框打开)
const conditionalFormatOpen = ref(false);
const dashboardResponse = ref<DashboardResponse | null>(null);

// Per-chart AI analysis state
const analysisByType = ref<Record<string, string>>({});
const analysisLoadingByType = ref<Record<string, boolean>>({});
// 看板数据版本: AI 分析请求回来时版本已变 (期间/数据源已换) 就丢弃结果
const dataVersion = createDataVersion();
const analysisExpandedByType = ref<Record<string, boolean>>({});

// PPT export
const isExportingPPT = ref(false);
const isExportingPDF = ref(false);
const isExportingExcel = ref(false);
const exportStageText = ref('');

// Export progress feedback
const exportProgress = ref({
  visible: false,
  currentStep: 0,
  steps: [] as string[],
  percentage: 0,
});
const EXPORT_STEPS: Record<string, string[]> = {
  ppt: ['准备图表数据', '渲染图表截图', '生成幻灯片', '打包下载'],
  pdf: ['准备页面内容', '渲染图表', '生成PDF', '下载文件'],
  excel: ['整理数据表', '格式化单元格', '生成文件', '下载'],
};
function startExportProgress(type: string) {
  exportProgress.value.steps = EXPORT_STEPS[type] || [];
  exportProgress.value.currentStep = 0;
  exportProgress.value.percentage = 0;
  exportProgress.value.visible = true;
}
async function advanceExportStep() {
  const ep = exportProgress.value;
  ep.currentStep++;
  ep.percentage = Math.round((ep.currentStep / ep.steps.length) * 100);
  await new Promise(r => setTimeout(r, 300));
}
function finishExportProgress() {
  exportProgress.value.percentage = 100;
  exportProgress.value.currentStep = exportProgress.value.steps.length;
  setTimeout(() => { exportProgress.value.visible = false; }, 600);
}

// Presentation mode
const isPresentationVisible = ref(false);

// Bookmarks
const showBookmarkPanel = ref(false);

// Small Multiples
const smallMultiplesVisible = ref(false);
const smallMultiplesDimension = ref('category');

// Conditional Formatting (panel is self-contained via service singleton)

// Chart instance refs for getDataURL
const chartRefSankey = ref<InstanceType<typeof SankeyChart> | null>(null);
// Generic chart DOM refs for existing chart components
const chartDomRefs = ref<Map<string, HTMLElement>>(new Map());

// Lazy rendering: track which chart cards are visible in the viewport
const visibleCharts = reactive(new Set<string>());
let lazyObserver: IntersectionObserver | null = null;
const observedElements = new WeakSet<Element>();

function setupLazyObserver(): void {
  if (lazyObserver) return;
  lazyObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const chartType = (entry.target as HTMLElement).dataset.chartType;
        if (!chartType) continue;
        if (entry.isIntersecting) {
          visibleCharts.add(chartType);
          // Once visible, render immediately if data exists
          const domEl = chartDomRefs.value.get(chartType);
          if (domEl && getChart(chartType)) {
            requestAnimationFrame(() => renderGenericChart(chartType, domEl));
          }
        }
      }
    },
    { rootMargin: '200px' },
  );
}

/** Mark all charts visible with staggered fade-in delay (initial load). */
function revealAllCharts(): void {
  const allTypes = charts.value.map((c) => c.chartType);
  allTypes.forEach((t, i) => {
    setTimeout(() => visibleCharts.add(t), i * 60);
  });
}

function observeChartCard(el: HTMLElement, chartType: string): void {
  setupLazyObserver();
  if (!lazyObserver || observedElements.has(el)) return;
  el.dataset.chartType = chartType;
  lazyObserver.observe(el);
  observedElements.add(el);
}

function teardownLazyObserver(): void {
  if (lazyObserver) {
    lazyObserver.disconnect();
    lazyObserver = null;
  }
  visibleCharts.clear();
}

// ---- Computed ----
// 期间选择 → 后端参数 (全部历史=all / 单年=year / 其余=month_range), 规则与单测见 financialDashboardState.ts
const periodRequest = computed(() => periodSelectionToRequest(periodSelection.value));
const periodRequestError = computed(() => ('error' in periodRequest.value ? periodRequest.value.error : null));
const resolvedPeriod = computed<PeriodRequest>(() => ('error' in periodRequest.value
  ? { periodType: 'year', year: periodSelection.value.year, startMonth: 1, endMonth: 12 }
  : periodRequest.value));
const periodButtonLabel = computed(() => periodRequestLabel(periodRequest.value));
const headline = computed(() => dashboardResponse.value?.headline ?? null);
const ruleFindings = computed(() => findingSections(dashboardResponse.value?.findings));

function applyPeriod() {
  periodPopoverOpen.value = false;
  // 已经出过看板就按新期间重出, 免得按钮上的期间和图表不是同一期
  if (dashboardResponse.value && !periodRequestError.value) generate();
}

const currentYear = computed(() => resolvedPeriod.value.year);
const periodType = computed(() => resolvedPeriod.value.periodType);
const startMonth = computed(() => resolvedPeriod.value.startMonth);
const endMonth = computed(() => resolvedPeriod.value.endMonth);

const charts = computed(() => dashboardResponse.value?.charts ?? []);

// P1 PERF fix: Pre-index charts by type to avoid O(n) find() per template access (~36+ calls per render)
const chartMap = computed(() => {
  const map = new Map<string, ChartResult>();
  for (const c of charts.value) map.set(c.chartType, c);
  return map;
});

function getChart(chartType: string): ChartResult | null {
  return chartMap.value.get(chartType) ?? null;
}

// P1 PERF fix: Pre-compute anomaly detection results to avoid recalculation in template
const anomalyMap = computed(() => {
  const map = new Map<string, string[]>();
  for (const [type, chart] of chartMap.value) {
    if (chart.kpis?.length) {
      map.set(type, detectAnomalies(chart.kpis));
    }
  }
  return map;
});

function getAnomalies(chartType: string): string[] {
  return anomalyMap.value.get(chartType) ?? [];
}

// ── Phase B Moderate: ChartInsightProvider wiring ─────────────────────────────
// Finance domain; canViewPrice gates absolute ¥ display in insight text.
const insightPerms = computed<UserPermissions>(() => ({
  canViewFinance: permissionStore.canViewPrice,
}));

/**
 * Chart-type → (chartType, meta) mapping for Tier1-coverable slots.
 *
 * Wired (bar/line/pie): LINE→TREND, BAR(ranking)→RANKING, BAR(2-series)→COMPARISON, PIE→PROPORTION.
 * Skipped (exotic): pnl_waterfall, cash_flow_waterfall, cost_flow_sankey, small_multiples, bullet_chart.
 *
 * meta inference: all keys have domain:'finance'. xDim and yMetric are inferred from the chart key.
 */
const FINANCE_CHART_META: Record<string, { chartType: string; meta: ChartMeta }> = {
  // BAR 2-series COMPARISON: actual vs budget
  budget_achievement: {
    chartType: 'comparison',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // LINE×2 COMPARISON: current year vs last year
  yoy_mom_comparison: {
    chartType: 'comparison',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // BAR(2-series) COMPARISON: category this year vs last year
  category_yoy_comparison: {
    chartType: 'comparison',
    meta: { xDim: 'category', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // PIE/donut PROPORTION: product structure
  category_structure_donut: {
    chartType: 'pie',
    meta: { xDim: 'product', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // BAR RANKING: channel revenue ranking
  channel_analysis: {
    chartType: 'bar',
    meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // LINE TREND: cashflow over time
  cashflow_trend: {
    chartType: 'line',
    meta: { xDim: 'time', yMetric: 'cost', aggregation: 'sum', domain: 'finance' },
  },
  // BAR COMPARISON: hr cost analysis (budget vs actual or period vs period)
  hr_cost_analysis: {
    chartType: 'comparison',
    meta: { xDim: 'time', yMetric: 'cost', aggregation: 'sum', domain: 'finance' },
  },
  // SKIPPED: pnl_waterfall → exotic WATERFALL
  // SKIPPED: cash_flow_waterfall → exotic WATERFALL
  // SKIPPED: cost_flow_sankey → exotic SANKEY
  // SKIPPED: small_multiples → exotic small-multiples
  // SKIPPED: bullet_chart → exotic gauge-like, not 1-D series
};

/**
 * Build ChartWithMeta from a ChartResult by extracting series/xAxis from echartsOption.
 * Returns null when:
 *  - chartType is not in FINANCE_CHART_META (exotic or skipped)
 *  - ChartResult is null / has no echartsOption
 */
function buildFinanceChartWithMeta(chartType: string): ChartWithMeta | null {
  const mapping = FINANCE_CHART_META[chartType];
  if (!mapping) return null; // exotic or not wired
  const result = getChart(chartType);
  if (!result?.echartsOption) return null;

  const opt = result.echartsOption as {
    xAxis?: { data?: string[] };
    series?: Array<{ type?: string; name?: string; data?: unknown[] }>;
    [key: string]: unknown;
  };

  // Extract xAxis labels + series from echartsOption
  const xAxis = opt.xAxis as { data?: string[] } | undefined;
  const series = Array.isArray(opt.series) ? opt.series : [];

  return {
    chartType: mapping.chartType,
    title: result.title,
    meta: mapping.meta,
    config: {
      xAxis: xAxis ? { data: xAxis.data ?? [] } : undefined,
      series: series.map((s) => ({
        type: (s as { type?: string }).type,
        name: (s as { name?: string }).name,
        data: Array.isArray((s as { data?: unknown[] }).data) ? (s as { data?: unknown[] }).data : [],
      })),
    },
  };
}

// 去掉与其他页面重复的图表:
// kpi_scorecard → 经营驾驶舱, expense_yoy_budget → 财务分析-成本, gross_margin_trend → 财务分析-利润,
// product_ranking → 销售分析+驾驶舱, ar_aging → 财务分析-应收, variance_analysis → 财务分析-预算
const chartTypes = [
 { key: 'budget_achievement', label: '预算达成分析', icon: '', group: '核心财务', order: 1 },
 { key: 'yoy_mom_comparison', label: '同环比分析', icon: '', group: '核心财务', order: 2 },
 { key: 'pnl_waterfall', label: '损益表瀑布图', icon: '', group: '核心财务', order: 3 },
 { key: 'expense_yoy_budget', label: '费用同比及预算达成', icon: '', group: '核心财务', order: 4 },
 { key: 'gross_margin_trend', label: '各月毛利率', icon: '', group: '核心财务', order: 5 },
 { key: 'category_yoy_comparison', label: '品类同期对比', icon: '', group: '品类与渠道', order: 4 },
 { key: 'category_structure_donut',label: '产品结构', icon: '', group: '品类与渠道', order: 5 },
 { key: 'channel_analysis', label: '渠道分析', icon: '', group: '品类与渠道', order: 6 },
 { key: 'business_unit_analysis', label: '业务单元分析', icon: '', group: '品类与渠道', order: 6 },
 { key: 'cash_flow_waterfall', label: '现金流量瀑布图', icon: '', group: '现金流与成本', order: 7 },
 { key: 'cashflow_trend', label: '现金流趋势', icon: '', group: '现金流与成本', order: 8 },
 { key: 'cost_flow_sankey', label: '成本流向桑基图', icon: '', group: '现金流与成本', order: 9 },
 { key: 'bullet_chart', label: '目标达成进度', icon: '', group: '运营专项', order: 10 },
 { key: 'hr_cost_analysis', label: '人力成本分析', icon: '', group: '运营专项', order: 11 },
 { key: 'small_multiples', label: '多维度对比矩阵', icon: '', group: '运营专项', order: 12 },
];

// 图表切换条: 只列这份数据能出图的图表; 出不了的收进「还缺数据」并带原因
const chartNav = computed(() => buildChartNav(chartTypes, getChart, dashboardResponse.value?.unavailableCharts ?? []));

// 当前选中的图这份数据出不了时, 自动切到第一张能出图的 (避免一打开就是空态)
watch(chartNav, (nav) => {
  if (activeTab.value === 'conclusions') return;
  const available = nav.groups.flatMap(g => g.items.map(i => i.key));
  if (available.length && !available.includes(activeTab.value)) activeTab.value = available[0];
});

const presentationSlides = computed<Slide[]>(() => {
  const slides: Slide[] = [{ type: 'cover' }];
  for (const ct of chartTypes) {
    if (getChart(ct.key)) {
      slides.push({ type: 'chart', chartType: ct.key, title: ct.label });
    }
  }
  slides.push({ type: 'conclusion' });
  return slides;
});

// Built-in demo data — comprehensive financial data covering all chart builder requirements:
// - 月份+品类+预算+实际+上年 → budget_achievement, yoy_mom, expense_yoy_budget, category_yoy, category_structure
// - 项目(P&L items) → pnl_waterfall, cost_flow_sankey
// - 毛利率 → gross_margin_trend
const DEMO_RAW_DATA: Record<string, unknown>[] = [
  // ---- 品类月度收入 (6个月 × 3品类 = 18行) ----
  // 统一 schema: 月份, 品类, 项目, 预算, 实际, 上年
  {'月份':'1月','品类':'酱料','项目':'营业收入','预算':2800,'实际':3100,'上年':1800},
  {'月份':'2月','品类':'酱料','项目':'营业收入','预算':2900,'实际':2700,'上年':1900},
  {'月份':'3月','品类':'酱料','项目':'营业收入','预算':3000,'实际':3200,'上年':2000},
  {'月份':'4月','品类':'酱料','项目':'营业收入','预算':3100,'实际':2950,'上年':2100},
  {'月份':'5月','品类':'酱料','项目':'营业收入','预算':3200,'实际':3400,'上年':2200},
  {'月份':'6月','品类':'酱料','项目':'营业收入','预算':3300,'实际':3100,'上年':2300},
  {'月份':'1月','品类':'汤料','项目':'营业收入','预算':2000,'实际':2200,'上年':1500},
  {'月份':'2月','品类':'汤料','项目':'营业收入','预算':2100,'实际':1900,'上年':1600},
  {'月份':'3月','品类':'汤料','项目':'营业收入','预算':2200,'实际':2400,'上年':1700},
  {'月份':'4月','品类':'汤料','项目':'营业收入','预算':2300,'实际':2150,'上年':1800},
  {'月份':'5月','品类':'汤料','项目':'营业收入','预算':2400,'实际':2500,'上年':1900},
  {'月份':'6月','品类':'汤料','项目':'营业收入','预算':2500,'实际':2350,'上年':2000},
  {'月份':'1月','品类':'底料','项目':'营业收入','预算':1500,'实际':1600,'上年':1200},
  {'月份':'2月','品类':'底料','项目':'营业收入','预算':1600,'实际':1400,'上年':1300},
  {'月份':'3月','品类':'底料','项目':'营业收入','预算':1700,'实际':1800,'上年':1400},
  {'月份':'4月','品类':'底料','项目':'营业收入','预算':1800,'实际':1750,'上年':1500},
  {'月份':'5月','品类':'底料','项目':'营业收入','预算':1900,'实际':2000,'上年':1600},
  {'月份':'6月','品类':'底料','项目':'营业收入','预算':2000,'实际':1850,'上年':1700},
  // ---- P&L 费用项目 (月度化，6个月 × 4类费用 = 24行) ----
  {'月份':'1月','项目':'营业成本','预算':2625,'实际':2473,'上年':2065},
  {'月份':'2月','项目':'营业成本','预算':2625,'实际':2520,'上年':2065},
  {'月份':'3月','项目':'营业成本','预算':2625,'实际':2590,'上年':2065},
  {'月份':'4月','项目':'营业成本','预算':2625,'实际':2650,'上年':2065},
  {'月份':'5月','项目':'营业成本','预算':2625,'实际':2780,'上年':2065},
  {'月份':'6月','项目':'营业成本','预算':2625,'实际':2490,'上年':2065},
  {'月份':'1月','项目':'销售费用','预算':600,'实际':565,'上年':472},
  {'月份':'2月','项目':'销售费用','预算':600,'实际':580,'上年':472},
  {'月份':'3月','项目':'销售费用','预算':600,'实际':610,'上年':472},
  {'月份':'4月','项目':'销售费用','预算':600,'实际':550,'上年':472},
  {'月份':'5月','项目':'销售费用','预算':600,'实际':620,'上年':472},
  {'月份':'6月','项目':'销售费用','预算':600,'实际':590,'上年':472},
  {'月份':'1月','项目':'管理费用','预算':450,'实际':424,'上年':354},
  {'月份':'2月','项目':'管理费用','预算':450,'实际':410,'上年':354},
  {'月份':'3月','项目':'管理费用','预算':450,'实际':445,'上年':354},
  {'月份':'4月','项目':'管理费用','预算':450,'实际':430,'上年':354},
  {'月份':'5月','项目':'管理费用','预算':450,'实际':460,'上年':354},
  {'月份':'6月','项目':'管理费用','预算':450,'实际':415,'上年':354},
  {'月份':'1月','项目':'研发费用','预算':300,'实际':283,'上年':236},
  {'月份':'2月','项目':'研发费用','预算':300,'实际':275,'上年':236},
  {'月份':'3月','项目':'研发费用','预算':300,'实际':310,'上年':236},
  {'月份':'4月','项目':'研发费用','预算':300,'实际':290,'上年':236},
  {'月份':'5月','项目':'研发费用','预算':300,'实际':305,'上年':236},
  {'月份':'6月','项目':'研发费用','预算':300,'实际':280,'上年':236},
];

// ---- Methods ----
// Bug #2: track in-flight generate so double-clicks and unmount can cancel/dedupe.
// `isComponentUnmounted` flag prevents state updates after the view is torn down.
let isComponentUnmounted = false;

async function generate(useDemo?: boolean) {
  // Bug #2: dedupe — ignore if a generate is already in flight (prevents double-click canceled errors)
  if (isGenerating.value) {
    ElMessage.info('正在生成中，请稍候...');
    return;
  }
  // If not explicitly passed, use tracked demo mode
  if (useDemo === undefined) useDemo = isInDemoMode.value;
  if (useDemo) isInDemoMode.value = true;
  const id = uploadId.value ?? (uploadIdInput.value ? parseInt(uploadIdInput.value, 10) : null);
  if (!useDemo && !id) {
    ElMessage.warning('请先输入数据源ID或选择已上传的数据');
    return;
  }

  if (id && isNaN(id)) {
    ElMessage.warning('数据源ID格式不正确');
    return;
  }

  if (periodRequestError.value) {
    ElMessage.warning(periodRequestError.value);
    return;
  }

  isGenerating.value = true;
  // 新一轮数据: 旧的单图分析、总结论、追问一律作废, 迟到的旧响应按版本号丢弃
  dataVersion.bump();
  analysisByType.value = {};
  analysisExpandedByType.value = {};
  analysisLoadingByType.value = {};
  conclusionsText.value = '';
  followUpsByType.value = {};

  const payload: FinancialDashboardRequest = {
    year: currentYear.value,
    period_type: periodType.value,
    start_month: startMonth.value,
    end_month: endMonth.value,
  };
  if (useDemo) {
    payload.raw_data = DEMO_RAW_DATA;
  } else if (id) {
    payload.upload_id = id;
  }
  // Fix 72: Pass slicer filters
  const activeSlicers = Object.entries(slicerFilters.value).filter(([, vals]) => vals.length > 0);
  if (activeSlicers.length > 0) {
    (payload as Record<string, unknown>).filters = Object.fromEntries(activeSlicers);
  }

  try {
    const resp = await batchGenerate(payload);
    // Bug #2: bail if the component unmounted while the request was in flight
    if (isComponentUnmounted) return;
    if (resp.success) {
      dashboardResponse.value = resp;
      queryHistory.commit(currentQuery());
      // Fix 72: Extract available filter dimensions from response
      if (resp.availableDimensions) {
        availableDimensions.value = resp.availableDimensions;
      } else if (useDemo) {
        // Extract dimensions from demo data
        const dims = new Map<string, Set<string>>();
        for (const row of DEMO_RAW_DATA) {
          for (const [k, v] of Object.entries(row)) {
            if (typeof v === 'string' && !['月份'].includes(k)) {
              if (!dims.has(k)) dims.set(k, new Set());
              dims.get(k)!.add(v);
            }
          }
        }
        availableDimensions.value = Array.from(dims.entries())
          .filter(([, vals]) => vals.size >= 2 && vals.size <= 20)
          .map(([name, vals]) => ({ name, values: Array.from(vals) }));
      }
      // Reveal all chart cards with staggered fade-in
      nextTick(() => revealAllCharts());
      // 只给当前查看的图自动生成 AI 分析 (其余图点「AI分析」再生成), 避免一次触发几十个请求
      if (viewMode.value === 'tab') nextTick(() => requestAnalysis(activeTab.value));
    } else {
      restoreLastApplied();
      ElMessage({ message: resp.error || resp.message || '图表生成失败，请检查数据源', type: 'error', duration: 0, showClose: true });
    }
  } catch (err) {
    console.error('generate failed:', err);
    restoreLastApplied();
    // 后端给的原因 (如「未找到数据源 / 无权访问其他工厂的数据」) 原样显示, 错误提示不自动消失
    const serverMessage = (err as { serverMessage?: string } | null)?.serverMessage;
    ElMessage({ message: serverMessage || '请求失败，请稍后重试', type: 'error', duration: 0, showClose: true });
  } finally {
    isGenerating.value = false;
  }
}

function forceRequestAnalysis(chartType: string) {
  // Clear existing result to force re-fetch
  delete analysisByType.value[chartType];
  requestAnalysis(chartType);
}

async function requestAnalysis(chartType: string) {
  // Guard: skip if component has been unmounted (user navigated away)
  // Prevents TypeError: Failed to fetch when pending requests are cancelled.
  if (isComponentUnmounted) return;

  const chart = getChart(chartType);
  if (!chart) return;
  // 出不了图的不做 AI 分析 (缺数据的原因已经显示在图的位置)
  if (chartGapReason(chart)) return;

  // Skip if already loaded
  if (analysisByType.value[chartType]) return;

  // Prevent duplicate requests while loading
  if (analysisLoadingByType.value[chartType]) return;

  analysisLoadingByType.value[chartType] = true;
  const version = dataVersion.current();

  try {
    const resp = await analyzeChart({
      chart_type: chartType,
      analysis_context: chart.analysisContext || '',
    });
    // After await, re-check unmounted — state updates would be wasted + throw
    if (isComponentUnmounted) return;
    if (!dataVersion.isCurrent(version)) return; // 期间/数据已换, 这条分析属于旧图表
    if (resp.success) {
      analysisByType.value[chartType] = resp.analysis || '暂无分析结果';
    } else {
      analysisByType.value[chartType] = resp.error || 'AI分析暂时不可用';
    }
  } catch (err) {
    // Swallow abort-style errors silently when unmounted (expected on navigation).
    if (isComponentUnmounted) return;
    const name = err instanceof Error ? err.name : '';
    // "Failed to fetch" happens when browser aborts a pending fetch on navigation.
    if (name === 'AbortError' || (err instanceof TypeError && String(err).includes('fetch'))) {
      // Silent — expected race on page leave
      return;
    }
    console.error('analyzeChart failed:', err);
    if (dataVersion.isCurrent(version)) analysisByType.value[chartType] = '分析请求失败，请重试';
  } finally {
    if (!isComponentUnmounted && dataVersion.isCurrent(version)) {
      analysisLoadingByType.value[chartType] = false;
    }
  }
}

function setChartDomRef(chartType: string, el: Element | null) {
  if (el instanceof HTMLElement) {
    chartDomRefs.value.set(chartType, el);
  } else {
    chartDomRefs.value.delete(chartType);
  }
}

function collectChartImages(): Record<string, string> {
  const images: Record<string, string> = {};

  // Collect from SankeyChart
  if (chartRefSankey.value?.chartInstance) {
    try {
      images['cost_flow_sankey'] = chartRefSankey.value.chartInstance.getDataURL({
        type: 'png', pixelRatio: 2, backgroundColor: '#fff',
      });
    } catch (e) { console.warn('Failed to get Sankey image:', e); }
  }

  // Collect from generic DOM refs
  for (const [chartType, domEl] of chartDomRefs.value.entries()) {
    const instance = echarts.getInstanceByDom(domEl);
    if (instance) {
      try {
        images[chartType] = instance.getDataURL({
          type: 'png', pixelRatio: 2, backgroundColor: '#fff',
        });
      } catch (e) { console.warn(`Failed to get image for ${chartType}:`, e); }
    }
  }

  return images;
}

function handleExportCommand(command: string) {
  if (command === 'ppt') handleExportPPT();
  else if (command === 'pdf') handleExportPDF();
  else if (command === 'excel') handleExportExcel();
}

async function handleExportPPT() {
  if (!dashboardResponse.value) {
    ElMessage.warning('请先生成图表后再导出PPT');
    return;
  }

  isExportingPPT.value = true;
  startExportProgress('ppt');
  try {
    exportStageText.value = '正在收集图表数据...';
    await advanceExportStep();
    await nextTick();
    const chartImages = collectChartImages();
    const analysisResults: Record<string, string> = { ...analysisByType.value };

    await advanceExportStep();
    exportStageText.value = '正在生成PPT文件...';
    const blob = await exportPPT({
      upload_id: uploadId.value ?? undefined,
      year: currentYear.value,
      period_type: periodType.value,
      start_month: startMonth.value,
      end_month: endMonth.value,
      chart_images: chartImages,
      analysis_results: analysisResults,
      company_name: '白垩纪科技',
    });

    await advanceExportStep();
    if (blob) {
      exportStageText.value = '正在下载文件...';
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `财务分析看板_${currentYear.value}.pptx`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      await advanceExportStep();
      ElMessage.success('PPT导出成功');
    } else {
      ElMessage.error('PPT导出失败，请检查服务状态');
    }
  } catch (err) {
    console.error('exportPPT failed:', err);
    ElMessage.error('PPT导出失败');
  } finally {
    isExportingPPT.value = false;
    exportStageText.value = '';
    finishExportProgress();
  }
}

async function handleExportPDF() {
  if (!dashboardResponse.value) {
    ElMessage.warning('请先生成图表后再导出PDF');
    return;
  }
  isExportingPDF.value = true;
  startExportProgress('pdf');
  try {
    exportStageText.value = '正在收集图表数据...';
    await advanceExportStep();
    await nextTick();
    const chartImages = collectChartImages();
    const analysisResults: Record<string, string> = { ...analysisByType.value };
    await advanceExportStep();
    exportStageText.value = '正在生成PDF报告...';
    const blob = await exportPDF({
      chart_images: chartImages,
      analysis_results: analysisResults,
      company_name: '白垩纪科技',
      year: currentYear.value,
      period_type: periodType.value,
      start_month: startMonth.value,
      end_month: endMonth.value,
    });
    await advanceExportStep();
    if (blob) {
      exportStageText.value = '正在下载文件...';
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `财务分析报告_${currentYear.value}.pdf`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      await advanceExportStep();
      ElMessage.success('PDF导出成功');
    } else {
      ElMessage.error('PDF导出失败，请检查服务状态');
    }
  } catch (err) {
    console.error('exportPDF failed:', err);
    ElMessage.error('PDF导出失败');
  } finally {
    isExportingPDF.value = false;
    exportStageText.value = '';
    finishExportProgress();
  }
}

async function handleExportExcel() {
  if (!dashboardResponse.value) {
    ElMessage.warning('请先生成图表后再导出Excel');
    return;
  }
  isExportingExcel.value = true;
  startExportProgress('excel');
  try {
    exportStageText.value = '正在整理数据...';
    await advanceExportStep();
    const charts = dashboardResponse.value.charts.map(c => ({
      chartType: c.chartType,
      title: c.title,
      kpis: c.kpis,
      tableData: c.tableData,
    }));
    const analysisResults: Record<string, string> = { ...analysisByType.value };
    await advanceExportStep();
    exportStageText.value = '正在生成Excel文件...';
    const blob = await exportExcel({
      charts,
      analysis_results: analysisResults,
      company_name: '白垩纪科技',
      year: currentYear.value,
      period_type: periodType.value,
      start_month: startMonth.value,
      end_month: endMonth.value,
    });
    await advanceExportStep();
    if (blob) {
      exportStageText.value = '正在下载文件...';
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `财务分析数据_${currentYear.value}.xlsx`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      await advanceExportStep();
      ElMessage.success('Excel导出成功');
    } else {
      ElMessage.error('Excel导出失败，请检查服务状态');
    }
  } catch (err) {
    console.error('exportExcel failed:', err);
    ElMessage.error('Excel导出失败');
  } finally {
    isExportingExcel.value = false;
    exportStageText.value = '';
    finishExportProgress();
  }
}

// Get echartsOption from API response for direct pass-through rendering
function getChartEchartsOption(chart: ChartResult | null): Record<string, unknown> | null {
  if (!chart?.echartsOption || Object.keys(chart.echartsOption).length === 0) return null;
  return chart.echartsOption;
}

// Render a generic chart with echartsOption from API
function renderGenericChart(chartType: string, domEl: Element | null, retries = 0) {
  if (!domEl || !(domEl instanceof HTMLElement)) return;
  const chart = getChart(chartType);
  if (!chart?.echartsOption) return;

  // Skip if container has zero dimensions (not yet visible), retry up to 3 times
  if (domEl.clientWidth === 0 || domEl.clientHeight === 0) {
    if (retries < 3) {
      requestAnimationFrame(() => renderGenericChart(chartType, domEl, retries + 1));
    }
    return;
  }

  let instance = echarts.getInstanceByDom(domEl);
  if (!instance) {
    // D4: SVG renderer option
    const renderer = useSvgRenderer.value ? 'svg' : 'canvas';
    instance = echarts.init(domEl, isDarkMode.value ? 'cretas-dark' : undefined, { renderer });
  }
  const processed = processEChartsOptions(safeClone(chart.echartsOption) as Record<string, unknown>);

  // D2: Merge user annotations as ECharts graphic elements
  const chartAnnotations = annotations.value[chartType] || [];
  if (chartAnnotations.length > 0) {
    const graphicItems = chartAnnotations.flatMap((ann) => [
      // Pin icon background
      {
        type: 'circle' as const,
        shape: { r: 4, cx: 0, cy: 0 },
        left: `${ann.x}%`,
        top: `${ann.y}%`,
        style: { fill: ann.color || '#e6a23c', stroke: '#fff', lineWidth: 1.5 },
        silent: true,
        z: 100,
      },
      // Annotation text label
      {
        type: 'text' as const,
        left: `${ann.x + 1}%`,
        top: `${ann.y - 1}%`,
        style: {
          text: ann.text,
          fontSize: 11,
          fill: ann.color || '#e6a23c',
          fontWeight: 'bold' as const,
          backgroundColor: 'rgba(255,255,255,0.92)',
          padding: [3, 8],
          borderRadius: 4,
          borderColor: ann.color || '#e6a23c',
          borderWidth: 1,
          shadowColor: 'rgba(0,0,0,0.1)',
          shadowBlur: 4,
        },
        silent: true,
        z: 100,
      },
    ]);
    const existing = (processed.graphic as unknown[]) || [];
    processed.graphic = [...(Array.isArray(existing) ? existing : [existing]), ...graphicItems];
  }

  try {
    instance.setOption(processed, { notMerge: true });
  } catch {
    // ECharts dataZoom may throw 'grid.master' TypeError during initial render
    // when grid model isn't fully initialized — retry once after a frame
    requestAnimationFrame(() => {
      try { instance.setOption(processed, { notMerge: true }); } catch { /* ignore */ }
    });
  }

  // B1: Wire cross-chart click handler
  instance.off('click');
  instance.on('click', (params: Record<string, unknown>) => {
    handleChartClick(chartType, params as { name?: string; seriesName?: string; componentType?: string });
  });

  // D2: Double-click to add annotation (opens dialog)
  instance.off('dblclick');
  instance.on('dblclick', (params: Record<string, unknown>) => {
    const event = params.event as { offsetX?: number; offsetY?: number } | undefined;
    if (!event?.offsetX) return;
    const xPct = Math.round((event.offsetX / domEl.clientWidth) * 100);
    const yPct = Math.round((event.offsetY / domEl.clientHeight) * 100);
    openAnnotationDialog(chartType, xPct, yPct);
  });
}

// 每次生成完成都重画 (见 dashboardRenderKey): 只看图表数量时, 月份筛选 / 换期间后图表区一直空着
watch(() => dashboardRenderKey(dashboardResponse.value, isGenerating.value), async (key) => {
  if (!key) return;
  await nextTick();
  setupLazyObserver();
  // Double rAF: first rAF schedules after v-if DOM insertion, second after layout
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      // Observe all chart card containers for lazy rendering
      const cardEls = document.querySelectorAll<HTMLElement>('.chart-card[data-chart-type]');
      for (const cardEl of cardEls) {
        const chartType = cardEl.dataset.chartType;
        if (chartType) observeChartCard(cardEl, chartType);
      }
      // Render all charts that are visible or have DOM refs ready
      for (const [chartType, domEl] of chartDomRefs.value.entries()) {
        if (visibleCharts.has(chartType)) {
          renderGenericChart(chartType, domEl);
        }
      }
      // Fallback: render any chart container that is laid out but still has no ECharts instance.
      // ⚠️ 判据是「画布有没有初始化」而不是 visibleCharts: revealAllCharts() 在这 500ms 内
      // 就把所有类型标成可见, 旧判据会跳过 Tab 视图的画布 (它没有 IntersectionObserver 兜底),
      // 结果首次加载 Tab 视图一张图都不画, 切一次标签才出现。
      setTimeout(() => {
        for (const [chartType, domEl] of chartDomRefs.value.entries()) {
          if (domEl.clientHeight > 0 && !echarts.getInstanceByDom(domEl)) {
            visibleCharts.add(chartType);
            renderGenericChart(chartType, domEl);
          }
        }
      }, 500);
      // E1: Connect charts for synchronized crosshair
      connectCharts();
    });
  });
});

// Note: cleanup is handled in the onBeforeUnmount at bottom of script

const periodLabel = computed(() => dashboardResponse.value?.period?.label || '');

// Bookmark current dashboard state
const currentDashboardState = computed<BookmarkState>(() => ({
  uploadId: uploadId.value ?? undefined,
  periodType: periodType.value,
  year: currentYear.value,
  startMonth: startMonth.value,
  endMonth: endMonth.value,
  chartTypes: charts.value.map((c) => c.chartType),
  filters: {},
}));

// Small multiples config
const smallMultiplesConfig = computed<SmallMultiplesConfig>(() => ({
  splitBy: smallMultiplesDimension.value,
  chartType: 'bar',
  sharedAxes: true,
  maxCells: 12,
}));

// Chart data reshaped for SmallMultiplesChart (flatten all chart KPIs into flat records)
const chartDataForSmallMultiples = computed<Record<string, unknown>[]>(() => {
  const rows: Record<string, unknown>[] = [];
  for (const chart of charts.value) {
    if (chart.kpis?.length) {
      for (const kpi of chart.kpis) {
        rows.push({
          category: chart.chartType,
          month: kpi.label,
          actual: typeof kpi.value === 'number' ? kpi.value : 0,
        });
      }
    }
  }
  return rows;
});

// Bookmark apply handler
function applyBookmark(state: BookmarkState) {
  if (state.uploadId) {
    uploadId.value = state.uploadId;
    uploadIdInput.value = String(state.uploadId);
  }
  showBookmarkPanel.value = false;
  generate();
}

// Conditional formatting change handler
function onFormattingRulesChange(_rules: unknown[]) {
  // Rules are managed by the ConditionalFormattingService singleton; no extra action needed
}

// ---- Tab navigation ----

async function generateConclusions() {
  if (isComponentUnmounted) return;
  conclusionsLoading.value = true;
  const version = dataVersion.current();
  try {
    const allAnalysis = Object.entries(analysisByType.value)
      .filter(([, text]) => text)
      .map(([type, text]) => {
        const ct = chartTypes.find(c => c.key === type);
        return `【${ct?.label || type}】\n${text}`;
      })
      .join('\n\n');

    if (!allAnalysis) {
      conclusionsText.value = '请先等待各图表的AI分析完成，然后再生成总结。';
      return;
    }

    const resp = await analyzeChart({
      chart_type: 'overall_summary',
      analysis_context: allAnalysis,
    });
    if (isComponentUnmounted) return;
    if (!dataVersion.isCurrent(version)) return; // 期间/数据已换, 这份结论属于旧图表
    if (resp.success) {
      conclusionsText.value = resp.analysis || '暂无分析结论';
    } else {
      conclusionsText.value = resp.error || '分析暂不可用';
    }
  } catch (err) {
    if (isComponentUnmounted) return;
    const name = err instanceof Error ? err.name : '';
    if (name === 'AbortError' || (err instanceof TypeError && String(err).includes('fetch'))) return;
    console.error('generateConclusions failed:', err);
    if (dataVersion.isCurrent(version)) conclusionsText.value = '生成结论失败，请重试';
  } finally {
    if (!isComponentUnmounted) conclusionsLoading.value = false;
  }
}

async function submitFollowUp(chartType: string) {
  const question = followUpInput.value.trim();
  if (!question) return;

  const chart = getChart(chartType);
  if (!chart) return;

  // Build previous analysis = initial analysis + all follow-up answers
  let fullAnalysis = analysisByType.value[chartType] || '';
  const history = followUpsByType.value[chartType] || [];
  for (const item of history) {
    fullAnalysis += `\n\n用户追问: ${item.question}\n回复: ${item.answer}`;
  }

  if (isComponentUnmounted) return;
  followUpLoading.value = true;
  followUpInput.value = '';
  const version = dataVersion.current();

  try {
    const resp = await analyzeChart({
      chart_type: chartType,
      analysis_context: chart.analysisContext || '',
      follow_up_question: question,
      previous_analysis: fullAnalysis,
    });
    if (isComponentUnmounted) return;
    if (!dataVersion.isCurrent(version)) return; // 期间/数据已换, 追问属于旧图表

    if (!followUpsByType.value[chartType]) followUpsByType.value[chartType] = [];
    followUpsByType.value[chartType].push({
      question,
      answer: resp.success ? (resp.analysis || '暂无回复') : (resp.error || '请求失败'),
    });
  } catch (err) {
    if (isComponentUnmounted) return;
    const name = err instanceof Error ? err.name : '';
    if (name === 'AbortError' || (err instanceof TypeError && String(err).includes('fetch'))) return;
    console.error('followUp failed:', err);
    if (!dataVersion.isCurrent(version)) return;
    if (!followUpsByType.value[chartType]) followUpsByType.value[chartType] = [];
    followUpsByType.value[chartType].push({ question, answer: '请求失败，请重试' });
  } finally {
    if (!isComponentUnmounted) followUpLoading.value = false;
  }
}

watch(activeTab, async (newTab) => {
  if (newTab === 'conclusions' || viewMode.value !== 'tab') return;
  // Auto-trigger AI analysis for the active chart
  requestAnalysis(newTab);
  await nextTick();
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      const domEl = chartDomRefs.value.get(newTab);
      if (domEl) renderGenericChart(newTab, domEl);
    });
  });
});

// ---- B3: Dark mode toggle ----
const REFRESH_OPTIONS = [
  { value: 0, label: '关' },
  { value: 60000, label: '每 1 分钟' },
  { value: 300000, label: '每 5 分钟' },
  { value: 900000, label: '每 15 分钟' },
];

async function changeDataSource() {
  try {
    const { value } = await ElMessageBox.prompt('输入上传记录的编号（在「数据上传」页可以看到）', '更换数据源', {
      inputValue: uploadIdInput.value || (uploadId.value ? String(uploadId.value) : ''),
      inputPattern: /^\d+$/,
      inputErrorMessage: '编号是数字',
      confirmButtonText: '生成看板',
      cancelButtonText: '取消',
    });
    uploadIdInput.value = value;
    uploadId.value = parseInt(value, 10);
    isInDemoMode.value = false;
    generate(false);
  } catch {
    // 取消
  }
}

function handleMoreCommand(command: string) {
  if (command === 'view:tab' || command === 'view:grid') viewMode.value = command === 'view:tab' ? 'tab' : 'grid';
  else if (command === 'dark') toggleDarkMode();
  else if (command.startsWith('refresh:')) autoRefreshInterval.value = Number(command.slice('refresh:'.length));
  else if (command === 'split') smallMultiplesVisible.value = !smallMultiplesVisible.value;
  else if (command === 'format') conditionalFormatOpen.value = true;
  else if (command === 'bookmark') showBookmarkPanel.value = true;
  else if (command === 'present') isPresentationVisible.value = true;
  else if (command === 'demo') generate(true);
  else if (command === 'source') changeDataSource();
}

function toggleDarkMode() {
  isDarkMode.value = !isDarkMode.value;
  const theme = isDarkMode.value ? 'cretas-dark' : undefined;
  // Re-render all generic charts with theme change
  nextTick(() => {
    for (const [chartType, domEl] of chartDomRefs.value.entries()) {
      const oldInstance = echarts.getInstanceByDom(domEl);
      if (oldInstance) oldInstance.dispose();
      const newInstance = echarts.init(domEl, theme);
      const chart = getChart(chartType);
      if (chart?.echartsOption) {
        const processed = processEChartsOptions(safeClone(chart.echartsOption) as Record<string, unknown>);
        newInstance.setOption(processed, { notMerge: true });
      }
    }
    // Re-render custom component charts (Sankey)
    for (const compRef of [chartRefSankey]) {
      const inst = compRef.value?.chartInstance;
      if (inst) {
        const domEl = inst.getDom();
        inst.dispose();
        if (domEl) {
          const newInst = echarts.init(domEl, theme);
          const chartType = 'cost_flow_sankey';
          const chart = getChart(chartType);
          if (chart?.echartsOption) {
            const processed = processEChartsOptions(safeClone(chart.echartsOption) as Record<string, unknown>);
            newInst.setOption(processed, { notMerge: true });
          }
        }
      }
    }
  });
}

// ---- B2: Simple drill-down for financial dashboard ----
const drillDownVisible = ref(false);
const drillDownContext = ref({ chartType: '', dimension: '', value: '' });
const drillBreadcrumb = ref<Array<{ dimension: string; value: string }>>([]);

function handleChartDrillDown(chartType: string, params: { name?: string; seriesName?: string }) {
  if (!params.name) return;
  // For financial charts, "drill down" means filtering the period to a specific month/quarter
  const monthMatch = params.name.match(/^(\d{1,2})月$/);
  if (monthMatch) {
    const month = parseInt(monthMatch[1], 10);
    drillDownContext.value = { chartType, dimension: '月份', value: params.name };
    drillBreadcrumb.value.push({ dimension: '月份', value: params.name });
    // Re-generate with narrowed period
    const savedStart = startMonth.value;
    const savedEnd = endMonth.value;
    periodSelection.value = {
      ...periodSelection.value,
      type: 'month_range',
      value: [`${currentYear.value}-${String(month).padStart(2, '0')}`, `${currentYear.value}-${String(month).padStart(2, '0')}`],
    };
    ElMessage.info(`下钻至 ${params.name}`);
    generate();
    drillDownVisible.value = true;
    return;
  }
  // For category names, use cross-filter instead
  handleChartClick(chartType, params);
}

function drillUp() {
  drillBreadcrumb.value.pop();
  if (drillBreadcrumb.value.length === 0) {
    // Restore full year view
    periodSelection.value = {
      ...periodSelection.value,
      type: 'year',
      value: String(currentYear.value),
    };
    drillDownVisible.value = false;
    generate();
  }
}

// ---- B1: Cross-chart filtering ----
function handleChartClick(chartType: string, params: { name?: string; seriesName?: string; componentType?: string }) {
  if (!params.name || params.componentType === 'markLine' || params.componentType === 'markArea') return;

  // Toggle filter: click same → clear, click different → set
  if (activeFilter.value?.value === params.name && activeFilter.value?.dimension === chartType) {
    activeFilter.value = null;
  } else {
    activeFilter.value = { dimension: chartType, value: params.name };
  }
  applyCrossFilter();
}

function applyCrossFilter() {
  for (const [chartType, domEl] of chartDomRefs.value.entries()) {
    const instance = echarts.getInstanceByDom(domEl);
    if (!instance) continue;

    const chart = getChart(chartType);
    if (!chart?.echartsOption) continue;

    if (!activeFilter.value) {
      // Clear filter — re-render original chart
      const processed = processEChartsOptions(safeClone(chart.echartsOption) as Record<string, unknown>);
      instance.setOption(processed, { notMerge: true });
      continue;
    }

    // Find matching data index in xAxis
    const filterVal = activeFilter.value.value;
    const rawOpt = chart.echartsOption as Record<string, unknown>;
    const xData = rawOpt.xAxis;
    const xAxisData = Array.isArray(xData) ? (xData[0] as Record<string, unknown>)?.data : (xData as Record<string, unknown>)?.data;

    if (Array.isArray(xAxisData)) {
      const matchIdx = xAxisData.indexOf(filterVal);
      // Apply opacity dimming via series-level emphasis/downplay
      instance.dispatchAction({ type: 'downplay' });
      if (matchIdx >= 0) {
        // Highlight matched index across all series
        const seriesArr = rawOpt.series;
        if (Array.isArray(seriesArr)) {
          for (let si = 0; si < seriesArr.length; si++) {
            instance.dispatchAction({ type: 'highlight', seriesIndex: si, dataIndex: matchIdx });
          }
        } else {
          instance.dispatchAction({ type: 'highlight', dataIndex: matchIdx });
        }
      }
    }
  }
}

// ---- D1: Auto-refresh ----
function setAutoRefresh(interval: number) {
  autoRefreshInterval.value = interval;
  if (autoRefreshTimer) {
    clearInterval(autoRefreshTimer);
    autoRefreshTimer = null;
  }
  if (interval > 0 && dashboardResponse.value) {
    autoRefreshTimer = setInterval(() => {
      if (!isGenerating.value) generate();
    }, interval);
  }
}

watch(() => autoRefreshInterval.value, (val) => setAutoRefresh(val));

// ---- D3: Anomaly detection (2σ) ----
function detectAnomalies(kpis: Array<{ label: string; value: string | number; unit?: string; trend?: string }>): string[] {
  const numericValues = kpis
    .map(k => typeof k.value === 'number' ? k.value : parseFloat(String(k.value)))
    .filter(v => !isNaN(v));
  if (numericValues.length < 3) return [];

  const mean = numericValues.reduce((a, b) => a + b, 0) / numericValues.length;
  const std = Math.sqrt(numericValues.reduce((a, b) => a + Math.pow(b - mean, 2), 0) / numericValues.length);
  if (std === 0) return [];

  const alerts: string[] = [];
  for (const kpi of kpis) {
    const v = typeof kpi.value === 'number' ? kpi.value : parseFloat(String(kpi.value));
    if (!isNaN(v) && Math.abs(v - mean) > 2 * std) {
      alerts.push(`${kpi.label}: ${kpi.value}${kpi.unit || ''} (偏差显著)`);
    }
  }
  return alerts;
}

// ---- C4: Keyboard navigation between chart cards ----
function handleCardKeydown(event: KeyboardEvent, cardIndex: number) {
  const cards = document.querySelectorAll('.chart-card[tabindex]');
  let nextIdx = -1;
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
    nextIdx = Math.min(cardIndex + 1, cards.length - 1);
  } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
    nextIdx = Math.max(cardIndex - 1, 0);
  } else {
    return;
  }
  event.preventDefault();
  (cards[nextIdx] as HTMLElement)?.focus();
}

// ---- E1: Synchronized crosshair (tooltip linkage) ----
// Time-series chart types that share the same x-axis (months)
const timeSeriesChartTypes = [
  'budget_achievement', 'yoy_mom_comparison',
  'category_yoy_comparison', 'cashflow_trend',
  'hr_cost_analysis',
];

function connectCharts() {
  // Wire up mousemove → showTip on sibling charts (throttled to avoid per-frame dispatch to all charts)
  for (const chartType of timeSeriesChartTypes) {
    const domEl = chartDomRefs.value.get(chartType);
    if (!domEl) continue;
    const instance = echarts.getInstanceByDom(domEl);
    if (!instance) continue;

    let lastDispatchedIndex = -1;
    let throttleTimer: ReturnType<typeof setTimeout> | null = null;

    instance.on('mousemove', (params: Record<string, unknown>) => {
      const dataIndex = params.dataIndex as number | undefined;
      if (dataIndex == null || dataIndex === lastDispatchedIndex) return;
      lastDispatchedIndex = dataIndex;
      if (throttleTimer) return; // Skip if throttle pending
      throttleTimer = setTimeout(() => {
        throttleTimer = null;
        for (const sibType of timeSeriesChartTypes) {
          if (sibType === chartType) continue;
          const sibDom = chartDomRefs.value.get(sibType);
          if (!sibDom) continue;
          const sibInst = echarts.getInstanceByDom(sibDom);
          if (!sibInst) continue;
          try { sibInst.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: lastDispatchedIndex }); } catch { /* grid not ready */ }
        }
      }, 32); // ~30fps throttle
    });
    instance.on('mouseout', () => {
      lastDispatchedIndex = -1;
      if (throttleTimer) { clearTimeout(throttleTimer); throttleTimer = null; }
      for (const sibType of timeSeriesChartTypes) {
        if (sibType === chartType) continue;
        const sibDom = chartDomRefs.value.get(sibType);
        if (!sibDom) continue;
        const sibInst = echarts.getInstanceByDom(sibDom);
        try { sibInst?.dispatchAction({ type: 'hideTip' }); } catch { /* grid not ready */ }
      }
    });
  }
}

// ---- E2: Animated KPI counter ----
const kpiRafIds: number[] = [];
// 预算达成状态: 颜色用加深色保证对比度, 同时给出文字 (不只靠颜色区分好坏)
function achievementColor(rate: number | null | undefined): string {
  if (rate == null) return 'var(--el-text-color-secondary)';
  if (rate >= 100) return '#1B7A4A';
  if (rate >= 80) return '#9A6700';
  return '#C0362C';
}
function achievementStatus(rate: number | null | undefined): string {
  if (rate == null) return '';
  if (rate >= 100) return '达成';
  if (rate >= 80) return '接近';
  return '未达';
}

// AI 分析文本是 markdown: 渲染后再消毒 (与 ProductionAnalysis 同一做法)
function renderAnalysis(text: string | null | undefined): string {
  if (!text) return '';
  return DOMPurify.sanitize(marked.parse(text, { async: false }) as string);
}

// KPI 原样显示后端格式化好的值 (千分位、单位由后端统一), 不做计数动画
function kpiText(original: string | number): string {
  return typeof original === 'number' ? original.toLocaleString('zh-CN') : String(original);
}

// ---- Fix 64: Spotlight / Focus mode (Power BI) ----
const spotlightChart = ref<string | null>(null);

function enterSpotlight(chartType: string) {
  spotlightChart.value = chartType;
  // After overlay mounts, render chart in the spotlight container
  nextTick(() => {
    nextTick(() => {
      const domEl = chartDomRefs.value.get(chartType);
      if (domEl) renderGenericChart(chartType, domEl);
      // Auto-focus overlay so Escape key works
      const overlay = document.querySelector('.spotlight-overlay') as HTMLElement;
      if (overlay) overlay.focus();
    });
  });
}

function exitSpotlight() {
  const ct = spotlightChart.value;
  spotlightChart.value = null;
  // Re-render chart back in original container after teleport unmounts
  if (ct) {
    nextTick(() => {
      nextTick(() => {
        const domEl = chartDomRefs.value.get(ct);
        if (domEl) renderGenericChart(ct, domEl);
      });
    });
  }
}

// Handle Escape key for spotlight
function handleSpotlightKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && spotlightChart.value) exitSpotlight();
}

// ---- E4: Data table drawer per chart ----
const tableVisibleByType = ref<Record<string, boolean>>({});

// ---- Resize all charts when container changes (sidebar toggle) ----
let resizeObserver: ResizeObserver | null = null;
let resizeRafId = 0;

function setupResizeObserver() {
  const container = document.querySelector('.financial-dashboard-pbi');
  if (!container || resizeObserver) return;
  resizeObserver = new ResizeObserver(() => {
    if (resizeRafId) return;
    resizeRafId = requestAnimationFrame(() => {
      resizeRafId = 0;
      for (const [, domEl] of chartDomRefs.value.entries()) {
        const inst = echarts.getInstanceByDom(domEl);
        inst?.resize();
      }
    });
  });
  resizeObserver.observe(container);
}

// Setup after first render
watch(() => charts.value.length, () => {
  nextTick(() => setupResizeObserver());
}, { once: true });

// 自动加载: 若已有数据源 (route query ?upload_id= 或 ?uploadId=), 进页即以「全部历史」
// 生成看板, 免手动点「生成看板」。无数据源时保留引导空态 (PBI 看板是 upload 绑定的, 没有
// upload_id 无法生成 — 餐饮租户默认落 gold「财务数据分析」子页, 见 financeDashboardSection.ts)。
onMounted(() => {
  const q = route.query;
  const rawId = q.upload_id ?? q.uploadId;
  const idStr = Array.isArray(rawId) ? rawId[0] : rawId;
  const id = idStr ? parseInt(String(idStr), 10) : NaN;
  if (!isNaN(id) && id > 0) {
    uploadId.value = id;
    uploadIdInput.value = String(id);
    // periodSelection 默认 allHistory=true → 全部历史
    nextTick(() => generate(false));
  }
});

// Cleanup on unmount
onBeforeUnmount(() => {
  // Bug #2: mark unmount so in-flight generate() stops touching reactive state,
  // avoiding "canceled" console noise when the user navigates away mid-request.
  isComponentUnmounted = true;
  if (autoRefreshTimer) clearInterval(autoRefreshTimer);
  if (resizeRafId) cancelAnimationFrame(resizeRafId);
  kpiRafIds.forEach((id) => cancelAnimationFrame(id));
  kpiRafIds.length = 0;
  resizeObserver?.disconnect();
  resizeObserver = null;
  teardownLazyObserver();
  for (const [, domEl] of chartDomRefs.value.entries()) {
    const instance = echarts.getInstanceByDom(domEl);
    instance?.dispose();
  }
  chartDomRefs.value.clear();
});
</script>

<template>
  <div class="financial-dashboard-pbi" :data-theme="isDarkMode ? 'dark' : 'light'" role="main" aria-label="财务分析看板">
    <!-- P4: 顶层 section tabs — 财务 PBI 看板 / 财务数据分析 (合并自原 /smart-bi/finance) -->
    <el-tabs v-model="sectionTab" class="finance-section-tabs">
    <el-tab-pane label="财务 PBI 看板" name="dashboard">
    <!-- follow-up (fu2): 餐饮租户 Gold 财务全量视图 (免上传 / 免手动生成).
         工厂租户 (或餐饮点「上传财务文件」) 走下方原 upload-based PBI 看板。 -->
    <FinancePbiGoldView
      v-if="goldFinanceMode"
      @switch-to-upload="showUploadForRestaurant = true"
    />

    <template v-else>
    <!-- Top Toolbar -->
    <el-card class="toolbar-card" shadow="never">
      <div class="toolbar-inner">
        <div class="toolbar-left">
          <span v-if="uploadId && !isInDemoMode" class="data-source-tag" data-testid="data-source-tag">数据源 #{{ uploadId }}</span>
          <span v-else-if="isInDemoMode" class="data-source-tag">演示数据</span>
          <el-input
            v-else
            v-model="uploadIdInput"
            placeholder="上传记录编号"
            style="width: 140px"
            size="small"
            clearable
            @change="(v: string) => { uploadId = v ? parseInt(v, 10) : null }"
          />
          <el-popover :visible="periodPopoverOpen" placement="bottom-start" :width="440" popper-class="period-popover">
            <template #reference>
              <el-button
                size="small"
                class="period-trigger"
                :class="{ 'is-invalid': periodRequestError }"
                data-testid="period-trigger"
                :aria-expanded="periodPopoverOpen"
                @click="periodPopoverOpen = !periodPopoverOpen"
              >
                <el-icon><Calendar /></el-icon>
                <span>{{ periodButtonLabel }}</span>
                <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
            </template>
            <PeriodSelector
              v-model="periodSelection"
              :show-quick-select="true"
              :show-custom-tab="false"
              :show-all-history="true"
              :show-compare="false"
              default-type="year"
              size="small"
            />
            <div class="period-popover__footer">
              <span v-if="periodRequestError" class="period-popover__error">{{ periodRequestError }}</span>
              <el-button size="small" @click="periodPopoverOpen = false">取消</el-button>
              <el-button size="small" type="primary" :disabled="!!periodRequestError" data-testid="period-apply" @click="applyPeriod">确定</el-button>
            </div>
          </el-popover>
          <el-button
            type="primary"
            size="small"
            :loading="isGenerating"
            style="margin-left: 8px"
            @click="generate"
          >
            <el-icon v-if="!isGenerating"><DataAnalysis /></el-icon>
            {{ isGenerating ? '生成中...' : '生成看板' }}
          </el-button>
        </div>
        <div class="toolbar-right">
          <el-dropdown :disabled="!dashboardResponse" trigger="click" @command="handleExportCommand">
            <el-button size="small" :disabled="!dashboardResponse" :loading="isExportingPPT || isExportingPDF || isExportingExcel">
              <el-icon><Download /></el-icon>
              {{ exportStageText || '导出报告' }} <el-icon v-if="!exportStageText" class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="ppt" :disabled="isExportingPPT">
 导出PPT演示文稿
                </el-dropdown-item>
                <el-dropdown-item command="pdf" :disabled="isExportingPDF">
 导出PDF报告
                </el-dropdown-item>
                <el-dropdown-item command="excel" :disabled="isExportingExcel">
 导出Excel数据
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <!-- 不常用的设置都收在这里: 看板默认只露出期间、生成、导出 -->
          <el-dropdown trigger="click" :hide-on-click="true" @command="handleMoreCommand">
            <el-button size="small" data-testid="toolbar-more">
              <el-icon><MoreFilled /></el-icon>
              <span>更多</span>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu class="toolbar-more-menu">
                <el-dropdown-item command="source">更换数据源…</el-dropdown-item>
                <el-dropdown-item command="demo">用演示数据看看</el-dropdown-item>
                <el-dropdown-item divided command="view:tab">{{ viewMode === 'tab' ? '✓ ' : '' }}一次看一张图</el-dropdown-item>
                <el-dropdown-item command="view:grid">{{ viewMode === 'grid' ? '✓ ' : '' }}所有图平铺</el-dropdown-item>
                <el-dropdown-item command="split" :disabled="!dashboardResponse">{{ smallMultiplesVisible ? '✓ ' : '' }}按维度拆成小图</el-dropdown-item>
                <el-dropdown-item command="format" :disabled="!dashboardResponse">条件格式…</el-dropdown-item>
                <el-dropdown-item divided command="bookmark" :disabled="!dashboardResponse">书签</el-dropdown-item>
                <el-dropdown-item command="present" :disabled="!dashboardResponse">演示模式</el-dropdown-item>
                <el-dropdown-item command="dark">{{ isDarkMode ? '切换到亮色' : '切换到暗色' }}</el-dropdown-item>
                <el-dropdown-item
                  v-for="(opt, i) in REFRESH_OPTIONS"
                  :key="opt.value"
                  :divided="i === 0"
                  :command="`refresh:${opt.value}`"
                  :disabled="!dashboardResponse"
                >{{ autoRefreshInterval === opt.value ? '✓ ' : '' }}自动刷新：{{ opt.label }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </el-card>

    <el-dialog v-model="conditionalFormatOpen" title="条件格式" width="560px" append-to-body>
      <ConditionalFormatPanel
        table-id="financial-dashboard"
        :available-fields="['value', 'budget', 'actual', 'variance']"
        @change="onFormattingRulesChange"
      />
    </el-dialog>

    <!-- 页首一句话结论: 后端按规则从同一指标库算出, 不经过 AI -->
    <p
      v-if="headline && !isGenerating"
      class="dashboard-headline"
      :class="`dashboard-headline--${headline.tone}`"
      data-testid="dashboard-headline"
    >
      <span class="dashboard-headline__dot" aria-hidden="true" />
      <span>{{ headline.text }}</span>
    </p>

    <!-- Interaction tips -->
    <div v-if="dashboardResponse && !isGenerating" class="interaction-tips">
      <span><b>单击</b>图形高亮同名数据 · <b>双击</b>添加标注</span>
    </div>

    <!-- Fix 72: Global Slicer / Filter bar (Power BI style) -->
    <div v-if="availableDimensions.length > 0 && dashboardResponse && !isGenerating" class="slicer-bar">
      <span class="slicer-label">筛选器</span>
      <div v-for="dim in availableDimensions" :key="dim.name" class="slicer-group">
        <span class="slicer-dim-name">{{ dim.name }}:</span>
        <el-check-tag
          v-for="val in dim.values"
          :key="val"
          :checked="slicerFilters[dim.name]?.includes(val)"
          @change="(checked: boolean) => {
            if (!slicerFilters[dim.name]) slicerFilters[dim.name] = [];
            if (checked) { slicerFilters[dim.name].push(val); }
            else { slicerFilters[dim.name] = slicerFilters[dim.name].filter((v: string) => v !== val); }
            generate();
          }"
        >
          {{ val }}
        </el-check-tag>
      </div>
      <el-button
        v-if="Object.values(slicerFilters).some((v: string[]) => v.length > 0)"
        size="small"
        text
        type="primary"
        @click="slicerFilters = {}; generate()"
      >
        清除全部
      </el-button>
    </div>

    <!-- B2: Drill-down breadcrumb -->
    <div v-if="drillDownVisible" class="cross-filter-bar" style="background: rgba(54,179,126,0.08); color: #1B7A4A">
      <div style="display: flex; align-items: center; gap: 4px">
        <span style="cursor: pointer; text-decoration: underline" @click="drillUp">全年</span>
        <span v-for="(bc, idx) in drillBreadcrumb" :key="idx"> &gt; <b>{{ bc.value }}</b></span>
      </div>
      <el-button size="small" text type="success" @click="drillUp">返回上级</el-button>
    </div>

    <!-- B1: Active cross-filter indicator -->
    <div v-if="activeFilter" class="cross-filter-bar">
      <span>高亮中: <b>{{ activeFilter.value }}</b>（仅高亮图形，KPI 与 AI 分析仍为全部数据）</span>
      <el-button size="small" text type="primary" @click="activeFilter = null; applyCrossFilter()">取消高亮</el-button>
    </div>

    <!-- Empty state -->
    <div v-if="!dashboardResponse && !isGenerating" class="empty-state">
      <SmartBIEmptyState type="no-data" title="请选择数据源" description="选好数据源后点击「生成看板」(默认「全部历史」, 展示该数据源全部数据)" :show-action="false" />
    </div>

    <!-- Loading skeleton (grid mode) -->
    <div v-if="isGenerating && viewMode === 'grid'" class="charts-grid">
      <el-card v-for="ct in chartTypes" :key="ct.key" class="chart-card chart-card--visible" shadow="hover">
        <template #header>
          <div class="chart-card-header">
            <span>{{ ct.icon }} {{ ct.label }}</span>
          </div>
        </template>
        <ChartSkeleton type="kpi" />
        <ChartSkeleton type="chart" />
      </el-card>
    </div>

    <!-- Charts Grid (grid mode) -->
    <div v-if="dashboardResponse && !isGenerating && viewMode === 'grid'" class="charts-grid">

      <!-- Chart: Cost Flow Sankey -->
      <el-card
        v-if="getChart('cost_flow_sankey')"
        :ref="(el: { $el?: HTMLElement } | null) => { if (el?.$el) observeChartCard(el.$el, 'cost_flow_sankey') }"
        class="chart-card chart-card--wide"
        :class="{ 'chart-card--visible': visibleCharts.has('cost_flow_sankey') }"
        data-chart-type="cost_flow_sankey"
        shadow="hover"
      >
        <template #header>
          <div class="chart-card-header">
 <span> 成本流向图</span>
            <el-button
              size="small"
              text
              type="primary"
              :loading="analysisLoadingByType['cost_flow_sankey']"
              @click="forceRequestAnalysis('cost_flow_sankey')"
            >
              {{ analysisByType['cost_flow_sankey'] ? '重新分析' : 'AI分析' }}
            </el-button>
          </div>
        </template>
        <SankeyChart
          ref="chartRefSankey"
          :nodes="[]"
          :links="[]"
          :echarts-option="getChart('cost_flow_sankey')!.echartsOption ?? {}"
          height="460px"
        />
        <div v-if="analysisLoadingByType['cost_flow_sankey'] || analysisByType['cost_flow_sankey']" class="analysis-panel">
          <ChartSkeleton v-if="analysisLoadingByType['cost_flow_sankey']" type="ai" />
          <div v-else class="analysis-text" v-html="renderAnalysis(analysisByType['cost_flow_sankey'])" />
        </div>
      </el-card>

      <!-- Generic Charts rendered via echartsOption from API -->
      <template v-for="ct in chartTypes.filter(c => c.key !== 'cost_flow_sankey')" :key="ct.key">
      <el-card
        v-if="getChart(ct.key)"
        :ref="(el: { $el?: HTMLElement } | null) => { if (el?.$el) observeChartCard(el.$el, ct.key) }"
        class="chart-card"
        :class="{ 'chart-card--wide chart-card--scorecard': ct.key === 'kpi_scorecard', 'chart-card--visible': visibleCharts.has(ct.key) }"
        :data-chart-type="ct.key"
        shadow="hover"
        role="region"
        :aria-label="ct.label"
        tabindex="0"
        @keydown="(e: KeyboardEvent) => handleCardKeydown(e, chartTypes.indexOf(ct))"
      >
        <template #header>
          <div class="chart-card-header">
            <div class="chart-card-title-group">
              <span>{{ ct.icon }} {{ ct.label }}</span>
              <el-tag
                v-if="getAnomalies(ct.key).length > 0"
                type="warning"
                size="small"
                effect="dark"
                class="anomaly-badge"
                :title="getAnomalies(ct.key).join('\n')"
              >
 {{ getAnomalies(ct.key).length }}
              </el-tag>
            </div>
            <div style="display: flex; align-items: center; gap: 4px">
              <!-- Fix 64: Spotlight button -->
              <el-button
                size="small"
                text
                title="聚焦放大"
                @click="enterSpotlight(ct.key)"
              >
 
              </el-button>
              <el-button
                v-if="annotations[ct.key]?.length"
                size="small"
                text
                type="warning"
                :title="`${annotations[ct.key].length} 个标注 (双击图表添加)`"
                @click="clearAllAnnotations(ct.key)"
              >
                <el-icon><Delete /></el-icon>
                {{ annotations[ct.key].length }}
              </el-button>
              <el-button
                v-if="getChart(ct.key)?.tableData"
                size="small"
                text
                type="info"
                @click="tableVisibleByType[ct.key] = !tableVisibleByType[ct.key]"
              >
                {{ tableVisibleByType[ct.key] ? '隐藏表格' : '数据表' }}
              </el-button>
              <el-button
                size="small"
                text
                type="primary"
                :loading="analysisLoadingByType[ct.key]"
                :disabled="!getChart(ct.key)"
                @click="forceRequestAnalysis(ct.key)"
              >
                {{ analysisByType[ct.key] ? '重新分析' : 'AI分析' }}
              </el-button>
            </div>
          </div>
        </template>

        <!-- KPI row from API response -->
        <div v-if="getChart(ct.key)!.kpis?.length" class="generic-kpi-row">
          <div
            v-for="kpi in getChart(ct.key)!.kpis.slice(0, 4)"
            :key="kpi.label"
            class="generic-kpi-chip"
            :class="{
              'kpi-up': kpi.trend === 'up',
              'kpi-down': kpi.trend === 'down',
            }"
            tabindex="0"
            :aria-label="`${kpi.label}: ${kpi.value}${kpi.unit || ''}`"
          >
            <span class="kpi-val">{{ kpiText(kpi.value) }}{{ kpi.unit }}</span>
            <span class="kpi-lbl">{{ kpi.label }}</span>
            <!-- A3: KPI sparkline -->
            <svg v-if="kpi.sparkline?.length >= 2" class="kpi-sparkline" viewBox="0 0 60 20" preserveAspectRatio="none">
              <path :d="sparklinePath(kpi.sparkline)" fill="none" stroke="currentColor" stroke-width="1.5" />
            </svg>
          </div>
        </div>

        <!-- Quarterly progress bars (budget_achievement) -->
        <div
          v-if="ct.key === 'budget_achievement' && getChart(ct.key)?.quarterlyProgress?.length"
          class="quarterly-progress-row"
        >
          <div
            v-for="qp in getChart(ct.key)!.quarterlyProgress"
            :key="qp.quarter"
            class="q-progress-item"
          >
            <span class="q-label">{{ qp.quarter }}</span>
            <el-progress
              :percentage="Math.min(qp.rate ?? 0, 100)"
              :color="achievementColor(qp.rate)"
              :stroke-width="10"
              :text-inside="true"
            />
            <span class="q-rate" :style="{ color: achievementColor(qp.rate) }">{{ qp.rate == null ? '—' : `${qp.rate}% ${achievementStatus(qp.rate)}` }}</span>
          </div>
        </div>

        <!-- 数据不足: 不出图, 原样告诉用户缺什么 -->
        <SmartBIEmptyState
          v-if="chartGapReason(getChart(ct.key))"
          type="no-data"
          title="这份数据出不了这张图"
          :description="chartGapReason(getChart(ct.key)) ?? ''"
          :show-action="false"
          class="chart-gap-notice"
          data-testid="chart-gap-notice"
        />
        <!-- ECharts canvas rendered via DOM ref -->
        <div
          v-else
          :ref="(el) => setChartDomRef(ct.key, el as Element | null)"
          class="generic-chart-canvas"
          role="img"
          :aria-label="`${ct.label}图表`"
          title="单击: 高亮同名数据 | 双击: 添加标注"
        />
        <ul v-if="chartNotes(getChart(ct.key)).length" class="chart-data-notes" data-testid="chart-data-notes">
          <li v-for="note in chartNotes(getChart(ct.key))" :key="note">{{ note }}</li>
        </ul>

        <!-- Phase B Moderate: ChartInsightProvider for bar/line/pie slots (exotic slots render nothing) -->
        <!-- Wired: budget_achievement(comparison) yoy_mom_comparison(comparison) category_yoy_comparison(comparison)
                   category_structure_donut(pie) channel_analysis(bar/ranking) cashflow_trend(line/trend) hr_cost_analysis(comparison)
             Skipped exotic: pnl_waterfall(WATERFALL) cash_flow_waterfall(WATERFALL) bullet_chart(gauge-like) small_multiples(exotic) -->
        <ChartInsightProvider
          v-if="buildFinanceChartWithMeta(ct.key)"
          :chart="buildFinanceChartWithMeta(ct.key)"
          :perms="insightPerms"
          :factory-id="authStore.factoryId || ''"
          depth="detailed"
        />

        <!-- Monthly data rows (yoy_mom_comparison) -->
        <div
          v-if="ct.key === 'yoy_mom_comparison' && getChart(ct.key)?.monthlyDataRows"
          class="monthly-data-rows"
        >
          <div class="data-row">
            <span class="data-row-label">本年</span>
            <span
              v-for="(v, idx) in getChart(ct.key)!.monthlyDataRows.currentYear"
              :key="'cy-' + idx"
              class="data-row-cell"
            >{{ v }}</span>
          </div>
          <div class="data-row">
            <span class="data-row-label">上年</span>
            <span
              v-for="(v, idx) in getChart(ct.key)!.monthlyDataRows.lastYear"
              :key="'ly-' + idx"
              class="data-row-cell"
            >{{ v }}</span>
          </div>
        </div>

        <!-- AI Analysis panel — always visible after auto-analysis -->
        <div v-if="analysisLoadingByType[ct.key] || analysisByType[ct.key]" class="analysis-panel">
          <ChartSkeleton v-if="analysisLoadingByType[ct.key]" type="ai" />
          <div v-else class="analysis-text" v-html="renderAnalysis(analysisByType[ct.key])" />
        </div>

        <!-- E4: Data table drawer (Fix 71: with inline sparklines) -->
        <div v-if="tableVisibleByType[ct.key] && getChart(ct.key)?.tableData" class="chart-data-table">
          <table>
            <thead>
              <tr>
                <th v-for="col in (getChart(ct.key)!.tableData as Record<string, unknown>)?.headers as string[] ?? []" :key="col">{{ col }}</th>
                <th style="min-width:80px">趋势</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, rIdx) in (getChart(ct.key)!.tableData as Record<string, unknown>)?.rows as Record<string, unknown>[] ?? []" :key="rIdx">
                <td>{{ (row as Record<string, unknown>).label }}</td>
                <td v-for="(val, vIdx) in ((row as Record<string, unknown>).values as unknown[] ?? [])" :key="vIdx">{{ val }}</td>
                <!-- Fix 71: Inline sparkline for each row -->
                <td>
                  <span
                    v-if="Array.isArray((row as Record<string, unknown>).values)"
                    v-html="sparklineSVG(
                      ((row as Record<string, unknown>).values as unknown[]).map((c: unknown) => parseFloat(String(c)) || 0),
                      -1, 80, 22, '#2D8B57'
                    )"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </el-card>
      </template>

    </div>

    <!-- Tab mode: Loading -->
    <div v-if="isGenerating && viewMode === 'tab'" class="dashboard-layout">
      <div class="chart-switcher chart-switcher--loading" aria-hidden="true">
        <span v-for="n in 5" :key="n" class="chart-switcher__placeholder" />
      </div>
      <main class="dash-content">
        <el-card shadow="hover">
          <ChartSkeleton type="kpi" />
          <ChartSkeleton type="chart" />
        </el-card>
      </main>
    </div>

    <!-- Tab mode: 图表切换条 (横向, 只列能出图的图表; 出不了的收进「还缺数据」并写明原因) -->
    <div v-if="dashboardResponse && !isGenerating && viewMode === 'tab'" class="dashboard-layout">
      <nav class="chart-switcher" aria-label="切换图表" data-testid="chart-switcher">
        <div class="chart-switcher__groups">
          <div v-for="group in chartNav.groups" :key="group.name" class="chart-switcher__group" role="group" :aria-label="group.name">
            <span class="chart-switcher__group-name">{{ group.name }}</span>
            <button
              v-for="ct in group.items"
              :key="ct.key"
              type="button"
              class="chart-switcher__item"
              :class="{ 'is-active': activeTab === ct.key }"
              :aria-pressed="activeTab === ct.key"
              @click="activeTab = ct.key"
            >
              {{ ct.label }}
            </button>
          </div>
        </div>
        <div class="chart-switcher__aside">
          <el-popover
            v-if="chartNav.missing.length"
            placement="bottom-end"
            :width="360"
            trigger="click"
          >
            <template #reference>
              <button type="button" class="chart-switcher__item chart-switcher__item--muted" data-testid="chart-switcher-missing">
                还缺数据（{{ chartNav.missing.length }}）
              </button>
            </template>
            <div class="missing-charts" data-testid="missing-charts">
              <template v-if="chartNav.missingByKind.column.length">
                <p class="missing-charts__hint">在这张表上补一列就能出：</p>
                <ul>
                  <li v-for="m in chartNav.missingByKind.column" :key="m.key">
                    <button type="button" class="missing-charts__name" @click="activeTab = m.key">{{ m.label }}</button>
                    <span class="missing-charts__reason">{{ m.reason }}</span>
                  </li>
                </ul>
              </template>
              <template v-if="chartNav.missingByKind.statement.length">
                <p class="missing-charts__hint">需要另一张报表：</p>
                <ul>
                  <li v-for="m in chartNav.missingByKind.statement" :key="m.key">
                    <button type="button" class="missing-charts__name" @click="activeTab = m.key">{{ m.label }}</button>
                    <span class="missing-charts__reason">{{ m.reason }}</span>
                  </li>
                </ul>
              </template>
            </div>
          </el-popover>
          <button
            type="button"
            class="chart-switcher__item chart-switcher__item--primary"
            :class="{ 'is-active': activeTab === 'conclusions' }"
            :aria-pressed="activeTab === 'conclusions'"
            @click="activeTab = 'conclusions'"
          >
            分析结论与建议
          </button>
        </div>
      </nav>

      <!-- Content area -->
      <main class="dash-content">
        <!-- Conclusions tab -->
        <template v-if="activeTab === 'conclusions'">
          <el-card shadow="hover">
            <template #header>
              <div class="chart-card-header">
 <span> 分析结论与建议</span>
                <el-button size="small" type="primary" :loading="conclusionsLoading" @click="generateConclusions">
                  {{ conclusionsText ? '重新生成' : '生成结论' }}
                </el-button>
              </div>
            </template>
            <!-- 规则结论: 按表内数据算出来, 每条带数字; AI 的段落在下面作补充 -->
            <div v-if="ruleFindings.length" class="findings" data-testid="findings">
              <section v-for="sec in ruleFindings" :key="sec.key" class="findings__group" :class="`findings__group--${sec.key}`">
                <h4 class="findings__title">{{ sec.title }}</h4>
                <ul class="findings__list">
                  <li v-for="(item, i) in sec.items" :key="i">{{ item }}</li>
                </ul>
              </section>
              <p class="findings__source">以上按表内数据算出。下面是 AI 汇总各图后的补充，可以重新生成。</p>
            </div>
            <p v-else class="findings__none" data-testid="findings-none">这份数据算不出规则结论（例如只有百分比的比率表）。可以让 AI 汇总各图的分析。</p>

            <h4 class="findings__ai-title">AI 补充</h4>
            <ChartSkeleton v-if="conclusionsLoading" type="ai" />
            <div v-else-if="conclusionsText" class="analysis-text" v-html="renderAnalysis(conclusionsText)" />
            <div v-else class="findings__ai-empty">
              <el-button type="primary" @click="generateConclusions">生成 AI 补充</el-button>
            </div>
          </el-card>
        </template>

        <!-- Active chart tab -->
        <template v-else-if="getChart(activeTab)">
          <!-- KPI row -->
          <div v-if="getChart(activeTab)!.kpis?.length" class="tab-kpi-row">
            <div
              v-for="kpi in getChart(activeTab)!.kpis"
              :key="kpi.label"
              class="generic-kpi-chip"
              :class="{ 'kpi-up': kpi.trend === 'up', 'kpi-down': kpi.trend === 'down' }"
            >
              <span class="kpi-val">{{ kpiText(kpi.value) }}{{ kpi.unit }}</span>
              <span class="kpi-lbl">{{ kpi.label }}</span>
              <svg v-if="kpi.sparkline?.length >= 2" class="kpi-sparkline" viewBox="0 0 60 20" preserveAspectRatio="none">
                <path :d="sparklinePath(kpi.sparkline)" fill="none" stroke="currentColor" stroke-width="1.5" />
              </svg>
            </div>
          </div>

          <!-- Quarterly progress bars (budget_achievement) -->
          <div
            v-if="activeTab === 'budget_achievement' && getChart(activeTab)?.quarterlyProgress?.length"
            class="quarterly-progress-row"
            style="margin-bottom: 16px"
          >
            <div v-for="qp in getChart(activeTab)!.quarterlyProgress" :key="qp.quarter" class="q-progress-item">
              <span class="q-label">{{ qp.quarter }}</span>
              <el-progress
                :percentage="Math.min(qp.rate ?? 0, 100)"
                :color="achievementColor(qp.rate)"
                :stroke-width="10"
                :text-inside="true"
              />
              <span class="q-rate" :style="{ color: achievementColor(qp.rate) }">{{ qp.rate == null ? '—' : `${qp.rate}% ${achievementStatus(qp.rate)}` }}</span>
            </div>
          </div>

          <!-- Chart -->
          <el-card shadow="hover" class="tab-chart-card">
            <template #header>
              <div class="chart-card-header">
                <div class="chart-card-title-group">
                  <span>{{ chartTypes.find(c => c.key === activeTab)?.icon }} {{ chartTypes.find(c => c.key === activeTab)?.label }}</span>
                  <el-tag
                    v-if="getAnomalies(activeTab).length > 0"
                    type="warning"
                    size="small"
                    effect="dark"
                    class="anomaly-badge"
                    :title="getAnomalies(activeTab).join('\n')"
                  >
 {{ getAnomalies(activeTab).length }}
                  </el-tag>
                </div>
                <div style="display: flex; align-items: center; gap: 4px">
 <el-button size="small" text title="聚焦放大" @click="enterSpotlight(activeTab)"></el-button>
                  <el-button
                    v-if="annotations[activeTab]?.length"
                    size="small"
                    text
                    type="warning"
                    :title="`${annotations[activeTab].length} 个标注`"
                    @click="clearAllAnnotations(activeTab)"
                  >
                    <el-icon><Delete /></el-icon> {{ annotations[activeTab].length }}
                  </el-button>
                  <el-button
                    v-if="getChart(activeTab)?.tableData"
                    size="small"
                    text
                    type="info"
                    @click="tableVisibleByType[activeTab] = !tableVisibleByType[activeTab]"
                  >
                    {{ tableVisibleByType[activeTab] ? '隐藏表格' : '数据表' }}
                  </el-button>
                  <el-button
                    size="small"
                    text
                    type="primary"
                    :loading="analysisLoadingByType[activeTab]"
                    @click="forceRequestAnalysis(activeTab)"
                  >
                    {{ analysisByType[activeTab] ? '重新分析' : 'AI分析' }}
                  </el-button>
                </div>
              </div>
            </template>
            <SmartBIEmptyState
              v-if="chartGapReason(getChart(activeTab))"
              type="no-data"
              title="这份数据出不了这张图"
              :description="chartGapReason(getChart(activeTab)) ?? ''"
              :show-action="false"
              class="chart-gap-notice"
              data-testid="chart-gap-notice"
            />
            <div
              v-else
              :ref="(el) => setChartDomRef(activeTab, el as Element | null)"
              class="generic-chart-canvas"
              style="height: 480px"
              role="img"
              :aria-label="`${chartTypes.find(c => c.key === activeTab)?.label}图表`"
              title="单击: 高亮同名数据 | 双击: 添加标注"
            />
            <ul v-if="chartNotes(getChart(activeTab)).length" class="chart-data-notes" data-testid="chart-data-notes">
              <li v-for="note in chartNotes(getChart(activeTab))" :key="note">{{ note }}</li>
            </ul>
          </el-card>

          <!-- Monthly data rows (yoy_mom_comparison) -->
          <div
            v-if="activeTab === 'yoy_mom_comparison' && getChart(activeTab)?.monthlyDataRows"
            class="monthly-data-rows"
            style="margin-top: 16px"
          >
            <div class="data-row">
              <span class="data-row-label">本年</span>
              <span
                v-for="(v, idx) in getChart(activeTab)!.monthlyDataRows.currentYear"
                :key="'cy-' + idx"
                class="data-row-cell"
              >{{ v }}</span>
            </div>
            <div class="data-row">
              <span class="data-row-label">上年</span>
              <span
                v-for="(v, idx) in getChart(activeTab)!.monthlyDataRows.lastYear"
                :key="'ly-' + idx"
                class="data-row-cell"
              >{{ v }}</span>
            </div>
          </div>

          <!-- Data table -->
          <el-card
            v-if="tableVisibleByType[activeTab] && getChart(activeTab)?.tableData"
            shadow="hover"
            style="margin-top: 16px"
          >
            <template #header>
              <div class="chart-card-header"><span>数据明细</span></div>
            </template>
            <div class="chart-data-table">
              <table>
                <thead>
                  <tr>
                    <th v-for="col in (getChart(activeTab)!.tableData as Record<string, unknown>)?.headers as string[] ?? []" :key="col">{{ col }}</th>
                    <th style="min-width:80px">趋势</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, rIdx) in (getChart(activeTab)!.tableData as Record<string, unknown>)?.rows as Record<string, unknown>[] ?? []" :key="rIdx">
                    <td>{{ (row as Record<string, unknown>).label }}</td>
                    <td v-for="(val, vIdx) in ((row as Record<string, unknown>).values as unknown[] ?? [])" :key="vIdx">{{ val }}</td>
                    <td>
                      <span
                        v-if="Array.isArray((row as Record<string, unknown>).values)"
                        v-html="sparklineSVG(
                          ((row as Record<string, unknown>).values as unknown[]).map((c: unknown) => parseFloat(String(c)) || 0),
                          -1, 80, 22, '#2D8B57'
                        )"
                      />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </el-card>

          <!-- AI Analysis (always visible) + Follow-up conversation -->
          <el-card shadow="hover" style="margin-top: 16px" class="analysis-card-tab">
            <template #header>
              <div class="chart-card-header">
                <span>AI 分析</span>
                <el-button
                  v-if="analysisByType[activeTab]"
                  size="small"
                  text
                  type="primary"
                  :loading="analysisLoadingByType[activeTab]"
                  @click="forceRequestAnalysis(activeTab); followUpsByType[activeTab] = []"
                >
                  重新分析
                </el-button>
              </div>
            </template>

            <!-- Initial analysis -->
            <ChartSkeleton v-if="analysisLoadingByType[activeTab]" type="ai" />
            <div v-else-if="analysisByType[activeTab]" class="analysis-text" v-html="renderAnalysis(analysisByType[activeTab])" />
            <div v-else style="text-align: center; padding: 24px; color: #909399">
              <p style="margin-bottom: 12px">点击下方按钮获取该图表的 AI 分析</p>
              <el-button type="primary" size="small" @click="requestAnalysis(activeTab)">生成 AI 分析</el-button>
            </div>

            <!-- Follow-up conversation history -->
            <div v-if="followUpsByType[activeTab]?.length" class="follow-up-history">
              <div v-for="(item, idx) in followUpsByType[activeTab]" :key="idx" class="follow-up-item">
                <div class="follow-up-question">
                  <span class="follow-up-role">追问</span>
                  <span>{{ item.question }}</span>
                </div>
                <div class="follow-up-answer analysis-text" v-html="renderAnalysis(item.answer)" />
              </div>
            </div>

            <!-- Follow-up loading -->
            <div v-if="followUpLoading" class="follow-up-item" style="margin-top: 12px">
              <ChartSkeleton type="ai" />
            </div>

            <!-- Follow-up input -->
            <div v-if="analysisByType[activeTab]" class="follow-up-input">
              <el-input
                v-model="followUpInput"
                placeholder="对此分析有疑问？输入追问..."
                size="small"
                :disabled="followUpLoading"
                @keyup.enter="submitFollowUp(activeTab)"
              />
              <el-button
                type="primary"
                size="small"
                :loading="followUpLoading"
                :disabled="!followUpInput.trim()"
                @click="submitFollowUp(activeTab)"
              >
                发送
              </el-button>
            </div>
          </el-card>
        </template>

        <!-- No data -->
        <template v-else>
          <SmartBIEmptyState
            type="no-data"
            title="这份数据出不了这张图"
            :description="chartNav.missing.find(m => m.key === activeTab)?.reason ?? '这份数据缺少这张图需要的列'"
            :show-action="false"
          />
        </template>
      </main>
    </div>

    <!-- Small Multiples Section -->
    <el-card
      v-if="smallMultiplesVisible && dashboardResponse && !isGenerating"
      class="dashboard-card"
      shadow="hover"
      style="margin-top: 16px"
    >
      <template #header>
        <div class="chart-card-header">
 <span> 维度对比 (Small Multiples)</span>
          <el-select
            v-model="smallMultiplesDimension"
            size="small"
            placeholder="选择拆分维度"
            style="width: 130px"
          >
            <el-option label="品类" value="category" />
            <el-option label="月份" value="month" />
            <el-option label="部门" value="department" />
          </el-select>
        </div>
      </template>
      <SmallMultiplesChart
        :data="chartDataForSmallMultiples"
        :config="smallMultiplesConfig"
        x-field="month"
        y-field="actual"
        height="500px"
      />
    </el-card>
    </template>
    </el-tab-pane>

    <!-- P4: 财务数据分析 (embed FinanceAnalysis, 原 /smart-bi/finance) -->
    <el-tab-pane label="财务数据分析" name="analysis">
      <FinanceAnalysis />
    </el-tab-pane>

    <!-- A3: 生产成本 — 工厂租户专属 tab (gold production cost, 复用 useProductionGold composable) -->
    <el-tab-pane
      v-if="authStore.factoryType !== 'RESTAURANT'"
      label="生产成本"
      name="production-cost"
    >
      <FactoryCostAnalysis />
    </el-tab-pane>
    </el-tabs>

    <!-- Bookmark Drawer -->
    <el-drawer
      v-model="showBookmarkPanel"
      title="视图书签"
      direction="rtl"
      size="350px"
    >
      <BookmarkPanel
        dashboard-id="financial-dashboard"
        :current-state="currentDashboardState"
        @apply="applyBookmark"
        @close="showBookmarkPanel = false"
      />
    </el-drawer>

    <!-- D2: Annotation Dialog -->
    <el-dialog
      v-model="annotationDialogVisible"
      title="添加数据标注"
      width="400px"
      :close-on-click-modal="true"
    >
      <el-form label-position="top">
        <el-form-item label="标注内容">
          <el-input
            v-model="annotationForm.text"
            placeholder="输入标注文字"
            maxlength="50"
            show-word-limit
            @keyup.enter="confirmAnnotation"
          />
        </el-form-item>
        <el-form-item label="标注颜色">
          <div style="display: flex; gap: 8px">
            <span
              v-for="c in ['#e6a23c', '#FF5630', '#36B37E', '#2D8B57', '#6B778C']"
              :key="c"
              style="width: 28px; height: 28px; border-radius: 50%; cursor: pointer; border: 2px solid transparent; transition: border-color 0.2s"
              :style="{ background: c, borderColor: annotationForm.color === c ? '#333' : 'transparent' }"
              @click="annotationForm.color = c"
            />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="annotationDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmAnnotation" :disabled="!annotationForm.text.trim()">添加标注</el-button>
      </template>
    </el-dialog>

    <!-- Presentation Mode -->
    <PresentationMode
      :visible="isPresentationVisible"
      :slides="presentationSlides"
      :chart-results="charts"
      company-name="白垩纪科技"
      :period="periodLabel"
      @close="isPresentationVisible = false"
    />

    <!-- Fix 64: Spotlight overlay -->
    <Teleport to="body">
      <div
        v-if="spotlightChart"
        class="spotlight-overlay"
        @click.self="exitSpotlight"
        @keydown="handleSpotlightKeydown"
        tabindex="0"
      >
        <div class="spotlight-container">
          <div class="spotlight-header">
            <span>{{ chartTypes.find(c => c.key === spotlightChart)?.icon }} {{ chartTypes.find(c => c.key === spotlightChart)?.label }}</span>
 <el-button size="small" text @click="exitSpotlight"> 退出聚焦</el-button>
          </div>
          <div
            :ref="(el) => { if (spotlightChart) setChartDomRef(spotlightChart, el as Element | null) }"
            class="spotlight-chart-canvas"
          />
        </div>
      </div>
    </Teleport>

    <!-- Export progress dialog -->
    <el-dialog v-model="exportProgress.visible" title="导出进度" width="400px" :close-on-click-modal="false" :show-close="false">
      <el-steps :active="exportProgress.currentStep" finish-status="success" simple style="margin-bottom: 20px">
        <el-step v-for="step in exportProgress.steps" :key="step" :title="step" />
      </el-steps>
      <el-progress :percentage="exportProgress.percentage" :stroke-width="8" />
    </el-dialog>
  </div>
</template>

<style scoped>
.financial-dashboard-pbi {
  padding: 16px;
  min-height: 100vh;
  background: #f5f7fa;
}

/* Toolbar */
.toolbar-card {
  margin-bottom: 16px;
}

:deep(.toolbar-card .el-card__body) {
  padding: 12px 16px;
}

.toolbar-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* Empty state */
.chart-data-notes {
  margin: 8px 0 0;
  padding: 8px 12px 8px 28px;
  background: #fdf6ec;
  border-radius: 4px;
  color: #b88230;
  font-size: 12px;
  line-height: 1.7;
}

.empty-state {
  display: flex;
  justify-content: center;
  padding: 80px 0;
}

/* Charts grid */
.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.chart-card--wide {
  grid-column: 1 / -1;
}

@media (max-width: 1200px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
  .chart-card--wide {
    grid-column: 1;
  }
}

/* Chart card */

/* C4: Focus ring for keyboard navigation */
.chart-card:focus {
  outline: 2px solid #2D8B57;
  outline-offset: 2px;
  box-shadow: 0 0 0 4px rgba(45, 139, 87, 0.15);
}

.chart-card:focus:not(:focus-visible) {
  outline: none;
  box-shadow: none;
}

.chart-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  font-size: 14px;
  color: var(--el-text-color-primary, #303133);
}

/* Generic KPI row */
.generic-kpi-row {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

/* KPI 卡: 中性底, 不按红绿着色 (红绿在不同图里含义不同); 标签在上、数值在下, 数值用正文主色保证对比度 */
.generic-kpi-chip {
  flex: 1;
  min-width: 140px;
  background: var(--el-fill-color-light, #f5f7fa);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column-reverse;
  align-items: flex-start;
  gap: 4px;
  color: var(--el-text-color-secondary, #606266);
}

.kpi-val {
  font-size: 24px;
  font-weight: 700;
  color: var(--el-text-color-primary, #303133);
  font-feature-settings: "tnum";
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.kpi-lbl {
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
}

/* A3: Sparkline (颜色跟随文字色, 不表示好坏) */
.kpi-sparkline {
  width: 72px;
  height: 20px;
  order: -1;
}

/* Quarterly progress bars (budget_achievement) */
.quarterly-progress-row {
  display: flex;
  gap: 12px;
  padding: 4px 16px 8px;
}

.q-progress-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.q-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular, #606266);
  white-space: nowrap;
}

.q-progress-item :deep(.el-progress) {
  flex: 1;
  min-width: 0;
}

.q-rate {
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

/* Monthly data rows (yoy_mom_comparison) */
.monthly-data-rows {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 16px;
  background: rgba(45, 139, 87, 0.03);
  border-radius: 6px;
  margin-top: 4px;
}

.data-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.data-row-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--el-text-color-secondary, #909399);
  width: 36px;
  flex-shrink: 0;
}

.data-row-cell {
  flex: 1;
  text-align: center;
  font-size: 11px;
  color: var(--el-text-color-regular, #606266);
}

/* Chart card title group */
.chart-card-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
}

.chart-card-title-group .chart-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* D3: Anomaly badge */
.anomaly-badge {
  font-size: 11px;
  cursor: help;
}

/* Interaction tips */
.findings {
  display: grid;
  gap: 14px;
  margin-bottom: 20px;
}
.findings__title,
.findings__ai-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}
.findings__group--good .findings__title { color: #1B7A4A; }
.findings__group--attention .findings__title { color: #C0362C; }
.findings__list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.8;
  font-size: 14px;
  color: var(--el-text-color-primary, #303133);
}
.findings__source,
.findings__none {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
}
.findings__ai-empty {
  padding: 16px 0;
}

.data-source-tag {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 4px;
  color: var(--el-text-color-regular, #606266);
  font-size: 12px;
  white-space: nowrap;
}

.dashboard-headline {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 0 0 12px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  background: var(--el-bg-color, #fff);
  color: var(--el-text-color-primary, #303133);
  font-size: 15px;
  line-height: 1.6;
}

.dashboard-headline__dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  transform: translateY(-1px);
  background: var(--el-text-color-secondary, #909399);
}

.dashboard-headline--good .dashboard-headline__dot { background: #1B7A4A; }
.dashboard-headline--warn .dashboard-headline__dot { background: #9A6700; }
.dashboard-headline--bad .dashboard-headline__dot { background: #C0362C; }

.period-trigger {
  margin-left: 8px;
  min-width: 128px;
  justify-content: space-between;
}

.period-trigger.is-invalid {
  color: #C0362C;
  border-color: #C0362C;
}

.period-popover__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.period-popover__error {
  margin-right: auto;
  color: #C0362C;
  font-size: 12px;
}

.interaction-tips {
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
  text-align: center;
  margin-bottom: 8px;
}

.interaction-tips b {
  color: var(--el-text-color-regular, #606266);
}

/* B1: Cross-filter bar */
.cross-filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(45, 139, 87, 0.08);
  border-radius: 8px;
  padding: 8px 16px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-color-primary, #2D8B57);
}

/* Analysis panel */
.analysis-panel {
  margin-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter, #f0f0f0);
  padding-top: 12px;
}

.analysis-text {
  font-size: 14px;
  color: var(--el-text-color-primary, #303133);
  line-height: 1.75;
  background: var(--el-fill-color-lighter, #fafafa);
  border-radius: 6px;
  padding: 12px 16px;
  max-width: 72em;
}

.analysis-text :deep(p),
.analysis-text :deep(ul),
.analysis-text :deep(ol) {
  margin: 0 0 8px;
}

.analysis-text :deep(ul),
.analysis-text :deep(ol) {
  padding-left: 1.4em;
}

.analysis-text :deep(h1),
.analysis-text :deep(h2),
.analysis-text :deep(h3),
.analysis-text :deep(h4) {
  margin: 12px 0 6px;
  font-size: 14px;
  font-weight: 700;
}

.analysis-text :deep(> :last-child) {
  margin-bottom: 0;
}

/* ---- B3: Dark mode ---- */
.financial-dashboard-pbi[data-theme="dark"] {
  background: var(--bg-color-page);
  color: var(--text-color-primary);
}

.financial-dashboard-pbi[data-theme="dark"] .toolbar-card,
.financial-dashboard-pbi[data-theme="dark"] .chart-card,
.financial-dashboard-pbi[data-theme="dark"] .dashboard-card {
  background: var(--bg-color-overlay);
  border-color: var(--border-color);
}

.financial-dashboard-pbi[data-theme="dark"] :deep(.el-card) {
  background: var(--bg-color-overlay);
  border-color: var(--border-color);
  color: var(--text-color-primary);
}

.financial-dashboard-pbi[data-theme="dark"] :deep(.el-card__header) {
  border-bottom-color: var(--border-color);
}

.financial-dashboard-pbi[data-theme="dark"] .chart-card-header {
  color: var(--text-color-primary);
}

.financial-dashboard-pbi[data-theme="dark"] .generic-kpi-chip {
  background: rgba(255, 255, 255, 0.06);
}

.financial-dashboard-pbi[data-theme="dark"] .kpi-val {
  color: var(--color-primary-light);
}

.financial-dashboard-pbi[data-theme="dark"] .kpi-lbl {
  color: var(--text-color-secondary);
}

.financial-dashboard-pbi[data-theme="dark"] .analysis-text {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-color-regular);
}

.financial-dashboard-pbi[data-theme="dark"] .monthly-data-rows {
  background: rgba(255, 255, 255, 0.04);
}

.financial-dashboard-pbi[data-theme="dark"] .data-row-cell,
.financial-dashboard-pbi[data-theme="dark"] .data-row-label {
  color: var(--text-color-secondary);
}

.financial-dashboard-pbi[data-theme="dark"] .cross-filter-bar {
  background: rgba(76, 154, 255, 0.12);
  color: var(--color-primary-light);
}

/* ---- Chart canvas base height (overridden by responsive breakpoints) ---- */
.generic-chart-canvas {
  height: 360px;
  width: 100%;
}

/* ---- C2: Responsive breakpoints ---- */
@media (max-width: 768px) {
  .charts-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .chart-card--wide {
    grid-column: 1;
  }
  .toolbar-inner {
    flex-direction: column;
    align-items: stretch;
  }
  .toolbar-left,
  .toolbar-right {
    justify-content: center;
  }
  .generic-kpi-row {
    gap: 6px;
  }
  .generic-kpi-chip {
    min-width: 80px;
    padding: 8px 8px;
  }
  .kpi-val {
    font-size: 20px;
  }
  .quarterly-progress-row {
    flex-wrap: wrap;
  }
  .generic-chart-canvas {
    height: 260px !important;
  }
}

@media (max-width: 480px) {
  .financial-dashboard-pbi {
    padding: 8px;
  }
  .generic-kpi-row {
    flex-direction: column;
  }
  .generic-kpi-chip {
    flex-direction: row;
    justify-content: space-between;
    align-items: center;
  }
  .kpi-val {
    font-size: 18px;
  }
  .kpi-lbl {
    font-size: 10px;
  }
  .generic-chart-canvas {
    height: 220px !important;
  }
  .toolbar-right {
    flex-wrap: wrap;
    justify-content: flex-start;
  }
}

/* ---- Lazy load fade-in ---- */
.chart-card {
  overflow: hidden;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 0.4s ease, transform 0.4s ease, box-shadow 0.25s ease;
}
.chart-card--visible {
  opacity: 1;
  transform: translateY(0);
}

/* ---- E5: Card hover micro-interactions ---- */
.chart-card--visible:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 28px rgba(45, 139, 87, 0.12), 0 2px 8px rgba(0, 0, 0, 0.06);
}

/* ---- E7: KPI Scorecard full-width header ---- */
.chart-card--scorecard {
  order: -1; /* always first in grid */
}
.chart-card--scorecard .generic-kpi-row {
  gap: 16px;
}
.chart-card--scorecard .generic-kpi-chip {
  padding: 16px 20px;
  border-radius: 12px;
  min-width: 140px;
}
.chart-card--scorecard .kpi-val {
  font-size: 32px;
  letter-spacing: -1px;
}
.chart-card--scorecard .kpi-lbl {
  font-size: 12px;
  font-weight: 500;
}
.chart-card--scorecard .kpi-sparkline {
  width: 80px;
  height: 24px;
}

/* ---- E4: Data table ---- */
.chart-data-table {
  margin-top: 12px;
  border-top: 1px solid #f0f0f0;
  padding-top: 12px;
  overflow-x: auto;
  max-height: 240px;
  overflow-y: auto;
}
.chart-data-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.chart-data-table th {
  background: rgba(45, 139, 87, 0.06);
  padding: 6px 10px;
  text-align: left;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
  border-bottom: 2px solid var(--el-border-color, #e8e8e8);
  position: sticky;
  top: 0;
  white-space: nowrap;
}
.chart-data-table td {
  padding: 5px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
  color: var(--el-text-color-regular, #606266);
  white-space: nowrap;
}
.chart-data-table tr:hover td {
  background: rgba(45, 139, 87, 0.03);
}

/* ---- E6: Print stylesheet ---- */
@media print {
  .financial-dashboard-pbi {
    background: #fff !important;
    padding: 0 !important;
  }
  .toolbar-card,
  .interaction-tips,
  .cross-filter-bar,
  .analysis-panel,
  .chart-data-table {
    display: none !important;
  }
  .charts-grid {
    display: block !important;
  }
  .chart-card {
    opacity: 1 !important;
    transform: none !important;
    break-inside: avoid;
    page-break-inside: avoid;
    margin-bottom: 16px;
    box-shadow: none !important;
    border: 1px solid #e0e0e0 !important;
  }
  .chart-card:hover {
    transform: none !important;
    box-shadow: none !important;
  }
  .generic-chart-canvas {
    height: 280px !important;
  }
  .kpi-val {
    color: #000 !important;
  }
}

/* Dark mode additions for E4/E5 */
.financial-dashboard-pbi[data-theme="dark"] .chart-card--visible:hover {
  box-shadow: 0 8px 28px rgba(76, 154, 255, 0.15), 0 2px 8px rgba(0, 0, 0, 0.2);
}
.financial-dashboard-pbi[data-theme="dark"] .chart-data-table th {
  background: rgba(76, 154, 255, 0.1);
  color: var(--text-color-primary);
  border-bottom-color: var(--border-color);
}
.financial-dashboard-pbi[data-theme="dark"] .chart-data-table td {
  color: var(--text-color-secondary);
  border-bottom-color: var(--border-color);
}
.financial-dashboard-pbi[data-theme="dark"] .chart-data-table tr:hover td {
  background: rgba(76, 154, 255, 0.06);
}

/* ---- Glassmorphism chart cards ---- */
.chart-card :deep(.el-card__body) {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.25);
}

/* ---- Dark mode glassmorphism variant ---- */
.financial-dashboard-pbi[data-theme="dark"] .chart-card :deep(.el-card__body) {
  background: rgba(30, 30, 46, 0.72);
  border-color: rgba(255, 255, 255, 0.08);
}

/* ---- Additional responsive: tablet toolbar row ---- */
@media (max-width: 1200px) {
  .toolbar-row {
    flex-wrap: wrap;
    gap: 8px;
  }
}

@media (max-width: 768px) {
  .chart-card-header h3 {
    font-size: 14px !important;
  }
  .toolbar-row {
    flex-wrap: wrap;
    gap: 8px;
  }
}

@media (max-width: 480px) {
  .generic-kpi-row {
    flex-wrap: wrap;
  }
  .generic-kpi-chip {
    min-width: 120px;
    flex: 1 1 45%;
  }
}

/* ---- Fix 72: Slicer / filter bar ---- */
.slicer-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  background: rgba(45, 139, 87, 0.04);
  border: 1px solid rgba(45, 139, 87, 0.1);
  border-radius: 8px;
  padding: 8px 16px;
  margin-bottom: 12px;
}
.slicer-label {
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  white-space: nowrap;
}
.slicer-group {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.slicer-dim-name {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
  margin-right: 2px;
}
.slicer-bar :deep(.el-check-tag) {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 12px;
}

/* ---- Fix 64: Spotlight overlay ---- */
.spotlight-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.spotlight-container {
  position: relative;
  width: 90vw;
  height: 85vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  cursor: default;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.spotlight-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  flex-shrink: 0;
}
.spotlight-chart-canvas {
  flex: 1;
  width: 100%;
  min-height: 0;
}

/* ---- Tab mode: 图表切换条 + 内容 (原深色内嵌侧栏与应用主侧栏叠成两层, 改为内容上方的浅色横向切换条) ---- */
.dashboard-layout {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chart-switcher {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
}

.chart-switcher__groups {
  display: flex;
  align-items: center;
  gap: 4px 20px;
  flex: 1;
  min-width: 0;
  flex-wrap: wrap;
}

.chart-switcher__group {
  display: flex;
  align-items: center;
  gap: 4px;
}

.chart-switcher__group-name {
  margin-right: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #606266);
  white-space: nowrap;
}

.chart-switcher__aside {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.chart-switcher__item {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-regular, #303133);
  font: inherit;
  font-size: 13px;
  line-height: 30px;
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 0.15s ease-out, color 0.15s ease-out;
}

.chart-switcher__item:hover {
  background: var(--el-fill-color-light, #f5f7fa);
}

.chart-switcher__item:focus-visible {
  outline: 2px solid var(--el-color-primary, #409eff);
  outline-offset: 1px;
}

.chart-switcher__item.is-active {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
  font-weight: 600;
}

.chart-switcher__item--muted {
  color: var(--el-text-color-secondary, #606266);
  border-color: var(--el-border-color, #dcdfe6);
}

.chart-switcher__item--primary {
  border-color: var(--el-color-primary-light-5, #a0cfff);
  color: var(--el-color-primary, #409eff);
}

.chart-switcher--loading {
  gap: 8px;
}

.chart-switcher__placeholder {
  width: 88px;
  height: 32px;
  border-radius: 6px;
  background: var(--el-fill-color-light, #f5f7fa);
}

.missing-charts__hint {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--el-text-color-regular, #303133);
}

.missing-charts ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.missing-charts li {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.missing-charts__name {
  align-self: flex-start;
  padding: 0;
  border: 0;
  background: none;
  color: var(--el-color-primary, #409eff);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.missing-charts__reason {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary, #606266);
}

.dash-content {
  flex: 1;
  min-width: 0;
}

.tab-kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.tab-chart-card {
  margin-bottom: 0;
}

/* 窄屏: 切换条横向滚动, 不换成多行堆叠 */
@media (max-width: 1024px) {
  .chart-switcher {
    position: static;
    flex-direction: column;
    align-items: stretch;
  }
  .chart-switcher__groups {
    flex-wrap: nowrap;
    overflow-x: auto;
    padding-bottom: 4px;
  }
  .chart-switcher__aside {
    justify-content: flex-end;
  }
}

@media (prefers-reduced-motion: reduce) {
  .chart-switcher__item {
    transition: none;
  }
}

@media print {
  .chart-switcher {
    display: none !important;
  }
  .dashboard-layout {
    display: block !important;
  }
}

/* ---- Follow-up conversation ---- */
.follow-up-history {
  margin-top: 16px;
  border-top: 1px dashed var(--el-border-color-lighter, #e8e8e8);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.follow-up-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.follow-up-question {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-primary, #303133);
}

.follow-up-role {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: #2D8B57;
  border-radius: 4px;
  padding: 1px 6px;
  line-height: 1.5;
}

.follow-up-answer {
  margin-left: 0;
}

.follow-up-input {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter, #f0f0f0);
  padding-top: 12px;
}

.follow-up-input .el-input {
  flex: 1;
}

/* Dark mode follow-up */
.financial-dashboard-pbi[data-theme="dark"] .follow-up-history {
  border-top-color: var(--border-color);
}

.financial-dashboard-pbi[data-theme="dark"] .follow-up-role {
  background: #1B7A4A;
}

.financial-dashboard-pbi[data-theme="dark"] .follow-up-input {
  border-top-color: var(--border-color);
}
</style>
