import type { FactoryChartPeriodType, FactoryChartSource, FactoryRecommendation } from './factoryCharts';
import type { FactoryChartPeriodSelection } from '../utils/smartbiFactoryChartModel';

export interface FactoryAnalysisRequest {
  question: string;
  source?: FactoryChartSource;
  uploadId?: number;
  year?: number;
  periodType?: FactoryChartPeriodType;
  startMonth?: number;
  endMonth?: number;
  analysisSubject?: string;
  contextId?: string;
}

export type FactoryAnalysisStatus = 'answered' | 'clarification' | 'unavailable';
export type FactoryAnalysisFactValue = string | number | boolean | null;

export interface FactoryAnalysisFact {
  id: string;
  candidateId: string;
  label: string;
  value: FactoryAnalysisFactValue;
  unit: string;
  source: string;
}

export interface FactoryAnalysisCatalogItem {
  id: string;
  familyId: string;
  chartType: string;
  domain: string;
  metric: string;
  unit: string;
  priority: string;
  sourceKey: string;
}

export interface FactoryAnalysisSourceError {
  source: string;
  message: string;
}

export interface FactoryAnalysisPeriod extends FactoryChartPeriodSelection {
  label: string;
}

export interface FactoryAnalysisData {
  factoryId: string;
  source: FactoryChartSource;
  sourceLabel?: string;
  period?: FactoryAnalysisPeriod;
  analysisSubject?: string | null;
  analysisScopeLabel?: string;
  question: string;
  status: FactoryAnalysisStatus;
  answer: string;
  selectedIds: string[];
  recommendations: FactoryRecommendation[];
  catalog: FactoryAnalysisCatalogItem[];
  facts: FactoryAnalysisFact[];
  limitations: string[];
  sourceErrors: FactoryAnalysisSourceError[];
  followUpQuestions: string[];
  contextId: string;
  subjects: Array<{ id: string; label: string }>;
}

export interface FactoryAnalysisResponse {
  success: boolean;
  data?: FactoryAnalysisData;
  message?: string;
}
