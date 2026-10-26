import type { FactoryChartDetailFilter, FactoryChartDetailsData, FactoryChartDetailsResponse } from '@/api/smartbi/factory-charts';

export const FACTORY_DETAIL_PAGE_SIZE = 50;
export function detailRequestKey(contextId: string, page: number, filters: readonly FactoryChartDetailFilter[] = [], point?: unknown): string {
  return JSON.stringify({ contextId, page, filters, point });
}
export function detailColumns(data: FactoryChartDetailsData | null): Array<{ key: string; dataKey: string; title: string; width: number }> {
  return (data?.columns ?? []).map((column) => ({ key: column, dataKey: column, title: column, width: Math.max(120, Math.min(260, column.length * 16 + 32)) }));
}
export function appendDetailPage(current: readonly Record<string, unknown>[], response: FactoryChartDetailsResponse): Record<string, unknown>[] {
  if (!response.success || !response.data) return [...current];
  return [...current, ...response.data.rows];
}
