import type { FactoryRecommendation, FactoryRecommendationRequest, FactoryRecommendationsData } from '@/api/smartbi/factory-charts';
import { sameJointUploads } from './factoryJointUploads';

export interface FactoryDetailRefreshScope {
  request: FactoryRecommendationRequest;
  periodLabel: string;
  identity: string;
}

function stable(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`;
  const record = value as Record<string, unknown>;
  return `{${Object.keys(record).sort().map(key => `${JSON.stringify(key)}:${stable(record[key])}`).join(',')}}`;
}

/** A matching aggregate does not prove matching original rows. Require the server snapshot digest too. */
export function refreshedDetailCandidate(original: FactoryRecommendation, response: FactoryRecommendationsData, scope: FactoryDetailRefreshScope): FactoryRecommendation {
  const next = response.recommendations.find(item => item.id === original.id);
  if (response.factoryId !== scope.request.factory_id || response.source !== scope.request.source
    || response.uploadId !== (scope.request.source === 'upload' ? scope.request.upload_id : null)
    || !sameJointUploads(response.jointUploads, scope.request.inspection_upload_id && scope.request.cost_upload_id
      ? { inspectionUploadId: scope.request.inspection_upload_id, costUploadId: scope.request.cost_upload_id } : null)
    || response.period.label !== scope.periodLabel
    || response.period.year !== scope.request.year || response.period.periodType !== scope.request.period_type
    || response.period.startMonth !== scope.request.start_month || response.period.endMonth !== scope.request.end_month
    || (response.analysisSubject || null) !== (scope.request.analysis_subject || null)) {
    throw new Error('工厂、来源、期间或产品已变化，请重新分析');
  }
  if (!next || !original.detailSnapshotKey || next.detailSnapshotKey !== original.detailSnapshotKey
    || next.source !== original.source || next.unit !== original.unit
    || stable(next.chart) !== stable(original.chart) || stable(next.kpis) !== stable(original.kpis)) {
    throw new Error('原始数据已变化或缺少快照凭证，请重新分析后查看明细');
  }
  if (!next.detailContext?.id) throw new Error(next.detailStatus?.message || '明细暂不可用，请稍后重试');
  return { ...original, detailContext: next.detailContext, detailStatus: next.detailStatus };
}
