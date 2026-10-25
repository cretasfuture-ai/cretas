import { pythonFetch, PYTHON_LLM_TIMEOUT_MS } from './common';
import { exportExcel, exportPDF, exportPPT, type ExcelExportRequest, type PDFExportRequest, type PPTExportRequest } from './financial-dashboard';

export type FactoryChartSource = 'upload' | 'business';
export type FactoryChartPeriodType = 'all' | 'year' | 'month_range';

export interface FactoryRecommendationRequest {
  source: FactoryChartSource;
  upload_id?: number;
  factory_id?: string;
  year: number;
  period_type: FactoryChartPeriodType;
  start_month: number;
  end_month: number;
  include_optional: boolean;
  use_ai: boolean;
}

export interface FactoryChartKpi { label: string; value: number | string | null; unit: string; }
export interface FactoryChartFindings { good: string[]; attention: string[]; suggestions: string[]; }

export interface FactoryRecommendation {
  id: string;
  title: string;
  metric: string;
  domain: string;
  priority: 'required' | 'optional';
  reason: string;
  aiReason?: string;
  source: string;
  unit: string;
  findings: FactoryChartFindings;
  chart: { chartType: string; echartsOption: Record<string, unknown> };
  kpis: FactoryChartKpi[];
  /** Financial candidates may return a structured table object; rendering is intentionally deferred. */
  tableData: Record<string, unknown> | Array<Record<string, unknown> | { label: string; value: number | string | null }> | null;
}

export interface FactoryUnavailableChart { id: string; title: string; missing: string[]; }
export interface FactorySourceError { source: string; message: string; }

export interface FactoryRecommendationsData {
  factoryId: string;
  source: FactoryChartSource;
  sourceLabel: string;
  period: { label: string };
  defaultSelection?: string[];
  ranking: { mode: 'ai' | 'rule'; reason: string };
  recommendations: FactoryRecommendation[];
  unavailable: FactoryUnavailableChart[];
  sourceErrors: FactorySourceError[];
}

export interface FactoryRecommendationsResponse {
  success: boolean;
  data: FactoryRecommendationsData;
  message?: string;
}

/** The server qualifies candidates and computes all values; the browser only renders them. */
export async function getFactoryChartRecommendations(
  payload: FactoryRecommendationRequest,
): Promise<FactoryRecommendationsResponse> {
  return await pythonFetch<FactoryRecommendationsResponse>(
    '/api/smartbi/financial-dashboard/factory-recommendations',
    {
      method: 'POST',
      body: JSON.stringify(payload),
      timeoutMs: PYTHON_LLM_TIMEOUT_MS,
      preserveResponseKeys: true,
    },
  );
}

export interface FactoryChartSelectionResponse {
  success: boolean;
  data: { factoryId: string; source: FactoryChartSource; selectedIds: string[] | null; updatedAt: string | null };
  message?: string;
}

/** Selection is server scoped to the authenticated user, factory, and source. */
export async function getFactoryChartSelection(factoryId: string, source: FactoryChartSource): Promise<FactoryChartSelectionResponse> {
  return await pythonFetch<FactoryChartSelectionResponse>(
    `/api/smartbi/financial-dashboard/factory-selection?factory_id=${encodeURIComponent(factoryId)}&source=${source}`,
    { method: 'GET', timeoutMs: PYTHON_LLM_TIMEOUT_MS, preserveResponseKeys: true },
  );
}

export async function saveFactoryChartSelection(
  factoryId: string,
  source: FactoryChartSource,
  selectedIds: readonly string[],
): Promise<FactoryChartSelectionResponse> {
  return await pythonFetch<FactoryChartSelectionResponse>(
    '/api/smartbi/financial-dashboard/factory-selection',
    {
      method: 'PUT',
      body: JSON.stringify({ factory_id: factoryId, source, selected_ids: [...selectedIds] }),
      timeoutMs: PYTHON_LLM_TIMEOUT_MS,
      preserveResponseKeys: true,
    },
  );
}

export type FactoryChartExportFormat = 'excel' | 'pdf' | 'ppt';
export type FactoryChartExportRequest = ExcelExportRequest | PDFExportRequest | PPTExportRequest;

/** Reuse the financial dashboard export endpoints; the caller validates the binary before saving. */
export async function exportFactoryCharts(format: FactoryChartExportFormat, payload: FactoryChartExportRequest): Promise<Blob | null> {
  if (format === 'excel') return exportExcel(payload as ExcelExportRequest);
  if (format === 'pdf') return exportPDF(payload as PDFExportRequest);
  return exportPPT(payload as PPTExportRequest);
}
