/**
 * 质检处置 API 客户端
 *
 * 功能：
 * 1. 评估质检处置建议
 * 2. 获取可用处置动作
 * 3. 执行处置动作
 * 4. 查询处置历史
 * 5. 配置处置规则
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */

import { apiClient } from './apiClient';
import {
  DispositionAction,
  DispositionEvaluation,
  DispositionResult,
  DispositionHistory,
  ExecuteDispositionRequest,
  InspectionSummary,
  AlternativeAction,
} from '../../types/qualityDisposition';

// Re-export types for convenience
export {
  DispositionAction,
  DispositionEvaluation,
  DispositionResult,
  DispositionHistory,
  ExecuteDispositionRequest,
  InspectionSummary,
  AlternativeAction,
};

// ==================== 额外类型定义 ====================

/**
 * 质检结果（用于评估请求）
 */
export interface QualityCheckResult {
  inspectionId: string;
  productionBatchId: number;
  inspectorId: number;
  inspectionDate?: string;
  sampleSize: number;
  passCount: number;
  failCount: number;
  passRate?: number;
  result?: 'PASS' | 'FAIL' | 'CONDITIONAL';
  notes?: string;
}

/**
 * 处置动作详情
 */
export interface DispositionActionInfo {
  actionCode: DispositionAction;
  actionName: string;
  description: string;
  requiresApproval: boolean;
  approvalLevel?: 'SUPERVISOR' | 'MANAGER' | 'QUALITY_HEAD' | 'FACTORY_MANAGER';
  applicableCondition?: string;
}

/**
 * 创建处置规则请求
 */
export interface CreateDispositionRuleRequest {
  ruleName: string;
  description?: string;
  minPassRate: number;
  maxDefectRate?: number;
  action: DispositionAction;
  requiresApproval: boolean;
  approvalLevel?: 'SUPERVISOR' | 'MANAGER' | 'QUALITY_HEAD' | 'FACTORY_MANAGER';
  priority?: number;
  enabled?: boolean;
}

/**
 * 处置规则
 */
export interface DispositionRule {
  id: string;
  factoryId: string;
  ruleName: string;
  description?: string;
  minPassRate: number;
  maxDefectRate?: number;
  action: DispositionAction;
  requiresApproval: boolean;
  approvalLevel?: string;
  priority: number;
  version: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * 待处置的质检记录 (GET /processing/quality/inspections?result=FAIL,CONDITIONAL)。
 * 后端按行返回 batchNumber / productName (见 ProcessingServiceImpl#convertInspectionToMap)。
 */
export interface DispositionCandidate {
  id: string;
  productionBatchId: number;
  inspectorId: number;
  inspectorName?: string | null;
  inspectionDate?: string | null;
  sampleSize: number;
  passCount: number;
  failCount: number;
  passRate?: number | null;
  result: string;
  notes?: string | null;
  batchNumber?: string | null;
  productName?: string | null;
}

/** GET /quality-disposition/pending 单条 (等待审批的处置申请)。 */
export interface PendingDisposition {
  id: string;
  entityId: string;
  productionBatchId?: number | null;
  batchNumber?: string | null;
  decisionMade: string;
  actionDescription?: string | null;
  reason?: string | null;
  executorId?: number | null;
  executorName?: string | null;
  approvalLevel?: string | null;
  createdAt: string;
}

/** POST /quality-disposition/{id}/approve 请求体 (与网页同一份)。 */
export interface ApproveDispositionRequest {
  approved: boolean;
  approverId: number;
  approverName?: string;
  comment?: string;
  /** 申请人就是审批人、且本厂允许本人审批时必填。 */
  selfApprovalReason?: string;
}

export interface ApproveDispositionResult {
  applicationId: string;
  approved: boolean;
  message: string;
  finishedGoodsHeld?: number | null;
}

interface Envelope<T> {
  success: boolean;
  data?: T | null;
  message?: string;
}

function unwrap<T>(res: Envelope<T>, fallback: string): T {
  if (!res || !res.success || res.data == null) {
    throw new Error(res?.message || fallback);
  }
  return res.data;
}

// ==================== API 客户端 ====================

/**
 * 质检处置 API
 */
export const qualityDispositionAPI = {
  /**
   * 评估质检处置建议
   *
   * @param factoryId 工厂ID
   * @param qualityResult 质检结果
   * @returns 处置评估结果
   */
  async evaluateDisposition(
    factoryId: string,
    qualityResult: QualityCheckResult
  ): Promise<DispositionEvaluation> {
    const response = await apiClient.post<{ code: number; data: DispositionEvaluation; message: string; success: boolean }>(
      `/api/mobile/${factoryId}/quality-disposition/evaluate`,
      qualityResult
    );
    return response.data;
  },

  /**
   * 获取可用的处置动作列表
   *
   * @param factoryId 工厂ID
   * @returns 处置动作列表
   */
  async getAvailableActions(factoryId: string): Promise<DispositionActionInfo[]> {
    const response = await apiClient.get<{ code: number; data: DispositionActionInfo[]; message: string; success: boolean }>(
      `/api/mobile/${factoryId}/quality-disposition/actions`
    );
    return response.data || [];
  },

  /**
   * 执行处置动作
   *
   * @param factoryId 工厂ID
   * @param request 执行处置请求
   * @returns 处置执行结果
   */
  async executeDisposition(
    factoryId: string,
    request: ExecuteDispositionRequest
  ): Promise<DispositionResult> {
    const response = await apiClient.post<{ code: number; data: DispositionResult; message: string; success: boolean }>(
      `/api/mobile/${factoryId}/quality-disposition/execute`,
      request
    );
    return response.data;
  },

  /**
   * 获取处置历史
   *
   * @param factoryId 工厂ID
   * @param batchId 生产批次ID
   * @returns 处置历史列表
   */
  async getDispositionHistory(
    factoryId: string,
    batchId: number
  ): Promise<DispositionHistory[]> {
    const response = await apiClient.get<{ code: number; data: DispositionHistory[]; message: string; success: boolean }>(
      `/api/mobile/${factoryId}/quality-disposition/history/${batchId}`
    );
    return response.data || [];
  },

  /**
   * 创建处置规则
   *
   * @param factoryId 工厂ID
   * @param request 规则创建请求
   * @returns 创建的规则
   */
  async createRule(
    factoryId: string,
    request: CreateDispositionRuleRequest
  ): Promise<DispositionRule> {
    const response = await apiClient.post<{ code: number; data: DispositionRule; message: string; success: boolean }>(
      `/api/mobile/${factoryId}/quality-disposition/rules`,
      request
    );
    return response.data;
  },

  /**
   * 获取处置规则列表
   *
   * @param factoryId 工厂ID
   * @returns 规则列表
   */
  /**
   * 待处置质检记录: 不合格 + 有条件放行 (与网页「待处置质检」同一个过滤)。
   */
  async listDispositionCandidates(factoryId: string): Promise<DispositionCandidate[]> {
    const res = await apiClient.get<Envelope<{ content?: DispositionCandidate[] }>>(
      `/api/mobile/${factoryId}/processing/quality/inspections`,
      { params: { page: 1, size: 100, result: 'FAIL,CONDITIONAL' } }
    );
    const rows = unwrap(res, '待处置批次读取失败').content ?? [];
    return [...rows].sort((a, b) => String(b.inspectionDate ?? '').localeCompare(String(a.inspectionDate ?? '')));
  },

  /** 等待审批的处置申请 (只读列表, 谁都能看; 审批按钮另按 approval_quality_disposition 判)。 */
  async getPendingDispositions(factoryId: string): Promise<PendingDisposition[]> {
    const res = await apiClient.get<Envelope<PendingDisposition[]>>(
      `/api/mobile/${factoryId}/quality-disposition/pending`
    );
    return unwrap(res, '待审批处置读取失败');
  },

  /** 批准 / 驳回 (后端 @RequirePermission approval_quality_disposition:approve)。 */
  async approveDisposition(
    factoryId: string,
    id: string,
    request: ApproveDispositionRequest
  ): Promise<ApproveDispositionResult> {
    const res = await apiClient.post<Envelope<ApproveDispositionResult>>(
      `/api/mobile/${factoryId}/quality-disposition/${encodeURIComponent(id)}/approve`,
      request
    );
    return unwrap(res, '审批处置失败');
  },

  async getRules(factoryId: string): Promise<DispositionRule[]> {
    const response = await apiClient.get<{ code: number; data: DispositionRule[]; message: string; success: boolean }>(
      `/api/mobile/${factoryId}/quality-disposition/rules`
    );
    return response.data || [];
  },
};

// Re-export helper functions from types for convenience
export {
  getActionLabel,
  getActionColor,
  getActionIcon,
  getStatusLabel,
  formatConfidence,
} from '../../types/qualityDisposition';

export default qualityDispositionAPI;
