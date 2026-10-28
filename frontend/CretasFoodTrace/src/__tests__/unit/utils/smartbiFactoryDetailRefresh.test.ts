import { mergeRefreshedDetailContext } from '../../../utils/smartbiFactoryDetailRefresh';
import type { FactoryRecommendation, FactoryRecommendationsData } from '../../../types/factoryCharts';

const original: FactoryRecommendation = {
  id: 'sales', title: '销售渠道', metric: '收入', domain: '销售', priority: 'required', reason: 'reason', source: 'business', unit: '元',
  findings: { good: [], attention: [], suggestions: [] }, chart: { chartType: 'bar', echartsOption: { series: [{ data: [1] }] } },
  kpis: [{ label: '收入', value: 10, unit: '元' }], tableData: null, detailSnapshotKey: 'snapshot-1',
};
const response: FactoryRecommendationsData = {
  factoryId: 'F006', source: 'business', sourceLabel: '业务数据', period: { label: '2026全年', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12 }, uploadId: null,
  ranking: { mode: 'rule', reason: '' }, recommendations: [], unavailable: [], sourceErrors: [], analysisSubject: null,
};
const scope = {
  factoryId: 'F006', authEpoch: '1309|F006|factory_admin', source: 'business' as const,
  period: { year: 2026, periodType: 'all' as const, startMonth: 1, endMonth: 12 }, periodLabel: '2026全年',
  analysisSubject: null, candidateId: 'sales', unit: '元',
};

describe('smartbiFactoryDetailRefresh', () => {
  it('merges only the refreshed immutable context while retaining frozen chart data', () => {
    const next = mergeRefreshedDetailContext(original, { ...original, detailContext: { id: 'ctx-2', expiresAt: '2099-01-01T00:00:00Z' } }, { ...response, recommendations: [{ ...original, detailContext: { id: 'ctx-2', expiresAt: '2099-01-01T00:00:00Z' } }] }, scope);
    expect(next.chart).toEqual(original.chart);
    expect(next.kpis).toEqual(original.kpis);
    expect(next.detailContext?.id).toBe('ctx-2');
  });

  it('fails closed when the opaque source snapshot proof is absent or changed', () => {
    expect(() => mergeRefreshedDetailContext({ ...original, detailSnapshotKey: undefined }, { ...original, detailContext: { id: 'ctx-2', expiresAt: '2099-01-01T00:00:00Z' } }, response, scope)).toThrow(/快照/);
    expect(() => mergeRefreshedDetailContext(original, { ...original, detailSnapshotKey: 'snapshot-2', detailContext: { id: 'ctx-2', expiresAt: '2099-01-01T00:00:00Z' } }, response, scope)).toThrow(/快照/);
  });

  it.each(['year', 'periodType', 'startMonth', 'endMonth'] as const)('rejects a changed structured period field: %s', field => {
    const changed = { ...response, recommendations: [{ ...original, detailContext: { id: 'ctx-2', expiresAt: '2099-01-01T00:00:00Z' } }], period: { ...response.period, [field]: field === 'periodType' ? 'year' : field === 'year' ? 2025 : field === 'startMonth' ? 2 : 11 } };
    expect(() => mergeRefreshedDetailContext(original, changed.recommendations[0], changed, scope)).toThrow(/期间/);
  });

  it('requires the response upload identity to match the original scope', () => {
    const uploadScope = { ...scope, uploadId: 42 };
    const candidate = { ...original, detailContext: { id: 'ctx-2', expiresAt: '2099-01-01T00:00:00Z' } };
    expect(mergeRefreshedDetailContext(original, candidate, { ...response, uploadId: 42, recommendations: [candidate] }, uploadScope).detailContext?.id).toBe('ctx-2');
    expect(() => mergeRefreshedDetailContext(original, candidate, { ...response, uploadId: null, recommendations: [candidate] }, uploadScope)).toThrow(/上传/);
    expect(() => mergeRefreshedDetailContext(original, candidate, { ...response, uploadId: undefined as never, recommendations: [candidate] }, uploadScope)).toThrow(/上传/);
  });

  it('requires all three upload identities for a joint detail refresh', () => {
    const candidate = { ...original, detailContext: { id: 'ctx-joint', expiresAt: '2099-01-01T00:00:00Z' } };
    const jointScope = { ...scope, uploadId: 42, inspectionUploadId: 43, costUploadId: 44 };
    const jointResponse = { ...response, uploadId: 42, jointUploads: { inspectionUploadId: 43, costUploadId: 44 }, recommendations: [candidate] };
    expect(mergeRefreshedDetailContext(original, candidate, jointResponse, jointScope).detailContext?.id).toBe('ctx-joint');
    expect(() => mergeRefreshedDetailContext(original, candidate, { ...jointResponse, jointUploads: { inspectionUploadId: 43, costUploadId: 45 } }, jointScope)).toThrow(/上传/);
  });
});
