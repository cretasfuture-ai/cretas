/**
 * 成本分析页（FactoryCostAnalysis）的纯函数：数字格式与口径文案。
 * 单独成文件是为了能不挂载页面就测——口径错了页面看起来仍然「合理」，必须有断言守着。
 */

/** 千分位整数金额，null 一律显示「—」（不是 0：不知道 ≠ 0）。 */
export function fmtMoney(value: number | null | undefined, digits = 0): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function fmtQty(value: number | null | undefined, digits = 1): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: digits });
}

export function fmtPercent(value: number | null | undefined, digits = 1): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return `${value.toFixed(digits)}%`;
}

export interface UnitQuantity {
  unit: string;
  quantity: number;
}

/** 按产出量从大到小列出各单位的产出；不同单位不相加（kg 和 个 加起来没有意义）。 */
export function quantityParts(byUnit: Record<string, number> | undefined | null): UnitQuantity[] {
  if (!byUnit) return [];
  return Object.entries(byUnit)
    .filter(([, quantity]) => Number.isFinite(quantity))
    .map(([unit, quantity]) => ({ unit, quantity }))
    .sort((a, b) => b.quantity - a.quantity);
}

/**
 * 汇总出成率：只读后端按批次数加权的值（单一实现，前端不自己算——两份口径会漂）。
 * 字段缺失（后端还没升级）时返回 null，由页面如实提示「后端未更新」。
 */
export function summaryYield(summary: { avgYieldRate?: number | null } | undefined | null): number | null {
  const value = summary?.avgYieldRate;
  return value == null || !Number.isFinite(value) ? null : value;
}

/** 后端没返回加权出成率这个字段（区别于「返回了 null = 确实没有数据」）。 */
export function summaryYieldMissing(summary: object | undefined | null): boolean {
  return !!summary && !('avgYieldRate' in summary);
}

/**
 * 把只含「有数据的日子」的序列补成连续日历日：无数据日为 null（图上断开，不连线、不插值）。
 */
export function fillCalendarDays<T extends { statDate: string }>(
  points: T[],
): Array<{ date: string; point: T | null }> {
  if (!points.length) return [];
  const byDate = new Map(points.map((p) => [p.statDate, p]));
  const dates = [...byDate.keys()].sort();
  const out: Array<{ date: string; point: T | null }> = [];
  // 全程用本地日历日（getFullYear/getMonth/getDate）：toISOString 会按 UTC 截断，
  // 东八区 0–8 点会退到前一天。
  const parse = (key: string) => {
    const [y, m, d] = key.split('-').map(Number);
    return new Date(y, m - 1, d);
  };
  const pad = (n: number) => String(n).padStart(2, '0');
  const cursor = parse(dates[0]);
  const last = parse(dates[dates.length - 1]);
  while (cursor <= last) {
    const key = `${cursor.getFullYear()}-${pad(cursor.getMonth() + 1)}-${pad(cursor.getDate())}`;
    out.push({ date: key, point: byDate.get(key) ?? null });
    cursor.setDate(cursor.getDate() + 1);
  }
  return out;
}

/** 饼图扇区占比小于该值时不画外挂标签（挤在一起不可读），占比只在图例里显示。 */
export const PIE_LABEL_MIN_PERCENT = 2;

/** 产品名未解析（已停用/不在 active 列表）时给可读兜底，而不是整串 UUID。 */
export function unresolvedProductLabel(productTypeId: string): string {
  return `未命名产品 · ${productTypeId.slice(0, 8)}`;
}

/**
 * 同一产品在多个单位下各有一行时，图表标签带上单位，避免出现两根同名柱子。
 */
export function productChartLabels(
  products: Array<{ productTypeId: string; unit?: string | null }>,
  nameOf: (productTypeId: string) => string,
): string[] {
  const seen = new Map<string, number>();
  for (const p of products) seen.set(p.productTypeId, (seen.get(p.productTypeId) ?? 0) + 1);
  return products.map((p) => {
    const name = nameOf(p.productTypeId);
    return (seen.get(p.productTypeId) ?? 0) > 1 && p.unit ? `${name}（${p.unit}）` : name;
  });
}

/** 「3/5 批有成本」——单位成本只基于有成本的批次，必须让人看得见。 */
export function costCoverageText(costedBatchCount: number | undefined, batchCount: number): string | null {
  if (costedBatchCount == null || costedBatchCount >= batchCount) return null;
  return `${costedBatchCount}/${batchCount} 批有成本`;
}

/** 成本类别配色：环形图与每日堆叠柱共用一份，同一类成本在两张图里同一个颜色。 */
export const COST_CATEGORY_COLORS = {
  材料: '#1b65a8',
  人工: '#52a36b',
  设备: '#e0a526',
  其他: '#d9534f',
} as const;

export type CostCategory = keyof typeof COST_CATEGORY_COLORS;

/** 窄屏阈值：手机宽度下图例移到图下方、明细表改卡片。 */
export const NARROW_MAX_WIDTH = 640;

/**
 * 产品对比按单位成本排：同单位的产品放在一起比（盒和袋的单价不能直接比大小），
 * 产品多的单位组在前；组内单位成本高的在前；算不出单位成本的放最后。
 */
export function productsByUnitCost<T extends { unit?: string | null; costPerUnit?: number | null }>(
  products: T[],
): T[] {
  const groupSize = new Map<string, number>();
  for (const p of products) {
    const key = p.unit ?? '';
    groupSize.set(key, (groupSize.get(key) ?? 0) + 1);
  }
  return [...products].sort((a, b) => {
    const aKnown = a.costPerUnit != null;
    const bKnown = b.costPerUnit != null;
    if (aKnown !== bKnown) return aKnown ? -1 : 1;
    const ua = a.unit ?? '';
    const ub = b.unit ?? '';
    if (ua !== ub) {
      const bySize = (groupSize.get(ub) ?? 0) - (groupSize.get(ua) ?? 0);
      return bySize !== 0 ? bySize : ua.localeCompare(ub);
    }
    return (b.costPerUnit ?? 0) - (a.costPerUnit ?? 0);
  });
}

/** 单位成本标签「¥16.24/盒」；算不出给「未填」，不是 ¥0。 */
export function unitCostLabel(costPerUnit: number | null | undefined, unit: string | null | undefined): string {
  if (costPerUnit == null || !Number.isFinite(costPerUnit)) return '未填';
  return `¥${fmtMoney(costPerUnit, 2)}/${unit || '单位未提供'}`;
}

/**
 * 平均出成率的说明：分母是「有出成率的批次」。全部批次都有时说「按 N 批加权」，
 * 部分批次没报良品/实际时说「N/M 批有出成率」，不让没有数的批次把「按 10 批」撑大。
 */
export function yieldBasisText(yieldBatchCount: number | null | undefined, batchCount: number | null | undefined): string {
  const n = yieldBatchCount ?? 0;
  const total = batchCount ?? 0;
  if (n <= 0) return '暂无带出成率的批次';
  if (total > n) return `${n}/${total} 批有出成率，按这 ${n} 批加权`;
  return `按 ${n} 批加权`;
}

