/**
 * 成本 / 损耗异常 API — CostDeviationCaseController
 *
 * 金额 null: priceMasked=true 是无权查看, 否则是金额未知; ⛔ 都不当 0 显示。
 * 阈值 null = 未配置, 该类不告警。
 */
import { get, post, put } from './request';
import type { ApiResponse } from '@/types/api';
import type { PlanCostVariance } from './costVariance';

export interface CostDeviationCase {
  id: string;
  sourceType: 'COST_VARIANCE' | 'MATERIAL_BALANCE';
  sourceLabel: string;
  planId: string;
  planNumber: string | null;
  productName: string | null;
  stage: string;
  stageLabel: string;
  ownerRole: string;
  ownerUserId: number | null;
  ownerName: string | null;
  metricPct: number;
  thresholdPct: number;
  amountVariance: number | null;
  priceMasked: boolean;
  unknownItemCount: number;
  summary: string;
  belowThresholdAt: string | null;
  resolutionCode: string | null;
  resolutionLabel: string | null;
  resolutionNote: string | null;
  resolvedAt: string | null;
  lastRefreshedAt: string;
  createdAt: string;
}

export interface BalanceRow {
  processName: string | null;
  level: string;
  unit: string | null;
  input: number | null;
  output: number | null;
  byproducts: number | null;
  confirmedLoss: number | null;
  unexplained: number | null;
  unexplainedPct: number | null;
  note: string | null;
}

export interface CostDeviationImpact {
  amount: number | null;
  amountNote: string | null;
  unitCostImpact: number | null;
  outputUnit: string | null;
  batchCount: number;
  unexplainedQty: number | null;
  unexplainedUnit: string | null;
}

export interface CostDeviationEvent {
  eventType: string;
  triggerReason: string;
  detail: string | null;
  actorType: string | null;
  createdAt: string;
}

export interface CostDeviationDetail {
  case: CostDeviationCase;
  batches: { batchNumber: string; status: string | null }[];
  evidenceError?: string;
  varianceEvidence: PlanCostVariance | null;
  balanceEvidence: { rows: BalanceRow[] } | null;
  impact: CostDeviationImpact;
  verifyPath: string[];
  events: CostDeviationEvent[];
}

export interface CostDeviationThresholds {
  variancePct: number | null;
  balancePct: number | null;
}

export interface CostDeviationScanResult {
  varianceConfigured: boolean;
  balanceConfigured: boolean;
  evaluated: number;
  created: number;
  refreshed: number;
  unchanged: number;
  alreadyResolved: number;
  within: number;
  failed: number;
  unknown: { sourceType: string; planNumber: string | null; reason: string }[];
}

export type CostDeviationResolution = 'REASONABLE' | 'CORRECTED' | 'STANDARD_UPDATE' | 'DATA_ERROR';

const base = (factoryId: string) => `/${factoryId}/cost-deviation-cases`;

export function listCostDeviationCases(factoryId: string, openOnly: boolean): Promise<ApiResponse<CostDeviationCase[]>> {
  return get<CostDeviationCase[]>(base(factoryId), { params: { openOnly } });
}
export function getCostDeviationCase(factoryId: string, id: string): Promise<ApiResponse<CostDeviationDetail>> {
  return get<CostDeviationDetail>(`${base(factoryId)}/${id}`);
}
export function getCostDeviationThresholds(factoryId: string): Promise<ApiResponse<CostDeviationThresholds>> {
  return get<CostDeviationThresholds>(`${base(factoryId)}/thresholds`);
}
export function saveCostDeviationThresholds(factoryId: string, body: CostDeviationThresholds): Promise<ApiResponse<CostDeviationThresholds>> {
  return put<CostDeviationThresholds>(`${base(factoryId)}/thresholds`, body);
}
export function scanCostDeviations(factoryId: string): Promise<ApiResponse<CostDeviationScanResult>> {
  return post<CostDeviationScanResult>(`${base(factoryId)}/scan`);
}
export function assignCostDeviationOwner(factoryId: string, id: string, ownerUserId: number): Promise<ApiResponse<null>> {
  return post<null>(`${base(factoryId)}/${id}/owner`, { ownerUserId });
}
export function startCostDeviationVerify(factoryId: string, id: string): Promise<ApiResponse<null>> {
  return post<null>(`${base(factoryId)}/${id}/start-verify`);
}
export function resolveCostDeviation(factoryId: string, id: string, resolution: CostDeviationResolution, note: string): Promise<ApiResponse<null>> {
  return post<null>(`${base(factoryId)}/${id}/resolve`, { resolution, note });
}
