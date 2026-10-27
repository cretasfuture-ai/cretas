import { flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({ insights: vi.fn(), save: vi.fn() }));
vi.mock('../common', async importOriginal => ({
  ...await importOriginal<typeof import('../common')>(), getFactoryId: () => 'F006',
}));
vi.mock('../upload', () => ({
  getUploadTableData: vi.fn(async () => ({ success: true, data: { data: [{ 产品: '甲', 数量: 10 }, { 产品: '乙', 数量: 20 }] } })),
  getUploadFields: vi.fn(async () => ({ success: true, data: [] })),
}));
vi.mock('../python-service', () => ({
  getCachedAnalysis: vi.fn(async () => null),
  recommendChart: vi.fn(async () => ({ success: false, recommendations: [] })),
  smartRecommendChart: vi.fn(async () => ({ success: false, recommendations: [] })),
  quickSummary: vi.fn(async () => ({ success: true, rowCount: 2, columnCount: 2, columns: [] })),
  batchBuildCharts: vi.fn(async () => ({ success: false, charts: [] })),
  buildChart: vi.fn(async () => ({ success: false })),
  getForecast: vi.fn(async () => ({ success: false })),
  generateInsights: vi.fn(async () => ({ success: false })),
  generateInsightsStream: h.insights,
  saveAnalysisToCache: h.save,
}));

import { enrichSheetAnalysis } from '../analysis';

const insights = { success: true, insights: [{ type: 'trend', text: '数量由10增加到20。' }] };
beforeEach(() => {
  h.insights.mockReset().mockResolvedValue(insights);
  h.save.mockReset().mockResolvedValue(undefined);
});

describe('real enrichment orchestration publication lifecycle', () => {
  it('does not persist a completed old analysis after its view withdrew publication', async () => {
    let resolveInsights!: (value: unknown) => void;
    h.insights.mockImplementationOnce(() => new Promise(resolve => { resolveInsights = resolve; }));
    let epoch = 1;
    const capturedEpoch = epoch;
    const progress = vi.fn();
    const pending = enrichSheetAnalysis(7001, true, progress, () => epoch === capturedEpoch);
    await flushPromises();
    expect(h.insights).toHaveBeenCalledTimes(1);
    epoch += 1;
    const progressBefore = progress.mock.calls.length;
    resolveInsights(insights);
    expect((await pending).success).toBe(false);
    expect(h.save).not.toHaveBeenCalled();
    expect(progress).toHaveBeenCalledTimes(progressBefore);
  });

  it('continues to persist ordinary active analysis', async () => {
    const result = await enrichSheetAnalysis(7002, true);
    expect(result.success).toBe(true);
    expect(h.save).toHaveBeenCalledWith(7002, 'F006', expect.objectContaining({ success: true, aiAnalysis: expect.stringContaining('数量由10增加到20') }));
  });

  it('rechecks the publication guard after the completion callback', async () => {
    let active = true;
    await enrichSheetAnalysis(7003, true, progress => { if (progress.phase === 'complete') active = false; }, () => active);
    expect(h.save).not.toHaveBeenCalled();
  });
});
