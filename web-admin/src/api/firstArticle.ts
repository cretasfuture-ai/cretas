/**
 * 工序首检 (原型统一 E 线 BL17)。后端: /api/mobile/{factoryId}/first-article (FirstArticleController)。
 * 设计: docs/specs/process-first-article-inspection-design.md。
 * 写操作 (存标准 / 判定) 先调对应 preview 接口拿服务端结论, 用户确认后再写。
 */
import { get, post, put } from './request';
import type { ApiResponse } from '@/types/api';

export type FirstArticleMode = 'OFF' | 'WARN' | 'BLOCK';
export type FirstArticleStatus = 'PENDING' | 'PASSED' | 'FAILED' | 'VOID';
export type FirstArticleTaskStatus = 'NOT_REQUESTED' | 'PENDING' | 'PASSED' | 'FAILED';
export type Verdict = 'PASSED' | 'FAILED';
export type ItemType = 'NUMERIC' | 'OPTION';

/** 后端 BigDecimal 序列化成 number; null = 没有这个数 (不是 0) */
type Num = number | null;

export interface FirstArticleItem {
  key: string | null;
  name: string;
  type: ItemType;
  required: boolean | null;
  unit: string | null;
  lower: Num;
  upper: Num;
  target: Num;
  options: string[] | null;
  passOptions: string[] | null;
}

export interface FirstArticleItemResult {
  key: string;
  name: string;
  type: ItemType;
  required: boolean;
  unit: string | null;
  numericValue: Num;
  optionValue: string | null;
  /** null = 选检项没填 */
  pass: boolean | null;
  rule: string;
}

export interface PolicyView { mode: FirstArticleMode; modeLabel: string; configured: boolean }

export interface StandardView {
  id: number;
  workProcessId: string;
  processName: string | null;
  name: string;
  items: FirstArticleItem[];
  active: boolean;
  revision: number;
  deactivatedReason: string | null;
  updatedAt: string | null;
}

export interface StandardRequest {
  id: number | null;
  workProcessId: string | null;
  name: string;
  items: FirstArticleItem[];
}

export interface StandardPreview {
  action: 'CREATE' | 'REVISE';
  workProcessId: string;
  processName: string | null;
  name: string;
  items: FirstArticleItem[];
  ruleTexts: string[];
  nextRevision: number;
}

export interface TaskRow {
  taskId: number;
  productionBatchId: number;
  batchNumber: string | null;
  workProcessId: string;
  processName: string | null;
  productTypeId: string;
  taskStatus: string;
  taskStatusLabel: string;
  actualStartAt: string | null;
  standardId: number;
  standardName: string;
  firstArticleStatus: FirstArticleTaskStatus;
  firstArticleStatusLabel: string;
  latestInspectionId: number | null;
}

export interface InspectionView {
  id: number;
  taskId: number;
  productionBatchId: number;
  workProcessId: string;
  processName: string | null;
  standardId: number;
  standardRevision: number;
  standardName: string;
  standardItems: FirstArticleItem[];
  status: FirstArticleStatus;
  statusLabel: string;
  sampleQuantity: Num;
  sampleUnit: string | null;
  requestNotes: string | null;
  requestedBy: number | null;
  requestedAt: string | null;
  results: FirstArticleItemResult[] | null;
  autoVerdict: Verdict | null;
  judgedBy: number | null;
  judgedAt: string | null;
  judgeNotes: string | null;
  voidReason: string | null;
  voidedAt: string | null;
}

export interface ResultInput { key: string; numericValue: number | null; optionValue: string | null }
export interface JudgeRequest { results: ResultInput[]; verdict: Verdict | null; notes: string | null }
export interface JudgePreview {
  inspectionId: number;
  results: FirstArticleItemResult[];
  autoVerdict: Verdict | null;
  failedItems: string[];
  verdict: Verdict | null;
  canSubmit: boolean;
  message: string;
}

export interface Page<T> { content: T[]; totalElements: number; page: number; size: number }

const base = (factoryId: string) => `/${factoryId}/first-article`;

export const getPolicy = (f: string): Promise<ApiResponse<PolicyView>> => get(`${base(f)}/policy`);
export const updatePolicy = (f: string, mode: FirstArticleMode): Promise<ApiResponse<PolicyView>> =>
  put(`${base(f)}/policy`, { mode });

export const listStandards = (f: string, includeInactive = false): Promise<ApiResponse<StandardView[]>> =>
  get(`${base(f)}/standards`, { params: { includeInactive } });
export const previewStandard = (f: string, body: StandardRequest): Promise<ApiResponse<StandardPreview>> =>
  post(`${base(f)}/standards/preview`, body);
export const saveStandard = (f: string, body: StandardRequest): Promise<ApiResponse<StandardView>> =>
  post(`${base(f)}/standards`, body);
export const deactivateStandard = (f: string, id: number, reason: string): Promise<ApiResponse<StandardView>> =>
  post(`${base(f)}/standards/${id}/deactivate`, { reason });

export const listTasks = (f: string, q: { onlyInProgress: boolean; page: number; size: number }):
  Promise<ApiResponse<Page<TaskRow>>> => get(`${base(f)}/tasks`, { params: q });

export const listInspections = (f: string, q: { status?: FirstArticleStatus | null; taskId?: number | null; page: number; size: number }):
  Promise<ApiResponse<Page<InspectionView>>> => {
  const params: Record<string, string | number> = { page: q.page, size: q.size };
  if (q.status) params.status = q.status;
  if (q.taskId) params.taskId = q.taskId;
  return get(`${base(f)}/inspections`, { params });
};
export const getInspection = (f: string, id: number): Promise<ApiResponse<InspectionView>> =>
  get(`${base(f)}/inspections/${id}`);
export const requestInspection = (f: string, body: { taskId: number; sampleQuantity: number | null; sampleUnit: string | null; notes: string | null }):
  Promise<ApiResponse<InspectionView>> => post(`${base(f)}/inspections`, body);
export const previewJudge = (f: string, id: number, body: JudgeRequest): Promise<ApiResponse<JudgePreview>> =>
  post(`${base(f)}/inspections/${id}/judge/preview`, body);
export const judgeInspection = (f: string, id: number, body: JudgeRequest): Promise<ApiResponse<InspectionView>> =>
  post(`${base(f)}/inspections/${id}/judge`, body);
export const voidInspection = (f: string, id: number, reason: string): Promise<ApiResponse<InspectionView>> =>
  post(`${base(f)}/inspections/${id}/void`, { reason });

export const MODE_LABEL: Record<FirstArticleMode, string> = {
  OFF: '关', WARN: '提示', BLOCK: '拦截',
};
export const MODE_HINT: Record<FirstArticleMode, string> = {
  OFF: '不查首检，开工和报完工与以前一样。',
  WARN: '要首检的工序还没首检合格时，开工、报完工照常，只给一句提示。',
  BLOCK: '要首检的工序首检合格前，不能报完工；开工不拦。',
};
export const TASK_FA_COLOR: Record<FirstArticleTaskStatus, 'neutral' | 'warning' | 'success' | 'error'> = {
  NOT_REQUESTED: 'neutral', PENDING: 'warning', PASSED: 'success', FAILED: 'error',
};
export const STATUS_COLOR: Record<FirstArticleStatus, 'neutral' | 'warning' | 'success' | 'error'> = {
  PENDING: 'warning', PASSED: 'success', FAILED: 'error', VOID: 'neutral',
};
export const STATUS_LABEL: Record<FirstArticleStatus, string> = {
  PENDING: '待判定', PASSED: '首检合格', FAILED: '首检不合格', VOID: '已作废',
};
