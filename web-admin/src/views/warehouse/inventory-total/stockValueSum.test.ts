import { describe, expect, it } from 'vitest';
import { sumStockValue } from './stockValueSum';

describe('sumStockValue', () => {
  it('sums when every row has a value, zero counts as priced', () => {
    expect(sumStockValue([{ totalValue: 10 }, { totalValue: '5.5' }, { totalValue: 0 }])).toBe(15.5);
  });
  it('returns null when any row is missing price instead of an understated total', () => {
    expect(sumStockValue([{ totalValue: 10 }, { totalValue: null }])).toBeNull();
    expect(sumStockValue([{ totalValue: 10 }, {}])).toBeNull();
  });
  it('empty list sums to 0', () => {
    expect(sumStockValue([])).toBe(0);
  });
});
