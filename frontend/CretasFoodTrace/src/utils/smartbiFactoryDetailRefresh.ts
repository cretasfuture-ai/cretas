import type {
  FactoryChartPeriodType,
  FactoryChartSource,
  FactoryRecommendation,
  FactoryRecommendationsData,
} from '../types/factoryCharts';

export interface FactoryDetailRefreshScope {
  factoryId: string;
  authEpoch: string;
  source: FactoryChartSource;
  uploadId?: number;
  inspectionUploadId?: number;
  costUploadId?: number;
  period: { year: number; periodType: FactoryChartPeriodType; startMonth: number; endMonth: number; label?: string };
  periodLabel: string;
  analysisSubject?: string | null;
  candidateId: string;
  unit: string;
}

function stableJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  return `{${Object.keys(value as Record<string, unknown>).sort().map(key => `${JSON.stringify(key)}:${stableJson((value as Record<string, unknown>)[key])}`).join(',')}}`;
}

export function isSameDetailScope(a: FactoryDetailRefreshScope, b: FactoryDetailRefreshScope): boolean {
  return a.factoryId === b.factoryId
    && a.authEpoch === b.authEpoch
    && a.source === b.source
    && a.uploadId === b.uploadId
    && a.inspectionUploadId === b.inspectionUploadId
    && a.costUploadId === b.costUploadId
    && a.period.year === b.period.year
    && a.period.periodType === b.period.periodType
    && a.period.startMonth === b.period.startMonth
    && a.period.endMonth === b.period.endMonth
    && a.periodLabel === b.periodLabel
    && (a.analysisSubject || null) === (b.analysisSubject || null)
    && a.candidateId === b.candidateId
    && a.unit === b.unit;
}

export function mergeRefreshedDetailContext(
  original: FactoryRecommendation,
  refreshed: FactoryRecommendation | undefined,
  response: FactoryRecommendationsData,
  expected: FactoryDetailRefreshScope,
): FactoryRecommendation {
  if (!refreshed || refreshed.id !== expected.candidateId || original.id !== expected.candidateId) {
    throw new Error('未找到原分析中的候选图表，无法恢复明细；请重新分析');
  }
  const responsePeriod = response.period;
  if (response.factoryId !== expected.factoryId
    || response.source !== expected.source
    || (response.analysisSubject || null) !== (expected.analysisSubject || null)
    || response.uploadId !== (expected.uploadId ?? null)
    || (response.jointUploads?.inspectionUploadId ?? null) !== (expected.inspectionUploadId ?? null)
    || (response.jointUploads?.costUploadId ?? null) !== (expected.costUploadId ?? null)
    || responsePeriod.label !== expected.periodLabel
    || responsePeriod.year !== expected.period.year
    || responsePeriod.periodType !== expected.period.periodType
    || responsePeriod.startMonth !== expected.period.startMonth
    || responsePeriod.endMonth !== expected.period.endMonth) {
    throw new Error('当前工厂、来源、上传文件、期间或分析产品已变化，请重新分析');
  }
  if (refreshed.source !== original.source || refreshed.unit !== original.unit
    || stableJson(refreshed.chart) !== stableJson(original.chart)
    || stableJson(refreshed.kpis) !== stableJson(original.kpis)) {
    throw new Error('图表数据已变化，请重新分析后查看原始明细');
  }
  if (!original.detailSnapshotKey || !refreshed.detailSnapshotKey
    || original.detailSnapshotKey !== refreshed.detailSnapshotKey) {
    throw new Error('原始数据快照无法证明未变化，请重新分析后查看原始明细');
  }
  if (!refreshed.detailContext?.id) {
    throw new Error(refreshed.detailStatus?.message || '当前图表暂时无法分配原始明细，请稍后重试');
  }
  return { ...original, detailContext: refreshed.detailContext, detailStatus: refreshed.detailStatus };
}
