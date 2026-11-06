import { get, post } from './request';
import type { ApiResponse } from '@/types/api';

export type ProcessParamCaseStage = 'DETECTED' | 'EXECUTING' | 'CLOSED' | string;
export type ProcessParamCaseStatus = 'LOW' | 'HIGH' | string;
export type ProcessParamCaseResolution = 'REASONABLE' | 'CORRECTED' | 'STANDARD_UPDATE' | 'DATA_ERROR';

export interface ProcessParamCaseView {
  id: string;
  sourceKey: string;
  sourceKind: string;
  processName: string | null;
  batchNumber: string | null;
  paramKey: string;
  paramLabel: string | null;
  unit: string | null;
  status: ProcessParamCaseStatus;
  stage: ProcessParamCaseStage;
  stageLabel: string;
  ownerRole: string | null;
  ownerUserId: number | null;
  ownerName: string | null;
  version: number;
  observedAt: string;
  closedAt: string | null;
  createdAt: string;
  lastRefreshedAt: string | null;
}

export interface ProcessParamCaseDetail {
  caseView: ProcessParamCaseView;
  sourceReportId: number | null;
  productionBatchId: number | null;
  productionPlanId: string | null;
  workProcessTaskId: number | null;
  processEntryStepKey: string | null;
  productTypeId: string | null;
  measuredValue: number | null;
  standardValue: number | null;
  rangeMin: number | null;
  rangeMax: number | null;
  resolutionCode: ProcessParamCaseResolution | null;
  resolutionNote: string | null;
  resolvedBy: number | null;
  resolvedAt: string | null;
}

export interface ProcessParamCaseAssignee {
  id: number;
  name: string;
  role: string;
}

export interface ProcessParamCaseListParams {
  openOnly?: boolean;
  page?: number;
  size?: number;
}

const BASE = (factoryId: string) => `/${factoryId}/quality/process-param-deviation/cases`;

export function listProcessParamCases(
  factoryId: string,
  params: ProcessParamCaseListParams = {},
): Promise<ApiResponse<ProcessParamCaseView[]>> {
  return get<ProcessParamCaseView[]>(BASE(factoryId), {
    params: { openOnly: true, page: 0, size: 50, ...params },
  });
}

export function listProcessParamCaseAssignees(
  factoryId: string,
): Promise<ApiResponse<ProcessParamCaseAssignee[]>> {
  return get<ProcessParamCaseAssignee[]>(`${BASE(factoryId)}/assignees`);
}

export function getProcessParamCase(
  factoryId: string,
  caseId: string,
): Promise<ApiResponse<ProcessParamCaseDetail>> {
  return get<ProcessParamCaseDetail>(`${BASE(factoryId)}/${encodeURIComponent(caseId)}`);
}

export function assignProcessParamCase(
  factoryId: string,
  caseId: string,
  body: { ownerUserId: number; version: number },
): Promise<ApiResponse<null>> {
  return post<null>(`${BASE(factoryId)}/${encodeURIComponent(caseId)}/assign`, body);
}

export function startProcessParamCaseVerify(
  factoryId: string,
  caseId: string,
  body: { version: number },
): Promise<ApiResponse<null>> {
  return post<null>(`${BASE(factoryId)}/${encodeURIComponent(caseId)}/start-verify`, body);
}

export function resolveProcessParamCase(
  factoryId: string,
  caseId: string,
  body: { resolution: ProcessParamCaseResolution; note: string; version: number },
): Promise<ApiResponse<null>> {
  return post<null>(`${BASE(factoryId)}/${encodeURIComponent(caseId)}/resolve`, body);
}
