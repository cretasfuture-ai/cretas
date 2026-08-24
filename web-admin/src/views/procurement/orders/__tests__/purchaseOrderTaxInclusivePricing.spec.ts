import { describe, expect, it } from 'vitest';
import {
  purchaseTaxIncludedUnitPrice,
  purchaseUntaxedUnitPrice,
} from '../purchaseOrderTaxInclusivePricing';

describe('purchase order tax-inclusive unit price contract', () => {
  it('keeps the entered tax-inclusive unit price stable while tax point changes', () => {
    expect(purchaseUntaxedUnitPrice(113, 13)).toBe(100);
    expect(purchaseUntaxedUnitPrice(113, 9)).toBe(103.6697);
    expect(purchaseUntaxedUnitPrice(113, 0)).toBe(113);
  });

  it('hydrates the operator field from stored untaxed snapshots', () => {
    expect(purchaseTaxIncludedUnitPrice(100, 13)).toBe(113);
    expect(purchaseTaxIncludedUnitPrice(100, 9)).toBe(109);
    expect(purchaseTaxIncludedUnitPrice(0, 13)).toBe(0);
  });

  it('fails closed for incomplete or invalid prices and tax points', () => {
    expect(purchaseUntaxedUnitPrice(null, 13)).toBeNull();
    expect(purchaseUntaxedUnitPrice(113, 101)).toBeNull();
    expect(purchaseTaxIncludedUnitPrice(100, -1)).toBeNull();
  });
});
