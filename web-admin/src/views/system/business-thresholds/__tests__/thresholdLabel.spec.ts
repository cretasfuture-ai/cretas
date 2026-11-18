import { describe, expect, it } from 'vitest';
import { thresholdDisplayName } from '../thresholdLabel';

describe('thresholdDisplayName', () => {
  it('短说明直接作显示名, 不外露键名', () => {
    expect(thresholdDisplayName({ thresholdKey: 'inventory.aging.fresh_days', description: '库龄新鲜段上限' })).toBe('库龄新鲜段上限');
  });
  it('长说明取冒号前', () => {
    expect(
      thresholdDisplayName({ thresholdKey: 'inventory.customer_supplied.allow_over_receive', description: '客供料收货能不能超收: 0 = 不能超收' }),
    ).toBe('客供料收货能不能超收');
  });
  it('没有说明才回退键名', () => {
    expect(thresholdDisplayName({ thresholdKey: 'a.b', description: null })).toBe('a.b');
    expect(thresholdDisplayName({ thresholdKey: 'a.b', description: '：x' })).toBe('a.b');
  });
});
