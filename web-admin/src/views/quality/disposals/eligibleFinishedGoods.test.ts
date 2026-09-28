import { describe, expect, it } from 'vitest';
import { disposalAvailableQuantity, isEligibleFinishedGoods } from './eligibleFinishedGoods';

describe('disposal finished-goods balance', () => {
  it('hides fully shipped stock and does not infer availability from produced quantity', () => {
    expect(isEligibleFinishedGoods({ availableQuantity: 0, producedQuantity: 100 })).toBe(false);
    expect(isEligibleFinishedGoods({ producedQuantity: 100 })).toBe(false);
    expect(disposalAvailableQuantity({ producedQuantity: 100 })).toBeNull();
  });

  it('offers only the unshipped, unreserved remainder of a partly shipped batch', () => {
    const batch = { producedQuantity: 100, shippedQuantity: 60, reservedQuantity: 10,
      availableQuantity: 30 };
    expect(isEligibleFinishedGoods(batch)).toBe(true);
    expect(disposalAvailableQuantity(batch)).toBe(30);
  });
});
