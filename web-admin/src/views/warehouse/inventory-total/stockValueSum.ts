/**
 * 库存总价值合计：任一行 totalValue 为 null（缺单价，后端整组置 null）时整体不可算，
 * 返回 null，不能把其余行加起来当总价值（会得到偏低的数）。
 */
export function sumStockValue(rows: ReadonlyArray<{ totalValue?: number | string | null }>): number | null {
  let sum = 0;
  for (const r of rows) {
    if (r.totalValue == null) return null;
    const n = Number(r.totalValue);
    if (!Number.isFinite(n)) return null;
    sum += n;
  }
  return sum;
}
