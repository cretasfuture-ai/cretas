import { describe, expect, it } from 'vitest';
import {
  ambiguousPackagingUnit,
  initialTaxIncludedUnitPrice,
  packagingSpecIdForUnit,
  productTaxRatePercent,
  salesOrderUnitOptions,
  untaxedUnitPrice,
} from '../salesOrderNewLineContract';

describe('sales order full-page price and unit contract', () => {
  it('keeps the operator-entered tax-inclusive price stable while the tax point changes', () => {
    expect(untaxedUnitPrice(113, 13)).toBe(100);
    expect(untaxedUnitPrice(113, 9)).toBe(103.6697);
    expect(untaxedUnitPrice(113, 0)).toBe(113);
  });

  it('prefills the product tax-inclusive price and supports enum or numeric tax rates', () => {
    expect(productTaxRatePercent({ taxRate: 'TAX_9' })).toBe(9);
    expect(productTaxRatePercent({ taxRate: 6 })).toBe(6);
    expect(initialTaxIncludedUnitPrice({ unitPrice: 100, taxRate: 'TAX_13' })).toBe(113);
    expect(initialTaxIncludedUnitPrice({ unitPrice: 99, taxIncludedUnitPrice: 108 })).toBe(108);
  });

  it('offers only units declared by the product and its active packaging specs', () => {
    const product = {
      unit: '盒',
      level1Unit: '框',
      boxConversionCoefficient: 20,
      packagingSpecs: [
        { id: 'spec-1', packageUnit: '框', baseUnit: '盒', conversionFactor: 20, defaultSpec: true, active: true },
        { id: 'inactive', packageUnit: '箱', baseUnit: '盒', conversionFactor: 10, active: false },
      ],
    };
    expect(salesOrderUnitOptions(product)).toEqual(['box', '框']);
    expect(packagingSpecIdForUnit(product, '框')).toBe('spec-1');
    expect(packagingSpecIdForUnit(product, '盒')).toBeUndefined();
  });

  it('blocks an ambiguous packaging unit unless the product has a default spec', () => {
    const product = {
      unit: '盒',
      packagingSpecs: [
        { id: 'a', packageUnit: '箱', baseUnit: '盒', conversionFactor: 10, active: true },
        { id: 'b', packageUnit: '箱', baseUnit: '盒', conversionFactor: 20, active: true },
      ],
    };
    expect(ambiguousPackagingUnit(product, '箱')).toBe(true);
    expect(packagingSpecIdForUnit(product, '箱')).toBeUndefined();
    product.packagingSpecs[1].defaultSpec = true;
    expect(ambiguousPackagingUnit(product, '箱')).toBe(false);
    expect(packagingSpecIdForUnit(product, '箱')).toBe('b');
  });
});
