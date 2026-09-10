import { describe, expect, it, vi } from 'vitest';
import { loadAllFinishedStock, summarizeFinishedStock, type FinishedStockBatch } from './finishedGoodsSummary';
const row = (id: string): FinishedStockBatch => ({ id, factoryId: 'TEST', productTypeId: 'p1', unit: 'box',
  producedQuantity: 10, shippedQuantity: 2, reservedQuantity: 1 });
describe('full finished stock query', () => {
  it('does not request another page after an in-flight query is cancelled', async () => {
    const controller = new AbortController();
    const fetch = vi.fn(async () => {
      controller.abort();
      return { content: [row('1')], totalElements: 2 };
    });
    await expect(loadAllFinishedStock('TEST', fetch, controller.signal)).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('loads every page before summing, including unassigned warehouses', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ content: [row('1')], totalElements: 2 })
      .mockResolvedValueOnce({ content: [row('2')], totalElements: 2 });
    expect(summarizeFinishedStock(await loadAllFinishedStock('TEST', fetch)))
      .toMatchObject([{ quantity: 16, available: 14, batchCount: 2, warehouseId: '' }]);
    expect(fetch.mock.calls).toEqual([[1], [2]]);
  });
  it('accepts a complete empty inventory', async () => {
    expect(await loadAllFinishedStock('TEST', async () => ({ content: [], totalElements: 0 }))).toEqual([]);
  });
  it.each(['repeat', 'drift', 'empty', 'tenant'])('rejects %s responses', async kind => {
    const fetch = vi.fn().mockResolvedValueOnce({ content: [row('1')], totalElements: 2 })
      .mockResolvedValueOnce({ content: kind === 'empty' ? [] : [{ ...row(kind === 'repeat' ? '1' : '2'), factoryId: kind === 'tenant' ? 'OTHER' : 'TEST' }], totalElements: kind === 'drift' ? 3 : 2 });
    await expect(loadAllFinishedStock('TEST', fetch)).rejects.toThrow();
  });
});
