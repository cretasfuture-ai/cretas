/**
 * S4 缺料异常处理实例: 一个缺料事件一条, 跟着财务审核 / 入库 / 质检放行自动刷新。
 * 「缺口已解决」(gapResolvedAt) 只看物料事实; 订单交付看 salesOrderStatus, 两者分开展示。
 */
import { get, post } from './request';
import type { ApiResponse } from '@/types/api';

export type ShortageCaseStage =
  | 'DETECTED'
  | 'PLANNED'
  | 'EXECUTING'
  | 'WAITING_SUPPLY'
  | 'PARTIALLY_RESOLVED'
  | 'GAP_RESOLVED'
  | 'CANCELLED'
  | 'MANUAL'
  | 'CLOSED';

export interface ShortageCase {
  id: string;
  sourceType: 'SALES_ORDER';
  sourceId: string;
  salesOrderNumber: string | null;
  salesOrderStatus: string | null;
  /** 订单状态的中文名 (界面只显示它) */
  salesOrderStatusLabel: string | null;
  stage: ShortageCaseStage;
  stageLabel: string;
  ownerRole: string;
  ownerUserId: number | null;
  gapLineCount: number;
  gapResolvedAt: string | null;
  lastRefreshedAt: string;
  lastRefreshReason: string;
  closedAt: string | null;
  createdAt: string;
}

export interface ShortageCaseLine {
  materialTypeId: string;
  materialName: string | null;
  /** 跟物料走; null = 单位未知 (不默认 kg) */
  unit: string | null;
  /** 单位的中文显示名; null = 单位未知 */
  unitLabel: string | null;
  requiredQty: number;
  releasedQty: number;
  inspectingQty: number;
  onOrderQty: number;
  /** 单位与需求不同、没计进在途的采购行数 */
  onOrderUnitMismatchLines: number;
  gapQty: number;
}

export interface ShortageCaseEvent {
  eventType: 'CREATED' | 'REFRESHED' | 'STAGE_CHANGED' | 'NOTE';
  fromStage: ShortageCaseStage | null;
  toStage: ShortageCaseStage | null;
  triggerReason: string;
  detail: string | null;
  actorType: string | null;
  actorId: number | null;
  createdAt: string;
}

export interface ShortageCaseDetail {
  case: ShortageCase;
  lines: ShortageCaseLine[];
  events: ShortageCaseEvent[];
}

export function listShortageCases(
  factoryId: string,
  params: { openOnly?: boolean; page?: number; size?: number } = {},
): Promise<ApiResponse<ShortageCase[]>> {
  return get(`/${factoryId}/shortage-cases`, { params });
}

export function getShortageCase(factoryId: string, caseId: string): Promise<ApiResponse<ShortageCaseDetail>> {
  return get(`/${factoryId}/shortage-cases/${caseId}`);
}

export function refreshShortageCase(factoryId: string, caseId: string): Promise<ApiResponse<ShortageCase>> {
  return post(`/${factoryId}/shortage-cases/${caseId}/refresh`, {});
}

/** 采购建议: 量 = 缺口 − 在途, 起订量取整; 单位不同 / 没供应商时 canDraft=false 并在 notes 里说明 */
export interface ProcurementProposal {
  materialTypeId: string;
  materialName: string | null;
  unit: string | null;
  gapQty: number;
  onOrderQty: number;
  suggestedQty: number;
  supplierId: string | null;
  supplierMaterialId: string | null;
  purchaseUnit: string | null;
  minOrderQty: number | null;
  leadTimeDays: number | null;
  expectedDeliveryDate: string | null;
  canDraft: boolean;
  notes: string[];
  existingPurchaseOrderId: string | null;
  existingPurchaseOrderNumber: string | null;
}

export interface ProcurementDraftResult {
  purchaseOrderId: string;
  purchaseOrderNumber: string;
  reused: boolean;
  quantity: number;
  unit: string;
  notes: string[];
}

export type ShortageCaseDecision = 'CANCEL' | 'MANUAL' | 'CLOSE';

export interface ShortageCaseAction {
  id: number;
  actionType: 'PURCHASE_DRAFT' | 'DECISION';
  materialTypeId: string | null;
  status: 'DONE' | 'SUPERSEDED';
  resultRef: string | null;
  quantity: number | null;
  unit: string | null;
  decision: ShortageCaseDecision | null;
  reason: string | null;
  actorType: string;
  actorId: number | null;
  createdAt: string;
}

export function getProcurementProposals(factoryId: string, caseId: string): Promise<ApiResponse<ProcurementProposal[]>> {
  return get(`/${factoryId}/shortage-cases/${caseId}/procurement-proposals`);
}

export function createProcurementDraft(
  factoryId: string,
  caseId: string,
  materialTypeId: string,
): Promise<ApiResponse<ProcurementDraftResult>> {
  return post(`/${factoryId}/shortage-cases/${caseId}/procurement-drafts`, { materialTypeId });
}

export function decideShortageCase(
  factoryId: string,
  caseId: string,
  body: { decision: ShortageCaseDecision; reason: string; ownerUserId?: number },
): Promise<ApiResponse<ShortageCaseStage>> {
  return post(`/${factoryId}/shortage-cases/${caseId}/decision`, body);
}

export function getShortageCaseActions(factoryId: string, caseId: string): Promise<ApiResponse<ShortageCaseAction[]>> {
  return get(`/${factoryId}/shortage-cases/${caseId}/actions`);
}
