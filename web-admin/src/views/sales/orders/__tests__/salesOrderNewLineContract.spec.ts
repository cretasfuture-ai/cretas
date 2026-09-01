import { describe, expect, it } from 'vitest';
import {
  ambiguousPackagingUnit,
  derivedUntaxedPriceOrNull,
  initialTaxIncludedUnitPrice,
  packagingSpecIdForUnit,
  productTaxRatePercent,
  salesOrderUnitOptions,
  untaxedUnitPrice,
  validTaxRatePercent,
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

  // 🔒 2026-09-02: list.vue (下单主录入面) 复用本模块做「含税单价录入 → 未税展示」——
  // derivedUntaxedPriceOrNull 是这条链路的唯一公式, 必须严格区分「算出来是 0」和
  // 「算不出来 (税率未知)」, 因为调用方(list.vue displayUntaxedUnitPrice)拿 null
  // 渲染成 '-', 拿 0 会渲染成一个看起来自信但错误的数字。
  it('derives the untaxed price from a tax-inclusive entry when the tax rate is valid (positive control)', () => {
    // 正对照: 含税 113 + 税率 13% → 未税必须是 100, 且必须真的算出来 (不是 null)。
    expect(derivedUntaxedPriceOrNull(113, 13)).toBe(100);
    // 换一个税点重算: 226 / 1.13 = 200, 不是 226, 不是 0。
    expect(derivedUntaxedPriceOrNull(226, 13)).toBe(200);
    // 0% 免税是合法税率 (不是"未知"), 未税就等于含税本身。
    expect(derivedUntaxedPriceOrNull(50, 0)).toBe(50);
  });

  it('refuses to guess an untaxed price when the tax rate is unknown (negative control)', () => {
    // 负对照: 税率缺失/非法时必须返回 null —— 调用方据此判定"不渲染", 不是 0, 也不是
    // 原样回显含税价 (113)。这三种错误结果各自代表本裁定明确禁止的三种坏行为。
    expect(derivedUntaxedPriceOrNull(113, null)).toBeNull();
    expect(derivedUntaxedPriceOrNull(113, undefined)).toBeNull();
    expect(derivedUntaxedPriceOrNull(113, '')).toBeNull();
    expect(derivedUntaxedPriceOrNull(113, -1)).toBeNull();
    expect(derivedUntaxedPriceOrNull(113, 101)).toBeNull();
    expect(derivedUntaxedPriceOrNull(113, 'not-a-number')).toBeNull();
    // 阴性对照三连: null 结果不许被误读成这三个具体数字中的任何一个。
    expect(derivedUntaxedPriceOrNull(113, null)).not.toBe(0);
    expect(derivedUntaxedPriceOrNull(113, null)).not.toBe(113);
    expect(derivedUntaxedPriceOrNull(113, null)).not.toBe(100);
  });

  it('treats 0 as a legitimate tax rate distinct from an unknown one', () => {
    expect(validTaxRatePercent(0)).toBe(0);
    expect(validTaxRatePercent('0')).toBe(0);
    expect(validTaxRatePercent(null)).toBeNull();
    expect(validTaxRatePercent(undefined)).toBeNull();
    expect(validTaxRatePercent('')).toBeNull();
    expect(validTaxRatePercent(100)).toBe(100);
    expect(validTaxRatePercent(100.0001)).toBeNull();
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
