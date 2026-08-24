import {
  createTransitReceiptIdempotencyKey,
  isValidReceiptDelta,
  receiptDeltaError,
  remainingReceiptQuantity,
} from '../../../../screens/warehouse/inventory/transitReceiptQuantity';

describe('production transit receipt quantity', () => {
  const fresh = { reportedQuantity: 10, receivedQuantity: null };

  it('accepts a 6, 3, 1 split against the cumulative remaining quantity', () => {
    expect(remainingReceiptQuantity(fresh)).toBe(10);
    expect(isValidReceiptDelta(6, fresh)).toBe(true);

    const afterSix = { reportedQuantity: 10, receivedQuantity: 6 };
    expect(remainingReceiptQuantity(afterSix)).toBe(4);
    expect(isValidReceiptDelta(3, afterSix)).toBe(true);

    const afterNine = { reportedQuantity: 10, receivedQuantity: 9 };
    expect(remainingReceiptQuantity(afterNine)).toBe(1);
    expect(isValidReceiptDelta(1, afterNine)).toBe(true);
  });

  it('accepts an equal receipt and rejects non-positive quantities', () => {
    expect(isValidReceiptDelta(10, fresh)).toBe(true);
    expect(isValidReceiptDelta(0, fresh)).toBe(false);
    expect(isValidReceiptDelta(-1, fresh)).toBe(false);
  });

  it('rejects 11 boxes against a remaining 10 boxes with a complete Chinese reason', () => {
    expect(isValidReceiptDelta(11, fresh)).toBe(false);
    expect(receiptDeltaError(11, fresh, '盒')).toBe(
      '本次实收 11 盒 超过待收 10 盒，系统不会入库；请按现场实际剩余数量重新填写。',
    );
  });

  it('does not allow a retry to exceed the remaining quantity', () => {
    const afterSix = { reportedQuantity: 10, receivedQuantity: 6 };
    expect(isValidReceiptDelta(5, afterSix)).toBe(false);
    expect(receiptDeltaError(5, afterSix, '盒')).toContain('超过待收 4 盒');
  });

  it('normalizes four-decimal business quantities before comparing the delta', () => {
    const decimalLine = { reportedQuantity: 0.3, receivedQuantity: 0.2 };
    expect(remainingReceiptQuantity(decimalLine)).toBe(0.1);
    expect(isValidReceiptDelta(0.1, decimalLine)).toBe(true);
  });

  it('creates bounded receipt-attempt keys and rotates them for a new mutation', () => {
    const first = createTransitReceiptIdempotencyKey('plan-1234567890', 1_700_000_000_000, 0.1);
    const retry = createTransitReceiptIdempotencyKey('plan-1234567890', 1_700_000_000_000, 0.1);
    const nextMutation = createTransitReceiptIdempotencyKey('plan-1234567890', 1_700_000_000_001, 0.2);

    expect(first).toBe(retry);
    expect(nextMutation).not.toBe(first);
    expect(first).toMatch(/^rn-wh-receipt-[A-Za-z0-9]+-[a-z0-9]+-[a-z0-9]+$/);
    expect(first.length).toBeLessThanOrEqual(96);
  });
});
