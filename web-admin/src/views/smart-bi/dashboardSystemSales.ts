/**
 * 经营驾驶舱 KPI 的「系统销售订单」来源。
 *
 * 根因: 驾驶舱 /dashboard/executive 只读上传 Excel/gold 数据; 经营分析页「销售订单」卡
 * 读业务库 Python /api/smartbi/factory-overview/sales (工厂来自登录)。没有上传数据的
 * 厂 (如 LSMDEMO) 驾驶舱空、分析页有数。这里把同一个接口的汇总映射成 KPI 卡,
 * 口径与分析页一致 (有效订单 = 不含已取消/草稿/财务驳回)。无订单返回 null, 不造数。
 */
import type { KPICard } from '@/types/smartbi';
import type { FactorySalesSummaryLike } from './components/analysis/factoryOverviewBullets';

export const SYSTEM_SALES_PERIOD = 'system-sales';
export const SYS_KEY = {
  amount: 'sys_sales_amount',
  orders: 'sys_sales_effective_orders',
  avg: 'sys_sales_avg_order',
  inFlight: 'sys_sales_in_flight',
} as const;

export function buildSystemSalesKpiCards(s: FactorySalesSummaryLike | null | undefined): KPICard[] | null {
  if (!s || s.orderCount <= 0 || s.effectiveOrderCount <= 0) return null;
  const cards: KPICard[] = [];
  const hasAmount = s.orderAmount != null;
  if (hasAmount) {
    cards.push({ key: SYS_KEY.amount, title: '订单金额', rawValue: s.orderAmount as number, trend: 'flat' });
  }
  cards.push({ key: SYS_KEY.orders, title: '有效订单数', rawValue: s.effectiveOrderCount, trend: 'flat' });
  const withAmount = s.effectiveOrderCount - (s.ordersWithoutAmount ?? 0);
  if (hasAmount && withAmount > 0) {
    cards.push({ key: SYS_KEY.avg, title: '单均金额', rawValue: (s.orderAmount as number) / withAmount, trend: 'flat' });
  }
  cards.push({ key: SYS_KEY.inFlight, title: '在途订单', rawValue: s.inFlightCount, trend: 'flat' });
  return cards;
}

export function hasSystemSalesCards(cards: KPICard[] | undefined | null): boolean {
  return !!cards && cards.some(c => c.key === SYS_KEY.orders);
}
