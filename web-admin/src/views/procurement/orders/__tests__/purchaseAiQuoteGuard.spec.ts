import { describe, expect, it } from 'vitest';
import { purchaseAiQuoteDifference } from '../purchaseAiQuoteGuard';

describe('AI quote comparison with persisted purchase rounding', () => {
  it('rejects the observed one-cent loss while accepting a representable quote', () => {
    expect(purchaseAiQuoteDifference(1, 5, 13)).toEqual({ quoted: '5.00', stored: '4.99' });
    expect(purchaseAiQuoteDifference(2, 5, 13)).toBeNull();
  });
  it('detects large-quantity four-decimal conversion drift in either direction', () => {
    expect(purchaseAiQuoteDifference(100000, 5, 13)).toEqual({ quoted: '500000.00', stored: '500002.40' });
    expect(purchaseAiQuoteDifference(100000, 2, 13)).toEqual({ quoted: '200000.00', stored: '199998.70' });
  });
  it('retains zero/exempt prices and decimal half-up boundaries', () => {
    expect(purchaseAiQuoteDifference(1, 0, 13)).toBeNull();
    expect(purchaseAiQuoteDifference(1, 1.005, 0)).toBeNull();
    expect(purchaseAiQuoteDifference(1e-2, 100, 0)).toBeNull();
    expect(purchaseAiQuoteDifference(1, 5, 0)).toBeNull();
  });
});
