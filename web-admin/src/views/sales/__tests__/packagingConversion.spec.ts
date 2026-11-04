import { describe, expect, it, vi } from 'vitest';
import { confirmPackagingConversion, isPackagingMismatchError, mismatchConfirmLines } from '../packagingConversion';
import { ApiError } from '@/types/api';

const confirm = vi.hoisted(() => vi.fn());
vi.mock('element-plus', () => ({ ElMessageBox: { confirm } }));

describe('规格不一致换算确认', () => {
  const row = {
    batchNumber: 'QA-FG02', allocatedQty: 1, unit: '箱', packagingMismatch: true,
    batchPackagingLabel: '1托盘=96盒', linePackagingLabel: '1箱=8盒',
  };

  it('每条都带品名、批次、两边规格和本次数量', () => {
    expect(mismatchConfirmLines('卤牛肉', [row])).toEqual([
      '卤牛肉 · 批次 QA-FG02：批次规格 1托盘=96盒，本行规格 1箱=8盒，本次发 1箱',
    ]);
  });

  it('规格一致或数量为 0 的行不用确认', () => {
    expect(mismatchConfirmLines('卤牛肉', [{ ...row, packagingMismatch: false }, { ...row, allocatedQty: 0 }])).toEqual([]);
  });

  it('按错误码认, 别的错误不算', () => {
    expect(isPackagingMismatchError(new ApiError('x', 'BATCH_PACKAGING_SPEC_MISMATCH', 409))).toBe(true);
    expect(isPackagingMismatchError(new ApiError('x', 'FINISHED_GOODS_BATCH_NOT_SALEABLE', 409))).toBe(false);
    expect(isPackagingMismatchError(null)).toBe(false);
  });

  it('确认返回 true, 取消返回 false', async () => {
    confirm.mockResolvedValueOnce('confirm');
    await expect(confirmPackagingConversion(['a'])).resolves.toBe(true);
    confirm.mockRejectedValueOnce('cancel');
    await expect(confirmPackagingConversion(['a'])).resolves.toBe(false);
  });
});
