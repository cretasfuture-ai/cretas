import { describe, expect, it } from 'vitest';
import { buildCapacityOverrides, capacityBlockReason, capacityKey, describeSampleDays, initialCapacityInputs } from '../dailyCapacityForm';
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
  it('prefills the suggested value only where nothing was typed before, and the user can still change it', () => {
    const suggested = { ...chicken, suggestedPerDay: 200, historyBasis: 'RAW_INPUT' };
    const inputs = initialCapacityInputs([beef, suggested], {});
    expect(inputs).toEqual({ [capacityKey(beef)]: null, [capacityKey(chicken)]: 200 });
    // 预填的值就是要提交的值, 改了就提交改后的
    expect(buildCapacityOverrides([suggested], inputs)).toEqual([{ productTypeId: 'RAW-CHICKEN', quantityPerDay: 200, unit: 'kg' }]);
    expect(buildCapacityOverrides([suggested], { ...inputs, [capacityKey(chicken)]: '180' }))
      .toEqual([{ productTypeId: 'RAW-CHICKEN', quantityPerDay: 180, unit: 'kg' }]);
    // 上一轮已经填过的不被建议值盖掉
    expect(initialCapacityInputs([suggested], { [capacityKey(chicken)]: 150 })).toEqual({ [capacityKey(chicken)]: 150 });
    // 没有建议值 (或非正数) 不预填
    expect(initialCapacityInputs([{ ...chicken, suggestedPerDay: 0 }], {})).toEqual({ [capacityKey(chicken)]: null });
  });

  it('describes the 1-2 days of history with the right verb and nothing when there is none', () => {
    expect(describeSampleDays({ ...beef, unit: '盒', historyBasis: 'FINISHED_OUTPUT', historyWindowDays: 90,
      sampleDays: [{ date: '2026-10-27', quantity: 40, unit: '盒' }] }))
      .toBe('近 90 天只有 1 天有记录：10-27 入库 40 盒');
    expect(describeSampleDays({ ...chicken, historyBasis: 'RAW_INPUT', historyWindowDays: 90,
      sampleDays: [{ date: '2026-10-25', quantity: 80, unit: 'kg' }, { date: '2026-10-27', quantity: 62.5, unit: 'kg' }] }))
      .toBe('近 90 天只有 2 天有记录：10-25 投入 80 kg，10-27 投入 62.5 kg');
    expect(describeSampleDays({ ...beef, sampleDays: [] })).toBe('');
    expect(describeSampleDays(beef)).toBe('');
  });
});
