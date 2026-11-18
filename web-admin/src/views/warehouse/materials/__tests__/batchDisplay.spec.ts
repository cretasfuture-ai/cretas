import { describe, expect, it } from 'vitest';
import { batchStatusDisplay, formatDateTimeMinute, isDepletedAvailableBatch } from '../batchDisplay';

const text = (s: string) => (s === 'AVAILABLE' ? '可用' : s);

describe('入库批次展示辅助', () => {
  it('ISO 入库时间格式化到分钟, 不再外露 T 与微秒', () => {
    expect(formatDateTimeMinute('2026-11-18T15:21:26.694783')).toBe('2026-11-18 15:21');
    expect(formatDateTimeMinute(null)).toBe('-');
  });

  it('可用但剩余 0 显示已用完', () => {
    expect(batchStatusDisplay({ status: 'AVAILABLE', currentQuantity: 0 }, text)).toEqual({ text: '已用完', depleted: true });
    expect(batchStatusDisplay({ status: 'AVAILABLE', currentQuantity: '0.00' }, text).text).toBe('已用完');
  });

  it('阴性对照: 有库存、读不到数量、其他状态都不改', () => {
    expect(batchStatusDisplay({ status: 'AVAILABLE', currentQuantity: 12.5 }, text).text).toBe('可用');
    expect(batchStatusDisplay({ status: 'AVAILABLE' }, text).text).toBe('可用');
    expect(batchStatusDisplay({ status: 'AVAILABLE', currentQuantity: null }, text).text).toBe('可用');
    expect(isDepletedAvailableBatch({ status: 'EXPIRED', currentQuantity: 0 })).toBe(false);
  });
});
