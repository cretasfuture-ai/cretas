import { describe, expect, it } from 'vitest';
import { buildSupplierPricePayload, type SupplierPriceForm } from './quotePayload';

function form(overrides: Partial<SupplierPriceForm> = {}): SupplierPriceForm {
  return {
    supplierId: 'supplier-1',
    unitPrice: 12.5,
    taxRate: null,
    currency: 'CNY',
    quotedUnit: 'kg',
    specification: '',
    minOrderQuantity: null,
    priceEffectiveDate: '',
    validUntil: '',
    deliveryDays: 0,
    remark: '',
    ...overrides,
  };
}

describe('buildSupplierPricePayload', () => {
  it('preserves an explicit zero tax rate', () => {
    expect(buildSupplierPricePayload(form({ taxRate: 0 })).taxRate).toBe(0);
  });

  it('sends no tax rate when the buyer left it empty, so the backend keeps it unknown', () => {
    const payload = buildSupplierPricePayload(form({ taxRate: null }));
    expect(payload.taxRate).toBeUndefined();
    expect('taxRate' in payload && payload.taxRate === 0).toBe(false);
  });

  it('carries currency, unit, spec, MOQ and dates as the comparison basis', () => {
    expect(buildSupplierPricePayload(form({
      taxRate: 13,
      currency: ' cny ',
      quotedUnit: ' 箱 ',
      specification: '10kg/箱',
      minOrderQuantity: 20,
      priceEffectiveDate: '2026-11-01',
      validUntil: '2026-11-30',
      deliveryDays: 5,
      remark: ' ',
    }))).toEqual({
      supplierId: 'supplier-1',
      unitPrice: 12.5,
      taxRate: 13,
      currency: 'CNY',
      quotedUnit: '箱',
      specification: '10kg/箱',
      minOrderQuantity: 20,
      priceEffectiveDate: '2026-11-01',
      validUntil: '2026-11-30',
      deliveryDays: 5,
      remark: undefined,
    });
  });

  it('does not invent a currency or unit when they are blank', () => {
    const payload = buildSupplierPricePayload(form({ currency: '', quotedUnit: '  ' }));
    expect(payload.currency).toBeUndefined();
    expect(payload.quotedUnit).toBeUndefined();
  });
});
