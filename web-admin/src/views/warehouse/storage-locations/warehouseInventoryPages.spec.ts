import { describe, expect, it, vi } from 'vitest';
import { loadAllWarehouseBatches } from './warehouseInventoryPages';

const batch = (id: string, factoryId = 'F006') => ({ id, factoryId });

describe('仓储空间逐页读取完整库存', () => {
  it('读取所有页后才给出完整批次', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ content: [batch('1')], totalElements: 3 })
      .mockResolvedValueOnce({ content: [batch('2')], totalElements: 3 })
      .mockResolvedValueOnce({ content: [batch('3')], totalElements: 3 });
    expect(await loadAllWarehouseBatches('F006', fetch)).toEqual([batch('1'), batch('2'), batch('3')]);
    expect(fetch.mock.calls).toEqual([[1], [2], [3]]);
  });

  it('空库存只请求第一页', async () => {
    const fetch = vi.fn().mockResolvedValue({ content: [], totalElements: 0 });
    expect(await loadAllWarehouseBatches('F006', fetch)).toEqual([]);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it.each(['duplicate', 'drift', 'missing', 'tenant'])('拒绝 %s，不能展示貌似完整的汇总', async kind => {
    const fetch = vi.fn().mockResolvedValueOnce({ content: [batch('1')], totalElements: 2 })
      .mockResolvedValueOnce({
        content: kind === 'missing' ? [] : [batch(kind === 'duplicate' ? '1' : '2', kind === 'tenant' ? 'OTHER' : 'F006')],
        totalElements: kind === 'drift' ? 3 : 2,
      });
    await expect(loadAllWarehouseBatches('F006', fetch)).rejects.toThrow();
  });

  it('取消后不请求下一页', async () => {
    const controller = new AbortController();
    const fetch = vi.fn(async () => { controller.abort(); return { content: [batch('1')], totalElements: 2 }; });
    await expect(loadAllWarehouseBatches('F006', fetch, controller.signal)).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
