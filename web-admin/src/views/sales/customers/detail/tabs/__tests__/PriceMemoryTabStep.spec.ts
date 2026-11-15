/**
 * 回归：专属价输入框 UInputNumber 的 step 必须不大于 min。
 * Reka NumberField 把值吸附到「min + k × step」，min=0.0001、step 缺省为 1 时，
 * 输入 12.5 失焦后变成 12.0001，保存下去就是静默存错价（2026-11-15 在 F006 真跑发现）。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('PriceMemoryTab 单价输入的 step', () => {
  const src = readFileSync(resolve(__dirname, '../PriceMemoryTab.vue'), 'utf-8');
  const tag = src.match(/<UInputNumber[^>]*data-testid="price-input"[^>]*>/)?.[0] ?? '';

  it('找到了被守的输入框（仪器自检）', () => {
    expect(tag).toContain(':min="0.0001"');
  });

  it('显式设置 step，且不大于 min', () => {
    const step = Number(tag.match(/:step="([0-9.]+)"/)?.[1]);
    const min = Number(tag.match(/:min="([0-9.]+)"/)?.[1]);
    expect(Number.isFinite(step)).toBe(true);
    expect(step).toBeLessThanOrEqual(min);
  });
});
