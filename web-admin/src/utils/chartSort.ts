/**
 * 图表 / 明细表通用排序（问题 50，2026-11-18）。
 *
 * - 柱状图：ChartSortControl 选「按数值降序 / 按数值升序 / 按名称 / 原始顺序」，页面用 sortChartRows 排好再 setOption。
 * - 明细表：el-table-column 加 sortable + :sort-method="tableSortBy(row => ...)"，不把「未填」当 0 排进中间。
 */

export type ChartSortMode = 'valueDesc' | 'valueAsc' | 'name' | 'original';

export const CHART_SORT_OPTIONS: Array<{ value: ChartSortMode; label: string }> = [
  { value: 'valueDesc', label: '数值降序' },
  { value: 'valueAsc', label: '数值升序' },
  { value: 'name', label: '按名称' },
  { value: 'original', label: '原始顺序' },
];

const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' });

function isKnown(v: number | null | undefined): v is number {
  return typeof v === 'number' && Number.isFinite(v);
}

/**
 * 按模式排序（不改原数组）。
 * @param group 可选分组键：同组的排在一起再组内排序（如单位成本按「盒 / kg」分组，跨单位不比大小）。
 *              分组按原数组里首次出现的先后排列。
 */
export function sortChartRows<T>(
  rows: readonly T[],
  mode: ChartSortMode,
  value: (row: T) => number | null | undefined,
  name: (row: T) => string,
  group?: (row: T) => string,
): T[] {
  const indexed = rows.map((row, index) => ({ row, index }));
  if (mode === 'original') return indexed.map((x) => x.row);
  const groupOrder = new Map<string, number>();
  if (group) {
    for (const { row } of indexed) {
      const g = group(row);
      if (!groupOrder.has(g)) groupOrder.set(g, groupOrder.size);
    }
  }
  return [...indexed]
    .sort((a, b) => {
      if (group) {
        const ga = groupOrder.get(group(a.row)) ?? 0;
        const gb = groupOrder.get(group(b.row)) ?? 0;
        if (ga !== gb) return ga - gb;
      }
      if (mode === 'name') {
        const c = collator.compare(name(a.row), name(b.row));
        return c !== 0 ? c : a.index - b.index;
      }
      const va = value(a.row);
      const vb = value(b.row);
      const ka = isKnown(va);
      const kb = isKnown(vb);
      if (ka !== kb) return ka ? -1 : 1; // 空值放最后
      if (!ka || !kb) return a.index - b.index;
      const d = mode === 'valueDesc' ? vb - va : va - vb;
      return d !== 0 ? d : a.index - b.index;
    })
    .map((x) => x.row);
}

/**
 * el-table 的 sort-method（升序比较函数，降序由 el-table 反转）。
 * 空值视为最小：配合 :sort-orders="TABLE_SORT_ORDERS" 第一次点表头就是降序，空值在最后。
 * 中文名称按拼音序、带数字的按数值序（「批次2」在「批次10」前）。
 */
export const TABLE_SORT_ORDERS: Array<'descending' | 'ascending' | null> = ['descending', 'ascending', null];

export function tableSortBy<T>(get: (row: T) => number | string | null | undefined) {
  return (a: T, b: T): number => {
    const va = get(a);
    const vb = get(b);
    const na = va == null || (typeof va === 'number' && !Number.isFinite(va));
    const nb = vb == null || (typeof vb === 'number' && !Number.isFinite(vb));
    if (na && nb) return 0;
    if (na) return -1;
    if (nb) return 1;
    if (typeof va === 'number' && typeof vb === 'number') return va - vb;
    return collator.compare(String(va), String(vb));
  };
}
