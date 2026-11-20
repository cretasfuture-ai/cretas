/**
 * #1548 第二轮审查 6: 「成品仍在隔离」列表查询失败时要报错, ⛔ 不能当成「没有」返回空数组 —— 那会把页签悄悄藏掉。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const getMock = vi.fn();
vi.mock('../request', () => ({ get: (...args: unknown[]) => getMock(...args), post: vi.fn() }));

import { listHeldGoodsBatches } from '../quality-disposition';

beforeEach(() => getMock.mockReset());

describe('listHeldGoodsBatches', () => {
  it('成功时原样返回', async () => {
    const rows = [{ productionBatchId: 1, releasableNow: 1, unclearOwnership: 0 }];
    getMock.mockResolvedValue({ success: true, data: rows });
    await expect(listHeldGoodsBatches('F1')).resolves.toEqual(rows);
    expect(getMock).toHaveBeenCalledWith('/F1/quality-disposition/held-goods-batches');
  });

  it('后端说失败: 抛出后端原话, 不返回空数组', async () => {
    getMock.mockResolvedValue({ success: false, message: '质检处置库存服务未注入' });
    await expect(listHeldGoodsBatches('F1')).rejects.toThrow('质检处置库存服务未注入');
  });

  it('成功但没有数据数组: 同样当失败, 不猜成「没有」', async () => {
    getMock.mockResolvedValue({ success: true, data: null });
    await expect(listHeldGoodsBatches('F1')).rejects.toThrow('查询成品隔离情况失败');
  });
});
