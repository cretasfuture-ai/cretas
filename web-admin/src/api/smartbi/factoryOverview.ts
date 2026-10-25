/**
 * 经营分析页顶部概览卡的工厂数据源 (取代原来的餐饮 gold 汇总)。
 *
 * - 销售订单汇总: Python /api/smartbi/factory-overview/sales (只读业务库, 工厂来自登录)
 * - 往来余额 / 近一年生产 / 按产品产量 / KPI: Java 工厂报表接口 (与下方各看板同源)
 */
import { get } from '@/api/request';
import { pythonFetch } from './common';
import type {
  FactorySalesSummaryLike,
  FactoryFinanceLike,
  FactoryTrendLike,
  ProductionByProductLike,
  FactoryKpiLike,
} from '@/views/smart-bi/components/analysis/factoryOverviewBullets';

/** 「全部历史」的起点: 早于任何工厂的第一条业务记录 */
const ALL_HISTORY_START = '2020-01-01';

function today(): string {
  // 后端按 GMT+8 取日历日; 本地日历日用本地 getter 拼, 不用 toISOString 截断
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function unwrap<T>(resp: { success?: boolean; data?: T; message?: string }, what: string): T {
  if (!resp?.success || resp.data == null) throw new Error(resp?.message || `${what}加载失败`);
  return resp.data;
}

export async function getFactorySalesSummary(): Promise<FactorySalesSummaryLike> {
  const resp = await pythonFetch<{ success: boolean; data: FactorySalesSummaryLike; message?: string }>(
    '/api/smartbi/factory-overview/sales',
  );
  return unwrap(resp, '销售订单汇总');
}

export async function getFactoryFinance(factoryId: string): Promise<FactoryFinanceLike> {
  const resp = await get<FactoryFinanceLike>(`/${factoryId}/reports/finance`, {
    params: { startDate: ALL_HISTORY_START, endDate: today() },
  });
  return unwrap(resp, '往来余额');
}

export async function getFactoryTrend(factoryId: string): Promise<FactoryTrendLike> {
  const resp = await get<FactoryTrendLike>(`/${factoryId}/reports/dashboard/trends`, {
    params: { period: 'year', metric: 'production', days: 365 },
  });
  return unwrap(resp, '生产趋势');
}

export async function getFactoryProductionByProduct(factoryId: string): Promise<ProductionByProductLike[]> {
  const resp = await get<ProductionByProductLike[]>(`/${factoryId}/reports/production-by-product`, {
    params: { startDate: ALL_HISTORY_START, endDate: today() },
  });
  return unwrap(resp, '按产品产量');
}

export async function getFactoryKpi(factoryId: string): Promise<FactoryKpiLike> {
  const resp = await get<FactoryKpiLike>(`/${factoryId}/reports/kpi-metrics`);
  return unwrap(resp, 'KPI');
}
