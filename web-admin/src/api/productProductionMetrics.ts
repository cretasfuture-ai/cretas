import { get } from './request';
import type { ActualFlowPeriod } from '@/views/indicator-center/actualFlowPeriod';

export interface ProductProductionResult {
  productTypeId: string | null;
  productName: string;
  batchCount: number;
  batchNumbers: string[];
  yieldRate: number | null;
  goodOutputRate: number | null;
  totalCost?: number | null;
  unitCost?: number | null;
  costMasked: boolean;
}
export interface ProductProductionMetrics {
  periodStart: string;
  periodEnd: string;
  sourceVersion: string;
  products: ProductProductionResult[];
}

export async function getProductProductionMetrics(factoryId: string, period: ActualFlowPeriod): Promise<ProductProductionMetrics> {
  const res = await get<ProductProductionMetrics>(`/${factoryId}/production/actual-metrics/by-product`, {
    params: { periodStart: period[0], periodEnd: period[1] },
  });
  if (!res.success || !res.data) throw new Error(res.message || '成品经营结果读取失败');
  return res.data;
}
