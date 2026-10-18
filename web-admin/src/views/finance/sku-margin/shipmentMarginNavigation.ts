import { readMarginPeriod } from '@/utils/financeReportPeriod';
export { readMarginPeriod } from '@/utils/financeReportPeriod';

export function readSourceLocation(query: Record<string, unknown>, factoryId: string) {
  const period = readMarginPeriod(query);
  if (!factoryId || query.factoryId !== factoryId || !period
      || typeof query.sourceSku !== 'string' || !query.sourceSku.trim()
      || typeof query.sourceLineId !== 'string' || !/^[1-9]\d*$/.test(query.sourceLineId)) return null;
  const lineId = Number(query.sourceLineId);
  return Number.isSafeInteger(lineId) ? { factoryId, period, productTypeId: query.sourceSku, lineId } : null;
}

export function shipmentSourceUrl(factoryId: string, period: [string, string], productTypeId: string, lineId: number): string {
  return '/finance/sku-margin?' + new URLSearchParams({ factoryId, periodStart: period[0], periodEnd: period[1], sourceSku: productTypeId, sourceLineId: String(lineId) });
}
