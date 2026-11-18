/**
 * 成本分析页四张图 → 一图一分析的输入（问题 40，LSM-COSTBI-D）。
 *
 * 复用 smart-bi 的 chartInsight（buildChartInsight / ChartInsightProvider），这里只负责把页面数据
 * 摆成它认的 ChartWithMeta：类目、数值、单位、上期同口径数据。分析句子在 chartInsight 里按数据现算。
 * - 上期 = 紧挨着当前区间、等长的前一段；接口没给（null）就不传 previous，⛔ 不编环比。
 * - 价格权限由调用方以 UserPermissions.canViewFinance 传入，成本类图在无权限时不出金额和占比。
 */
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import type {
  CostStructureData,
  CostStructureSummary,
  ProductCostItem,
  YieldTrendData,
} from '@/api/smartbi/factoryGold';
import { fillCalendarDays } from './costAnalysisFormat';

const COST_KEYS = [
  ['材料成本', 'totalMaterialCost'],
  ['人工成本', 'totalLaborCost'],
  ['设备成本', 'totalEquipmentCost'],
  ['其他成本', 'totalOtherCost'],
] as const;

/** 当前区间的前一段等长区间（含首尾），用来取「上期」。 */
export function previousRange(startDate: string, endDate: string): { startDate: string; endDate: string } {
  const parse = (key: string) => {
    const [y, m, d] = key.split('-').map(Number);
    return new Date(y, m - 1, d);
  };
  const pad = (n: number) => String(n).padStart(2, '0');
  const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const start = parse(startDate);
  const end = parse(endDate);
  const days = Math.round((end.getTime() - start.getTime()) / 86400000) + 1;
  const prevEnd = new Date(start);
  prevEnd.setDate(prevEnd.getDate() - 1);
  const prevStart = new Date(prevEnd);
  prevStart.setDate(prevStart.getDate() - (days - 1));
  return { startDate: fmt(prevStart), endDate: fmt(prevEnd) };
}

function pieConfig(summary: Partial<CostStructureSummary> | undefined | null): ChartWithMeta['config'] | null {
  if (!summary) return null;
  const data = COST_KEYS
    .map(([name, key]): { name: string; value: number | null | undefined } => ({ name, value: summary[key] }))
    .filter((d): d is { name: string; value: number } => typeof d.value === 'number' && d.value > 0);
  return data.length ? { series: [{ type: 'pie', data }] } : null;
}

export function costPieInsightChart(
  current: CostStructureData | null,
  previous: CostStructureData | null,
): ChartWithMeta | null {
  const config = pieConfig(current?.summary as Partial<CostStructureSummary> | undefined);
  if (!config) return null;
  const prevConfig = pieConfig(previous?.summary as Partial<CostStructureSummary> | undefined);
  return {
    chartType: 'pie',
    title: '成本结构占比',
    meta: { xDim: 'category', yMetric: 'cost', aggregation: 'sum', domain: 'factory' },
    config,
    previous: prevConfig ? { config: prevConfig } : null,
  };
}

function dailyCostConfig(data: CostStructureData | null): ChartWithMeta['config'] | null {
  if (!data?.rows?.length) return null;
  const days = fillCalendarDays(data.rows);
  return {
    xAxis: { data: days.map((d) => d.date.slice(5)) },
    series: COST_KEYS.map(([name, key]) => ({
      name: name.replace('成本', ''),
      type: 'bar',
      data: days.map((d) => d.point?.[key] ?? null),
    })),
  };
}

export function costTrendInsightChart(
  current: CostStructureData | null,
  previous: CostStructureData | null,
): ChartWithMeta | null {
  const config = dailyCostConfig(current);
  if (!config) return null;
  const prevConfig = dailyCostConfig(previous);
  return {
    chartType: 'bar',
    title: '每日成本趋势',
    meta: { xDim: 'time', yMetric: 'cost', aggregation: 'sum', domain: 'factory' },
    config,
    previous: prevConfig ? { config: prevConfig } : null,
  };
}

function yieldConfig(data: YieldTrendData | null): ChartWithMeta['config'] | null {
  if (!data?.trend?.length) return null;
  const days = fillCalendarDays(data.trend);
  return {
    xAxis: { data: days.map((d) => d.date.slice(5)) },
    series: [{
      name: '出成率',
      type: 'line',
      data: days.map((d) => (d.point?.avgYieldRate != null ? Number(d.point.avgYieldRate.toFixed(1)) : null)),
    }],
  };
}

export function yieldTrendInsightChart(
  current: YieldTrendData | null,
  previous: YieldTrendData | null,
): ChartWithMeta | null {
  const config = yieldConfig(current);
  if (!config) return null;
  const prevConfig = yieldConfig(previous);
  return {
    chartType: 'line',
    title: '出成率趋势',
    meta: { xDim: 'time', yMetric: 'pct', aggregation: 'avg', domain: 'factory', unit: '%', betterWhen: 'higher' },
    config,
    previous: prevConfig ? { config: prevConfig } : null,
  };
}

/**
 * 产品对比条形图（与图上同一批产品、同一组标签）：有价格权限比单位成本，没有比出成率。
 * previousProducts 用同样的 labelOf 生成标签，上期里能按标签找到同一产品才算环比。
 */
export function productInsightChart(
  products: ProductCostItem[],
  labels: string[],
  canViewPrice: boolean,
  previousProducts: ProductCostItem[] | null,
  labelOf: (p: ProductCostItem) => string,
): ChartWithMeta | null {
  if (!products.length) return null;
  const value = (p: ProductCostItem) => (canViewPrice ? p.costPerUnit : p.avgYieldRate);
  const prev = (previousProducts ?? []).filter((p) => value(p) != null);
  return {
    chartType: 'bar',
    title: canViewPrice ? '各产品单位成本' : '各产品出成率',
    meta: canViewPrice
      ? { xDim: 'product', yMetric: 'cost', aggregation: 'avg', domain: 'factory', betterWhen: 'lower' }
      : { xDim: 'product', yMetric: 'pct', aggregation: 'avg', domain: 'factory', unit: '%', betterWhen: 'higher' },
    config: {
      yAxis: { data: labels },
      series: [{ type: 'bar', data: products.map(value) }],
    },
    pointUnits: canViewPrice ? products.map((p) => p.unit ?? undefined) : undefined,
    previous: prev.length
      ? { config: { yAxis: { data: prev.map(labelOf) }, series: [{ type: 'bar', data: prev.map(value) }] } }
      : null,
  };
}
