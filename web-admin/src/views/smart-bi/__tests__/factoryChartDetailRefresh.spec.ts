import { describe, expect, it } from 'vitest';
import type { FactoryRecommendation, FactoryRecommendationsData } from '@/api/smartbi/factory-charts';
import { refreshedDetailCandidate, type FactoryDetailRefreshScope } from '../factoryChartDetailRefresh';

const original: FactoryRecommendation = { id: 'production', title: '产量', metric: '产量', domain: '生产', priority: 'required', reason: '', source: '业务系统', unit: '盒', findings: { good: [], attention: [], suggestions: [] }, chart: { chartType: 'bar', echartsOption: { series: [{ data: [100] }] } }, kpis: [{ label: '产量', value: 100, unit: '盒' }], tableData: null, detailSnapshotKey: 'immutable-original', detailContext: { id: 'old', expiresAt: '2020-01-01' } };
const scope: FactoryDetailRefreshScope = { identity: 'F006:admin', periodLabel: '全部历史', request: { factory_id: 'F006', source: 'business', year: 2026, period_type: 'all', start_month: 1, end_month: 12, include_optional: true, use_ai: false, analysis_subject: 'product-1' } };
function response(change: Partial<FactoryRecommendation> = {}): FactoryRecommendationsData {
  return { factoryId: 'F006', source: 'business', uploadId: null, sourceLabel: '业务系统', period: { label: '全部历史', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12 }, ranking: { mode: 'rule', reason: '' }, analysisSubject: 'product-1', recommendations: [{ ...original, ...change, detailContext: { id: 'new', expiresAt: '2099-01-01' } }], unavailable: [], sourceErrors: [] };
}
describe('safe exact-original detail renewal', () => {
  it('retains frozen chart and renews only a matching immutable snapshot', () => {
    const result = refreshedDetailCandidate(original, response(), scope);
    expect(result.detailContext?.id).toBe('new');
    expect(result.chart).toBe(original.chart);
  });
  it('rejects changed raw rows even when the chart aggregates are identical', () => {
    expect(() => refreshedDetailCandidate(original, response({ detailSnapshotKey: 'different-batch-same-aggregate' }), scope)).toThrow('原始数据已变化');
    expect(() => refreshedDetailCandidate({ ...original, detailSnapshotKey: undefined }, response(), scope)).toThrow('快照凭证');
  });
  it.each(['factoryId', 'source', 'analysisSubject'] as const)('rejects a changed %s', key => {
    const changed = { ...response(), [key]: 'other' } as FactoryRecommendationsData;
    expect(() => refreshedDetailCandidate(original, changed, scope)).toThrow('请重新分析');
  });
  it('rejects changed unit and missing capacity instead of silently replacing the chart', () => {
    expect(() => refreshedDetailCandidate(original, response({ unit: 'kg' }), scope)).toThrow('请重新分析');
    const unavailable = response(); unavailable.recommendations[0].detailContext = undefined;
    unavailable.recommendations[0].detailStatus = { state: 'capacity', message: '容量已满' };
    expect(() => refreshedDetailCandidate(original, unavailable, scope)).toThrow('容量已满');
  });
  it.each(['year', 'periodType', 'startMonth', 'endMonth'] as const)('rejects wrong structured period %s even with the same display label', key => {
    const changed = response();
    const overrides = { year: 2025, periodType: 'year' as const, startMonth: 2, endMonth: 11 };
    changed.period = { ...changed.period, [key]: overrides[key] };
    expect(() => refreshedDetailCandidate(original, changed, scope)).toThrow('请重新分析');
  });
  it('requires exact upload identity including an explicit null for business source', () => {
    const uploadScope: FactoryDetailRefreshScope = { ...scope, request: { ...scope.request, source: 'upload', upload_id: 5259 } };
    const good = { ...response(), source: 'upload' as const, uploadId: 5259 };
    expect(refreshedDetailCandidate(original, good, uploadScope).detailContext?.id).toBe('new');
    expect(() => refreshedDetailCandidate(original, { ...good, uploadId: 5260 }, uploadScope)).toThrow('请重新分析');
    const missing = { ...good } as Partial<FactoryRecommendationsData>; delete missing.uploadId;
    expect(() => refreshedDetailCandidate(original, missing as FactoryRecommendationsData, uploadScope)).toThrow('请重新分析');
    expect(() => refreshedDetailCandidate(original, { ...response(), uploadId: 5259 }, scope)).toThrow('请重新分析');
  });
  it('requires the original inspection and cost uploads as well as the production anchor', () => {
    const three: FactoryDetailRefreshScope = { ...scope, request: { ...scope.request, source: 'upload', upload_id: 101, inspection_upload_id: 102, cost_upload_id: 103 } };
    const good = { ...response(), source: 'upload' as const, uploadId: 101, jointUploads: { inspectionUploadId: 102, costUploadId: 103 } };
    expect(refreshedDetailCandidate(original, good, three).detailContext?.id).toBe('new');
    for (const jointUploads of [undefined, null, { inspectionUploadId: 104, costUploadId: 103 }, { inspectionUploadId: 102, costUploadId: 104 }]) {
      expect(() => refreshedDetailCandidate(original, { ...good, jointUploads }, three)).toThrow('请重新分析');
    }
    expect(() => refreshedDetailCandidate(original, good, { ...scope, request: { ...scope.request, source: 'upload', upload_id: 101 } })).toThrow('请重新分析');
  });
});
