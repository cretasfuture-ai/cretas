import type { ChartMeta, ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

export function blacklakeInsightChart(
  title: string, chartType: 'line' | 'bar', metric: ChartMeta['yMetric'],
  points: Array<{ label: string; value: number }>,
): ChartWithMeta | null {
  if (!points.length) return null;
  return {
    chartType, title,
    meta: { domain: 'factory', xDim: chartType === 'line' ? 'time' : 'product', yMetric: metric, aggregation: 'sum' },
    config: {
      xAxis: { data: points.map(point => point.label) },
      series: [{ name: title, type: chartType, data: points.map(point => point.value) }],
    },
  };
}

export interface BlacklakeSort {
  prop: string | null;
  order: 'ascending' | 'descending' | null;
}
type Scalar = string | number;
type SortKey = Scalar | Scalar[] | null;
const collator = new Intl.Collator('zh-CN', { numeric: true });

export function sortBlacklakeRows<Row>(
  rows: Row[], sort: BlacklakeSort, keys: Record<string, (row: Row) => SortKey>,
) {
  const key = sort.prop ? keys[sort.prop] : undefined;
  if (!key || !sort.order) return rows;
  const direction = sort.order === 'ascending' ? 1 : -1;
  return [...rows].sort((a, b) => {
    const x = key(a);
    const y = key(b);
    if (x === null || y === null) return Number(x === null) - Number(y === null);
    const left = Array.isArray(x) ? x : [x];
    const right = Array.isArray(y) ? y : [y];
    for (let i = 0; i < Math.min(left.length, right.length); i++) {
      const v = left[i];
      const w = right[i];
      const compared = typeof v === 'number' && typeof w === 'number' ? v - w : collator.compare(String(v), String(w));
      if (compared) return compared * direction;
    }
    return (left.length - right.length) * direction;
  });
}
