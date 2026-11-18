/**
 * 一图一分析（问题 40）PR3：SKU 毛利率 / 物料价格趋势 / 人效看板 / 排产甘特。
 *
 * 全部按「图上那一组数」现算，不写死结论句：
 * - 输入 = 页面画图用的同一份数据（页面和这里共用同一个取数/过滤函数，数不会对不上）。
 * - 取不到的数不当 0、不编（毛利率 null、计划工时未配置、完成概率缺失都如实说明缺什么）。
 * - 比率（毛利率 / 达成率）不加总，只比较、计数。
 * - 返回 InsightResult（source='rules'），由 smart-bi 的 ChartInsight 组件统一展示。
 */
import type { InsightResult } from '@/views/smart-bi/components/chartInsight';

function rules(finding: string, implication?: string, suggestion?: string): InsightResult {
  return { finding, implication, suggestion, source: 'rules', tier: 1 };
}

/** 一位小数（与图上标签 Math.round(x*10)/10 同口径） */
export function r1(v: number): number {
  return Math.round(v * 10) / 10;
}

function joinNames(names: string[], max = 3): string {
  const shown = names.slice(0, max).join('、');
  return names.length > max ? `${shown}等 ${names.length} 个` : shown;
}

// ============================================================
// 1. SKU 毛利率（Top10 横向柱图）
// ============================================================

export interface SkuMarginPoint {
  name: string;
  /** 订单毛利率 %；null = 算不出（不参与排名、不当 0） */
  rate: number | null;
}

export const SKU_TOP_N = 10;
/** 与页面 getMarginColor 同阈值：≥30 绿、15~30 黄、<15 红 */
export const MARGIN_GOOD = 30;
export const MARGIN_LOW = 15;

/** 图上那 10 根柱：有毛利率的产品按毛利率降序取前 10 */
export function skuTopRows<T extends { rate: number | null }>(rows: readonly T[]): T[] {
  return rows
    .filter((r): r is T & { rate: number } => r.rate !== null)
    .sort((a, b) => b.rate - a.rate)
    .slice(0, SKU_TOP_N);
}

export function buildSkuMarginInsight(rows: readonly SkuMarginPoint[]): InsightResult | null {
  const rated = rows.filter((r) => r.rate !== null) as Array<SkuMarginPoint & { rate: number }>;
  if (rated.length === 0) return null;
  const top = skuTopRows(rated);
  const hi = top[0];
  const lo = top[top.length - 1];
  const unrated = rows.length - rated.length;

  const finding =
    top.length === 1
      ? `图中只有 1 个产品有订单毛利率：${hi.name} ${r1(hi.rate)}%。`
      : `图中前 ${top.length} 名订单毛利率从 ${r1(hi.rate)}%（${hi.name}）到 ${r1(lo.rate)}%（${lo.name}），相差 ${r1(hi.rate - lo.rate)} 个百分点。`;

  const low = rated.filter((r) => r.rate < MARGIN_LOW).sort((a, b) => a.rate - b.rate);
  const good = rated.filter((r) => r.rate >= MARGIN_GOOD).length;
  const parts = [`全部 ${rated.length} 个有毛利率的产品里：${good} 个 ≥${MARGIN_GOOD}%，${low.length} 个 <${MARGIN_LOW}%`];
  if (rows.length > rated.length) parts.push(`另有 ${unrated} 个算不出毛利率（缺售价或成本），不在图里`);
  const implication = `${parts.join('；')}。毛利率是比率，这里只比较高低，没有加总。`;

  const suggestion =
    low.length > 0
      ? `低于 ${MARGIN_LOW}% 的产品不在前 ${top.length} 名里，可先看：${joinNames(low.map((r) => `${r.name} ${r1(r.rate)}%`))}。`
      : undefined;
  return rules(finding, implication, suggestion);
}

// ============================================================
// 2. 物料价格趋势（单物料采购单价折线）
// ============================================================

export interface PricePoint {
  date: string;
  price: number;
}

export function buildPriceTrendInsight(
  materialName: string,
  points: readonly PricePoint[],
  opts: { unit?: string },
): InsightResult | null {
  if (points.length === 0) return null;
  const fmt = (v: number) => `¥${v.toFixed(2)}`;
  const unit = opts.unit ? `/${opts.unit}` : '';
  const first = points[0];
  const last = points[points.length - 1];

  if (points.length === 1) {
    return rules(
      `${materialName} 只有 1 批有单价：${first.date} ${fmt(first.price)}${unit}，一个点画不出趋势。`,
    );
  }
  const max = points.reduce((a, b) => (b.price > a.price ? b : a));
  const min = points.reduce((a, b) => (b.price < a.price ? b : a));
  const diff = last.price - first.price;
  const dirWord = diff > 0 ? '上涨' : diff < 0 ? '下降' : '持平';
  const pct = first.price > 0 && diff !== 0 ? `（${r1((Math.abs(diff) / first.price) * 100)}%）` : '';
  const finding =
    diff === 0
      ? `${materialName} 图中 ${points.length} 批单价从 ${first.date} 到 ${last.date} 持平在 ${fmt(first.price)}${unit}。`
      : `${materialName} 图中 ${points.length} 批单价从 ${first.date} 的 ${fmt(first.price)}${unit} ${dirWord}到 ${last.date} 的 ${fmt(last.price)}${unit}${pct}。`;
  const implication =
    max.price === min.price
      ? `这 ${points.length} 批单价都是 ${fmt(max.price)}${unit}。`
      : `最高 ${fmt(max.price)}（${max.date}），最低 ${fmt(min.price)}（${min.date}），波动 ${fmt(max.price - min.price)}${unit}。`;
  // 价格有升有降时，提示先看最高那批的来源；单调不变就不多说
  const suggestion = max.price !== min.price && last.price === max.price
    ? `最近一批 ${last.date} 就是图中最高价，下次采购前先核对这个价是否合理。`
    : undefined;
  return rules(finding, implication, suggestion);
}

// ============================================================
// 3. 人效看板：达成率 = 实际工时 / 计划工时
// ============================================================

export type LaborAlert = 'BELOW_ALERT' | 'ABOVE_ALERT' | 'OK';
export interface RatePoint {
  name: string;
  rate: number | null;
  alert: LaborAlert | null;
}
export const LABOR_LOW = 75;
export const LABOR_HIGH = 150;

function alertCounts(pts: readonly RatePoint[]) {
  const known = pts.filter((p) => p.rate !== null) as Array<RatePoint & { rate: number }>;
  return {
    known,
    unconfigured: pts.length - known.length,
    above: known.filter((p) => p.alert === 'ABOVE_ALERT'),
    below: known.filter((p) => p.alert === 'BELOW_ALERT'),
  };
}

function rateSpread(known: Array<RatePoint & { rate: number }>, what: string, unit: string): string {
  const hi = known.reduce((a, b) => (b.rate > a.rate ? b : a));
  const lo = known.reduce((a, b) => (b.rate < a.rate ? b : a));
  if (known.length === 1) return `${what}只有 ${hi.name} 有达成率：${r1(hi.rate)}%。`;
  if (hi.rate === lo.rate) return `${known.length} ${unit}达成率都是 ${r1(hi.rate)}%。`;
  return `${known.length} ${unit}里达成率最高 ${r1(hi.rate)}%（${hi.name}），最低 ${r1(lo.rate)}%（${lo.name}）。`;
}

/** 图 1：各产品达成率对比（柱） */
export function buildLaborProductInsight(pts: readonly RatePoint[]): InsightResult | null {
  if (pts.length === 0) return null;
  const { known, unconfigured, above, below } = alertCounts(pts);
  if (known.length === 0) {
    return rules(`图中 ${pts.length} 个产品都没有配置计划工时，算不出达成率。`, undefined, '到工序配置里补 estimatedMinutes 后才有达成率。');
  }
  const finding = rateSpread(known, '图中', '个产品');
  const bits: string[] = [];
  if (above.length) bits.push(`${above.length} 个超过 ${LABOR_HIGH}%（超时预警：${joinNames(above.map((p) => p.name))}）`);
  if (below.length) bits.push(`${below.length} 个低于 ${LABOR_LOW}%（低效预警：${joinNames(below.map((p) => p.name))}）`);
  if (unconfigured) bits.push(`${unconfigured} 个未配置计划工时，图里是空柱`);
  const implication = bits.length ? `${bits.join('；')}。` : `没有产品触发 ${LABOR_LOW}%~${LABOR_HIGH}% 以外的预警。`;
  return rules(finding, implication);
}

/** 图 2：批次达成率趋势。批次顺序以接口返回为准，不据此断言「最新」，只说分布 */
export function buildLaborBatchInsight(
  pts: readonly RatePoint[],
  opts: { shownCap: number; totalBatches: number; productName: string },
): InsightResult | null {
  if (pts.length === 0) return null;
  const { known, unconfigured, above, below } = alertCounts(pts);
  if (known.length === 0) {
    return rules(`${opts.productName} 图中 ${pts.length} 批都没有计划工时，算不出达成率。`);
  }
  const finding = `${opts.productName}：${rateSpread(known, '图中', '批')}`;
  const bits: string[] = [`${above.length} 批超过 ${LABOR_HIGH}%，${below.length} 批低于 ${LABOR_LOW}%`];
  if (unconfigured) bits.push(`${unconfigured} 批未配置计划工时`);
  if (opts.totalBatches > opts.shownCap) bits.push(`共 ${opts.totalBatches} 批，图只画前 ${opts.shownCap} 批`);
  const worst = [...above].sort((a, b) => (b.rate as number) - (a.rate as number))[0];
  const suggestion = worst ? `先看 ${worst.name}（${r1(worst.rate as number)}%），超出计划最多。` : undefined;
  return rules(finding, `${bits.join('；')}。`, suggestion);
}

export interface StepPoint {
  name: string;
  actual: number | null;
  planned: number | null;
}

/** 图 3：逐工序实际 vs 计划工时（分钟） */
export function buildLaborStepInsight(steps: readonly StepPoint[]): InsightResult | null {
  if (steps.length === 0) return null;
  const both = steps.filter((s) => s.actual !== null && s.planned !== null && (s.planned as number) > 0) as Array<
    StepPoint & { actual: number; planned: number }
  >;
  const noPlan = steps.length - both.length;
  if (both.length === 0) {
    return rules(`图中 ${steps.length} 道工序都缺实际或计划工时，没法对比。`);
  }
  const over = both
    .map((s) => ({ ...s, ratio: s.actual / s.planned }))
    .sort((a, b) => b.ratio - a.ratio);
  const top = over[0];
  const finding =
    top.actual > top.planned
      ? `${top.name} 实际 ${top.actual} 分钟，计划 ${top.planned} 分钟，超出 ${r1((top.ratio - 1) * 100)}%，是图中超计划最多的工序。`
      : `图中 ${both.length} 道可对比工序都没超计划，${top.name} 最接近：实际 ${top.actual} / 计划 ${top.planned} 分钟。`;
  const overs = over.filter((s) => s.actual > s.planned).length;
  const bits = [`${both.length} 道可对比工序里 ${overs} 道超计划`];
  if (noPlan) bits.push(`${noPlan} 道缺实际或计划工时，未纳入比较`);
  return rules(finding, `${bits.join('；')}。`);
}

// ============================================================
// 4. 排产甘特
// ============================================================

export interface GanttItem {
  label: string;
  line: string;
  status: 'pending' | 'in_progress' | 'completed' | 'delayed';
  /** 预测完成概率 0~1；缺失 = 不知道（⛔ 不当 0 标红） */
  prob?: number | null;
  startMs: number;
  endMs: number;
}

export const GANTT_RISK_PROB = 0.7;

/** 条的颜色类别：页面画图和这里分析共用，保证「红条数」与图一致 */
export function ganttBarKind(s: Pick<GanttItem, 'status' | 'prob'>): 'completed' | 'in_progress' | 'risk' | 'pending' {
  if (s.status === 'completed') return 'completed';
  if (s.status === 'in_progress') return 'in_progress';
  if (s.status === 'delayed' || (typeof s.prob === 'number' && s.prob < GANTT_RISK_PROB)) return 'risk';
  return 'pending';
}

function md(ms: number): string {
  const d = new Date(ms);
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

export function buildGanttInsight(items: readonly GanttItem[]): InsightResult | null {
  if (items.length === 0) return null;
  const kinds = items.map((i) => ({ i, k: ganttBarKind(i) }));
  const cnt = (k: string) => kinds.filter((x) => x.k === k).length;
  const lines = new Set(items.map((i) => i.line)).size;
  const start = Math.min(...items.map((i) => i.startMs));
  const end = Math.max(...items.map((i) => i.endMs));
  const finding = `图中 ${items.length} 个排程分布在 ${lines} 条产线，时间 ${md(start)}至${md(end)}：已完成 ${cnt('completed')}、进行中 ${cnt('in_progress')}、待开始 ${cnt('pending')}、延期/风险 ${cnt('risk')}。`;

  const risk = kinds.filter((x) => x.k === 'risk').map((x) => x.i);
  const noProb = items.filter((i) => i.status !== 'completed' && (i.prob === null || i.prob === undefined)).length;
  const bits: string[] = [];
  if (risk.length) {
    const delayed = risk.filter((r) => r.status === 'delayed').length;
    bits.push(`${risk.length} 个标红（${delayed} 个已延期，${risk.length - delayed} 个完成概率低于 ${GANTT_RISK_PROB * 100}%）：${joinNames(risk.map((r) => r.label))}`);
  } else {
    bits.push('没有排程标红');
  }
  if (noProb) bits.push(`${noProb} 个未完成排程暂无完成概率，不按风险计`);
  const lowest = risk
    .filter((r) => typeof r.prob === 'number')
    .sort((a, b) => (a.prob as number) - (b.prob as number))[0];
  const suggestion = risk.length
    ? lowest
      ? `优先看 ${lowest.label}（${lowest.line}），完成概率只有 ${r1((lowest.prob as number) * 100)}%，核对人手和时间是否够。`
      : '优先核对标红排程为什么延期。'
    : undefined;
  return rules(finding, `${bits.join('；')}。`, suggestion);
}
