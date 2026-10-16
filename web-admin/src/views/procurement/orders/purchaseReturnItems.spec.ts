import { describe, expect, it } from 'vitest';
import { buildPurchaseReturnItems } from './purchaseReturnItems';

const flour = { materialTypeId: 'flour', materialName: '面粉', unit: '箱', quantity: 10, receivedQuantity: 12, unitPrice: 44.2478, taxRate: 13, priceUnit: '箱', quantityToPriceFactor: 1, purchasePackageUnitSnapshot: '箱', inventoryBaseUnitSnapshot: 'kg', packageToBaseFactorSnapshot: 10 };
describe('purchase return source units', () => {
  it('converts box quantities and untaxed prices to inventory kg and gross prices', () => {
    expect(buildPurchaseReturnItems([flour])[0]).toMatchObject({ materialTypeId: 'flour', maxQuantity: 100, unit: 'kg', unitPrice: 5 });
  });
  it('preserves Chinese piece units for packaging returns', () => {
    const bag = { ...flour, materialTypeId: 'bag', unit: '包', purchasePackageUnitSnapshot: '包', inventoryBaseUnitSnapshot: '个', priceUnit: '包', unitPrice: 2.6549 };
    expect(buildPurchaseReturnItems([bag])[0]).toMatchObject({ maxQuantity: 100, unit: '个', unitPrice: 0.3 });
  });
  it('uses actual received quantities when less than the planned quantity', () => {
    expect(buildPurchaseReturnItems([{ ...flour, receivedQuantity: 3 }])[0].maxQuantity).toBe(30);
    expect(buildPurchaseReturnItems([{ ...flour, receivedQuantity: 0 }])[0].maxQuantity).toBe(0);
  });
  it('uses a non-one pricing factor and zero tax', () => {
    expect(buildPurchaseReturnItems([{ ...flour, quantityToPriceFactor: 2, unitPrice: 0.5, taxRate: 0 }])[0].unitPrice).toBe(0.1);
  });
  it('accepts legacy g only with an authoritative inventory base unit', () => {
    const old = { materialTypeId: 'flour', unit: 'g', quantity: 250, receivedQuantity: 250, unitPrice: 0.005, taxRate: 0 };
    expect(buildPurchaseReturnItems([old], new Map([['flour', 'kg']]))[0]).toMatchObject({ unit: 'kg', maxQuantity: 0.25, unitPrice: 5 });
    expect(() => buildPurchaseReturnItems([old])).toThrow(/基本单位/);
  });
  it('rejects missing or invalid package and pricing conversions', () => {
    expect(() => buildPurchaseReturnItems([{ ...flour, packageToBaseFactorSnapshot: null }])).toThrow(/换算/);
    expect(() => buildPurchaseReturnItems([{ ...flour, quantityToPriceFactor: 0 }])).toThrow(/计价/);
    expect(() => buildPurchaseReturnItems([{ ...flour, unitPrice: null }])).toThrow(/单价/);
  });
  it('rejects a source quantity below inventory precision rather than rounding it', () => {
    expect(() => buildPurchaseReturnItems([{ ...flour, unit: 'g', purchasePackageUnitSnapshot: 'g', packageToBaseFactorSnapshot: 0.001, quantity: 1, receivedQuantity: 1 }])).toThrow(/两位小数/);
  });
});
