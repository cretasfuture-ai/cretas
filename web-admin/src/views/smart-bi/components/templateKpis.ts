/**
 * Which kpiValues entries a TemplateCard shows in its 4 KPI slots.
 *
 * Templates put context fields next to the KPIs (monthly_trend emits
 * `unit: "元"` and `aggregation: "sum"`). They are not KPIs, but Postgres
 * jsonb stores keys shortest-first, so they sorted to the front and took two
 * of the four slots as raw English ("unit / 元 / aggregation / sum" on the
 * F006 经营驾驶舱, 2026-10-12). They describe the values; never show them as one.
 */
export const KPI_META_KEYS: ReadonlySet<string> = new Set(['unit', 'aggregation']);

export const KPI_SLOT_COUNT = 4;

export function visibleKpiEntries(
  kv: Record<string, unknown> | null | undefined,
  hideKey: (key: string) => boolean = () => false,
): Array<[string, unknown]> {
  if (!kv || typeof kv !== 'object') return [];
  return Object.entries(kv)
    .filter(([key]) => !KPI_META_KEYS.has(key) && !hideKey(key))
    .slice(0, KPI_SLOT_COUNT);
}

// English KPI key → 中文. Templates emit snake_case keys (backend may camelCase
// them); both forms resolve. Unknown keys fall through to the raw key, and
// templateKpiLabels.spec.ts pins that every key a Python template emits has a label.
export const KPI_LABEL_MAP: Record<string, string> = {
  // generic totals
  total: '合计',
  // monthly_trend 的 total_revenue 是主度量的累计 (工厂可能是产量), 不一定是营业额
  totalRevenue: '累计',
  totalTracked: '统计总数',
  totalOrders: '订单总数',
  totalItemCount: '明细总数',
  grandTotal: '总计',
  grandRevenue: '营业额',
  grandOrders: '订单总数',
  grandAmount: '合计金额',
  revenue: '营业额',
  orders: '订单数',
  orderCount: '订单数',
  billCount: '单数',
  avgBillValue: '客单价',
  avgOrder: '平均客单',
  storeCount: '门店数',
  dayCount: '天数',
  periodCount: '期数',
  monthCount: '月份数',
  latestMonth: '最近月份',
  latestRevenue: '最近月营业额',
  // peaks / extremes
  peakValue: '峰值',
  peakPeriod: '峰值日期',
  peakMonth: '峰值月份',
  peakArea: '峰值区域',
  peakSlot: '峰值时段',
  peakRevenue: '峰值营业额',
  // R5-9: two "worst/trough" keys used to read identically. Differentiate:
  // worstMonth = the worst single value; troughMonth = the smoothed trough
  // point from anomaly detection.
  worstMonth: '最差月份',
  troughMonth: '平滑低谷月',
  // stats
  max: '最大值',
  min: '最小值',
  mean: '均值',
  std: '标准差',
  median: '中位数',
  avg: '平均值',
  // deltas / ratios
  deltaPct: '波动率(%)',
  dodDeltaPct: '环比(%)',
  yoyDeltaPct: '同比(%)',
  sharePct: '占比(%)',
  netSharePct: '到账率(%)',
  anomalyCount: '异常月数',
  weekdayAvgOrder: '工作日均客单',
  weekendAvgOrder: '周末均客单',
  weekendOrderShare: '周末订单占比(%)',
  // dimensions (generic top_n / category_distribution / anomaly)
  dimCount: '维度数',
  topLabel: 'Top 项',
  topValue: 'Top 值',
  topSharePct: 'Top 占比(%)',
  topCategory: 'Top 分类',
  categoryCount: '分类数',
  topCategoryShare: 'Top 分类占比(%)',
  // finance
  netRevenue: '实收',
  grossRevenue: '应收',
  hasCostData: '是否含成本',
  topSlot: '主要时段',
  topChannel: '主要渠道',
  topPlatform: '最大平台',
  topPlatformAmount: '最大平台金额',
  platformCount: '平台数',
  // stored value card
  cardTotal: '储值卡消费',
  cardOrders: '储值卡笔数',
  // dish / product
  topDishName: '热销菜品',
  topDishQty: '热销销量',
  topDishRevenue: '热销营收',
  distinctDishes: '菜品总数',
  bottomDish: '最滞销菜品',
  bottomQty: '最滞销销量',
  nearZeroCount: '近零销量数',
  // combo
  comboOrders: '套餐订单数',
  topComboName: '热销套餐',
  usageRatePct: '使用率(%)',
  // product
  topProduct: '热销商品',
  topProductRevenue: '热销商品营收',
  // LSM-COSTBI 第 10 项: 物化模板实际产出但之前漏了的键 (界面曾露出 troughValue)
  troughValue: '谷值',
  troughPeriod: '谷值日期',
  avgValue: '平均值',
  outlierCount: '异常条数',
  topDim: 'Top 维度',
  totalLabels: '项目总数',
  labelsFor80Pct: '贡献 80% 的项目数',
  labelsFor80PctShare: '贡献 80% 的项目占比(%)',
  labelsFor80pct: '贡献 80% 的项目数',
  labelsFor80pctShare: '贡献 80% 的项目占比(%)',
  avgDefectRatePct: '平均不良率(%)',
  topDefectCount: '最多不良数',
  topDefectRatePct: '最高不良率(%)',
  freq: '粒度',
};

function snakeToCamel(key: string): string {
  return key.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase());
}

export function localizeKpiKey(key: string): string {
  return KPI_LABEL_MAP[key] || KPI_LABEL_MAP[snakeToCamel(key)] || key;
}
