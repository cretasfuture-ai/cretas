import { describe, expect, it } from 'vitest';
import { appendDetailPage, detailColumns, detailRequestKey } from '../factoryChartDetails';

describe('factory chart detail paging contract', () => {
  it('keeps request identity bound to context, page, filters and clicked point', () => {
    const a = detailRequestKey('ctx-1', 1, [{ field: 'month', value: '03' }], { name: '生产额' });
    const b = detailRequestKey('ctx-1', 1, [{ field: 'month', value: '04' }], { name: '生产额' });
    expect(a).not.toBe(b);
  });

  it('appends only successful server rows and preserves structured cell values', () => {
    const current = [{ id: 1, amount: 2.64, unit: '元/kg' }];
    const next = appendDetailPage(current, { success: true, data: {
      factoryId: 'F006', candidateId: 'c1', title: '单位成本', source: '业务系统', period: '2026年3月',
      columns: ['id', 'amount', 'unit'], rows: [{ id: 2, amount: 3.1, unit: '元/kg' }], total: 2, page: 2, pageSize: 50,
      hasMore: false, appliedFilters: [], availableFilters: [], snapshotAt: '2026-10-26T00:00:00Z',
    }});
    expect(next).toHaveLength(2);
    expect(next[1]).toEqual({ id: 2, amount: 3.1, unit: '元/kg' });
    expect(appendDetailPage(current, { success: false, message: 'expired' })).toEqual(current);
  });

  it('derives virtual table columns from server columns without inventing fields', () => {
    expect(detailColumns({ columns: ['批次', '金额'], rows: [], factoryId: 'F006', candidateId: 'c1', title: 'x', source: 'x', period: 'x', total: 0, page: 1, pageSize: 50, hasMore: false, appliedFilters: [], availableFilters: [], snapshotAt: 'x' })).toEqual([
      { key: '批次', dataKey: '批次', title: '批次', width: 120 },
      { key: '金额', dataKey: '金额', title: '金额', width: 120 },
    ]);
  });
});
