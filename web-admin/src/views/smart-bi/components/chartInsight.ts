/**
 * U2 — Chart Auto-Insight Tier 1 (rules-first, 0 LLM token) + Tier 2 gateway.
 *
 * Phase 1: TREND + RANKING families (Tier 1).
 * Phase 2 (U2 task): PROPORTION + COMPARISON + KPI families added.
 *
 * Reads chart._meta (ChartMeta, supplied by backend U1) + userPermissions,
 * produces a structured InsightResult or null (data insufficient → no display).
 *
 * Design constraints (spec §2.3/§2.6, plan U2 contracts):
 * - Strict null for insufficient data — no fabrication ("禁止降级处理")
 * - RBAC: finance yMetric (revenue/margin/cost) + !canViewFinance → ratios/% only, NEVER ¥
 * - Suggestion verbs: observation only (关注/排查/分析/了解)
 *   NEVER causal-prescriptive (复制/引流/加大/扩张/推广)
 * - Replaces getChartMiniInsight() in SmartBIAnalysis.vue (single Tier 1 source)
 *
 * U2 additions (2026-06-10):
 * - PROPORTION family (PIE chartType): ≥2 slices → "X 占 N%, 前二占 M%"
 * - COMPARISON family: 2 comparable series → "A 较 B 高 N%"
 * - KPI family: actual+target → "达成 N%"; finance guard: only %, NEVER absolute ¥
 * - Removed showAbsolute dead code in RANKING (both branches were identical)
 * - PIE chartType now routes to PROPORTION (no longer always null)
 *
 * U3 addition (2026-06-10):
 * - deriveChartMeta: heuristic ChartMeta inference from plan/column names with expanded word tables
 *
 * Gold-wire additions (2026-06-10):
 * - computeDataPattern: deterministic canonical bucket string for Tier 2 request
 * - fetchTier2Insight: async POST to /api/smartbi/chart-insight; honest-null on failure
 */

// ============================================================
// Contracts (locked — mirrors plan U2 §契约)
// ============================================================

import { getPythonAuthHeaders } from '@/api/smartbi/common';

export interface ChartMeta {
  xDim: 'time' | 'store' | 'product' | 'channel' | 'category' | 'other';
  yMetric: 'revenue' | 'quantity' | 'margin' | 'cost' | 'count' | 'pct' | 'other';
  aggregation: 'sum' | 'avg' | 'max' | 'count';
  domain: 'restaurant' | 'factory' | 'finance';
  /** 数值单位：'元'（默认按金额写 ¥）、'%'、'kg'、'件'……；不给时金额类按 ¥，其余只写数字 */
  unit?: string;
  /** 哪个方向算好：成本默认 lower，其余默认 higher；决定「最该看的那一项」 */
  betterWhen?: 'higher' | 'lower';
}

export interface InsightResult {
  finding: string;
  implication?: string;
  suggestion?: string;
  source: 'rules' | 'template' | 'llm' | 'cache';
  tier: 1 | 2;
  /** G4: corpus row key returned by Tier 2 endpoint; null/undefined for Tier 1 rules-based insights */
  input_hash?: string | null;
  cacheHit?: boolean;
  cacheToken?: string;
}

export interface UserPermissions {
  /** Whether the current user holds finance:read_write — gates absolute ¥ values */
  canViewFinance: boolean;
}

/** Minimal chart shape consumed by Tier 1 — matches ChartGridItem's chart prop + optional meta */
export interface ChartWithMeta {
  chartType?: string;
  title?: string;
  meta?: ChartMeta | null;
  config?: {
    xAxis?: { data?: string[] };
    series?: Array<{ name?: string; type?: string; data?: unknown[] }>;
    [key: string]: unknown;
  };
  /** 上一期同口径数据（同样的 config 形状）；不给 → 不写环比，⛔ 不编 */
  previous?: { config?: ChartWithMeta['config'] } | null;
  /** 逐点单位（与类目轴一一对应），如单位成本的「盒」「kg」：不同单位不互相比大小 */
  pointUnits?: Array<string | undefined>;
  /** 漏斗 / 桑基这类「环节接环节」的图：按环节顺序给出各环节的量（FLOW 族） */
  flow?: FlowData | null;
}

/** FLOW 族的一个环节。value 为 null 表示这一环节没有可用的数，missing 写清缺的是什么。 */
export interface FlowStage {
  label: string;
  value: number | null;
  /** 显示单位：'元' 按金额写 ¥；'个'、'kg'、'袋'…… */
  unit?: string;
  /** 只有 compareKey 相同的环节才互相比；默认等于 unit。不同东西同单位（入库批次 / 生产批次）给不同的 key */
  compareKey?: string;
  /** 金额环节：没有价格权限时整段不出现 */
  money?: boolean;
  /** 统计口径（如「最近10张采购单」）：口径不同的环节不互相比 */
  scope?: string;
  /** value 为 null 时，缺的是什么（如「袋、盒两种单位，没法相加」） */
  missing?: string;
  /** 页面上这个读数的写法（卡片 / 提示框），给了就照抄，保证分析里的数与图上逐字一致 */
  display?: string;
}

export interface FlowData {
  /** funnel = 相邻阶段转化；chain = 每个环节只和前面最近的同单位环节比 */
  kind: 'funnel' | 'chain';
  stages: FlowStage[];
  /** 附在结论后的口径说明（如「采购额只合计了列表里的10张单，共99张」） */
  note?: string;
}

// ============================================================
// Internal helpers
// ============================================================

/** Finance-sensitive yMetric types that gate absolute ¥ display (§2.6) */
const FINANCE_METRICS = new Set<ChartMeta['yMetric']>(['revenue', 'margin', 'cost']);

/** xDim values that trigger RANKING family */
const RANKING_DIMS = new Set<ChartMeta['xDim']>(['store', 'product', 'channel', 'category']);

/** Minimum data points for TREND（日度图有 3 个点就能说均值和最差一天） */
const TREND_MIN_POINTS = 3;

/**
 * Extract numeric series values from a chart config.
 * Returns [] when no valid series data found.
 */
function extractValues(config: ChartWithMeta['config']): number[] {
  const series = config?.series;
  if (!series?.length) return [];
  const allValues: number[] = [];
  for (const s of series) {
    if (!Array.isArray(s.data)) continue;
    for (const d of s.data) {
      if (typeof d === 'number' && isFinite(d)) {
        allValues.push(d);
      } else if (d !== null && typeof d === 'object' && 'value' in d) {
        const v = (d as { value?: unknown }).value;
        if (typeof v === 'number' && isFinite(v)) allValues.push(v);
      }
    }
  }
  return allValues;
}

/**
 * Extract xAxis labels (parallel to series values).
 * Returns [] when no labels.
 */
function extractLabels(config: ChartWithMeta['config']): string[] {
  const xData = config?.xAxis?.data;
  if (!Array.isArray(xData)) return [];
  return xData.map((x) => String(x));
}

/**
 * Format a percentage to 1 decimal place (e.g. 0.235 → "23.5%").
 * Used for ratio-only display when finance gated.
 *
 * Note: slot values like {topShare} already include the '%' character.
 * Templates must not add a second '%' after these slots.
 */
function fmtPct(ratio: number): string {
  return (ratio * 100).toFixed(1) + '%';
}

/**
 * Determine monotonicity of a series.
 * Returns 'rising', 'falling', or 'mixed'.
 */
function monotonicity(values: number[]): 'rising' | 'falling' | 'mixed' {
  let rises = 0;
  let falls = 0;
  for (let i = 1; i < values.length; i++) {
    const delta = values[i] - values[i - 1];
    if (delta > 0) rises++;
    else if (delta < 0) falls++;
  }
  if (falls === 0) return 'rising';
  if (rises === 0) return 'falling';
  return 'mixed';
}

// ============================================================
// Computed-insight helpers (LSM-COSTBI-D, 2026-11-18)
//
// 每张图一句「按当前数据现算」的结论 + 一句建议：
// - 点出最该看的那一项：数值、占比、和上期比的变化（chart.previous 给了才算环比，⛔ 不编）
// - 没有成本/价格查看权限（finance yMetric + !canViewFinance）：不出现金额，也不出现占比
// - 不写「超过一半」「分布相对均衡」这类不看数也能说的模板话；中文与标签之间不留空格
// ============================================================

type Point = { label: string; value: number; unit?: string };

/** 中文数字格式：≥1 万写「x.xx万」，≥100 取整加千分位，其余最多两位小数。 */
function fmtNum(v: number): string {
  const abs = Math.abs(v);
  if (abs >= 10000) return `${(v / 10000).toFixed(2).replace(/\.?0+$/, '')}万`;
  if (abs >= 100) return Math.round(v).toLocaleString('zh-CN');
  return String(Number(v.toFixed(2)));
}

function isMoneyMetric(meta: ChartMeta): boolean {
  return FINANCE_METRICS.has(meta.yMetric) && (meta.unit == null || meta.unit === '元' || meta.unit === '¥');
}

function isPctMetric(meta: ChartMeta): boolean {
  return meta.yMetric === 'pct' || meta.unit === '%';
}

/** 一个值带单位的写法；pointUnit 是逐点单位（如单位成本的「盒」「kg」）。 */
function fmtValue(v: number, meta: ChartMeta, pointUnit?: string): string {
  if (isPctMetric(meta)) return `${v.toFixed(1)}%`;
  if (isMoneyMetric(meta)) return `¥${fmtNum(v)}${pointUnit ? `/${pointUnit}` : ''}`;
  if (!pointUnit && FINANCE_METRICS.has(meta.yMetric) && meta.unit?.endsWith('元')) {
    return `${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${meta.unit}`;
  }
  const unit = pointUnit ?? meta.unit ?? '';
  return `${fmtNum(v)}${unit}`;
}

/** 两个值的差：百分比指标说「个百分点」，其余说相对变化 %。 */
function fmtDelta(cur: number, base: number, meta: ChartMeta): { text: string; dir: '高' | '低' | '持平' } | null {
  if (isPctMetric(meta)) {
    const d = cur - base;
    if (Math.abs(d) < 0.05) return { text: '', dir: '持平' };
    return { text: `${Math.abs(d).toFixed(1)}个百分点`, dir: d > 0 ? '高' : '低' };
  }
  if (base === 0) return null;
  const r = (cur - base) / Math.abs(base);
  if (Math.abs(r) < 0.0005) return { text: '', dir: '持平' };
  return { text: fmtPct(Math.abs(r)), dir: r > 0 ? '高' : '低' };
}

function vsPrevious(cur: number, prev: number | null | undefined, meta: ChartMeta, what = ''): string {
  if (prev == null || !isFinite(prev)) return '';
  const d = fmtDelta(cur, prev, meta);
  if (!d) return '';
  return d.dir === '持平' ? `，${what}与上期持平` : `，${what}比上期${d.dir}${d.text}`;
}

function financeGated(meta: ChartMeta, permissions: UserPermissions): boolean {
  return FINANCE_METRICS.has(meta.yMetric) && !permissions.canViewFinance;
}

function betterDirection(meta: ChartMeta): 'higher' | 'lower' {
  if (meta.betterWhen) return meta.betterWhen;
  return meta.yMetric === 'cost' ? 'lower' : 'higher';
}

/** 类目轴标签：竖条图在 xAxis，横条图在 yAxis。 */
function categoryLabels(config: ChartWithMeta['config']): string[] {
  const fromX = extractLabels(config);
  if (fromX.length) return fromX;
  const y = (config as { yAxis?: unknown } | undefined)?.yAxis;
  const first = Array.isArray(y) ? y[0] : y;
  const data = (first as { data?: unknown } | undefined)?.data;
  return Array.isArray(data) ? data.map((d) => String(d)) : [];
}

function numericAt(d: unknown): number | null {
  if (typeof d === 'number' && isFinite(d)) return d;
  if (d !== null && typeof d === 'object' && 'value' in d) {
    const v = (d as { value?: unknown }).value;
    if (typeof v === 'number' && isFinite(v)) return v;
  }
  return null;
}

/** 按类目位置把所有系列相加（堆叠图看合计）；某位置所有系列都没值 → null（缺数据，不当 0）。 */
function totalsByIndex(config: ChartWithMeta['config']): Array<number | null> {
  const series = config?.series ?? [];
  const len = Math.max(categoryLabels(config).length, ...series.map((s) => (Array.isArray(s.data) ? s.data.length : 0)), 0);
  const out: Array<number | null> = [];
  for (let i = 0; i < len; i++) {
    let sum = 0;
    let seen = false;
    for (const s of series) {
      const v = Array.isArray(s.data) ? numericAt(s.data[i]) : null;
      if (v !== null) {
        sum += v;
        seen = true;
      }
    }
    out.push(seen ? sum : null);
  }
  return out;
}

function seriesTotals(config: ChartWithMeta['config']): Array<{ name: string; total: number }> {
  return (config?.series ?? []).map((s, i) => ({
    name: String(s.name ?? `系列${i + 1}`),
    total: (Array.isArray(s.data) ? s.data : []).reduce<number>((acc, d) => acc + (numericAt(d) ?? 0), 0),
  }));
}

function finish(finding: string, suggestion?: string): InsightResult {
  return { finding, suggestion, source: 'rules', tier: 1 };
}

// ============================================================
// TREND family generator
// ============================================================

function buildTrendInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  const meta = chart.meta!;
  const labels = categoryLabels(chart.config);
  const totals = totalsByIndex(chart.config);
  const points: Point[] = totals
    .map((v, i) => (v === null ? null : { label: labels[i] ?? `第${i + 1}期`, value: v }))
    .filter((p): p is Point => p !== null);
  if (points.length < TREND_MIN_POINTS) return null;

  const gated = financeGated(meta, permissions);
  const gaps = totals.length - points.length;
  const gapNote = gaps > 0 ? `（${gaps}个日期无数据，未计入）` : '';
  const mean = points.reduce((s, p) => s + p.value, 0) / points.length;
  const half = Math.floor(points.length / 2);
  const firstHalf = points.slice(0, half);
  const secondHalf = points.slice(points.length - half);
  const avg = (ps: Point[]) => ps.reduce((s, p) => s + p.value, 0) / ps.length;
  const halfDelta = half > 0 ? fmtDelta(avg(secondHalf), avg(firstHalf), meta) : null;
  // 前后半段差不到 2%（百分比指标差不到 0.5 个百分点）算持平：日常波动不当成趋势说。
  const halfFlat = half > 0 && (isPctMetric(meta)
    ? Math.abs(avg(secondHalf) - avg(firstHalf)) < 0.5
    : avg(firstHalf) !== 0 && Math.abs(avg(secondHalf) - avg(firstHalf)) / Math.abs(avg(firstHalf)) < 0.02);
  const halfText = !halfDelta
    ? ''
    : halfFlat || halfDelta.dir === '持平'
      ? '；前后半段基本持平'
      : `；后半段比前半段${halfDelta.dir}${halfDelta.text}`;
  const better = betterDirection(meta);
  const averageLike = meta.aggregation === 'avg' || isPctMetric(meta);

  if (averageLike) {
    const worst = points.reduce((w, p) => (better === 'higher' ? (p.value < w.value ? p : w) : (p.value > w.value ? p : w)));
    const d = fmtDelta(worst.value, mean, meta);
    const prevTotals = chart.previous ? totalsByIndex(chart.previous.config).filter((v): v is number => v !== null) : [];
    const prevMean = prevTotals.length ? prevTotals.reduce((s, v) => s + v, 0) / prevTotals.length : null;
    if (gated) {
      return finish(
        `${worst.label}${better === 'higher' ? '最低' : '最高'}${halfText.replace(/^；/, '，')}${gapNote}。`,
        `先查${worst.label}的明细。`,
      );
    }
    // 各期几乎一样（百分比指标差不到 0.5 个百分点，其余差不到 2%）：没有「最差的一天」可点名。
    const hi = Math.max(...points.map((p) => p.value));
    const lo = Math.min(...points.map((p) => p.value));
    const noSpread = isPctMetric(meta) ? hi - lo < 0.5 : mean !== 0 && (hi - lo) / Math.abs(mean) < 0.02;
    if (noSpread) {
      return finish(
        `${points.length}个日期都在${fmtValue(mean, meta)}左右${vsPrevious(mean, prevMean, meta, '平均')}，最高最低相差不到${isPctMetric(meta) ? '0.5个百分点' : '2%'}${gapNote}。`,
        meta.domain === 'factory' && isPctMetric(meta)
          ? '各期基本一致，不必逐日排查；长期不变时先确认报工是不是把良品直接填成了实际产出。'
          : '各期基本一致，不必逐期排查。',
      );
    }
    const worstWord = better === 'higher' ? '最低' : '最高';
    const dText = d && d.dir !== '持平' ? `，比平均${d.dir}${d.text}` : '';
    return finish(
      `各期平均${fmtValue(mean, meta)}${vsPrevious(mean, prevMean, meta, '平均')}；${worst.label}${worstWord}，${fmtValue(worst.value, meta)}${dText}${halfText}${gapNote}。`,
      `先查${worst.label}的批次明细，找出${better === 'higher' ? '拉低' : '推高'}的原因。`,
    );
  }

  // 合计类（每日成本、每日产量……）：看峰值和合计
  const peak = points.reduce((m, p) => (p.value > m.value ? p : m));
  const total = points.reduce((s, p) => s + p.value, 0);
  const ratio = mean !== 0 ? peak.value / mean : null;
  const ratioText = ratio !== null ? `，是平均的${ratio.toFixed(1)}倍` : '';
  const prevTotal = chart.previous
    ? totalsByIndex(chart.previous.config).reduce<number>((s, v) => s + (v ?? 0), 0)
    : null;
  const parts = seriesTotals(chart.config).filter((s) => s.total > 0).sort((a, b) => b.total - a.total);
  const mainPart = parts.length >= 2 && total > 0 ? parts[0] : null;

  if (gated) {
    return finish(
      `${peak.label}最高${ratioText}${halfText}${gapNote}。（无成本查看权限，不显示金额和占比）`,
      `先查${peak.label}的明细。`,
    );
  }
  const partText = mainPart ? `；其中${mainPart.name}占${fmtPct(mainPart.total / total)}` : '';
  const finding =
    `区间合计${fmtValue(total, meta)}${vsPrevious(total, prevTotal && prevTotal > 0 ? prevTotal : null, meta, '合计')}` +
    `；${peak.label}最高，${fmtValue(peak.value, meta)}${ratioText}${partText}${halfText}${gapNote}。`;
  const suggestion = ratio !== null && ratio >= 1.5
    ? `先查${peak.label}：比平均高${fmtPct(ratio - 1)}，对照其他日期的${mainPart ? mainPart.name : '明细'}，核对峰值来自哪些记录。`
    : undefined;
  return finish(finding, suggestion);
}

// ============================================================
// RANKING family generator
// ============================================================

function buildRankingInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  const meta = chart.meta!;
  const labels = categoryLabels(chart.config);
  const totals = totalsByIndex(chart.config);
  const all: Point[] = totals
    .map((v, i): Point | null => (v === null ? null : { label: labels[i] ?? `项目${i + 1}`, value: v, unit: chart.pointUnits?.[i] }))
    .filter((p): p is Point => p !== null && p.value > 0);
  if (all.length < 2) return null;

  const sortedAll = [...all].sort((a, b) => b.value - a.value);
  // 逐点单位不同（盒 / kg）时只在同单位里比，跨单位比大小没意义：取项目最多的那个单位组
  // （一样多时取含最大值的那组）。
  const unitCount = new Map<string, number>();
  for (const p of all) unitCount.set(p.unit ?? '', (unitCount.get(p.unit ?? '') ?? 0) + 1);
  const mixedUnits = unitCount.size > 1;
  const topUnitKey = [...unitCount.entries()].sort((x, y) =>
    y[1] - x[1] || (sortedAll.findIndex((p) => (p.unit ?? '') === x[0]) - sortedAll.findIndex((p) => (p.unit ?? '') === y[0])),
  )[0][0];
  const topUnit = topUnitKey || undefined;
  const group = mixedUnits ? sortedAll.filter((p) => (p.unit ?? '') === topUnitKey) : sortedAll;
  if (group.length < 2) {
    const top = sortedAll[0];
    const gated = financeGated(meta, permissions);
    return finish(
      gated
        ? `${top.label}排第一；其余项目单位不同，不直接比大小。`
        : `${top.label}最高，${fmtValue(top.value, meta, top.unit)}；其余项目单位不同，不直接比大小。`,
      `先看${top.label}的明细构成。`,
    );
  }
  const top = group[0];
  const bottom = group[group.length - 1];
  const mean = group.reduce((s, p) => s + p.value, 0) / group.length;
  const total = group.reduce((s, p) => s + p.value, 0);
  const ratio = top.value / mean;
  const scope = mixedUnits ? `按${topUnit ?? '同一单位'}计的${group.length}项里，` : '';
  const gated = financeGated(meta, permissions);
  const sumLike = meta.aggregation === 'sum' || meta.aggregation === 'count';
  const better = betterDirection(meta);
  const flat = isPctMetric(meta) ? top.value - bottom.value < 0.5 : (top.value - bottom.value) / mean < 0.02;
  if (flat) {
    return finish(
      gated
        ? `${scope}${group.length}项差别很小。`
        : `${scope}${group.length}项都在${fmtValue(mean, meta, top.unit)}左右，最高最低相差不到${isPctMetric(meta) ? '0.5个百分点' : '2%'}。`,
      '各项差别不大，不必单独挑出哪一项。',
    );
  }
  const focus = better === 'lower' ? top : bottom;
  const focusGap = fmtDelta(focus.value, mean, meta);
  const focusGapText = focusGap && focusGap.dir !== '持平' ? `比平均${focusGap.dir}${focusGap.text}` : '与平均接近';
  const checkWhat = meta.domain === 'factory' && meta.yMetric === 'cost' ? '用料和工时' : '明细构成';
  const suggestion =
    better === 'lower'
      ? `先查${focus.label}：${focusGapText}，看${checkWhat}。`
      : `先看${focus.label}：${focusGapText}，找出差在哪里。`;

  if (gated) {
    return finish(
      `${scope}${top.label}最高，是平均的${ratio.toFixed(1)}倍；最低是${bottom.label}。（无成本查看权限，不显示金额和占比）`,
      suggestion,
    );
  }
  const prevLabels = chart.previous ? categoryLabels(chart.previous.config) : [];
  const prevTotals = chart.previous ? totalsByIndex(chart.previous.config) : [];
  const prevIdx = prevLabels.indexOf(top.label);
  const prevTop = prevIdx >= 0 ? prevTotals[prevIdx] : null;
  const shareText = sumLike && !mixedUnits && total > 0 && group.length >= 3
    ? `，前三合计占${fmtPct((group[0].value + group[1].value + group[2].value) / total)}`
    : sumLike && !mixedUnits && total > 0
      ? `，占合计${fmtPct(top.value / total)}`
      : '';
  return finish(
    `${scope}${top.label}最高，${fmtValue(top.value, meta, top.unit)}，是平均的${ratio.toFixed(1)}倍${vsPrevious(top.value, prevTop, meta)}${shareText}；最低是${bottom.label}，${fmtValue(bottom.value, meta, bottom.unit)}。`,
    suggestion,
  );
}

// ============================================================
// PROPORTION family generator (PIE charts)
// ============================================================

/**
 * Extract PIE series slices: [{label, value}] from config.series[0].data[].
 * Each item may be {name, value} object or bare number.
 */
export function extractPieSlices(config: ChartWithMeta['config']): Array<{ label: string; value: number }> {
  const series = config?.series;
  if (!series?.length) return [];
  const s = series[0];
  if (!Array.isArray(s.data)) return [];
  const result: Array<{ label: string; value: number }> = [];
  let idx = 0;
  for (const d of s.data) {
    if (typeof d === 'number' && isFinite(d)) {
      result.push({ label: `项目${idx + 1}`, value: d });
    } else if (d !== null && typeof d === 'object') {
      const obj = d as { name?: unknown; value?: unknown };
      const v = obj.value;
      const n = obj.name;
      if (typeof v === 'number' && isFinite(v)) {
        result.push({ label: String(n ?? `项目${idx + 1}`), value: v });
      }
    }
    idx++;
  }
  return result;
}

/**
 * PROPORTION (PIE)：最大一项的占比与金额，给了上期就算占比变化（个百分点）。
 * - ≥2 个正值扇区，否则 null
 * - 无成本查看权限：只说排序，不出金额和占比
 */
export function buildProportionInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  if (!chart?.meta) return null;
  const meta = chart.meta;

  const slices = extractPieSlices(chart.config).filter((s) => s.value > 0);
  if (slices.length < 2) return null;

  const sorted = [...slices].sort((a, b) => b.value - a.value);
  const total = sorted.reduce((s, p) => s + p.value, 0);
  if (total <= 0) return null;
  const [top, second] = sorted;

  if (financeGated(meta, permissions)) {
    return finish(
      `${top.label}是最大一项，其次是${second.label}。（无成本查看权限，不显示金额和占比）`,
      `先看${top.label}的明细。`,
    );
  }

  const share = top.value / total;
  const check = meta.yMetric === 'revenue' ? '核对本期订单的销量和成交单价，确认销售额主要来自哪些订单'
    : meta.yMetric === 'cost' ? '核对费用明细的用量和单价，找出金额占比高的原因'
      : '打开图表明细，核对本期哪些记录贡献最大';
  const prevSlices = chart.previous ? extractPieSlices(chart.previous.config).filter((s) => s.value > 0) : [];
  const prevTotal = prevSlices.reduce((s, p) => s + p.value, 0);
  const prevTop = prevSlices.find((s) => s.label === top.label);
  let prevText = '';
  if (prevTotal > 0 && prevTop) {
    const d = (share - prevTop.value / prevTotal) * 100;
    prevText = Math.abs(d) < 0.05 ? '，占比与上期持平' : `，占比比上期${d > 0 ? '高' : '低'}${Math.abs(d).toFixed(1)}个百分点`;
  }

  if (share >= 0.4) {
    const rest = sorted.slice(1, 3).map((s) => `${s.label}${fmtPct(s.value / total)}`).join('、');
    return finish(
      `${top.label}占${fmtPct(share)}（${fmtValue(top.value, meta)}）${prevText}，是最大一项；其次${rest}。`,
      `${top.label}占合计的${fmtPct(share)}，先从这一项${check}。`,
    );
  }
  const k = Math.min(3, sorted.length);
  const topK = sorted.slice(0, k).reduce((s, p) => s + p.value, 0);
  return finish(
    `${top.label}占${fmtPct(share)}（${fmtValue(top.value, meta)}），是最大一项${prevText}；前${k}项合计占${fmtPct(topK / total)}，其余${sorted.length - k}项占${fmtPct((total - topK) / total)}。`,
    `${top.label}和${second.label}合计占${fmtPct((top.value + second.value) / total)}，先从这两项${check}。`,
  );
}

// ============================================================
// COMPARISON family generator (2 comparable series)
// ============================================================

/**
 * COMPARISON：两条系列的合计差多少，并指出差距最大的那个类目。
 * 只用 %，不出金额（两组都没权限时也安全）。
 */
export function buildComparisonInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  if (!chart?.meta) return null;
  void permissions;

  const series = chart.config?.series;
  if (!series || series.length < 2) return null;
  const [a, b] = seriesTotals(chart.config).slice(0, 2);
  if (!a || !b) return null;
  if (b.total === 0) return null;

  const diff = (a.total - b.total) / Math.abs(b.total);
  const higher = a.total >= b.total ? a : b;
  const lower = higher === a ? b : a;
  const labels = categoryLabels(chart.config);
  let widest: { label: string; gap: number } | null = null;
  for (let i = 0; i < labels.length; i++) {
    const va = Array.isArray(series[0].data) ? numericAt(series[0].data[i]) : null;
    const vb = Array.isArray(series[1].data) ? numericAt(series[1].data[i]) : null;
    if (va === null || vb === null || vb === 0) continue;
    const gap = Math.abs((va - vb) / vb);
    if (!widest || gap > widest.gap) widest = { label: labels[i], gap };
  }
  const widestText = widest ? `；差距最大在${widest.label}（${fmtPct(widest.gap)}）` : '';
  return finish(
    `${higher.name}较${lower.name}高${fmtPct(Math.abs(diff))}${widestText}。`,
    widest ? `${widest.label}的差距最大（${fmtPct(widest.gap)}），先核对两组在这一项的统计范围和原始明细。` : undefined,
  );
}

// ============================================================
// KPI family generator (actual + target)
// ============================================================

/**
 * KPI：达成率；有权限时补「还差多少」，无权限只给 %。
 */
export function buildKpiInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  if (!chart?.meta) return null;
  const meta = chart.meta;

  const series = chart.config?.series;
  if (!series || series.length < 2) return null;

  const actual = Array.isArray(series[0].data) ? numericAt(series[0].data[0]) : null;
  const target = Array.isArray(series[1].data) ? numericAt(series[1].data[0]) : null;
  if (actual === null || target === null) return null;
  if (target === 0) return null;

  const achievement = actual / target;
  const gated = financeGated(meta, permissions);
  const gapText = achievement < 1 && !gated ? `，还差${fmtValue(target - actual, meta)}` : '';
  const finding = `达成${(achievement * 100).toFixed(1)}%${gapText}。`;
  let suggestion: string | undefined;
  if (achievement < 1) suggestion = `距离目标还差${(100 - achievement * 100).toFixed(1)}个百分点，按明细核对哪些任务还没有完成。`;
  else if (achievement > 1) suggestion = `超出目标${(achievement * 100 - 100).toFixed(1)}个百分点，核对超额部分是否来自一次性记录。`;
  return finish(finding, suggestion);
}

// ============================================================
// FLOW family generator (漏斗 / 桑基：环节接环节)
// ============================================================

/** 环节读数照页面卡片 / 提示框的写法：千分位、最多两位小数，不缩写成「万」，保证和图上的数逐字一致。 */
function fmtFlowNum(v: number): string {
  return Number(v.toFixed(2)).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

function fmtFlowValue(stage: FlowStage): string {
  if (stage.display) return stage.display;
  if (stage.money || stage.unit === '元' || stage.unit === '¥') return `¥${fmtFlowNum(stage.value as number)}`;
  return `${fmtFlowNum(stage.value as number)}${stage.unit ?? ''}`;
}

function flowRate(cur: number, base: number): string {
  return fmtPct(cur / base);
}

/**
 * FLOW：
 * - funnel：相邻阶段的转化（后一阶段是前一阶段的百分之几），点出转化最低的一步和整体转化。
 * - chain：每个环节只和「前面最近的、单位与口径都相同」的环节比；单位不同（元 / 批 / 数量）⛔ 不硬换算。
 * - 金额环节在无价格权限时整段不出现（不出 ¥，也不拿它算比例）。
 * - 缺数的环节照实说缺什么，不当 0。
 */
export function buildFlowInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  const flow = chart.flow;
  if (!flow?.stages?.length) return null;
  const gatedMoney = !permissions.canViewFinance;
  const hiddenMoney = gatedMoney && flow.stages.some((s) => s.money);
  const stages = flow.stages.filter((s) => !(s.money && gatedMoney));
  const usable = (s: FlowStage) => s.value !== null && isFinite(s.value);
  const keyOf = (s: FlowStage) => `${s.compareKey ?? s.unit ?? ''}|${s.scope ?? ''}`;
  const missingText = stages
    .filter((s) => !usable(s))
    .map((s) => `${s.label}${s.missing ? `：${s.missing}` : '没有数'}`)
    .join('；');
  const tail = [
    missingText ? `${missingText}。` : '',
    flow.note ? `${flow.note}。` : '',
    hiddenMoney ? '（无价格权限，不显示金额环节）' : '',
  ].join('');

  const live = stages.filter(usable);
  if (!live.length) {
    return missingText || flow.note ? finish(tail, '先补齐上面缺的数，再看各环节的变化。') : null;
  }

  // 每个环节找前面最近的同 key 环节
  type Pair = { from: FlowStage; to: FlowStage; rate: number };
  const pairs: Pair[] = [];
  const zeroBases: FlowStage[] = [];
  for (let i = 0; i < stages.length; i++) {
    const to = stages[i];
    if (!usable(to)) continue;
    for (let j = i - 1; j >= 0; j--) {
      const from = stages[j];
      if (!usable(from) || keyOf(from) !== keyOf(to)) continue;
      if ((from.value as number) > 0) pairs.push({ from, to, rate: (to.value as number) / (from.value as number) });
      else if (!zeroBases.includes(from)) zeroBases.push(from);
      break;
    }
  }

  if (flow.kind === 'funnel') {
    if (live.every((s) => s.value === 0)) {
      return finish(
        `${live.length}个阶段都是0${live[0].unit ?? ''}，还没有数据，算不出转化。${tail}`,
        '先录入数据，再看哪一步转化最低。',
      );
    }
    const first = live.find((s) => (s.value as number) > 0)!;
    const last = live[live.length - 1];
    const overall = first !== last && keyOf(first) === keyOf(last)
      ? `从${first.label}（${fmtFlowValue(first)}）到${last.label}（${fmtFlowValue(last)}）整体${flowRate(last.value as number, first.value as number)}`
      : '';
    const worst = pairs.length ? pairs.reduce((w, p) => (p.rate < w.rate ? p : w)) : null;
    const worstText = worst
      ? `转化最低的是${worst.from.label}→${worst.to.label}，${fmtFlowValue(worst.from)}到${fmtFlowValue(worst.to)}，${flowRate(worst.to.value as number, worst.from.value as number)}`
      : '';
    const zeroText = zeroBases.length ? `${zeroBases.map((s) => s.label).join('、')}目前是0，下一步算不出转化` : '';
    const body = [overall, worstText, zeroText].filter(Boolean).join('；');
    if (!body) return tail ? finish(tail) : null;
    return finish(
      `${body}。${tail}`,
      worst ? `先看卡在${worst.from.label}的${fmtFlowValue(worst.from)}，找出没往下走的原因。` : '先补齐各阶段数据再比较转化。',
    );
  }

  // chain
  const parts: string[] = [];
  const firstLive = live[0];
  const sameAsFirst = live.filter((s) => keyOf(s) === keyOf(firstLive));
  const lastSame = sameAsFirst[sameAsFirst.length - 1];
  if (lastSame !== firstLive && (firstLive.value as number) > 0) {
    parts.push(
      `${firstLive.label}${fmtFlowValue(firstLive)}，到${lastSame.label}${fmtFlowValue(lastSame)}，是开头的${flowRate(lastSame.value as number, firstLive.value as number)}`,
    );
  }
  const drops = pairs.filter((p) => p.rate < 1);
  const worst = drops.length ? drops.reduce((w, p) => (p.rate < w.rate ? p : w)) : null;
  if (worst) {
    parts.push(
      `降得最多的是${worst.from.label}→${worst.to.label}，从${fmtFlowValue(worst.from)}到${fmtFlowValue(worst.to)}，剩${flowRate(worst.to.value as number, worst.from.value as number)}`,
    );
  }
  const gains = pairs.filter((p) => p.rate > 1.0005);
  if (gains.length) {
    const g = gains.reduce((m, p) => (p.rate > m.rate ? p : m));
    parts.push(`${g.from.label}→${g.to.label}多了${fmtPct(g.rate - 1)}`);
  }
  // 单位切换：后面换了单位、前面没有可比的环节，照实说不比
  const switched = live.filter((s, i) => i > 0 && keyOf(s) !== keyOf(firstLive) && !pairs.some((p) => p.to === s)
    && !live.slice(0, i).some((x) => keyOf(x) === keyOf(s)));
  if (!pairs.length) {
    parts.push(
      `${live.map((s) => `${s.label}${fmtFlowValue(s)}`).join('、')}${live.length > 1 ? '，单位或口径各不相同，不互相换算' : ''}`,
    );
  } else if (switched.length) {
    parts.push(`${switched.map((s) => `${s.label}${fmtFlowValue(s)}`).join('、')}，单位或口径不同，不和前面的环节换算`);
  }
  if (!parts.length) {
    if (!tail) return null;
    return finish(tail, '各环节单位不同，分别看各自的明细。');
  }
  const suggestion = worst
    ? `先查${worst.from.label}这一步：对一下它的投入和交到下一步的量，看损耗在哪。`
    : '各环节之间没有可直接比较的下降，分别核对各环节明细。';
  return finish(`${parts.join('；')}。${tail}`, suggestion);
}

// ============================================================
// Public API
// ============================================================

/**
 * Build a Tier 1 rules-based insight for a chart.
 *
 * @param chart  Chart object containing `.meta` (ChartMeta from backend U1)
 *               and `.config` (ECharts options).
 * @param permissions  Current user's permission context.
 * @returns  Structured InsightResult, or null when data is insufficient
 *           (caller should render nothing — no fallback fabrication).
 *
 * Family routing:
 * - PIE / PROPORTION: buildProportionInsight (≥2 slices → insight; <2 → null)
 * - time xDim: TREND family
 * - store/product/channel/category xDim: RANKING family
 * - 'kpi' chartType (explicit): KPI family
 * - 'comparison' chartType (explicit): COMPARISON family
 * - otherwise: null (Phase 2+ will add more families)
 *
 * Callers should fall back to fetchTier2Insight when null is returned.
 */
export function buildChartInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  // Guard: meta must exist
  if (!chart?.meta) return null;

  const ct = chart.chartType?.toLowerCase() ?? '';

  // FLOW family：漏斗 / 桑基（环节接环节）
  if (chart.flow || ct === 'funnel' || ct === 'sankey') {
    return buildFlowInsight(chart, permissions);
  }

  // PIE = PROPORTION family (U2: now implemented, not null)
  if (ct === 'pie') {
    return buildProportionInsight(chart, permissions);
  }

  // KPI family: explicit chartType dispatch
  if (ct === 'kpi') {
    return buildKpiInsight(chart, permissions);
  }

  // COMPARISON family: explicit chartType dispatch
  if (ct === 'comparison') {
    return buildComparisonInsight(chart, permissions);
  }

  const meta = chart.meta;

  // TREND family: LINE chart with time dimension
  if (meta.xDim === 'time') {
    return buildTrendInsight(chart, permissions);
  }

  // RANKING family: categorical dimension (store/product/channel/category)
  if (RANKING_DIMS.has(meta.xDim)) {
    return buildRankingInsight(chart, permissions);
  }

  // Unrecognized family → null (no fabrication)
  return null;
}

// ============================================================
// U3 — deriveChartMeta: heuristic ChartMeta inference
// ============================================================

/**
 * Expanded xDim keyword tables (U3).
 * Each entry: [keyword_pattern, xDim value]
 * Checked in order — first match wins.
 */
const X_DIM_KEYWORDS: Array<[RegExp, ChartMeta['xDim']]> = [
  // Time dimension
  [/月|日期|时间|周|季度|年|date|time|week|month|year/i, 'time'],
  // Store dimension
  [/门店|店|store/i, 'store'],
  // Product dimension
  [/产品|品名|物料|原材料|sku|product/i, 'product'],
  // Channel dimension — 渠道/平台 map to channel
  [/渠道|平台|channel|platform/i, 'channel'],
  // Category dimension — 科目/部门/工序/供应商/客户/批次 map to category
  [/分类|类别|科目|部门|工序|供应商|客户|批次|category|dept|process/i, 'category'],
];

/**
 * Expanded yMetric keyword tables (U3).
 * Each entry: [keyword_pattern, yMetric value]
 * Checked in order — first match wins.
 *
 * ORDERING NOTE: Cost-specific compound terms (领料/采购/损耗/费用/应付/应收) must
 * appear BEFORE the generic revenue check because those compound field names like
 * "领料金额" contain the substring 金额 which would otherwise match revenue first.
 */
const Y_METRIC_KEYWORDS: Array<[RegExp, ChartMeta['yMetric']]> = [
  // Percentage / ratio — check first (占比/完成率 are unambiguous)
  [/占比|比率|比例|完成率|达成率|pct|percent|rate/i, 'pct'],
  // Margin / profit — before revenue to avoid "毛利率" matching rate above
  [/毛利|利润|profit|margin/i, 'margin'],
  // Cost-specific terms BEFORE generic 金额 revenue check
  // 损耗/领料/采购/费用/应付/应收 are cost/payable/receivable domain
  [/成本|损耗|领料|采购|费用|应付|应收|cost|waste|purchase|expense|receivable/i, 'cost'],
  // Revenue / income — 金额 here is generic fallback (after cost-specific terms above)
  [/营收|收入|销售额|金额|revenue|income|sales/i, 'revenue'],
  // Quantity — 产量/件数/单数/次数/人次
  [/数量|产量|件数|单数|次数|人次|耗时|count|qty|quantity|num/i, 'quantity'],
];

/**
 * Infer aggregation from column/metric names (heuristic).
 */
function inferAggregation(name: string): ChartMeta['aggregation'] {
  if (/平均|avg|average|mean/i.test(name)) return 'avg';
  if (/最大|最高|max/i.test(name)) return 'max';
  if (/计数|count|数量/i.test(name)) return 'count';
  return 'sum'; // default
}

/**
 * Infer domain from column/context strings.
 */
function inferDomain(text: string): ChartMeta['domain'] {
  if (/餐饮|门店|堂食|外卖|菜品|桌/i.test(text)) return 'restaurant';
  if (/财务|应收|应付|结账|报表|科目/i.test(text)) return 'finance';
  return 'factory'; // default
}

/**
 * Derive a ChartMeta heuristically from plan/column context.
 *
 * @param plan            Plan object — expects {xField?, yFields?, chartType?, title?}
 * @param monthlyColumns  Column names (array of strings) for time-dimension detection
 * @param dataInfo        Free-form context string (title, description, domain hints)
 * @returns ChartMeta when sufficient clues exist, null when clues are insufficient.
 *          Never throws.
 *
 * Word tables (U3 expanded):
 * - xDim: 时间/月/日期 + 门店/产品/品名/物料/原材料 + 渠道/平台 + 科目/部门/工序/供应商/客户/批次
 * - yMetric: 营收/收入 + 毛利/利润 + 成本/损耗/领料/采购/费用/应收/应付 + 数量/件数 + 占比/比率
 *
 * Phase A consumers: U5 only. The 6 explicit gold/dashboard metas are NOT affected
 * (they use hand-filled explicit meta; this function is for unmatched charts).
 */
export function deriveChartMeta(
  plan: {
    xField?: string;
    yFields?: string[];
    chartType?: string;
    title?: string;
  } | null | undefined,
  monthlyColumns: string[],
  dataInfo: string,
): ChartMeta | null {
  try {
    // Collect all text clues
    const xFieldStr = plan?.xField ?? '';
    const yFieldsStr = (plan?.yFields ?? []).join(' ');
    const titleStr = plan?.title ?? '';
    const allText = [xFieldStr, yFieldsStr, titleStr, dataInfo, ...monthlyColumns].join(' ');

    // ------- infer xDim -------
    let xDim: ChartMeta['xDim'] | null = null;

    // Check monthly columns first — common pattern for time series
    if (monthlyColumns.length >= 3) {
      const monthPattern = /^\d{4}[-年]\d{1,2}月?$|^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)/i;
      const hasMonthly = monthlyColumns.some((c) => monthPattern.test(c.trim()));
      if (hasMonthly) xDim = 'time';
    }

    // Check xField keywords
    if (!xDim && xFieldStr) {
      for (const [re, dim] of X_DIM_KEYWORDS) {
        if (re.test(xFieldStr)) {
          xDim = dim;
          break;
        }
      }
    }

    // Fallback: scan allText for xDim clues
    if (!xDim) {
      for (const [re, dim] of X_DIM_KEYWORDS) {
        if (re.test(allText)) {
          xDim = dim;
          break;
        }
      }
    }

    // ------- infer yMetric -------
    let yMetric: ChartMeta['yMetric'] | null = null;

    // Check yFields first (most specific)
    if (yFieldsStr) {
      for (const [re, metric] of Y_METRIC_KEYWORDS) {
        if (re.test(yFieldsStr)) {
          yMetric = metric;
          break;
        }
      }
    }

    // Fallback: scan titleStr + dataInfo
    if (!yMetric) {
      const titleAndInfo = `${titleStr} ${dataInfo}`;
      for (const [re, metric] of Y_METRIC_KEYWORDS) {
        if (re.test(titleAndInfo)) {
          yMetric = metric;
          break;
        }
      }
    }

    // If we can't determine either dimension, return null (insufficient clues)
    if (!xDim || !yMetric) return null;

    // ------- infer aggregation + domain -------
    const aggregation = inferAggregation(yFieldsStr || dataInfo);
    const domain = inferDomain(allText);

    return {
      xDim,
      yMetric,
      aggregation,
      domain,
    };
  } catch {
    return null;
  }
}

// ============================================================
// computeDataPattern — deterministic canonical bucket string (spec §2.1)
// ============================================================

/**
 * Compute a deterministic canonical data-pattern string for Tier 2 requests.
 *
 * - RANKING / PROPORTION: topShare bucketed + count bucket
 *   e.g. `ranking:top-share:50-65:n4-8` or `proportion:top-share:65-80:n2-3`
 * - TREND (xDim=time): monotonicity + volatility
 *   e.g. `trend:rising:low`
 * - Empty values → `other` (never throws)
 *
 * @param meta        ChartMeta (for routing to correct family)
 * @param values      Numeric series values
 * @param labels      Parallel string labels (may be empty for TREND)
 * @param chartType   Optional: 'pie'/'PIE' triggers 'proportion:' prefix instead of 'ranking:'
 */
export function computeDataPattern(
  meta: ChartMeta,
  values: number[],
  labels: string[],
  chartType?: string,
): string {
  try {
    if (!values.length) return 'other:empty';

    // TREND family
    if (meta.xDim === 'time') {
      if (values.length < 2) return 'trend:insufficient';
      const mono = monotonicity(values);

      // Volatility: σ/μ at threshold 0.3
      const mean = values.reduce((s, v) => s + v, 0) / values.length;
      let volatility: 'low' | 'high' = 'low';
      if (mean !== 0) {
        const variance = values.reduce((s, v) => s + (v - mean) ** 2, 0) / values.length;
        const cv = Math.sqrt(variance) / Math.abs(mean);
        volatility = cv >= 0.3 ? 'high' : 'low';
      }
      return `trend:${mono}:${volatility}`;
    }

    // RANKING / PROPORTION family
    const isPie = chartType ? chartType.toLowerCase() === 'pie' : false;
    const prefix = isPie ? 'proportion' : 'ranking';

    const positiveValues = values.filter((v) => v > 0);
    if (!positiveValues.length) return `${prefix}:no-data`;

    const total = positiveValues.reduce((s, v) => s + v, 0);
    if (total === 0) return `${prefix}:zero-total`;

    const maxVal = Math.max(...positiveValues);
    const topSharePct = (maxVal / total) * 100;

    // Top-share bucket
    let shareBucket: string;
    if (topSharePct >= 80) {
      shareBucket = '80+';
    } else if (topSharePct >= 65) {
      shareBucket = '65-80';
    } else if (topSharePct >= 50) {
      shareBucket = '50-65';
    } else {
      shareBucket = '0-50';
    }

    // Count bucket
    const n = positiveValues.length;
    let countBucket: string;
    if (n >= 9) {
      countBucket = 'n9+';
    } else if (n >= 4) {
      countBucket = 'n4-8';
    } else {
      countBucket = 'n2-3';
    }

    // Suppress unused labels (may be used for richer patterns in future phases)
    void labels;

    return `${prefix}:top-share:${shareBucket}:${countBucket}`;
  } catch {
    return 'other:error';
  }
}

// ============================================================
// Tier 2 request/response types (mirrors Python ChartInsightRequest)
// ============================================================

interface Tier2Request {
  chart_type: string;
  x_dim: string;
  y_metric: string;
  aggregation: string;
  domain: string;
  data_pattern: string;
  permission_tier: 'finance_visible' | 'finance_hidden';
  factory_id: string;
  series_values: number[];
  series_labels: string[];
}

interface Tier2ResponseData {
  finding: string;
  implication?: string;
  suggestion?: string;
  source: InsightResult['source'];
  tier: number;
  /** G4: corpus row key for the feedback endpoint */
  input_hash?: string | null;
  cacheHit?: boolean;
  cacheToken?: string;
}

interface Tier2Response {
  success: boolean;
  data: Tier2ResponseData | null;
  code?: string;
}

/**
 * Extract PIE series values from a pie chart config.
 * For PIE: values are in series[0].data[i].value; labels in .name.
 */
function extractPieValues(config: ChartWithMeta['config']): number[] {
  return extractPieSlices(config).map((s) => s.value);
}

function extractPieLabels(config: ChartWithMeta['config']): string[] {
  return extractPieSlices(config).map((s) => s.label);
}

/**
 * The Python SmartBI service URL — mirrors how pythonFetch resolves it.
 * Uses the same env var as common.ts so auth headers and baseURL are consistent.
 */
const PYTHON_SMARTBI_URL =
  typeof import.meta !== 'undefined' && import.meta.env
    ? (import.meta.env.VITE_SMARTBI_URL as string | undefined) ?? '/smartbi-api'
    : '/smartbi-api';

/**
 * Fetch a Tier 2 LLM/template insight from the backend.
 *
 * Posts to POST /api/smartbi/chart-insight (JWT-authenticated via auth header
 * from localStorage, same mechanism as pythonFetch in common.ts).
 *
 * - `factory_id` MUST equal the JWT factoryId — pass the component's prop factoryId.
 * - On success: returns mapped InsightResult (tier:2, source from response).
 * - Returns the API reason for a known empty response or access/service error.
 * - Returns null when the server does not provide a reason.
 * - Never throws to the caller.
 *
 * @param chart      ChartWithMeta (provides meta, config, chartType for request body)
 * @param perms      User permissions (gates permission_tier field)
 * @param factoryId  JWT-matching factory ID (from component prop, NOT derived internally)
 */
export async function fetchTier2Insight(
  chart: ChartWithMeta,
  perms: UserPermissions,
  factoryId: string,
): Promise<InsightResult | { reason: string } | null> {
  if (!chart.meta) return null;
  // The authenticated Tier 2 endpoint accepts factory/finance only. Restaurant
  // charts can still use Tier 1; do not issue a known-invalid request or relabel
  // their domain to obtain an unrelated template.
  if (chart.meta.domain === 'restaurant') return null;

  try {
    const meta = chart.meta;
    const chartType = (chart.chartType ?? 'bar').toUpperCase();
    const isPie = chartType === 'PIE';

    // Extract series data — PIE uses .data[i].value/.name; others use xAxis + series[i].data
    const seriesValues = isPie ? extractPieValues(chart.config) : extractValues(chart.config);
    const seriesLabels = isPie ? extractPieLabels(chart.config) : extractLabels(chart.config);

    const dataPattern = computeDataPattern(meta, seriesValues, seriesLabels, chart.chartType);
    const permissionTier: 'finance_visible' | 'finance_hidden' = perms.canViewFinance
      ? 'finance_visible'
      : 'finance_hidden';

    const body: Tier2Request = {
      chart_type: chartType,
      x_dim: meta.xDim,
      y_metric: meta.yMetric,
      aggregation: meta.aggregation,
      domain: meta.domain,
      data_pattern: dataPattern,
      permission_tier: permissionTier,
      factory_id: factoryId,
      series_values: seriesValues,
      series_labels: seriesLabels,
    };

    // Auth header — same pattern as getPythonAuthHeaders() in common.ts
    const headers = await getPythonAuthHeaders();

    const response = await fetch(`${PYTHON_SMARTBI_URL}/api/smartbi/chart-insight`, {
      method: 'POST',
      headers,
      credentials: 'include',
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      console.warn(`[chart-insight] Tier2 HTTP ${response.status} for factory ${factoryId}`);
      if (response.status === 401) return { reason: 'UNAUTHORIZED' };
      if (response.status === 403) return { reason: 'PERMISSION_DENIED' };
      if (response.status === 503) return { reason: 'SERVICE_UNAVAILABLE' };
      return null;
    }

    const json = (await response.json()) as Tier2Response;

    if (!json.success || !json.data) {
      return typeof json.code === 'string' ? { reason: json.code } : null;
    }

    const d = json.data;
    return {
      finding: d.finding,
      implication: d.implication,
      suggestion: d.suggestion,
      source: d.source,
      tier: 2,
      input_hash: d.input_hash ?? null,  // G4: carry corpus key for feedback
      cacheHit: d.cacheHit,
      cacheToken: d.cacheToken,
    };
  } catch (err) {
    console.warn('[chart-insight] Tier2 fetch failed (honest-null):', err);
    return null;
  }
}

// ============================================================
// G4 — submitInsightFeedback: POST /api/smartbi/insight/feedback
// ============================================================

/**
 * Submit a 👍/👎 vote for a served corpus insight.
 *
 * @param inputHash   The `input_hash` from the InsightResult (corpus row key)
 * @param vote        'up' or 'down'
 * @param factoryId   Must match the JWT factoryId (same as used in fetchTier2Insight)
 * @returns           true on success, false on any error (never throws)
 */
export async function submitInsightFeedback(
  inputHash: string,
  vote: 'up' | 'down',
  factoryId: string,
  cacheToken?: string,
): Promise<boolean> {
  try {
    const headers = await getPythonAuthHeaders();

    const response = await fetch(`${PYTHON_SMARTBI_URL}/api/smartbi/insight/feedback`, {
      method: 'POST',
      headers,
      credentials: 'include',
      body: JSON.stringify({ input_hash: inputHash, vote, factory_id: factoryId, cache_token: cacheToken }),
    });

    if (!response.ok) {
      console.warn(`[insight-feedback] HTTP ${response.status} for vote=${vote}`);
      return false;
    }
    const json = await response.json();
    return json?.success === true;
  } catch (err) {
    console.warn('[insight-feedback] submitFeedback failed:', err);
    return false;
  }
}
