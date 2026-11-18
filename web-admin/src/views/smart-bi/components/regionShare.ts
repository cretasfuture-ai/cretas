/**
 * 区域销售分布: 把后端 rankings.region 的「区域 -> 金额」换算成占比。
 *
 * 铁律(禁止降级处理): 数据不可信就明确说没有, 不渲染任何区域数字。
 * - 金额必须是有限的非负数; 出现 NaN / Infinity / 负数 / 非数字 一律判为不可用
 *   (负数会让合计变小, 占比冲到几千百分比)。
 * - 合计必须 > 0。
 * - 占比 = 区域值 ÷ 区域合计 × 100, 保留 1 位小数, 合计 ≈ 100%。
 */
export interface RegionShareInput {
  name: string;
  value: number;
}

export interface RegionShareRow {
  name: string;
  sales: number;
  /** 0-100, 1 位小数 */
  percentage: number;
}

export function buildRegionShares(items: ReadonlyArray<RegionShareInput> | null | undefined): RegionShareRow[] {
  if (!items || items.length === 0) return [];
  for (const it of items) {
    if (typeof it.value !== 'number' || !Number.isFinite(it.value) || it.value < 0) return [];
  }
  const total = items.reduce((sum, it) => sum + it.value, 0);
  if (!(total > 0)) return [];
  return items.map((it) => ({
    name: it.name,
    sales: it.value,
    percentage: Math.round((it.value / total) * 1000) / 10,
  }));
}

export function formatSharePercent(p: number): string {
  return `${p.toFixed(1)}%`;
}
