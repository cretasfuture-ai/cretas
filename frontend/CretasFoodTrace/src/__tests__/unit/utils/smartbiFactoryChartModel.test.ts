import {
  appendDetailsPage,
  buildDetailsRequest,
  buildRecommendationRequest,
  formatChartCell,
  normalizeSelectedIds,
  selectRecommendations,
} from '../../../utils/smartbiFactoryChartModel';
import type { FactoryChartDetailsData, FactoryRecommendation } from '../../../types/factoryCharts';

const chart = (id: string): FactoryRecommendation => ({
  id,
  title: id,
  metric: '数量',
  domain: '库存',
  priority: 'required',
  reason: 'reason',
  source: 'business',
  unit: '件',
  findings: { good: [], attention: [], suggestions: [] },
  chart: { chartType: 'bar_horizontal', echartsOption: { series: [] } },
  kpis: [],
  tableData: null,
});

const page = (pageNumber: number, rows: Record<string, unknown>[]): FactoryChartDetailsData => ({
  factoryId: 'F006', candidateId: 'c1', title: '明细', source: 'business', period: '2026全年',
  columns: ['订单号'], rows, total: 3, page: pageNumber, pageSize: 50, hasMore: pageNumber === 1,
  appliedFilters: [], availableFilters: [], snapshotAt: 'now',
});

describe('smartbiFactoryChartModel', () => {
  it('preserves null versus explicitly empty selection', () => {
    expect(normalizeSelectedIds(null)).toBeNull();
    expect(normalizeSelectedIds([])).toEqual([]);
    expect(selectRecommendations([chart('a')], null)).toHaveLength(1);
    expect(selectRecommendations([chart('a')], [])).toEqual([]);
  });

  it('builds bounded recommendation and detail contracts', () => {
    expect(buildRecommendationRequest('business', { year: 2026, periodType: 'month_range', startMonth: 2, endMonth: 4 }, false, true)).toMatchObject({
      source: 'business', year: 2026, period_type: 'month_range', start_month: 2, end_month: 4, include_optional: false, use_ai: true,
    });
    expect(buildDetailsRequest('ctx', { name: 'QA直营', seriesName: '收入', dataIndex: 0 }, 2, [{ field: '部门', value: 'QA' }])).toEqual({
      context_id: 'ctx', page: 2, page_size: 50, filters: [{ field: '部门', value: 'QA' }], point: { name: 'QA直营', seriesName: '收入', dataIndex: 0 },
    });
    expect(() => buildDetailsRequest('ctx', undefined, 0)).toThrow('页码');
    expect(() => buildRecommendationRequest('business', { year: 2026, periodType: 'year', startMonth: 2, endMonth: 4 }, false, false)).toThrow('有效的时间范围');
  });

  it('appends pages without content deduplication and rejects gaps', () => {
    const first = page(1, [{ '订单号': 'same' }, { '订单号': 'same' }]);
    const second = page(2, [{ '订单号': 'same' }]);
    const merged = appendDetailsPage(first, second);
    expect(merged.rows).toEqual([{ '订单号': 'same' }, { '订单号': 'same' }, { '订单号': 'same' }]);
    expect(() => appendDetailsPage(first, page(3, []))).toThrow('不连续');
  });

  it('formats visible cells without converting real zero to missing', () => {
    expect(formatChartCell(0)).toBe('0');
    expect(formatChartCell(null)).toBe('—');
    expect(formatChartCell('QA-SBI')).toBe('QA-SBI');
  });

  it.each([[1899, false], [1900, true], [2100, true], [2101, false]])('matches server year bounds at %s', (year, valid) => {
    const request = () => buildRecommendationRequest('business', { year: Number(year), periodType: 'year', startMonth: 1, endMonth: 12 }, false, false);
    if (valid) expect(request()).toMatchObject({ year });
    else expect(request).toThrow('有效的时间范围');
  });
});
