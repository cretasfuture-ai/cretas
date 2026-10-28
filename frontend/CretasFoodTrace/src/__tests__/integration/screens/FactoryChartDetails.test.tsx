import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import FactoryChartDetails from '../../../screens/smartbi/FactoryChartDetails';
import { getFactoryChartDetails } from '../../../services/api/smartbiFactoryCharts';

jest.mock('../../../services/api/smartbiFactoryCharts', () => ({ getFactoryChartDetails: jest.fn() }));

const route = {
  key: 'FactoryChartDetails-test',
  name: 'FactoryChartDetails' as const,
  params: {
    recommendation: { id: 'sales', title: '销售渠道', metric: '收入', domain: '销售', priority: 'required' as const, reason: 'reason', source: 'business', unit: '元', findings: { good: [], attention: [], suggestions: [] }, chart: { chartType: 'bar_horizontal', echartsOption: {} }, kpis: [], tableData: null, detailContext: { id: 'ctx', expiresAt: '2099-01-01T00:00:00Z' } },
    source: 'business' as const,
    period: { year: 2026, periodType: 'all' as const, startMonth: 1, endMonth: 12 },
    periodLabel: '2026全年',
    contextKey: { factoryId: 'F006', source: 'business' as const, periodLabel: '2026全年', authEpoch: '||' },
  },
};

const page = (number: number, rows: Record<string, unknown>[], hasMore: boolean, availableFilters: Array<{ field: string; label: string; kind: string }> = []) => ({
  success: true,
  data: { factoryId: 'F006', candidateId: 'sales', title: '销售渠道', source: 'business', period: '2026全年', columns: ['订单号', '金额'], rows, total: 3, page: number, pageSize: 50, hasMore, appliedFilters: [], availableFilters, snapshotAt: 'now' },
});

describe('FactoryChartDetails', () => {
  beforeEach(() => jest.clearAllMocks());

  it('uses page size 50, replaces next page, and appends only explicit load-more', async () => {
    (getFactoryChartDetails as jest.Mock)
      .mockResolvedValueOnce(page(1, [{ '订单号': 'A', 金额: 0 }], true))
      .mockResolvedValueOnce(page(2, [{ '订单号': 'B', 金额: 30 }], true))
      .mockResolvedValueOnce(page(3, [{ '订单号': 'C', 金额: 40 }], false));
    const screen = render(<FactoryChartDetails route={route} navigation={{} as never} />);
    await waitFor(() => expect(screen.getByText('A')).toBeTruthy());
    expect((getFactoryChartDetails as jest.Mock).mock.calls[0][0]).toMatchObject({ context_id: 'ctx', page: 1, page_size: 50 });
    fireEvent.press(screen.getByText('下一页'));
    await waitFor(() => expect(screen.getByText('B')).toBeTruthy());
    expect(screen.queryByText('A')).toBeNull();
    fireEvent.press(screen.getByText('加载更多'));
    await waitFor(() => expect(screen.getByText('C')).toBeTruthy());
    expect(screen.getByText('B')).toBeTruthy();
  });

  it('retries the failed page-two attempt without advancing page state', async () => {
    (getFactoryChartDetails as jest.Mock)
      .mockResolvedValueOnce(page(1, [{ '订单号': 'A' }], true))
      .mockRejectedValueOnce(new Error('page two failed'))
      .mockResolvedValueOnce(page(2, [{ '订单号': 'B' }], false));
    const screen = render(<FactoryChartDetails route={route} navigation={{} as never} />);
    await waitFor(() => expect(screen.getByText('A')).toBeTruthy());
    fireEvent.press(screen.getByText('下一页'));
    await waitFor(() => expect(screen.getByText('page two failed')).toBeTruthy());
    expect(screen.getByText('A')).toBeTruthy();
    fireEvent.press(screen.getByText('重试'));
    await waitFor(() => expect(screen.getByText('B')).toBeTruthy());
    expect((getFactoryChartDetails as jest.Mock).mock.calls.map(call => call[0].page)).toEqual([1, 2, 2]);
  });

  it('retries a failed filter with the same filter and carries it into the next page', async () => {
    const filters = [{ field: '部门', label: '部门', kind: 'dimension' }];
    (getFactoryChartDetails as jest.Mock)
      .mockResolvedValueOnce(page(1, [{ '订单号': 'A' }], true, filters))
      .mockRejectedValueOnce(new Error('filter failed'))
      .mockResolvedValueOnce(page(1, [{ '订单号': 'F' }], true, filters))
      .mockResolvedValueOnce(page(2, [{ '订单号': 'F2' }], false, filters));
    const screen = render(<FactoryChartDetails route={route} navigation={{} as never} />);
    await waitFor(() => expect(screen.getByText('A')).toBeTruthy());
    fireEvent.press(screen.getByText('部门'));
    fireEvent.changeText(screen.getByTestId('factory-detail-filter-input'), 'QA');
    fireEvent.press(screen.getByText('筛选'));
    await waitFor(() => expect(screen.getByText('filter failed')).toBeTruthy());
    fireEvent.press(screen.getByText('重试'));
    await waitFor(() => expect(screen.getByText('F')).toBeTruthy());
    expect((getFactoryChartDetails as jest.Mock).mock.calls[2][0].filters).toEqual([{ field: '部门', value: 'QA' }]);
    fireEvent.press(screen.getByText('下一页'));
    await waitFor(() => expect(screen.getByText('F2')).toBeTruthy());
    expect((getFactoryChartDetails as jest.Mock).mock.calls[3][0].filters).toEqual([{ field: '部门', value: 'QA' }]);
  });

  it.each([
    ['factory', { factoryId: 'F999' }],
    ['source', { source: 'upload' }],
    ['period', { period: '2025全年' }],
    ['candidate', { candidateId: 'other' }],
  ])('rejects a response with a wrong %s contract instead of rendering rows', async (_label, override) => {
    (getFactoryChartDetails as jest.Mock).mockResolvedValue({ ...page(1, [{ '订单号': 'WRONG' }], false), data: { ...page(1, [], false).data, ...override } });
    const screen = render(<FactoryChartDetails route={route} navigation={{} as never} />);
    await waitFor(() => expect(screen.getByText(/契约/)).toBeTruthy());
    expect(screen.queryByText('WRONG')).toBeNull();
  });

  it('keeps ordinary details available when the chart does not support point drilldown', async () => {
    (getFactoryChartDetails as jest.Mock).mockResolvedValue(page(1, [{ '订单号': 'ORD-1' }], false));
    const noPointRoute = { ...route, params: { ...route.params, point: { name: 'ignored' }, recommendation: { ...route.params.recommendation, detailContext: { id: 'ctx', expiresAt: '2099-01-01T00:00:00Z', drilldown: false } } } };
    const screen = render(<FactoryChartDetails route={noPointRoute} navigation={{} as never} />);
    await waitFor(() => expect(screen.getByText('ORD-1')).toBeTruthy());
    expect((getFactoryChartDetails as jest.Mock).mock.calls[0][0].point).toBeUndefined();
  });

  it('does not write a late response after the details screen unmounts', async () => {
    let resolveResponse: ((value: ReturnType<typeof page>) => void) | undefined;
    const response = new Promise<ReturnType<typeof page>>(resolve => { resolveResponse = resolve; });
    (getFactoryChartDetails as jest.Mock).mockReturnValue(response);
    const screen = render(<FactoryChartDetails route={route} navigation={{} as never} />);
    await waitFor(() => expect(getFactoryChartDetails).toHaveBeenCalledTimes(1));
    screen.unmount();
    await act(async () => { resolveResponse?.(page(1, [{ '订单号': 'LATE' }], false)); });
    expect(getFactoryChartDetails).toHaveBeenCalledTimes(1);
  });

  it('blocks a route captured under a stale auth epoch before requesting rows', async () => {
    const staleRoute = { ...route, params: { ...route.params, contextKey: { ...route.params.contextKey, authEpoch: 'old-user|F006|factory_manager' } } };
    const screen = render(<FactoryChartDetails route={staleRoute} navigation={{} as never} />);
    await waitFor(() => expect(screen.getByText(/登录状态或权限/)).toBeTruthy());
    expect(getFactoryChartDetails).not.toHaveBeenCalled();
  });
});
