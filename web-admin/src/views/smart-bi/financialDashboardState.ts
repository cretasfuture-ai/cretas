/**
 * 财务看板 (FinancialDashboardPBI) 的纯逻辑: 期间参数、图表缺数据说明、数据版本。
 * 抽出来是为了能单测; 组件里只做调用。
 */
import type { PeriodSelection } from '@/components/smartbi/PeriodSelector.vue';

/** Keep formatted amounts unchanged while making the full unit one readable token. */
export function kpiDisplayParts(original: string | number, unit = '') {
  const value = typeof original === 'number' ? original.toLocaleString('zh-CN') : String(original);
  const scaledAmount = unit === '元' ? /^([+-]?[\d,.]+)(万|亿)$/.exec(value) : null;
  return {
    value: scaledAmount ? scaledAmount[1] : value,
    unit: scaledAmount ? `${scaledAmount[2]}元` : unit,
    numeric: /^[+-]?[\d,.]+$/.test(scaledAmount ? scaledAmount[1] : value),
  };
}

/** 后端 /financial-dashboard/batch 的期间参数 (period_type 见 financial_data_normalizer._apply_period_filter) */
export interface PeriodRequest {
  periodType: 'all' | 'year' | 'month_range';
  year: number;
  startMonth: number;
  endMonth: number;
}

export type PeriodRequestResult = PeriodRequest | { error: string };

const YEAR_MONTH = /^(\d{4})-(\d{1,2})$/;
const YEAR_QUARTER = /^(\d{4})-Q([1-4])$/i;

function parseYearMonth(text: string): { year: number; month: number } | null {
  const m = YEAR_MONTH.exec(String(text).trim());
  if (!m) return null;
  const month = Number(m[2]);
  return month >= 1 && month <= 12 ? { year: Number(m[1]), month } : null;
}

function parseYearQuarter(text: string): { year: number; quarter: number } | null {
  const m = YEAR_QUARTER.exec(String(text).trim());
  return m ? { year: Number(m[1]), quarter: Number(m[2]) } : null;
}

function pair(value: PeriodSelection['value']): [string, string] | null {
  return Array.isArray(value) && value.length === 2 ? [String(value[0]), String(value[1])] : null;
}

const INVALID = (what: string): { error: string } => ({ error: `无法识别的期间：${what}` });

/**
 * PeriodSelector 的选择 → 后端期间参数。
 *
 * - 全部历史 → all (不按年也不按月过滤)
 * - 单年 → year (只要那一年, 多年度数据不会混在一起)
 * - 单月 / 单季 / 月份区间 / 季度区间 / 同年自定义 → month_range
 * - 跨年自定义 → 报错, ⛔ 不静默截成某一年
 */
export function periodSelectionToRequest(selection: PeriodSelection): PeriodRequestResult {
  const year = selection.year;
  if (selection.allHistory) return { periodType: 'all', year, startMonth: 1, endMonth: 12 };

  switch (selection.type) {
    case 'year':
      return { periodType: 'year', year, startMonth: 1, endMonth: 12 };
    case 'month': {
      const ym = parseYearMonth(String(selection.value));
      if (!ym) return INVALID(String(selection.value));
      return { periodType: 'month_range', year: ym.year, startMonth: ym.month, endMonth: ym.month };
    }
    case 'quarter': {
      const yq = parseYearQuarter(String(selection.value));
      if (!yq) return INVALID(String(selection.value));
      return { periodType: 'month_range', year: yq.year, startMonth: yq.quarter * 3 - 2, endMonth: yq.quarter * 3 };
    }
    case 'quarter_range': {
      const p = pair(selection.value);
      const a = p && parseYearQuarter(p[0]);
      const b = p && parseYearQuarter(p[1]);
      if (!a || !b) return INVALID(String(selection.value));
      if (a.year !== b.year) return { error: '跨年的季度区间暂不支持，请分年查看' };
      return { periodType: 'month_range', year: a.year, startMonth: a.quarter * 3 - 2, endMonth: b.quarter * 3 };
    }
    case 'month_range':
    case 'custom': {
      const p = pair(selection.value);
      const a = p && parseYearMonth(p[0]);
      const b = p && parseYearMonth(p[1]);
      if (!a || !b) return INVALID(String(selection.value));
      if (a.year !== b.year) return { error: '跨年的月份区间暂不支持，请分年查看' };
      return { periodType: 'month_range', year: a.year, startMonth: a.month, endMonth: b.month };
    }
    default:
      return INVALID(String(selection.type));
  }
}

/** 期间按钮上的文字: 全部历史 / 2026年 / 2026年3月 / 2026年Q2 / 2026年1–7月。无效选择显示原因。 */
export function periodRequestLabel(request: PeriodRequestResult): string {
  if ('error' in request) return request.error;
  const { periodType, year, startMonth, endMonth } = request;
  if (periodType === 'all') return '全部历史';
  if (periodType === 'year' || (startMonth === 1 && endMonth === 12)) return `${year}年`;
  if (startMonth === endMonth) return `${year}年${startMonth}月`;
  if (startMonth % 3 === 1 && endMonth === startMonth + 2) return `${year}年Q${(startMonth + 2) / 3}`;
  return `${year}年${startMonth}–${endMonth}月`;
}

/**
 * 重新画图的触发值: 每次生成完成 (拿到结果且不在生成中) 都是一个新值, 生成中为 null。
 * 旧触发只看「图表数量」: 点月份筛选 / 换期间后数量不变, 看板在生成中整块卸载又重新挂上,
 * 新画布从没被画过, 图表区一直是空的 (2026-10-22 线上验收)。
 */
export function dashboardRenderKey<T extends object>(response: T | null | undefined, generating: boolean): T | null {
  return generating || !response ? null : response;
}

/** 看板图表结果中本模块用到的字段 */
export interface ChartStateLike {
  echartsOption?: Record<string, unknown> | null;
  analysisContext?: string;
  metadata?: {
    dataQuality?: string;
    reason?: string;
    note?: string;
    reconciliation?: string[];
    /** 出不了图时缺的是什么: column = 补一列; statement = 要另一张报表 */
    gapKind?: GapKind;
  } & Record<string, unknown>;
}

/** 图表没有可画的内容时, 给用户看的原因 (后端原文); 有图时返回 null。 */
export function chartGapReason(chart: ChartStateLike | null | undefined): string | null {
  if (!chart) return null;
  const option = chart.echartsOption;
  if (option && Object.keys(option).length > 0) return null;
  return chart.metadata?.reason || chart.analysisContext || '该图表暂无数据，请检查数据源';
}

/** 需要跟图一起显示的口径说明: 对账差异、合计口径等。 */
export function chartNotes(chart: ChartStateLike | null | undefined): string[] {
  if (!chart?.metadata) return [];
  const notes = [...(chart.metadata.reconciliation ?? [])];
  if (chart.metadata.note) notes.push(chart.metadata.note);
  return notes;
}

export interface ChartNavEntry {
  key: string;
  label: string;
  group: string;
}

export type GapKind = 'column' | 'statement';

/** 后端 unavailableCharts 的一条: 这份数据出不了的图, 缺哪一列 / 要哪张表 */
export interface UnavailableChart {
  chartType: string;
  reason: string;
  gapKind: GapKind;
}

export type MissingChart<T> = T & { reason: string; kind: GapKind };

export interface ChartNavModel<T extends ChartNavEntry> {
  /** 有图可看的图表, 按分组保持原顺序 */
  groups: { name: string; items: T[] }[];
  /** 这份数据出不了的图表, 带给用户看的原因 */
  missing: MissingChart<T>[];
  /** 同一批, 按「补一列就能出」和「需要另一张表」分开 */
  missingByKind: Record<GapKind, MissingChart<T>[]>;
}

/** 只在后端没给原因时用 (旧后端); 新后端对每张出不了的图都写明缺哪一列 */
const NOT_BUILT_REASON = '这份数据缺少这张图需要的列';

/**
 * 看板图表切换条的数据: 只把能出图的图表放进切换条, 出不了的收进「还缺数据」并写明原因。
 * 后端没有生成的图 (列不满足、比率表等) 用 unavailable 里后端写的原因。
 */
export function buildChartNav<T extends ChartNavEntry>(
  entries: readonly T[],
  getChart: (key: string) => ChartStateLike | null | undefined,
  unavailable: readonly UnavailableChart[] = [],
): ChartNavModel<T> {
  const groups: { name: string; items: T[] }[] = [];
  const missing: MissingChart<T>[] = [];
  for (const entry of entries) {
    const chart = getChart(entry.key);
    if (!chart) {
      const told = unavailable.find(u => u.chartType === entry.key);
      missing.push({ ...entry, reason: told?.reason ?? NOT_BUILT_REASON, kind: told?.gapKind ?? 'column' });
      continue;
    }
    const gap = chartGapReason(chart);
    if (gap) {
      missing.push({ ...entry, reason: gap, kind: chart.metadata?.gapKind ?? 'column' });
      continue;
    }
    let group = groups.find(g => g.name === entry.group);
    if (!group) {
      group = { name: entry.group, items: [] };
      groups.push(group);
    }
    group.items.push(entry);
  }
  const missingByKind: Record<GapKind, MissingChart<T>[]> = {
    column: missing.filter(m => m.kind === 'column'),
    statement: missing.filter(m => m.kind === 'statement'),
  };
  return { groups, missing, missingByKind };
}

/** 后端 findings: 规则算出来的 好的 / 需要关注 / 建议 (每条都带数字) */
export interface DashboardFindings {
  good: string[];
  attention: string[];
  suggestions: string[];
}

/** 「分析结论与建议」页的规则部分, 按 做得好的 / 需要关注 / 建议 分组; 空组不出现。 */
export function findingSections(findings: DashboardFindings | null | undefined) {
  if (!findings) return [];
  return [
    { key: 'good', title: '做得好的', items: findings.good ?? [] },
    { key: 'attention', title: '需要关注', items: findings.attention ?? [] },
    { key: 'suggestions', title: '建议', items: findings.suggestions ?? [] },
  ].filter(s => s.items.length > 0);
}

/**
 * 记住页面上正显示的那一次查询 (筛选 + 期间)。月份芯片 / 期间是先改再请求的,
 * 请求失败时用它退回, 否则芯片显示「3月」而页面还是全年的数 (设计复评 2026-10-23)。
 */
export function createQueryHistory<Q>() {
  let applied: Q | null = null;
  const clone = (q: Q): Q => JSON.parse(JSON.stringify(q)) as Q;
  return {
    commit: (q: Q) => { applied = clone(q); },
    lastApplied: (): Q | null => (applied ? clone(applied) : null),
  };
}

/**
 * 看板数据版本: 每次重新生成 +1。AI 分析 / 总结论 / 追问在请求前记下版本,
 * 回来时版本已变就丢弃, 避免「新图表配旧结论」。
 */
export function createDataVersion() {
  let version = 0;
  return {
    current: () => version,
    bump: () => ++version,
    isCurrent: (v: number) => v === version,
  };
}
