import request from '@/api/request';
import type { ApiResponse } from '@/types/api';
import type { FactoryChartPeriodType, FactoryChartSource, FactoryRecommendation, FactorySourceError, FactoryJointUploads } from './factory-charts';

/** Fixed public request for the authenticated factory conversation endpoint. */
export interface FactoryAnalysisRequest {
  question: string;
  source?: FactoryChartSource;
  uploadId?: number;
  inspectionUploadId?: number;
  costUploadId?: number;
  year?: number;
  periodType?: FactoryChartPeriodType;
  startMonth?: number;
  endMonth?: number;
  analysisSubject?: string | null;
  contextId?: string;
}

export interface FactoryAnalysisCatalogItem {
  id: string;
  familyId: string;
  chartType: string;
  domain: string;
  metric: string;
  unit: string;
  priority?: string;
  sourceKey?: string;
}

export interface FactoryAnalysisFact {
  id: string;
  candidateId: string;
  label: string;
  value: string | number | boolean | null;
  unit: string;
  source: string;
}

/** Structured period returned with every answer; labels are display-only. */
export interface FactoryAnalysisPeriodSnapshot {
  label: string;
  year: number;
  periodType: FactoryChartPeriodType;
  startMonth: number;
  endMonth: number;
}

export interface FactoryAnalysisData {
  factoryId: string;
  source: FactoryChartSource;
  uploadId?: number | null;
  jointUploads?: FactoryJointUploads | null;
  sourceLabel?: string;
  period: FactoryAnalysisPeriodSnapshot;
  analysisSubject?: string | null;
  analysisScopeLabel?: string;
  question: string;
  status: 'answered' | 'clarification' | 'unavailable';
  answer: string;
  selectedIds: string[];
  recommendations: FactoryRecommendation[];
  catalog: FactoryAnalysisCatalogItem[];
  facts: FactoryAnalysisFact[];
  limitations: string[];
  sourceErrors: FactorySourceError[];
  followUpQuestions: string[];
  contextId: string;
  subjects: Array<{ id: string; label: string }>;
}

export type FactoryAnalysisResponse = ApiResponse<FactoryAnalysisData>;

/**
 * Sends only the fixed, tenant-bound contract. Factory/user/role authority is
 * established by the authenticated Java request and is deliberately absent
 * from this body.
 */
export async function postFactoryAnalysis(
  factoryId: string,
  payload: FactoryAnalysisRequest,
  signal?: AbortSignal,
): Promise<FactoryAnalysisResponse> {
  return await request.post<FactoryAnalysisData>(
    `/${encodeURIComponent(factoryId)}/smart-bi/factory-analysis`,
    payload,
    { timeout: 120_000, signal },
  );
}
