import { describe, expect, it } from 'vitest';
import type { SystemUnit } from '@/api/systemUnits';
import { purchaseUnitSelectionReason, resolvePurchaseUnits } from './purchaseUnitContract';

const units: SystemUnit[] = [
  { unitCode: 'kg', unitName: '公斤', baseUnit: 'g', category: 'MASS', isActive: true },
  { unitCode: 'g', unitName: '克', baseUnit: 'g', category: 'MASS', isActive: true },
  { unitCode: 'm', unitName: '米', baseUnit: 'm', category: 'LENGTH', isActive: true },
  { unitCode: 'cm', unitName: '厘米', baseUnit: 'm', category: 'LENGTH', isActive: true },
  { unitCode: 'minute', unitName: '分钟', baseUnit: 'minute', category: 'TIME', isActive: true },
];

describe('purchase unit contract', () => {
  it('defaults metre materials to canonical m and only exposes length units', () => {
    const result = resolvePurchaseUnits('m', units, units.filter((unit) => unit.unitCode !== 'minute'));
    expect(result.defaultUnit).toBe('m');
    expect(result.compatibleUnits.map((unit) => unit.unitCode)).toEqual(['m', 'cm']);
    expect(result.reason).toBe('');
  });

  it('defaults kilogram materials to kg and only exposes mass units', () => {
    const result = resolvePurchaseUnits('kg', units, units.filter((unit) => unit.unitCode !== 'minute'));
    expect(result.defaultUnit).toBe('kg');
    expect(result.compatibleUnits.map((unit) => unit.unitCode)).toEqual(['kg', 'g']);
  });

  it('blocks a cross-dimension selection before submit with a complete Chinese reason', () => {
    const result = resolvePurchaseUnits('kg', units, units);
    expect(purchaseUnitSelectionReason('m', result)).toContain('量纲不兼容');
    expect(result.compatibleUnits.map((unit) => unit.unitCode)).not.toContain('m');
  });

  it('explains when the material base unit has no legal purchase option', () => {
    const result = resolvePurchaseUnits('minute', units, units.filter((unit) => unit.unitCode !== 'minute'));
    expect(result.defaultUnit).toBe('');
    expect(result.reason).toContain('不允许用于采购数量');
    expect(result.reason).toContain('配置到库存基本单位的明确换算');
  });
});
