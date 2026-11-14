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
 * 汇总出成率：优先用后端按批次数加权的值；后端还没升级（字段缺失）时，
 * 用日行按批次数加权现算——绝不退回按天简单平均。
 */
export function summaryYield(
  summary: { avgYieldRate?: number | null } | undefined | null,
  rows?: Array<{ avgYieldRate: number | null; batchCount: number }>,
): number | null {
  const value = summary?.avgYieldRate;
  if (value != null && Number.isFinite(value)) return value;
  if (summary && 'avgYieldRate' in summary) return null;
  let weighted = 0;
  let batches = 0;
  for (const row of rows ?? []) {
    if (row.avgYieldRate == null || !Number.isFinite(row.avgYieldRate) || !row.batchCount) continue;
    weighted += row.avgYieldRate * row.batchCount;
    batches += row.batchCount;
  }
  return batches ? Math.round((weighted / batches) * 100) / 100 : null;
}

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
