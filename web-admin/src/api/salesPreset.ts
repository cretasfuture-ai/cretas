/**
 * Sprint 4 W2 S-REPORTS-PRESETS — Vue client to Python /api/smartbi/{factoryId}/sales-preset/*.
 *
 * Direct Python fetch (consistent w/ revenue-report / restaurant-analytics pattern).
 * No Java pass-through.
 */
import { pythonFetch } from '@/api/smartbi/common';
import { getFactoryId } from '@/api/smartbi/common';

const base = () => `/api/smartbi/${getFactoryId()}/sales-preset`;

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
}

export interface TaxBreakdown {
  revenue: number;
  taxableAmount: number;
  taxAmount: number;
  totalAmountWithTax: number;
}

export interface DailyReport extends TaxBreakdown {
  date: string;
  orderCount: number;
  paid: number;
  unpaid: number;
}

export interface MonthlyReport extends TaxBreakdown {
  yearMonth: string;
  orderCount: number;
  paid: number;
  unpaid: number;
  daily: Array<{ date: string; orderCount: number } & TaxBreakdown>;
}

export interface YearlyReport extends TaxBreakdown {
  year: number;
  orderCount: number;
  paid: number;
  monthly: Array<{ month: number; orderCount: number } & TaxBreakdown>;
}

export interface CustomerRankRow extends TaxBreakdown {
  rank: number;
  customerId: string;
  customerName: string;
  orderCount: number;
  paid: number;
}

export interface ProductRankRow extends TaxBreakdown {
  rank: number;
  productTypeId: string;
  productName: string | null;
  totalQty: number;
  unit: string | null;
  orderCount: number;
}

// ─── 经营分析 4 张: 业务员 / 区域 / 增长率 / 毛利率 ───────────────────────
// 约定: 后端「不知道」给 null (没填业务员 / 地址识别不了 / 没回填成本 / 基数为 0 的比率),
// 前端显示「—」并说明原因, ⛔ 不要当 0 显示。

export interface SalespersonRow extends TaxBreakdown {
  rank: number;
  salespersonId: number | null;
  salespersonName: string | null;
  orderCount: number;
  customerCount: number;
  paid: number;
  unpaid: number;
  share: number | null;
}

export interface SalespersonReport {
  startDate: string;
  endDate: string;
  rank: SalespersonRow[];
  unassigned: TaxBreakdown & { orderCount: number; share: number | null };
  summary: { orderCount: number; assignedOrderCount: number; totalAmountWithTax: number };
}

export type RegionLevel = 'province' | 'region';

export interface RegionStat extends TaxBreakdown {
  orderCount: number;
  customerCount: number;
  lastYearAmountWithTax: number;
  yoyRate: number | null;
  share: number | null;
}

export interface RegionRow extends RegionStat {
  rank: number;
  region: string;
  label: string;
}

export interface RegionalReport {
  startDate: string;
  endDate: string;
  lastYearStartDate: string;
  lastYearEndDate: string;
  level: RegionLevel;
  regions: RegionRow[];
  noAddress: RegionStat;
  unrecognized: RegionStat;
  summary: { orderCount: number; totalAmountWithTax: number; recognizedShare: number | null };
}

export interface GrowthMonth extends TaxBreakdown {
  yearMonth: string;
  orderCount: number;
  prevMonthAmountWithTax: number;
  lastYearAmountWithTax: number;
  momRate: number | null;
  yoyRate: number | null;
  isPartial: boolean;
}

export interface GrowthReport {
  endMonth: string;
  months: GrowthMonth[];
}

export interface GrossMarginRow {
  rank: number;
  productTypeId: string;
  productName: string | null;
  unit: string | null;
  totalQty: number;
  orderCount: number;
  revenue: number;
  costedRevenue: number;
  cost: number | null;
  grossProfit: number | null;
  marginRate: number | null;
  costCoverage: number | null;
  lineCount: number;
  costedLineCount: number;
}

export interface GrossMarginReport {
  startDate: string;
  endDate: string;
  order: 'desc' | 'asc';
  rank: GrossMarginRow[];
  summary: {
    productCount: number;
    costedProductCount: number;
    revenue: number;
    costedRevenue: number;
    cost: number | null;
    grossProfit: number | null;
    marginRate: number | null;
    costCoverage: number | null;
  };
}

function rangeQuery(startDate?: string, endDate?: string): URLSearchParams {
  const q = new URLSearchParams();
  if (startDate) q.set('startDate', startDate);
  if (endDate) q.set('endDate', endDate);
  return q;
}

export function fetchSalespersonPerformance(
  startDate?: string, endDate?: string,
): Promise<ApiResponse<SalespersonReport>> {
  return pythonFetch(`${base()}/salesperson-performance?${rangeQuery(startDate, endDate).toString()}`);
}

export function fetchRegionalComparison(
  startDate?: string, endDate?: string, level: RegionLevel = 'province',
): Promise<ApiResponse<RegionalReport>> {
  const q = rangeQuery(startDate, endDate);
  q.set('level', level);
  return pythonFetch(`${base()}/regional-comparison?${q.toString()}`);
}

export function fetchGrowthRate(endMonth?: string, months = 12): Promise<ApiResponse<GrowthReport>> {
  const q = new URLSearchParams();
  if (endMonth) q.set('endMonth', endMonth);
  q.set('months', String(months));
  return pythonFetch(`${base()}/growth-rate?${q.toString()}`);
}

export function fetchGrossMargin(
  startDate?: string, endDate?: string, order: 'desc' | 'asc' = 'desc', limit = 50,
): Promise<ApiResponse<GrossMarginReport>> {
  const q = rangeQuery(startDate, endDate);
  q.set('order', order);
  q.set('limit', String(limit));
  return pythonFetch(`${base()}/gross-margin?${q.toString()}`);
}

export function fetchDailyReport(date?: string): Promise<ApiResponse<DailyReport>> {
  const q = date ? `?date=${encodeURIComponent(date)}` : '';
  return pythonFetch(`${base()}/daily-report${q}`);
}

export function fetchMonthlyReport(yearMonth?: string): Promise<ApiResponse<MonthlyReport>> {
  const q = yearMonth ? `?yearMonth=${encodeURIComponent(yearMonth)}` : '';
  return pythonFetch(`${base()}/monthly-report${q}`);
}

export function fetchYearlyReport(year?: number): Promise<ApiResponse<YearlyReport>> {
  const q = year ? `?year=${year}` : '';
  return pythonFetch(`${base()}/yearly-report${q}`);
}

export function fetchCustomerRank(
  startDate?: string, endDate?: string, limit = 20,
): Promise<ApiResponse<{ startDate: string; endDate: string; rank: CustomerRankRow[] }>> {
  const q = new URLSearchParams();
  if (startDate) q.set('startDate', startDate);
  if (endDate) q.set('endDate', endDate);
  q.set('limit', String(limit));
  return pythonFetch(`${base()}/customer-rank?${q.toString()}`);
}

export function fetchProductRank(
  startDate?: string, endDate?: string, limit = 20,
): Promise<ApiResponse<{ startDate: string; endDate: string; rank: ProductRankRow[] }>> {
  const q = new URLSearchParams();
  if (startDate) q.set('startDate', startDate);
  if (endDate) q.set('endDate', endDate);
  q.set('limit', String(limit));
  return pythonFetch(`${base()}/product-rank?${q.toString()}`);
}
