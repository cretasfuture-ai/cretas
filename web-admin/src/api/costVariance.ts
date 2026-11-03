/**
 * 成本差异报表 API (食品 DAG 缺口 09/10) — ReportController
 *
 * GET /api/mobile/{factoryId}/reports/cost-variance?startDate&endDate     期间汇总 + 各计划摘要
 * GET /api/mobile/{factoryId}/reports/cost-variance/plans/{planId}        单计划逐项明细 + 多产出分摊方法
 *
 * 口径 (Steve 2026-11-03): 标准成本 = 计划锁定配方的标准用量 × 开计划时物料单价快照;
 * 差异拆价格差 / 用量差 / 替代料差 / 人工差; 缺单价/缺快照的项后端给 null + 原因, 前端照写「无法比较」。
 */
import { get } from './request';

export type VarianceLineStatus = 'COMPARABLE' | 'PARTIAL' | 'INCOMPARABLE';

export interface PlanCostVarianceLine {
  lineType: 'MATERIAL' | 'SUBSTITUTE' | 'UNPLANNED' | 'LABOR';
  materialTypeId: string | null;
  materialName: string | null;
  materialCategory: string | null;
  parentMaterialTypeId: string | null;
  priceUnit: string | null;
  snapshotUnitPrice: number | null;
  standardQuantity: number | null;
  standardAmount: number | null;
  actualQuantity: number | null;
  actualUnitPrice: number | null;
  actualAmount: number | null;
  priceVariance: number | null;
  usageVariance: number | null;
  substituteVariance: number | null;
  laborVariance: number | null;
  status: VarianceLineStatus;
  reasons: string[];
}

export interface AllocationOutput {
  productTypeId: string | null;
  productName: string | null;
  batchNumber: string | null;
  ratio: number | null;
  workflowFixedCostShare: number | null;
  allocatedCost: number | null;
}

export interface AllocationMethod {
  processCode: string | null;
  processOrder: number | null;
  basis: string;
  basisLabel: string;
  outputs: AllocationOutput[];
}

export interface PlanCostVariance {
  planId: string;
  planNumber: string | null;
  productTypeId: string | null;
  productName: string | null;
  planStatus: string | null;
  comparable: boolean;
  comparabilityReason: string | null;
  snapshotCapturedAt: string | null;
  outputQuantity: number | null;
  outputUnit: string | null;
  outputKg: number | null;
  standardCost: number | null;
  actualCost: number | null;
  totalVariance: number | null;
  varianceRate: number | null;
  priceVariance: number | null;
  usageVariance: number | null;
  substituteVariance: number | null;
  laborVariance: number | null;
  recoveredMaterialNote: string | null;
  comparableItemCount: number;
  incomparableItemCount: number;
  customerSuppliedItemCount: number;
  priceMasked: boolean;
  lines: PlanCostVarianceLine[];
  allocationMethods: AllocationMethod[];
}

export interface CostVarianceReport {
  comparable: boolean | null;
  comparabilityReason: string | null;
  standardCostBasis: string | null;
  processSecretNote: string | null;
  startDate: string;
  endDate: string;
  totalBomCost: number | null;
  totalActualCost: number | null;
  totalVariance: number | null;
  totalVarianceRate: number | null;
  varianceStatus: string | null;
  priceVariance: number | null;
  usageVariance: number | null;
  substituteVariance: number | null;
  laborVariance: number | null;
  comparedPlanCount: number | null;
  incomparablePlanCount: number | null;
  batchCount: number | null;
  planVariances: PlanCostVariance[] | null;
}

export function getCostVarianceReport(factoryId: string, startDate: string, endDate: string) {
  return get<CostVarianceReport>(`/${factoryId}/reports/cost-variance`, { params: { startDate, endDate } });
}

export function getPlanCostVariance(factoryId: string, planId: string) {
  return get<PlanCostVariance>(`/${factoryId}/reports/cost-variance/plans/${encodeURIComponent(planId)}`);
}
