import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { dailyCapacityBlockReason, dailyCapacityChange, saveDailyCapacityIfChanged } from '../productDailyCapacity';

describe('product daily capacity save wiring', () => {
  it('saves through the dedicated endpoint only when changed, with the saved product id', async () => {
    const save = vi.fn().mockResolvedValue({ success: true });
    expect(await saveDailyCapacityIfChanged('PT-1', { quantity: 300, unit: '袋' }, 300, '袋', save)).toBe('unchanged');
    expect(save).not.toHaveBeenCalled();
    expect(await saveDailyCapacityIfChanged('PT-1', { quantity: 300, unit: '袋' }, 250, '袋', save)).toBe('saved');
    expect(save).toHaveBeenLastCalledWith('PT-1', 250, '袋');
    expect(await saveDailyCapacityIfChanged('PT-1', { quantity: 300, unit: '袋' }, null, '', save)).toBe('saved');
    expect(save).toHaveBeenLastCalledWith('PT-1', null, null);
    await expect(saveDailyCapacityIfChanged('', { quantity: null, unit: '' }, 10, '袋', save)).rejects.toThrow();
  });

  it('the product dialog saves the capacity after the product and hydrates it on edit', () => {
    const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');
    const submit = source.slice(source.indexOf('async function handleSubmit()'), source.indexOf('function offerBomConfiguration'));
    // 先存产品 (put/post), 成功后才存日产能, 用的是产品 id
    expect(submit.indexOf('/product-types/${formData.id}')).toBeGreaterThan(-1);
    expect(submit.indexOf('saveDailyCapacityIfChanged(')).toBeGreaterThan(submit.indexOf('/product-types/${formData.id}'));
    expect(submit).toContain('setProductDailyCapacity(factoryId.value!, productId, quantityPerDay, unit)');
    expect(source).toContain('dailyCapacityAmount.value = product.dailyCapacity != null ? Number(product.dailyCapacity) : null;');
    expect(source).toContain('data-testid="product-daily-capacity"');
  });
});

describe('product daily capacity field', () => {
  it('empty is allowed; a value needs to be positive and to carry a unit', () => {
    expect(dailyCapacityBlockReason(null, '')).toBe('');
    expect(dailyCapacityBlockReason('', '袋')).toBe('');
    expect(dailyCapacityBlockReason(0, '袋')).toContain('大于 0');
    expect(dailyCapacityBlockReason(300, '')).toContain('单位');
    expect(dailyCapacityBlockReason(300, '  ')).toContain('单位');
    expect(dailyCapacityBlockReason(300, '袋')).toBe('');
  });

  it('sends nothing when unchanged, the new value when changed, and an explicit clear when emptied', () => {
    expect(dailyCapacityChange({ quantity: null, unit: '' }, null, '')).toBeNull();
    expect(dailyCapacityChange({ quantity: 300, unit: '袋' }, 300, '袋')).toBeNull();
    expect(dailyCapacityChange({ quantity: 300, unit: '袋' }, 300, ' 袋 ')).toBeNull();
    expect(dailyCapacityChange({ quantity: 300, unit: '袋' }, 250, '袋')).toEqual({ quantityPerDay: 250, unit: '袋' });
    expect(dailyCapacityChange({ quantity: 300, unit: '袋' }, 300, 'kg')).toEqual({ quantityPerDay: 300, unit: 'kg' });
    expect(dailyCapacityChange({ quantity: null, unit: '' }, '120.456', '袋')).toEqual({ quantityPerDay: 120.46, unit: '袋' });
    expect(dailyCapacityChange({ quantity: 300, unit: '袋' }, null, '袋')).toEqual({ quantityPerDay: null, unit: null });
    expect(() => dailyCapacityChange({ quantity: null, unit: '' }, -1, '袋')).toThrow();
  });
});
