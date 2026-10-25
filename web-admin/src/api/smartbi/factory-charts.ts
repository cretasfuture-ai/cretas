import { pythonFetch, PYTHON_LLM_TIMEOUT_MS } from './common';

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
  tableData: Record<string, unknown> | Array<{ label: string; value: number | string | null }> | null;
}

export interface FactoryUnavailableChart { id: string; title: string; missing: string[]; }
export interface FactorySourceError { source: string; message: string; }

export interface FactoryRecommendationsData {
  factoryId: string;
  source: FactoryChartSource;
  sourceLabel: string;
  period: { label: string };
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
