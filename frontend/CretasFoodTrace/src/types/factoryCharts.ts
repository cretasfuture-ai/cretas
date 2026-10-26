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
  detailContext?: FactoryChartDetailContext;
}

export interface FactoryChartDetailContext { id: string; expiresAt: string; drilldown?: boolean; }
export interface FactoryChartDetailFilter { field: string; value: string | number | boolean | null; }
export interface FactoryChartDetailsRequest { context_id: string; page?: number; page_size?: number; filters?: FactoryChartDetailFilter[]; point?: { name: string; seriesName?: string; dataIndex?: number }; }
export interface FactoryChartDetailsData {
  factoryId: string; candidateId: string; title: string; source: string; period: string;
  columns: string[]; rows: Record<string, unknown>[]; total: number; page: number; pageSize: number;
  hasMore: boolean; appliedFilters: FactoryChartDetailFilter[]; availableFilters: Array<{ field: string; label: string; kind: string }>;
  snapshotAt: string | number;
}
export interface FactoryChartDetailsResponse { success: boolean; data?: FactoryChartDetailsData; message?: string; }

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


export interface FactoryChartSelectionData { factoryId: string; source: FactoryChartSource; selectedIds: string[] | null; updatedAt: string | null; }
export interface FactoryChartSelectionResponse { success: boolean; data: FactoryChartSelectionData; message?: string; }
export interface FactoryChartUpload { id: number; fileName: string; }
export type FactoryChartExportFormat = 'excel' | 'pdf' | 'ppt';
export interface FactoryChartExportFile { uri: string; name: string; mimeType: string; size: number; factoryId: string; userId: string; role: string; }
export interface FactoryChartExportContext { factoryId: string; source: FactoryChartSource; sourceLabel: string; periodLabel: string; selectedIds: readonly string[]; year?: number; periodType?: string; startMonth?: number; endMonth?: number; }
export interface FactoryChartExportPayload { factory_id: string; charts: Array<Record<string, unknown>>; chart_images: Record<string, string>; analysis_results: Record<string, string>; chart_titles?: Record<string, string>; period_label?: string; report_title?: string; company_name?: string; year?: number; period_type?: string; start_month?: number; end_month?: number; }
export interface FactoryChartPoint { name: string; seriesName?: string; dataIndex?: number; }
