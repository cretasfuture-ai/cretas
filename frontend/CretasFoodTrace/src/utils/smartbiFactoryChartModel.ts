import type {
  FactoryChartDetailFilter,
  FactoryChartDetailsData,
  FactoryChartDetailsRequest,
  FactoryChartPoint,
  FactoryChartPeriodType,
  FactoryChartSource,
  FactoryRecommendation,
  FactoryRecommendationRequest,
} from '../types/factoryCharts';

export interface FactoryChartPeriodSelection {
  year: number;
  periodType: FactoryChartPeriodType;
  startMonth: number;
  endMonth: number;
}

export interface FactoryChartContextKey {
  factoryId: string;
  source: FactoryChartSource;
  periodLabel: string;
  authEpoch: string;
}

export function normalizeSelectedIds(value: readonly string[] | null | undefined): string[] | null {
  return value == null ? null : [...value];
}

export function isValidPeriod(period: FactoryChartPeriodSelection): boolean {
  return Number.isInteger(period.year)
    && period.year >= 1900 && period.year <= 2100
    && Number.isInteger(period.startMonth)
    && Number.isInteger(period.endMonth)
    && period.startMonth >= 1
    && period.startMonth <= 12
    && period.endMonth >= 1
    && period.endMonth <= 12
    && (period.periodType === 'month_range' || (period.startMonth === 1 && period.endMonth === 12))
    && (period.periodType !== 'month_range' || period.startMonth <= period.endMonth);
}

export function buildRecommendationRequest(
  source: FactoryChartSource,
  period: FactoryChartPeriodSelection,
  includeOptional: boolean,
  useAi: boolean,
  uploadId?: number,
  guided = false,
  analysisSubject: string | null = null,
): FactoryRecommendationRequest {
  if (!isValidPeriod(period)) {
    throw new Error('请选择有效的时间范围');
  }
  return {
    source,
    ...(source === 'upload' && uploadId != null ? { upload_id: uploadId } : {}),
    year: period.year,
    period_type: period.periodType,
    start_month: period.startMonth,
    end_month: period.endMonth,
    include_optional: includeOptional,
    use_ai: useAi,
    guided,
    analysis_subject: analysisSubject,
  };
}

export function selectRecommendations(
  recommendations: readonly FactoryRecommendation[],
  selectedIds: readonly string[] | null,
): FactoryRecommendation[] {
  if (selectedIds == null) return [...recommendations];
  const selected = new Set(selectedIds);
  return recommendations.filter(item => selected.has(item.id));
}

export function buildDetailsRequest(
  contextId: string,
  point: FactoryChartPoint | undefined,
  page: number,
  filters: readonly FactoryChartDetailFilter[] = [],
): FactoryChartDetailsRequest {
  if (!contextId.trim()) throw new Error('缺少明细上下文');
  if (!Number.isInteger(page) || page < 1) throw new Error('明细页码无效');
  return {
    context_id: contextId,
    page,
    page_size: 50,
    ...(filters.length > 0 ? { filters: [...filters] } : {}),
    ...(point ? { point } : {}),
  };
}

export function appendDetailsPage(
  previous: FactoryChartDetailsData | null,
  next: FactoryChartDetailsData,
): FactoryChartDetailsData {
  if (!previous || next.page <= 1) return { ...next, rows: [...next.rows] };
  if (next.page !== previous.page + 1) throw new Error('明细页码不连续');
  if (next.pageSize !== 50 || previous.pageSize !== 50) throw new Error('明细分页大小必须为50');
  if (next.candidateId !== previous.candidateId || next.factoryId !== previous.factoryId) {
    throw new Error('明细上下文已变化');
  }
  return {
    ...next,
    columns: [...next.columns],
    rows: [...previous.rows, ...next.rows],
  };
}

export function isSameChartContext(a: FactoryChartContextKey, b: FactoryChartContextKey): boolean {
  return a.factoryId === b.factoryId
    && a.source === b.source
    && a.periodLabel === b.periodLabel
    && a.authEpoch === b.authEpoch;
}

export function formatChartCell(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}
