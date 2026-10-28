import { describe, expect, it } from 'vitest';
import { buildCapacityOverrides, capacityBlockReason, capacityKey } from '../dailyCapacityForm';
import type { DailyCapacityRequired } from '@/api/scheduling';

const beef: DailyCapacityRequired = {
  productTypeId: 'PT-BEEF', productName: '酱牛肉', unit: '袋', jointPlan: false,
  question: '「酱牛肉」一天能做多少 袋？', reason: 'MISSING', planNumbers: ['PLAN-B'],
};
const chicken: DailyCapacityRequired = {
  productTypeId: 'RAW-CHICKEN', productName: '西装鸡', unit: 'kg', jointPlan: true,
  question: '这个联产计划（西装鸡）一天能做多少 kg？', reason: 'MISSING', planNumbers: ['PLAN-A'],
};

describe('daily capacity on-the-spot form', () => {
  it('names the first product that still needs a positive number', () => {
    expect(capacityBlockReason([beef, chicken], {})).toContain('酱牛肉');
    expect(capacityBlockReason([beef, chicken], { [capacityKey(beef)]: 300 })).toContain('西装鸡');
    expect(capacityBlockReason([beef, chicken], { [capacityKey(beef)]: 300, [capacityKey(chicken)]: 0 })).toContain('西装鸡');
    expect(capacityBlockReason([beef, chicken], { [capacityKey(beef)]: 300, [capacityKey(chicken)]: -5 })).toContain('西装鸡');
    expect(capacityBlockReason([beef, chicken], { [capacityKey(beef)]: '300', [capacityKey(chicken)]: '200.5' })).toBe('');
  });

  it('builds overrides in the plan unit the backend asked for (the user never picks a unit)', () => {
    expect(buildCapacityOverrides([beef, chicken], { [capacityKey(beef)]: '300', [capacityKey(chicken)]: 200.456 })).toEqual([
      { productTypeId: 'PT-BEEF', quantityPerDay: 300, unit: '袋' },
      { productTypeId: 'RAW-CHICKEN', quantityPerDay: 200.46, unit: 'kg' },
    ]);
    expect(() => buildCapacityOverrides([beef], {})).toThrow('酱牛肉');
  });
});
