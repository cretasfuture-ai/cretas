import { SHIPMENT_COST_SOURCE_INCOMPLETE } from './financeReportCodes';
/**
 * Sprint 7 T3 — 财务三表 API client.
 *
 * <p>路径前缀 /api/mobile/{factoryId}/finance/report.
 */

import { get } from '@/api/request';
import axios from 'axios';

export interface LineItem {
  accountCode: string;
  accountName: string;
  amount: number;
}

export interface BalanceSheetDTO {
  scopeApplied?: boolean;
  scopeLabel?: string;
  scopeNote?: string;
  factoryId: string;
  year: number;
  month: number;
  /** 期间结账状态 — OPEN/PENDING_CLOSE/CLOSED */
  periodStatus: 'OPEN' | 'PENDING_CLOSE' | 'CLOSED';
  assets: LineItem[];
  liabilities: LineItem[];
  equity: LineItem[];
  totalAssets: number;
  totalLiabilities: number;
  totalEquity: number;
  totalLiabilitiesAndEquity: number;
  /** true 当 |totalAssets - totalLiabilitiesAndEquity| <= 0.01. */
  balanceCheck: boolean;
  generatedAt: string;
  /**
   * F006 财务审计 Bug 6 (2026-07-04): 官方报表已切 POSTED-only (人工审核制, DRAFT 待审草稿
   * 不计入). 本字段 = 期间内待过账凭证数, 用于向财务人员说明"报表数字看着少"的原因.
   */
  pendingDraftVoucherCount?: number;
}

export interface IncomeStatementDTO {
  scopeApplied?: boolean;
  scopeLabel?: string;
  scopeNote?: string;
  factoryId: string;
  startYear: number;
  startMonth: number;
  endYear: number;
  endMonth: number;
  revenues: LineItem[] | null;
  costs: LineItem[] | null;
  expenses: LineItem[] | null;
  totalRevenue: number | null;
  totalCost: number | null;
  grossProfit: number | null;
  totalExpense: number | null;
  operatingProfit: number | null;
  incomeTax: number | null;
  netProfit: number | null;
  generatedAt: string;
  /** F006 财务审计 Bug 6 (2026-07-04): 期间内待过账 (DRAFT) 凭证数, 见 BalanceSheetDTO 同名字段. */
  pendingDraftVoucherCount?: number;
  costDataAvailable?: boolean;
  grossMarginStatus?: 'CALCULABLE' | 'MISSING_COST_DATA' | 'NO_REVENUE';
  grossMarginStatusMessage?: string;
}

export interface CashFlowActivity {
  counterpartCode: string;
  counterpartName: string;
  inflow: number;
  outflow: number;
  netAmount: number;
}

export interface CashFlowDTO {
  scopeApplied?: boolean;
  scopeLabel?: string;
  scopeNote?: string;
  factoryId: string;
  startYear: number;
  startMonth: number;
  endYear: number;
  endMonth: number;
  operatingActivities: CashFlowActivity[];
  investingActivities: CashFlowActivity[];
  financingActivities: CashFlowActivity[];
  operatingNetCashFlow: number;
  investingNetCashFlow: number;
  financingNetCashFlow: number;
  netIncreaseInCash: number;
  beginningCash: number;
  endingCash: number;
  generatedAt: string;
  /**
   * F006 财务审计 Bug 6 follow-up (2026-07-04): 期间内待过账 (DRAFT) 凭证数, 见
   * BalanceSheetDTO 同名字段.
   */
  pendingDraftVoucherCount?: number;
}

/**
 * 资产负债表 — 截至 year/month 月底.
 */
export function getBalanceSheet(factoryId: string, year: number, month: number) {
  return get<BalanceSheetDTO>(
    `/${factoryId}/finance/report/balance-sheet`,
    { params: { year, month } }
  );
}

/**
 * 利润表 — 期间累计.
 */
export interface ShipmentMarginBatchSource {
  batchNumber: string;
  quantity: number;
  unit: string;
  unitCost: number | null;
  rawCost: number | null;
  cost: number | null;
}

export interface ShipmentMarginSourceLine {
  shipmentLineId: number;
  deliveryNumber: string;
  deliveryDate: string;
  quantity: number;
  unit: string;
  revenue: number | null;
  rawCost: number | null;
  cost: number | null;
  batches: ShipmentMarginBatchSource[];
}

export interface ShipmentSkuMarginRow {
  productTypeId: string;
  productName: string;
  unit: string;
  quantity: number;
  shipmentLineCount: number;
  batchCount: number;
  sources: ShipmentMarginSourceLine[] | null;
  revenue: number | null;
  rawCost: number | null;
  cost: number | null;
  grossProfit: number | null;
  marginRate: number | null;
}

export interface ShipmentSkuMargin {
  periodStart: string;
  periodEnd: string;
  rows: ShipmentSkuMarginRow[];
  totalRevenue: number | null;
  totalCost: number | null;
  grossProfit: number | null;
  marginRate: number | null;
  roundingAdjustment: number | null;
  reconciliationReturnedRevenue?: number | null;
  reconciliationReturnedCost?: number | null;
  reconciliationNetRevenue?: number | null;
  reconciliationNetCost?: number | null;
  reconciliationNetGrossProfit?: number | null;
  reconciliationStatus: 'MATCH' | 'MISMATCH' | 'PENDING_VOUCHERS' | 'PARTIAL_PERIOD';
  reconciliationMessage: string;
  financialStatement: IncomeStatementDTO | null;
}

export function getShipmentSkuMargin(factoryId: string, periodStart: string, periodEnd: string) {
  return get<ShipmentSkuMargin>(`/${factoryId}/finance/report/shipment-sku-margin`,
    // 出货扣库不完整 (409) 由页面内说明, 不让全局拦截器弹红窗; 其它错误照旧走全局提示。
    { params: { periodStart, periodEnd }, _handledErrorCodes: [SHIPMENT_COST_SOURCE_INCOMPLETE] });
}

/**
 * 经营分析「财务」区块的系统毛利 (Steve 2026-11-18: 收入 = 发货收入, 与应收挂账同一批发运单)。
 * 金额按工厂权限配置的财务读可见, 无权时为 null (status / 条数 / 原因照常返回)。
 */
export type ShippedGrossProfitStatus = 'OK' | 'NO_SHIPMENT' | 'PRICE_MISSING' | 'COST_INCOMPLETE';

export interface ShippedGrossProfit {
  periodStart: string;
  periodEnd: string;
  status: ShippedGrossProfitStatus;
  shipmentLineCount: number;
  revenue: number | null;
  cost: number | null;
  grossProfit: number | null;
  marginRate: number | null;
  unavailableReason: string | null;
  revenueBasis: string;
  costBasis: string;
}

export function getShippedGrossProfit(factoryId: string, periodStart: string, periodEnd: string) {
  // _silent: 卡片自己说明失败原因 (含 403 无权), 不再弹全局提示
  return get<ShippedGrossProfit>(`/${factoryId}/finance/report/shipped-gross-profit`,
    { params: { periodStart, periodEnd }, _silent: true });
}

export function getIncomeStatement(
  factoryId: string,
  startYear: number,
  startMonth: number,
  endYear: number,
  endMonth: number,
) {
  return get<IncomeStatementDTO>(
    `/${factoryId}/finance/report/income-statement`,
    { params: { startYear, startMonth, endYear, endMonth } }
  );
}

/**
 * 现金流量表 — 期间累计, 直接法.
 */
export function getCashFlow(
  factoryId: string,
  startYear: number,
  startMonth: number,
  endYear: number,
  endMonth: number,
) {
  return get<CashFlowDTO>(
    `/${factoryId}/finance/report/cash-flow`,
    { params: { startYear, startMonth, endYear, endMonth } }
  );
}

/**
 * 导出资产负债表 xlsx.
 * 使用 blob 下载, 调用方负责触发浏览器保存.
 */
export async function exportBalanceSheet(
  factoryId: string,
  year: number,
  month: number,
): Promise<void> {
  const response = await axios.get(
    `/api/mobile/${factoryId}/finance/report/balance-sheet/export`,
    { params: { year, month }, responseType: 'blob' },
  );
  const blob = new Blob([response.data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const cd = response.headers['content-disposition'] ?? '';
  const match = cd.match(/filename\*=UTF-8''(.+)/i);
  a.download = match ? decodeURIComponent(match[1]) : `资产负债表_${year}${String(month).padStart(2, '0')}.xlsx`;
  a.href = url;
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * 导出现金流量表 xlsx.
 */
export async function exportCashFlow(
  factoryId: string,
  startYear: number,
  startMonth: number,
  endYear: number,
  endMonth: number,
): Promise<void> {
  const response = await axios.get(
    `/api/mobile/${factoryId}/finance/report/cash-flow/export`,
    { params: { startYear, startMonth, endYear, endMonth }, responseType: 'blob' },
  );
  const blob = new Blob([response.data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const cd = response.headers['content-disposition'] ?? '';
  const match = cd.match(/filename\*=UTF-8''(.+)/i);
  a.download = match
    ? decodeURIComponent(match[1])
    : `现金流量表_${startYear}${String(startMonth).padStart(2, '0')}_${endYear}${String(endMonth).padStart(2, '0')}.xlsx`;
  a.href = url;
  a.click();
  URL.revokeObjectURL(url);
}
