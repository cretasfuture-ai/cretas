/**
 * 交付风险异常: 可能延期的销售订单, 与缺料同一种处理实例。
 * 只生成处理任务和记录结果, 不改排程、不下采购。level = UNKNOWN 表示数据不够判断, 不是正常。
 */
import { get, post } from './request';
import type { ApiResponse } from '@/types/api';

export type RiskLevel = 'RISK' | 'UNKNOWN' | 'OK';

export interface OrderAssessment {
  salesOrderId: string;
  orderNumber: string | null;
  customerName: string | null;
  orderStatusLabel: string | null;
  dueDate: string | null;
  level: RiskLevel;
  signals: { code: string; label: string }[];
  evidence: string[];
  unknowns: string[];
  impact: string[];
  suggestions: string[];
  /** null = 算不出 */
  estimatedDaysNeeded: number | null;
  daysLeft: number | null;
}

export interface ScanResult {
  scanned: number;
  truncated: boolean;
  risk: number;
  unknown: number;
  ok: number;
  created: number;
  refreshed: number;
  orders: OrderAssessment[];
}

export interface DeliveryRiskCase {
  id: string;
  salesOrderId: string;
  orderNumber: string | null;
  customerName: string | null;
  dueDate: string | null;
  riskLevel: RiskLevel | null;
  stage: string;
  stageLabel: string;
  ownerRole: string;
  ownerUserId: number | null;
  ownerName: string | null;
  assessedAt: string | null;
  closedAt: string | null;
  createdAt: string;
}

export interface RiskAction {
  id: number;
  actionType: string;
  decision: string | null;
  reason: string | null;
  createdAt: string;
}

export interface RiskEvent {
  eventType: string;
  triggerReason: string;
  detail: string | null;
  createdAt: string;
}

export interface RiskCheck {
  id: number;
  verdict: string;
  verdictLabel: string;
  detail: string;
  createdAt: string;
}

export interface DeliveryRiskDetail {
  case: DeliveryRiskCase;
  assessment: OrderAssessment;
  actions: RiskAction[];
  events: RiskEvent[];
  checks: RiskCheck[];
}

export interface Assignee {
  id: number;
  name: string;
  role: string | null;
}

const base = (factoryId: string) => `/${factoryId}/delivery-risks`;

export function listDeliveryRisks(factoryId: string, openOnly: boolean): Promise<ApiResponse<DeliveryRiskCase[]>> {
  return get(base(factoryId), { params: { openOnly, size: 100 } });
}
export function scanDeliveryRisks(factoryId: string): Promise<ApiResponse<ScanResult>> {
  return post(`${base(factoryId)}/scan`);
}
export function getDeliveryRisk(factoryId: string, caseId: string): Promise<ApiResponse<DeliveryRiskDetail>> {
  return get(`${base(factoryId)}/${caseId}`);
}
export function listAssignees(factoryId: string): Promise<ApiResponse<Assignee[]>> {
  return get(`${base(factoryId)}/assignees`);
}
export function assignDeliveryRisk(factoryId: string, caseId: string, ownerUserId: number, task: string): Promise<ApiResponse<DeliveryRiskCase>> {
  return post(`${base(factoryId)}/${caseId}/assign`, { ownerUserId, task });
}
export function recheckDeliveryRisk(factoryId: string, caseId: string): Promise<ApiResponse<OrderAssessment>> {
  return post(`${base(factoryId)}/${caseId}/recheck`);
}
export function finishDeliveryRisk(factoryId: string, caseId: string, decision: 'CLOSE' | 'CANCEL', result: string): Promise<ApiResponse<RiskCheck>> {
  return post(`${base(factoryId)}/${caseId}/finish`, { decision, result });
}
